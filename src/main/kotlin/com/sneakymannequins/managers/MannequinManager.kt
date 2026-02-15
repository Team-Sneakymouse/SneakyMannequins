package com.sneakymannequins.managers

import com.sneakymannequins.SneakyMannequins
import com.sneakymannequins.model.LayerSelection
import com.sneakymannequins.model.LayerOption
import com.sneakymannequins.model.LayerDefinition
import com.sneakymannequins.model.Mannequin
import com.sneakymannequins.model.PixelChange
import com.sneakymannequins.model.PixelFrame
import com.sneakymannequins.model.SkinSelection
import com.sneakymannequins.nms.VolatileHandler
import com.sneakymannequins.render.PixelProjector
import com.sneakymannequins.util.SkinComposer
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.entity.Display
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.util.Vector
import java.util.UUID
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

// ── Data classes ────────────────────────────────────────────────────────────────

private data class ControlState(
    var layerIndex: Int = 0,
    val partIndex: MutableMap<String, Int> = mutableMapOf(),
    val colorIndex: MutableMap<String, Int> = mutableMapOf(),
    val channelIndex: MutableMap<String, Int> = mutableMapOf(),
    var mode: ControlMode = ControlMode.NONE
)

private enum class ControlMode { NONE, PART, COLOR }

/**
 * Describes one HUD button: its identifier, default label, and translation offset.
 * Translations are in entity-local space (billboard FIXED, rotated by yaw):
 *   tx: positive = right from the viewer's POV
 *   ty: positive = up
 *   tz: negative = behind the mannequin
 */
private data class HudButton(
    val name: String,
    val defaultLabel: String,
    val tx: Float,
    val ty: Float,
    val tz: Float,
    val lineWidth: Int = 200
)

/** Canonical per-button visual state (shared across all viewers). */
private data class ButtonVisual(
    var text: String,
    var textColor: Int = 0xFFFFFF,   // RGB white
    var bgColor: Int = 0x78000000.toInt()  // semi-transparent black (matches HUD_BG_DEFAULT)
)

/** Per-player virtual HUD tracking. */
private data class PlayerHudState(
    val mannequinId: UUID,
    val entityIds: Map<String, Int>,  // buttonName → virtual entity ID
    val frameEntityId: Int? = null,   // optional backdrop ItemDisplay
    var lastYaw: Float = Float.NaN,
    var hoveredButton: String? = null
)

// ── Manager ─────────────────────────────────────────────────────────────────────

class MannequinManager(
    private val plugin: SneakyMannequins,
    private val layerManager: LayerManager,
    private val handler: VolatileHandler,
    private val persistence: MannequinPersistence
) {
    private val mannequins = mutableMapOf<UUID, Mannequin>()
    private val sentTo = mutableMapOf<UUID, MutableSet<UUID>>()        // viewerId → mannequins seen
    private val statusText = mutableMapOf<UUID, String>()              // mannequinId → last action
    private val poseState = mutableMapOf<UUID, Boolean>()              // mannequinId → true = T-pose
    private val controlState = mutableMapOf<UUID, ControlState>()
    private val interactionDebounce = mutableMapOf<Pair<UUID, String>, Long>()

    /** Per-mannequin canonical button visuals. */
    private val buttonVisuals = mutableMapOf<UUID, MutableMap<String, ButtonVisual>>()

    /** Per-player virtual HUD state. */
    private val playerHuds = mutableMapOf<UUID, PlayerHudState>()

    private var hoverTaskId: Int = -1

    // ── HUD button layout ───────────────────────────────────────────────────────

    companion object {
        private const val VISIBLE_RANGE_SQ = 32.0 * 32.0
        private const val HOVER_RANGE = 6.0
        private const val INTERACT_RADIUS = 3.0f
        private const val HUD_BG_DEFAULT = 0x78000000.toInt()       // semi-transparent black
        private const val HUD_BG_HIGHLIGHT = 0xB8336699.toInt()     // translucent blue
        private const val BUTTON_TOLERANCE = 0.35
        private const val ROTATION_INTERP_TICKS = 3
        private const val YAW_THRESHOLD = 0.02f                     // radians (~1°)
        /** Y offset applied to the frame ItemDisplay spawn position so its
         *  AABB sits well above the Interaction entity and can't intercept
         *  client-side ray-casts.  Compensated in the translation ty. */
        private const val FRAME_Y_OFFSET = 10.0

        private val HUD_LAYOUT = listOf(
            HudButton("status", "Controls", 0.0f, 2.8f, -2.0f, lineWidth = 256),
            // Left column
            HudButton("model",  "Model",   -1.1f, 2.2f, -2.0f),
            HudButton("pose",   "Pose",    -1.1f, 1.7f, -2.0f),
            HudButton("random", "Random",  -1.1f, 1.2f, -2.0f),
            // Right column
            HudButton("layer",  "Layer",    1.1f, 2.2f, -2.0f),
            HudButton("part",   "Part",     1.1f, 1.7f, -2.0f),
            HudButton("channel","Channel",  1.1f, 1.2f, -2.0f),
            HudButton("color",  "Color",    1.1f, 0.7f, -2.0f),
        )

        /** Button names that respond to clicks.  "status" is display-only. */
        private val CLICKABLE_BUTTONS = setOf("model", "pose", "random", "layer", "part", "channel", "color")
    }

    // ── Lifecycle ───────────────────────────────────────────────────────────────

    fun loadFromDisk() {
        val loaded = persistence.load()
        loaded.forEach { (id, loc) ->
            val selection = bootstrapSelection()
            mannequins[id] = Mannequin(id = id, location = loc.clone(), selection = selection)
            controlState[id] = ControlState()
            initButtonVisuals(id)
            cleanupControlEntities(id)
            spawnInteractionEntity(id)
        }
    }

    fun persist() {
        persistence.save(mannequins.values)
    }

    fun create(location: Location): Mannequin {
        val selection = bootstrapSelection()
        val mannequin = Mannequin(location = location.clone(), selection = selection)
        mannequins[mannequin.id] = mannequin
        controlState[mannequin.id] = ControlState()
        initButtonVisuals(mannequin.id)
        spawnInteractionEntity(mannequin.id)
        persist()
        return mannequin
    }

    fun get(id: UUID): Mannequin? = mannequins[id]

    fun remove(mannequinId: UUID, viewers: Collection<Player>) {
        viewers.forEach { viewer -> handler.destroyMannequin(viewer, mannequinId) }
        // Destroy virtual HUDs for all players viewing this mannequin
        destroyHudsForMannequin(mannequinId)
        cleanupControlEntities(mannequinId)
        mannequins.remove(mannequinId)
        buttonVisuals.remove(mannequinId)
        controlState.remove(mannequinId)
        statusText.remove(mannequinId)
        poseState.remove(mannequinId)
        persist()
    }

    fun forgetViewer(viewerId: UUID) {
        sentTo.remove(viewerId)
        destroyPlayerHud(viewerId)
    }

    fun shutdown() {
        stopHoverTask()
        val viewers = plugin.server.onlinePlayers
        mannequins.keys.forEach { id ->
            viewers.forEach { viewer -> handler.destroyMannequin(viewer, id) }
        }
        // Destroy all virtual HUDs
        for (playerId in playerHuds.keys.toList()) {
            val player = plugin.server.getPlayer(playerId) ?: continue
            destroyPlayerHud(player)
        }
        persist()
        mannequins.clear()
    }

    fun reloadAll() {
        stopHoverTask()
        val viewers = plugin.server.onlinePlayers
        mannequins.keys.forEach { id ->
            viewers.forEach { viewer -> handler.destroyMannequin(viewer, id) }
            cleanupControlEntities(id)
        }
        // Destroy all virtual HUDs
        for (playerId in playerHuds.keys.toList()) {
            val player = plugin.server.getPlayer(playerId) ?: continue
            destroyPlayerHud(player)
        }
        mannequins.clear()
        buttonVisuals.clear()
        sentTo.clear()
        statusText.clear()
        poseState.clear()
        controlState.clear()
        interactionDebounce.clear()
        playerHuds.clear()

        loadFromDisk()
        startHoverTask()

        plugin.server.onlinePlayers.forEach { viewer -> renderVisibleTo(viewer) }
    }

    // ── Rendering ───────────────────────────────────────────────────────────────

    fun renderVisibleTo(viewer: Player) {
        val nearby = mannequins.values.filter { man ->
            man.location.world == viewer.world && man.location.distanceSquared(viewer.location) <= VISIBLE_RANGE_SQ
        }
        nearby.forEach { man ->
            val seen = sentTo.computeIfAbsent(viewer.uniqueId) { mutableSetOf() }
            if (man.id !in seen) {
                renderFull(man, listOf(viewer))
                seen += man.id
            } else {
                render(man, listOf(viewer))
            }
        }
    }

    fun render(mannequin: Mannequin, viewers: Collection<Player>): Int {
        val definitions = layerManager.definitionsInOrder()
        val composed = SkinComposer.compose(definitions, mannequin.selection, useSlimModel = isSlimModel(mannequin))
        val nextFrame = PixelFrame.fromImage(composed)
        val diff = mannequin.lastFrame.diff(nextFrame)
        mannequin.lastFrame = nextFrame
        if (plugin.config.getBoolean("plugin.debug", false)) {
            plugin.logger.info("Rendering mannequin ${mannequin.id} with ${diff.size} pixel changes to ${viewers.size} viewers")
        }
        val projected = PixelProjector.project(
            origin = mannequin.location,
            changes = diff,
            pixelScale = 1.0 / 16.0,
            scaleMultiplier = handler.pixelScaleMultiplier(),
            slimArms = isSlimModel(mannequin)
        )
        viewers.forEach { viewer -> handler.applyProjectedPixels(viewer, mannequin.id, projected) }
        return diff.size
    }

    private fun renderFull(mannequin: Mannequin, viewers: Collection<Player>) {
        val definitions = layerManager.definitionsInOrder()
        val composed = SkinComposer.compose(definitions, mannequin.selection, useSlimModel = isSlimModel(mannequin))
        mannequin.lastFrame = PixelFrame.fromImage(composed)
        val changes = mutableListOf<PixelChange>()
        for (x in 0 until composed.width) {
            for (y in 0 until composed.height) {
                val argb = composed.getRGB(x, y)
                if ((argb ushr 24) != 0) {
                    changes += PixelChange(x, y, argb, visible = true)
                }
            }
        }
        val projected = PixelProjector.project(
            origin = mannequin.location,
            changes = changes,
            pixelScale = 1.0 / 16.0,
            scaleMultiplier = handler.pixelScaleMultiplier(),
            slimArms = isSlimModel(mannequin)
        )
        viewers.forEach { viewer -> handler.applyProjectedPixels(viewer, mannequin.id, projected) }
    }

    private fun isSlimModel(mannequin: Mannequin): Boolean {
        val bodyId = mannequin.selection.selections["body"]?.option?.id ?: return false
        return bodyId.contains("slim", ignoreCase = true)
    }

    fun nearestMannequin(location: Location, radius: Double = 10.0): Mannequin? {
        return mannequins.values.minByOrNull { man ->
            if (man.location.world != location.world) Double.MAX_VALUE else man.location.distance(location)
        }?.takeIf { it.location.world == location.world && it.location.distance(location) <= radius }
    }

    // ── Interaction entity (real, server-side) ──────────────────────────────────

    private fun spawnInteractionEntity(mannequinId: UUID) {
        val man = mannequins[mannequinId] ?: return
        val world = man.location.world ?: return
        val loc = man.location.clone()

        // Check if one already exists
        val existing = world.getNearbyEntities(loc, 8.0, 8.0, 8.0).any {
            it is org.bukkit.entity.Interaction
                    && it.scoreboardTags.contains("sneakymannequin_control")
                    && it.scoreboardTags.contains("mannequin:$mannequinId")
        }
        if (existing) return

        world.spawn(loc, org.bukkit.entity.Interaction::class.java) { inter ->
            inter.interactionWidth = INTERACT_RADIUS * 2
            inter.interactionHeight = INTERACT_RADIUS * 2
            inter.isResponsive = true
            inter.scoreboardTags.add("sneakymannequin_control")
            inter.scoreboardTags.add("mannequin:$mannequinId")
            inter.scoreboardTags.add("button:interact")
        }
    }

    /** Remove all control entities (Interaction + any legacy TextDisplays) for a mannequin. */
    private fun cleanupControlEntities(mannequinId: UUID) {
        val man = mannequins[mannequinId] ?: return
        val world = man.location.world ?: return
        world.getNearbyEntities(man.location, 10.0, 10.0, 10.0).forEach {
            if (it.scoreboardTags.contains("sneakymannequin_control")
                && it.scoreboardTags.contains("mannequin:$mannequinId")
            ) {
                it.remove()
            }
        }
    }

    // ── Virtual HUD management ──────────────────────────────────────────────────

    /** Initialise the canonical button visuals for a mannequin. */
    private fun initButtonVisuals(mannequinId: UUID) {
        val visuals = mutableMapOf<String, ButtonVisual>()
        for (btn in HUD_LAYOUT) {
            val label = if (btn.name == "status") (statusText[mannequinId] ?: btn.defaultLabel) else btn.defaultLabel
            visuals[btn.name] = ButtonVisual(text = label)
        }
        buttonVisuals[mannequinId] = visuals
    }

    /** Spawn the full virtual HUD for a player viewing a mannequin. */
    private fun spawnPlayerHud(player: Player, mannequin: Mannequin, yaw: Float) {
        val manId = mannequin.id
        val loc = mannequin.location
        val visuals = buttonVisuals[manId] ?: return
        val ids = mutableMapOf<String, Int>()

        for (btn in HUD_LAYOUT) {
            val entityId = handler.allocateEntityId()
            val vis = visuals[btn.name] ?: ButtonVisual(text = btn.defaultLabel)
            handler.spawnHudTextDisplay(
                viewer = player, entityId = entityId,
                x = loc.x, y = loc.y, z = loc.z,
                text = vis.text, textColor = vis.textColor, bgColor = vis.bgColor,
                tx = btn.tx, ty = btn.ty, tz = btn.tz,
                yaw = yaw, lineWidth = btn.lineWidth
            )
            ids[btn.name] = entityId
        }

        // Spawn optional backdrop ItemDisplay from config
        val frameId = spawnHudFrame(player, loc, yaw)

        playerHuds[player.uniqueId] = PlayerHudState(
            mannequinId = manId,
            entityIds = ids,
            frameEntityId = frameId,
            lastYaw = yaw
        )
    }

    /** Destroy the virtual HUD for a player (if any). */
    private fun destroyPlayerHud(player: Player) {
        destroyPlayerHud(player.uniqueId, player)
    }

    private fun destroyPlayerHud(playerId: UUID, player: Player? = null) {
        val state = playerHuds.remove(playerId) ?: return
        val p = player ?: plugin.server.getPlayer(playerId) ?: return
        val allIds = state.entityIds.values.toMutableList()
        state.frameEntityId?.let { allIds += it }
        handler.destroyEntities(p, allIds.toIntArray())
    }

    /** Destroy all virtual HUDs that show a specific mannequin. */
    private fun destroyHudsForMannequin(mannequinId: UUID) {
        val toRemove = playerHuds.entries.filter { it.value.mannequinId == mannequinId }
        for ((playerId, state) in toRemove) {
            val player = plugin.server.getPlayer(playerId)
            if (player != null) {
                val allIds = state.entityIds.values.toMutableList()
                state.frameEntityId?.let { allIds += it }
                handler.destroyEntities(player, allIds.toIntArray())
            }
            playerHuds.remove(playerId)
        }
    }

    /** Push the current visual state of one button to all players viewing this mannequin. */
    private fun pushButtonToViewers(mannequinId: UUID, buttonName: String) {
        val vis = buttonVisuals[mannequinId]?.get(buttonName) ?: return
        val btn = HUD_LAYOUT.firstOrNull { it.name == buttonName } ?: return
        playerHuds.forEach { (playerId, state) ->
            if (state.mannequinId != mannequinId) return@forEach
            val player = plugin.server.getPlayer(playerId) ?: return@forEach
            val entityId = state.entityIds[buttonName] ?: return@forEach
            // Use the player's current yaw for the rotation
            handler.updateHudTextDisplay(
                viewer = player, entityId = entityId,
                text = vis.text, textColor = vis.textColor,
                bgColor = if (state.hoveredButton == buttonName) HUD_BG_HIGHLIGHT else vis.bgColor,
                tx = btn.tx, ty = btn.ty, tz = btn.tz,
                yaw = state.lastYaw, lineWidth = btn.lineWidth,
                interpolationTicks = 0 // instant text update
            )
        }
    }

    // ── HUD frame (ItemDisplay backdrop) ───────────────────────────────────────

    /** Read hud-frame config and spawn the ItemDisplay if enabled. Returns entity ID or null. */
    private fun spawnHudFrame(player: Player, loc: Location, yaw: Float): Int? {
        if (!plugin.config.getBoolean("hud-frame.enabled", false)) return null
        val item = plugin.config.getString("hud-frame.item") ?: "minecraft:glass_pane"
        val displayCtx = plugin.config.getString("hud-frame.display-context") ?: "FIXED"
        val tx = plugin.config.getDouble("hud-frame.translation.x", 0.0).toFloat()
        val ty = plugin.config.getDouble("hud-frame.translation.y", 1.7).toFloat()
        val tz = plugin.config.getDouble("hud-frame.translation.z", -2.0).toFloat()
        val sx = plugin.config.getDouble("hud-frame.scale.x", 3.0).toFloat()
        val sy = plugin.config.getDouble("hud-frame.scale.y", 3.0).toFloat()
        val sz = plugin.config.getDouble("hud-frame.scale.z", 0.05).toFloat()
        val entityId = handler.allocateEntityId()
        // Spawn with a Y offset so the entity AABB can't intercept client ray-casts;
        // compensate in the translation so the visual position is unchanged.
        handler.spawnHudItemDisplay(
            viewer = player, entityId = entityId,
            x = loc.x, y = loc.y + FRAME_Y_OFFSET, z = loc.z,
            item = item, displayContext = displayCtx,
            tx = tx, ty = ty - FRAME_Y_OFFSET.toFloat(), tz = tz,
            sx = sx, sy = sy, sz = sz,
            yaw = yaw
        )
        return entityId
    }

    /** Update the frame's rotation to match the new yaw. */
    private fun updateHudFrame(player: Player, hud: PlayerHudState, yaw: Float) {
        val frameId = hud.frameEntityId ?: return
        if (!plugin.config.getBoolean("hud-frame.enabled", false)) return
        val item = plugin.config.getString("hud-frame.item") ?: "minecraft:glass_pane"
        val displayCtx = plugin.config.getString("hud-frame.display-context") ?: "FIXED"
        val tx = plugin.config.getDouble("hud-frame.translation.x", 0.0).toFloat()
        val ty = plugin.config.getDouble("hud-frame.translation.y", 1.7).toFloat()
        val tz = plugin.config.getDouble("hud-frame.translation.z", -2.0).toFloat()
        val sx = plugin.config.getDouble("hud-frame.scale.x", 3.0).toFloat()
        val sy = plugin.config.getDouble("hud-frame.scale.y", 3.0).toFloat()
        val sz = plugin.config.getDouble("hud-frame.scale.z", 0.05).toFloat()
        // Entity lives at Y + FRAME_Y_OFFSET; compensate in translation
        handler.updateHudItemDisplay(
            viewer = player, entityId = frameId,
            item = item, displayContext = displayCtx,
            tx = tx, ty = ty - FRAME_Y_OFFSET.toFloat(), tz = tz,
            sx = sx, sy = sy, sz = sz,
            yaw = yaw,
            interpolationTicks = ROTATION_INTERP_TICKS
        )
    }

    // ── Hover + rotation tick ───────────────────────────────────────────────────

    fun startHoverTask() {
        if (hoverTaskId != -1) return
        hoverTaskId = plugin.server.scheduler.scheduleSyncRepeatingTask(plugin, Runnable {
            tickHoverAndRotation()
        }, 0L, 1L)
    }

    fun stopHoverTask() {
        if (hoverTaskId != -1) {
            plugin.server.scheduler.cancelTask(hoverTaskId)
            hoverTaskId = -1
        }
    }

    /**
     * Runs every tick.  For each online player:
     *  1. Find the nearest mannequin (within HOVER_RANGE).
     *  2. Spawn / despawn the virtual HUD as needed.
     *  3. Update the HUD rotation (with interpolation) if the player moved.
     *  4. Determine which button the crosshair is over → highlight it.
     */
    private fun tickHoverAndRotation() {
        for (player in plugin.server.onlinePlayers) {
            val nearest = nearestMannequin(player.location, HOVER_RANGE)
            val currentHud = playerHuds[player.uniqueId]

            // ── Despawn HUD if mannequin changed or player moved away ────────
            if (nearest == null || (currentHud != null && currentHud.mannequinId != nearest.id)) {
                if (currentHud != null) destroyPlayerHud(player)
            }

            if (nearest == null) continue

            // ── Compute facing yaw (radians, from mannequin toward player) ───
            val dx = player.location.x - nearest.location.x
            val dz = player.location.z - nearest.location.z
            val yaw = atan2(dx, dz).toFloat()  // same convention as Minecraft entity yaw

            // ── Spawn HUD if needed ──────────────────────────────────────────
            if (playerHuds[player.uniqueId] == null) {
                spawnPlayerHud(player, nearest, yaw)
            }

            val hudState = playerHuds[player.uniqueId] ?: continue

            // ── Update rotation if yaw changed ──────────────────────────────
            if (abs(yaw - hudState.lastYaw) > YAW_THRESHOLD) {
                val visuals = buttonVisuals[nearest.id] ?: continue
                for (btn in HUD_LAYOUT) {
                    val entityId = hudState.entityIds[btn.name] ?: continue
                    val vis = visuals[btn.name] ?: continue
                    val bg = if (hudState.hoveredButton == btn.name) HUD_BG_HIGHLIGHT else vis.bgColor
                    handler.updateHudTextDisplay(
                        viewer = player, entityId = entityId,
                        text = vis.text, textColor = vis.textColor, bgColor = bg,
                        tx = btn.tx, ty = btn.ty, tz = btn.tz,
                        yaw = yaw, lineWidth = btn.lineWidth,
                        interpolationTicks = ROTATION_INTERP_TICKS
                    )
                }
                // Rotate the backdrop frame alongside the buttons
                updateHudFrame(player, hudState, yaw)
                hudState.lastYaw = yaw
            }

            // ── Hover detection ─────────────────────────────────────────────
            val hovered = computeHoveredButton(player, nearest)
            val prev = hudState.hoveredButton

            if (hovered != prev) {
                // Un-highlight previous
                if (prev != null) {
                    sendButtonBg(player, hudState, nearest.id, prev, HUD_BG_DEFAULT)
                }
                // Highlight new
                if (hovered != null) {
                    sendButtonBg(player, hudState, nearest.id, hovered, HUD_BG_HIGHLIGHT)
                    val ph = basePlaceholders(player, nearest).apply { put("button", hovered) }
                    fireTrigger("hover", ph)
                }
                hudState.hoveredButton = hovered
            }
        }
    }

    /** Send a background colour override for a single button to a single player.
     *  Uses a lightweight packet that only touches the background colour,
     *  so it won't interrupt an in-progress rotation interpolation. */
    private fun sendButtonBg(player: Player, hud: PlayerHudState, manId: UUID, buttonName: String, bgColor: Int) {
        val entityId = hud.entityIds[buttonName] ?: return
        handler.sendHudBackground(player, entityId, bgColor)
    }

    // ── Look-direction → button resolution ──────────────────────────────────────

    /**
     * Determine which HUD button the player's crosshair is closest to.
     * Returns the button name, or null if none is within tolerance.
     */
    private fun computeHoveredButton(player: Player, mannequin: Mannequin): String? {
        val eyeLoc = player.eyeLocation
        val lookDir = eyeLoc.direction.normalize()
        val eyeVec = eyeLoc.toVector()
        val manVec = mannequin.location.toVector()

        var bestName: String? = null
        var bestDist = Double.MAX_VALUE

        for (btn in HUD_LAYOUT) {
            if (btn.name == "status") continue
            val worldPos = buttonWorldPos(manVec, eyeVec, btn.tx, btn.ty, btn.tz)
            val dist = distanceFromRay(eyeVec, lookDir, worldPos)
            if (dist < bestDist) {
                bestDist = dist
                bestName = btn.name
            }
        }

        return if (bestDist <= BUTTON_TOLERANCE) bestName else null
    }

    /**
     * Compute the world-space position of a billboard-FIXED button that has been
     * Y-rotated to face the viewer.  The rotation is the same yaw computed from
     * player position → mannequin position, matching the NMS transformation.
     */
    private fun buttonWorldPos(mannequinPos: Vector, viewerPos: Vector, tx: Float, ty: Float, tz: Float): Vector {
        val dx = viewerPos.x - mannequinPos.x
        val dz = viewerPos.z - mannequinPos.z
        val horizDist = sqrt(dx * dx + dz * dz)
        if (horizDist < 0.001) {
            return mannequinPos.clone().add(Vector(0.0, ty.toDouble(), 0.0))
        }
        // Forward: from mannequin toward the viewer (XZ plane)
        val fwdX = dx / horizDist
        val fwdZ = dz / horizDist
        // Right: 90° CW rotation of forward (viewer's right)
        val rightX = fwdZ
        val rightZ = -fwdX

        return Vector(
            mannequinPos.x + rightX * tx + fwdX * tz,
            mannequinPos.y + ty,
            mannequinPos.z + rightZ * tx + fwdZ * tz
        )
    }

    /** Shortest distance from a point to a ray (origin + t*dir, t≥0). */
    private fun distanceFromRay(rayOrigin: Vector, rayDir: Vector, point: Vector): Double {
        val diff = point.clone().subtract(rayOrigin)
        val t = diff.dot(rayDir)
        if (t < 0) return Double.MAX_VALUE
        val closest = rayOrigin.clone().add(rayDir.clone().multiply(t))
        return closest.distance(point)
    }

    // ── Interaction handling ────────────────────────────────────────────────────

    /**
     * Called when a player interacts with (left/right click) the Interaction entity.
     * Uses the hover state to determine which button was targeted.
     */
    fun handleInteract(entity: Entity, player: Player, backwards: Boolean) {
        if (entity !is org.bukkit.entity.Interaction) return
        val tags = entity.scoreboardTags
        if (!tags.contains("sneakymannequin_control")) return
        val manTag = tags.firstOrNull { it.startsWith("mannequin:") } ?: return
        val manId = runCatching { UUID.fromString(manTag.removePrefix("mannequin:")) }.getOrNull() ?: return

        // Debounce
        val debounceKey = player.uniqueId to manId.toString()
        val now = System.currentTimeMillis()
        val last = interactionDebounce[debounceKey]
        if (last != null && now - last < 200) return
        interactionDebounce[debounceKey] = now

        val mannequin = mannequins[manId] ?: return
        val state = controlState.getOrPut(manId) { ControlState() }

        // Use the already-computed hover from the tick loop
        val hoveredButton = playerHuds[player.uniqueId]?.hoveredButton

        if (hoveredButton != null && hoveredButton in CLICKABLE_BUTTONS) {
            executeButton(hoveredButton, manId, mannequin, state, player, backwards)
        } else {
            executeModeAction(manId, mannequin, state, player, backwards)
        }
    }

    // ── Button execution ────────────────────────────────────────────────────────

    private fun executeButton(
        button: String,
        manId: UUID,
        mannequin: Mannequin,
        state: ControlState,
        player: Player,
        backwards: Boolean
    ) {
        // Fire click trigger
        val clickPh = basePlaceholders(player, mannequin).apply {
            put("button", button)
        }
        fireTrigger("click", clickPh)

        val layers = layerManager.definitionsInOrder()
        if (layers.isEmpty()) return
        val layer = layers.getOrNull(state.layerIndex % layers.size) ?: layers.first()

        when (button) {
            "model" -> {
                val baseLayerId = "body"
                val options = layerManager.optionsFor(baseLayerId).ifEmpty { layerManager.defaultSkinOptions() }
                if (options.isEmpty()) {
                    plugin.logger.warning("Model toggle requested but no body/default options found")
                    return
                }
                val current = mannequin.selection.selections[baseLayerId]?.option
                val next = if (current?.id.equals("default", true)) {
                    options.firstOrNull { it.id.equals("default_slim", true) } ?: options.firstOrNull()
                } else {
                    options.firstOrNull { it.id.equals("default", true) } ?: options.firstOrNull()
                }
                mannequin.selection = mannequin.selection.copy(
                    selections = mannequin.selection.selections + (baseLayerId to (mannequin.selection.selections[baseLayerId]?.copy(option = next, channelColors = emptyMap())
                        ?: LayerSelection(baseLayerId, next)))
                )
                mannequin.lastFrame = PixelFrame.blank()
                state.colorIndex[baseLayerId] = 0
                val modelLabel = when (next?.id?.lowercase()) {
                    "default_slim" -> "Slim"
                    "default" -> "Default"
                    else -> next?.displayName ?: next?.id ?: "Default"
                }
                updateStatus(manId, "Model: $modelLabel")
                val viewers = nearbyViewers(mannequin)
                viewers.forEach { viewer -> handler.destroyMannequin(viewer, mannequin.id) }
                renderFull(mannequin, viewers)
                return
            }
            "pose" -> {
                val newPose = !(poseState[manId] ?: false)
                poseState[manId] = newPose
                updateStatus(manId, if (newPose) "Pose: T-Pose" else "Pose: Default")
            }
            "random" -> {
                updateStatus(manId, "Random: (coming soon)")
                return
            }
            "layer" -> {
                val delta = if (backwards) -1 else 1
                state.layerIndex = (state.layerIndex + delta + layers.size) % layers.size
                val newLayer = layers[state.layerIndex]
                updateStatus(manId, "Layer: ${newLayer.displayName}")
                state.partIndex[newLayer.id] = 0
                state.channelIndex[newLayer.id] = 0
                state.colorIndex[newLayer.id] = 0
                state.mode = ControlMode.NONE
                val option = mannequin.selection.selections[newLayer.id]?.option
                    ?: layerManager.optionsFor(newLayer.id).firstOrNull()
                refreshDynamicLabels(manId, option, layer)
            }
            "part" -> {
                if (state.mode == ControlMode.PART) {
                    cyclePart(layer, mannequin, state, player, backwards)?.let { updateStatus(manId, it) }
                } else {
                    state.mode = ControlMode.PART
                    refreshDynamicLabels(
                        manId,
                        mannequin.selection.selections[layer.id]?.option
                            ?: layerManager.optionsFor(layer.id).firstOrNull(),
                        layer
                    )
                    updateStatus(manId, "Mode: Part")
                }
            }
            "channel" -> {
                val option = mannequin.selection.selections[layer.id]?.option
                    ?: layerManager.optionsFor(layer.id).firstOrNull()
                val channels = option?.masks?.keys?.sorted() ?: emptyList()
                val channelDisabled = channels.size <= 1
                if (channels.isEmpty()) {
                    updateStatus(manId, "Channel: N/A")
                } else if (!channelDisabled) {
                    val delta = if (backwards) -1 else 1
                    val idx = (state.channelIndex.getOrDefault(layer.id, 0) + delta + channels.size) % channels.size
                    state.channelIndex[layer.id] = idx
                    val selectedChannel = channels[idx]
                    updateStatus(manId, "Channel: $selectedChannel")

                    // Flash selected channel white for 500ms
                    val savedColors = mannequin.selection.selections[layer.id]?.channelColors ?: emptyMap()
                    val flashColors = savedColors + (selectedChannel to java.awt.Color.WHITE)
                    val flashSel = mannequin.selection.selections[layer.id]?.copy(channelColors = flashColors)
                        ?: LayerSelection(layer.id, option, channelColors = flashColors)
                    mannequin.selection = mannequin.selection.copy(
                        selections = mannequin.selection.selections + (layer.id to flashSel)
                    )
                    val viewers = nearbyViewers(mannequin)
                    render(mannequin, viewers)

                    val restoreSel = flashSel.copy(channelColors = savedColors)
                    plugin.server.scheduler.runTaskLater(plugin, Runnable {
                        mannequin.selection = mannequin.selection.copy(
                            selections = mannequin.selection.selections + (layer.id to restoreSel)
                        )
                        render(mannequin, nearbyViewers(mannequin))
                    }, 10L)
                    refreshDynamicLabels(manId, option, layer)
                    return // already rendered
                } else {
                    updateStatus(manId, "Channel: Locked")
                }
                refreshDynamicLabels(manId, option, layer)
            }
            "color" -> {
                if (state.mode == ControlMode.COLOR) {
                    cycleColor(layer, mannequin, state, player, backwards)?.let { updateStatus(manId, it) }
                } else {
                    state.mode = ControlMode.COLOR
                    refreshDynamicLabels(
                        manId,
                        mannequin.selection.selections[layer.id]?.option
                            ?: layerManager.optionsFor(layer.id).firstOrNull(),
                        layer
                    )
                    updateStatus(manId, "Mode: Color")
                }
            }
        }

        val viewers = nearbyViewers(mannequin)
        render(mannequin, viewers)
    }

    private fun executeModeAction(manId: UUID, mannequin: Mannequin, state: ControlState, player: Player, backwards: Boolean) {
        val layers = layerManager.definitionsInOrder()
        if (layers.isEmpty()) return
        val layer = layers.getOrNull(state.layerIndex % layers.size) ?: layers.first()

        when (state.mode) {
            ControlMode.PART -> cyclePart(layer, mannequin, state, player, backwards)?.let {
                updateStatus(manId, it)
                render(mannequin, nearbyViewers(mannequin))
            }
            ControlMode.COLOR -> cycleColor(layer, mannequin, state, player, backwards)?.let {
                updateStatus(manId, it)
                render(mannequin, nearbyViewers(mannequin))
            }
            else -> {}
        }
    }

    // ── Status & label helpers ──────────────────────────────────────────────────

    private fun updateStatus(mannequinId: UUID, msg: String) {
        statusText[mannequinId] = msg
        val visuals = buttonVisuals[mannequinId] ?: return
        visuals["status"]?.text = msg
        pushButtonToViewers(mannequinId, "status")
    }

    private fun refreshDynamicLabels(mannequinId: UUID, option: LayerOption?, layer: LayerDefinition?) {
        val channels = option?.masks?.keys?.sorted() ?: emptyList()
        val channelDisabled = channels.size <= 1
        val mode = controlState[mannequinId]?.mode
        val visuals = buttonVisuals[mannequinId] ?: return

        visuals["channel"]?.let {
            it.text = "Channel"
            it.textColor = if (channelDisabled) 0x888888 else 0xFFFFFF
        }
        visuals["part"]?.let {
            it.text = "Part"
            it.textColor = if (mode == ControlMode.PART) 0xFFFF55 else 0xFFFFFF
        }
        visuals["color"]?.let {
            it.text = "Color"
            it.textColor = if (mode == ControlMode.COLOR) 0xFFFF55 else 0xFFFFFF
        }

        pushButtonToViewers(mannequinId, "channel")
        pushButtonToViewers(mannequinId, "part")
        pushButtonToViewers(mannequinId, "color")
    }

    // ── Part / Colour cycling ───────────────────────────────────────────────────

    private fun cyclePart(layer: LayerDefinition, mannequin: Mannequin, state: ControlState, player: Player, backwards: Boolean): String? {
        val opts = layerManager.optionsFor(layer.id)
        if (opts.isEmpty()) return null
        val delta = if (backwards) -1 else 1
        val idx = (state.partIndex.getOrDefault(layer.id, 0) + delta + opts.size) % opts.size
        state.partIndex[layer.id] = idx
        val chosen = opts[idx]
        mannequin.selection = mannequin.selection.copy(
            selections = mannequin.selection.selections + (layer.id to LayerSelection(layer.id, chosen))
        )
        state.channelIndex[layer.id] = 0
        state.colorIndex[layer.id] = 0
        refreshDynamicLabels(mannequin.id, chosen, layer)

        // Fire per-layer part-change trigger
        val ph = basePlaceholders(player, mannequin).apply {
            put("layer", layer.id)
            put("part", chosen.displayName)
        }
        fireLayerTrigger("part-change", layer.id, ph)

        return "Part: ${chosen.displayName}"
    }

    private fun cycleColor(layer: LayerDefinition, mannequin: Mannequin, state: ControlState, player: Player, backwards: Boolean): String? {
        val current = mannequin.selection.selections[layer.id]
        val option = current?.option ?: layerManager.optionsFor(layer.id).firstOrNull() ?: return "Color: N/A"
        val palettes = option.allowedPalettes.ifEmpty { layer.defaultPalettes }
        val colors = palettes.flatMap { palId -> layerManager.palette(palId)?.colors.orEmpty() }
        val optionsList = listOf("Default") + colors.map { prettyName(it.name) }
        if (optionsList.isEmpty()) return "Color: N/A"
        val delta = if (backwards) -1 else 1
        val idx = (state.colorIndex.getOrDefault(layer.id, 0) + delta + optionsList.size) % optionsList.size
        state.colorIndex[layer.id] = idx
        val channelIdx = state.channelIndex.getOrDefault(layer.id, 0)
        val selectedChannel = option.masks.keys.sorted().getOrNull(channelIdx)

        val prevColors = current?.channelColors ?: emptyMap()
        val newColors = if (idx == 0 && selectedChannel != null) {
            prevColors - selectedChannel
        } else if (idx > 0 && selectedChannel != null) {
            val color = colors.getOrNull(idx - 1)?.color
            if (color != null) prevColors + (selectedChannel to color) else prevColors
        } else {
            prevColors
        }

        val selection = current?.copy(channelColors = newColors)
            ?: LayerSelection(layer.id, option, channelColors = newColors)
        mannequin.selection = mannequin.selection.copy(
            selections = mannequin.selection.selections + (layer.id to selection)
        )

        val colorLabel = if (idx == 0) "Default" else optionsList[idx]

        // Fire color-change trigger
        val ph = basePlaceholders(player, mannequin).apply {
            put("layer", layer.id)
            put("color", colorLabel)
            put("channel", (selectedChannel ?: 0).toString())
        }
        fireTrigger("color-change", ph)

        return "Color: $colorLabel"
    }

    // ── Utilities ───────────────────────────────────────────────────────────────

    private fun nearbyViewers(mannequin: Mannequin): List<Player> =
        plugin.server.onlinePlayers.filter {
            it.world == mannequin.location.world && it.location.distanceSquared(mannequin.location) <= VISIBLE_RANGE_SQ
        }

    private fun bootstrapSelection(): SkinSelection {
        val definitions = layerManager.definitionsInOrder()
        val preferredModel = plugin.config.getString("plugin.default-skin-model", "CLASSIC")?.uppercase() ?: "CLASSIC"
        val selections = definitions.associate { def ->
            val options = layerManager.optionsFor(def.id).ifEmpty {
                if (def.id.equals("body", ignoreCase = true)) layerManager.defaultSkinOptions() else emptyList()
            }
            val chosen = when {
                def.id.equals("body", ignoreCase = true) && preferredModel == "SLIM" ->
                    options.firstOrNull { it.id.equals("default_slim", ignoreCase = true) } ?: options.firstOrNull()
                def.id.equals("body", ignoreCase = true) ->
                    options.firstOrNull { it.id.equals("default", ignoreCase = true) } ?: options.firstOrNull()
                else -> options.firstOrNull()
            }
            def.id to LayerSelection(
                layerId = def.id,
                option = chosen
            )
        }
        return SkinSelection(selections)
    }

    // ── Trigger helpers ───────────────────────────────────────────────────────

    /**
     * Fire a list of console commands from a config path, substituting placeholders.
     * @param configPath  path under `triggers`, e.g. "hover" or "click"
     * @param placeholders  map of placeholder names (without braces) to values
     */
    private fun fireTrigger(configPath: String, placeholders: Map<String, String>) {
        val commands = plugin.config.getStringList("triggers.$configPath")
        dispatchCommands(commands, placeholders)
    }

    /**
     * Fire a per-layer trigger. Looks for `triggers.<base>.<layerKey>` first,
     * falling back to `triggers.<base>.default`.
     */
    private fun fireLayerTrigger(base: String, layerKey: String, placeholders: Map<String, String>) {
        val perLayer = plugin.config.getStringList("triggers.$base.$layerKey")
        val commands = perLayer.ifEmpty { plugin.config.getStringList("triggers.$base.default") }
        dispatchCommands(commands, placeholders)
    }

    private fun dispatchCommands(commands: List<String>, placeholders: Map<String, String>) {
        if (commands.isEmpty()) return
        val consoleSender = plugin.server.consoleSender
        for (template in commands) {
            var cmd = template
            for ((key, value) in placeholders) {
                cmd = cmd.replace("{$key}", value)
            }
            if (cmd.isNotBlank()) {
                plugin.server.dispatchCommand(consoleSender, cmd)
            }
        }
    }

    /** Build the base set of placeholders that every trigger gets. */
    private fun basePlaceholders(player: Player, mannequin: Mannequin): MutableMap<String, String> {
        val loc = mannequin.location
        return mutableMapOf(
            "player" to player.name,
            "x" to String.format("%.2f", loc.x),
            "y" to String.format("%.2f", loc.y),
            "z" to String.format("%.2f", loc.z)
        )
    }

    private fun prettyName(raw: String): String =
        raw.trim()
            .split(Regex("[_\\-\\s]+"))
            .filter { it.isNotBlank() }
            .joinToString(" ") { part ->
                part.lowercase().replaceFirstChar { ch -> ch.titlecase() }
            }
            .ifEmpty { raw }
}


