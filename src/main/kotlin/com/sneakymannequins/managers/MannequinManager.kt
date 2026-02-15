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
import org.bukkit.Sound
import org.bukkit.entity.Display
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.util.Transformation
import org.bukkit.util.Vector
import org.joml.AxisAngle4f
import org.joml.Vector3f
import java.util.UUID
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
 * Describes one HUD button: its identifier, default label, and position offset.
 * Offsets are in "screen-space" relative to the mannequin with billboard VERTICAL:
 *   tx: negative = left, positive = right   (from the player's point of view)
 *   ty: positive = up
 *   tz: depth behind the mannequin (typically a small negative value)
 */
private data class HudButton(
    val name: String,
    val defaultLabel: String,
    val tx: Float,
    val ty: Float,
    val tz: Float,
    val width: Float = 1.2f,
    val height: Float = 0.5f,
    val lineWidth: Int = 200
)

/** Runtime state for one HUD button entity. */
private data class HudButtonState(
    val entityUUID: UUID,   // Bukkit entity UUID
    val entityId: Int,      // NMS numeric entity id (for per-player packets)
    val button: HudButton
)

// ── Manager ─────────────────────────────────────────────────────────────────────

class MannequinManager(
    private val plugin: SneakyMannequins,
    private val layerManager: LayerManager,
    private val handler: VolatileHandler,
    private val persistence: MannequinPersistence
) {
    private val mannequins = mutableMapOf<UUID, Mannequin>()
    private val sentTo = mutableMapOf<UUID, MutableSet<UUID>>() // viewerId -> mannequins seen
    private val statusText = mutableMapOf<UUID, String>()       // mannequinId -> last action
    private val poseState = mutableMapOf<UUID, Boolean>()       // mannequinId -> true = T-pose
    private val controlState = mutableMapOf<UUID, ControlState>()
    private val interactionDebounce = mutableMapOf<Pair<UUID, String>, Long>()

    /** Per-mannequin HUD entities: buttonName → HudButtonState */
    private val hudButtons = mutableMapOf<UUID, Map<String, HudButtonState>>()

    /** Tracks which button each player is currently hovering (mannequinId to buttonName). */
    private val playerHover = mutableMapOf<UUID, Pair<UUID, String>>()

    private var hoverTaskId: Int = -1

    // ── HUD button layout ───────────────────────────────────────────────────────

    companion object {
        private const val VISIBLE_RANGE_SQ = 32.0 * 32.0
        private const val HOVER_RANGE = 6.0        // max range for hover scanning
        private const val INTERACT_RADIUS = 3.0f   // Interaction entity radius
        private const val HUD_BG_DEFAULT = 0x78000000.toInt()   // ARGB: semi-transparent black
        private const val HUD_BG_HIGHLIGHT = 0xB8336699.toInt() // ARGB: translucent blue
        private const val BUTTON_TOLERANCE = 0.35   // world-unit distance from look-ray to button centre

        private val HUD_LAYOUT = listOf(
            HudButton("status", "Controls", 0.0f, 2.8f, -2.0f, width = 2.8f, height = 0.7f, lineWidth = 256),
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

        /** Names of buttons that can be activated by clicking. "status" is display-only. */
        private val CLICKABLE_BUTTONS = setOf("model", "pose", "random", "layer", "part", "channel", "color")
    }

    // ── Lifecycle ───────────────────────────────────────────────────────────────

    fun loadFromDisk() {
        val loaded = persistence.load()
        loaded.forEach { (id, loc) ->
            val selection = bootstrapSelection()
            mannequins[id] = Mannequin(id = id, location = loc.clone(), selection = selection)
            controlState[id] = ControlState()
            cleanupControlEntities(id)
            spawnHud(id)
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
        spawnHud(mannequin.id)
        persist()
        return mannequin
    }

    fun get(id: UUID): Mannequin? = mannequins[id]

    fun remove(mannequinId: UUID, viewers: Collection<Player>) {
        viewers.forEach { viewer -> handler.destroyMannequin(viewer, mannequinId) }
        cleanupControlEntities(mannequinId)
        mannequins.remove(mannequinId)
        hudButtons.remove(mannequinId)
        controlState.remove(mannequinId)
        statusText.remove(mannequinId)
        poseState.remove(mannequinId)
        persist()
    }

    fun forgetViewer(viewerId: UUID) {
        sentTo.remove(viewerId)
        playerHover.remove(viewerId)
    }

    fun shutdown() {
        stopHoverTask()
        val viewers = plugin.server.onlinePlayers
        mannequins.keys.forEach { id ->
            viewers.forEach { viewer -> handler.destroyMannequin(viewer, id) }
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
        mannequins.clear()
        hudButtons.clear()
        sentTo.clear()
        statusText.clear()
        poseState.clear()
        controlState.clear()
        interactionDebounce.clear()
        playerHover.clear()

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

    // ── HUD spawning ────────────────────────────────────────────────────────────

    /**
     * Spawns the holographic HUD for a mannequin: one Interaction entity (radius 3)
     * plus a set of TextDisplay buttons positioned via billboard VERTICAL + translation.
     * All entities are placed at the mannequin's location.
     */
    private fun spawnHud(mannequinId: UUID) {
        val man = mannequins[mannequinId] ?: return
        val world = man.location.world ?: return
        val loc = man.location.clone()

        // Spawn the single Interaction entity
        world.spawn(loc, org.bukkit.entity.Interaction::class.java) { inter ->
            inter.interactionWidth = INTERACT_RADIUS * 2
            inter.interactionHeight = INTERACT_RADIUS * 2
            inter.isResponsive = true
            inter.scoreboardTags.add("sneakymannequin_control")
            inter.scoreboardTags.add("mannequin:$mannequinId")
            inter.scoreboardTags.add("button:interact")
        }

        val buttonStates = mutableMapOf<String, HudButtonState>()

        for (btn in HUD_LAYOUT) {
            val label = if (btn.name == "status") (statusText[mannequinId] ?: btn.defaultLabel) else btn.defaultLabel
            val td = world.spawn(loc, TextDisplay::class.java) { td ->
                td.text(Component.text(label))
                td.billboard = Display.Billboard.VERTICAL
                td.backgroundColor = Color.fromARGB(
                    (HUD_BG_DEFAULT ushr 24) and 0xFF,
                    (HUD_BG_DEFAULT ushr 16) and 0xFF,
                    (HUD_BG_DEFAULT ushr 8) and 0xFF,
                    HUD_BG_DEFAULT and 0xFF
                )
                td.isShadowed = false
                td.viewRange = 32f
                td.textOpacity = 255.toByte()
                td.lineWidth = btn.lineWidth
                td.isPersistent = false
                // Position via translation
                td.transformation = Transformation(
                    Vector3f(btn.tx, btn.ty, btn.tz),
                    AxisAngle4f(0f, 0f, 1f, 0f),
                    Vector3f(1f, 1f, 1f),
                    AxisAngle4f(0f, 0f, 1f, 0f)
                )
                td.scoreboardTags.add("sneakymannequin_control")
                td.scoreboardTags.add("mannequin:$mannequinId")
                td.scoreboardTags.add("button:${btn.name}")
            }
            val craftEntity = td as org.bukkit.craftbukkit.entity.CraftEntity
            val nmsId = craftEntity.handle.id
            val bukkitUUID: UUID = craftEntity.uniqueId
            buttonStates[btn.name] = HudButtonState(bukkitUUID, nmsId, btn)
        }

        hudButtons[mannequinId] = buttonStates

        // Refresh dynamic labels (channel disabled/active mode colours)
        val state = controlState.getOrPut(mannequinId) { ControlState() }
        val layers = layerManager.definitionsInOrder()
        val layer = layers.getOrNull(state.layerIndex % layers.size) ?: layers.firstOrNull()
        val option = layer?.let {
            mannequins[mannequinId]?.selection?.selections?.get(it.id)?.option
                ?: layerManager.optionsFor(it.id).firstOrNull()
        }
        refreshDynamicLabels(mannequinId, option, layer)
    }

    // ── HUD cleanup ─────────────────────────────────────────────────────────────

    /** Remove all control/HUD entities for a mannequin. */
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
        hudButtons.remove(mannequinId)
    }

    // ── Hover task ──────────────────────────────────────────────────────────────

    fun startHoverTask() {
        if (hoverTaskId != -1) return
        hoverTaskId = plugin.server.scheduler.scheduleSyncRepeatingTask(plugin, Runnable {
            tickHover()
        }, 0L, 1L)
    }

    fun stopHoverTask() {
        if (hoverTaskId != -1) {
            plugin.server.scheduler.cancelTask(hoverTaskId)
            hoverTaskId = -1
        }
        playerHover.clear()
    }

    private fun tickHover() {
        for (player in plugin.server.onlinePlayers) {
            val nearest = nearestMannequin(player.location, HOVER_RANGE)
            if (nearest == null) {
                clearHover(player)
                continue
            }
            val hovered = computeHoveredButton(player, nearest)
            val prev = playerHover[player.uniqueId]
            val cur = hovered?.let { nearest.id to it }

            if (cur != prev) {
                // Un-highlight previous
                if (prev != null) {
                    sendButtonHighlight(player, prev.first, prev.second, HUD_BG_DEFAULT)
                }
                // Highlight new
                if (cur != null) {
                    sendButtonHighlight(player, cur.first, cur.second, HUD_BG_HIGHLIGHT)
                    player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.15f, 1.8f)
                }
                if (cur != null) playerHover[player.uniqueId] = cur else playerHover.remove(player.uniqueId)
            }
        }
    }

    private fun clearHover(player: Player) {
        val prev = playerHover.remove(player.uniqueId) ?: return
        sendButtonHighlight(player, prev.first, prev.second, HUD_BG_DEFAULT)
    }

    private fun sendButtonHighlight(player: Player, mannequinId: UUID, buttonName: String, argb: Int) {
        val state = hudButtons[mannequinId]?.get(buttonName) ?: return
        handler.sendTextDisplayHighlight(player, state.entityId, argb)
    }

    // ── Look-direction → button resolution ──────────────────────────────────────

    /**
     * Determine which HUD button (if any) the player's crosshair is aiming at.
     * Returns the button name, or null if none is within tolerance.
     */
    private fun computeHoveredButton(player: Player, mannequin: Mannequin): String? {
        val buttons = hudButtons[mannequin.id] ?: return null
        val eyeLoc = player.eyeLocation
        val lookDir = eyeLoc.direction.normalize()
        val eyeVec = eyeLoc.toVector()
        val manVec = mannequin.location.toVector()

        var bestName: String? = null
        var bestDist = Double.MAX_VALUE

        for ((name, state) in buttons) {
            if (name == "status") continue // status is not clickable
            val btn = state.button
            val worldPos = buttonWorldPos(manVec, eyeVec, btn.tx, btn.ty, btn.tz)
            val dist = distanceFromRay(eyeVec, lookDir, worldPos)
            if (dist < bestDist) {
                bestDist = dist
                bestName = name
            }
        }

        return if (bestDist <= BUTTON_TOLERANCE) bestName else null
    }

    /**
     * Compute the world-space position of a billboard-VERTICAL button, accounting
     * for the screen-facing rotation toward the given viewer position.
     *
     * Billboard VERTICAL makes the entity face the viewer, so the entity's local
     * +X axis is the viewer's visual LEFT.  We negate tx so that a positive tx in
     * the layout appears on the viewer's right.
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
        // Entity-local right (= viewer's LEFT), so we negate for world mapping
        val rightX = fwdZ   // negated compared to player-right
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
        if (t < 0) return Double.MAX_VALUE // behind the viewer
        val closest = rayOrigin.clone().add(rayDir.clone().multiply(t))
        return closest.distance(point)
    }

    // ── Interaction handling ────────────────────────────────────────────────────

    /**
     * Called when a player interacts with (left-click / right-click) the single
     * Interaction entity of a mannequin.  We resolve which button (if any) the
     * player was aiming at and dispatch accordingly.
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

        // Determine what the player is looking at
        val hoveredButton = computeHoveredButton(player, mannequin)

        if (hoveredButton != null && hoveredButton in CLICKABLE_BUTTONS) {
            executeButton(hoveredButton, manId, mannequin, state, player, backwards)
        } else {
            // No button targeted → mode-based cycling (like the old area click)
            executeModeAction(manId, mannequin, state, backwards)
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
                // Placeholder — no functionality yet
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
                val option = mannequin.selection.selections[newLayer.id]?.option ?: layerManager.optionsFor(newLayer.id).firstOrNull()
                refreshDynamicLabels(manId, option, newLayer)
            }
            "part" -> {
                if (state.mode == ControlMode.PART) {
                    cyclePart(layer, mannequin, state, backwards)?.let { updateStatus(manId, it) }
                } else {
                    state.mode = ControlMode.PART
                    refreshDynamicLabels(
                        manId,
                        mannequin.selection.selections[layer.id]?.option ?: layerManager.optionsFor(layer.id).firstOrNull(),
                        layer
                    )
                    updateStatus(manId, "Mode: Part")
                }
            }
            "channel" -> {
                val option = mannequin.selection.selections[layer.id]?.option ?: layerManager.optionsFor(layer.id).firstOrNull()
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
                    cycleColor(layer, mannequin, state, backwards)?.let { updateStatus(manId, it) }
                } else {
                    state.mode = ControlMode.COLOR
                    refreshDynamicLabels(
                        manId,
                        mannequin.selection.selections[layer.id]?.option ?: layerManager.optionsFor(layer.id).firstOrNull(),
                        layer
                    )
                    updateStatus(manId, "Mode: Color")
                }
            }
        }

        // Render to nearby players
        val viewers = nearbyViewers(mannequin)
        render(mannequin, viewers)
    }

    /** Mode-based cycling when the player clicks the interaction entity but isn't aiming at a button. */
    private fun executeModeAction(manId: UUID, mannequin: Mannequin, state: ControlState, backwards: Boolean) {
        val layers = layerManager.definitionsInOrder()
        if (layers.isEmpty()) return
        val layer = layers.getOrNull(state.layerIndex % layers.size) ?: layers.first()

        when (state.mode) {
            ControlMode.PART -> cyclePart(layer, mannequin, state, backwards)?.let {
                updateStatus(manId, it)
                render(mannequin, nearbyViewers(mannequin))
            }
            ControlMode.COLOR -> cycleColor(layer, mannequin, state, backwards)?.let {
                updateStatus(manId, it)
                render(mannequin, nearbyViewers(mannequin))
            }
            else -> {} // no active mode, ignore
        }
    }

    // ── Status & label helpers ──────────────────────────────────────────────────

    private fun updateStatus(mannequinId: UUID, msg: String) {
        statusText[mannequinId] = msg
        val state = hudButtons[mannequinId]?.get("status") ?: return
        val man = mannequins[mannequinId] ?: return
        val world = man.location.world ?: return
        // Find the real TextDisplay entity and update its text
        world.getNearbyEntities(man.location, 2.0, 4.0, 2.0).forEach {
            if (it is TextDisplay && it.uniqueId == state.entityUUID) {
                it.text(Component.text(msg))
            }
        }
    }

    private fun refreshDynamicLabels(mannequinId: UUID, option: LayerOption?, layer: LayerDefinition?) {
        val channels = option?.masks?.keys?.sorted() ?: emptyList()
        val channelDisabled = channels.size <= 1
        val mode = controlState[mannequinId]?.mode
        val man = mannequins[mannequinId] ?: return
        val world = man.location.world ?: return
        val buttons = hudButtons[mannequinId] ?: return

        fun setLabel(buttonName: String, text: Component) {
            val bs = buttons[buttonName] ?: return
            world.getNearbyEntities(man.location, 2.0, 4.0, 2.0).forEach {
                if (it is TextDisplay && it.uniqueId == bs.entityUUID) {
                    it.text(text)
                }
            }
        }

        setLabel("channel", Component.text("Channel", if (channelDisabled) NamedTextColor.GRAY else NamedTextColor.WHITE))
        setLabel("part", Component.text("Part", if (mode == ControlMode.PART) NamedTextColor.YELLOW else NamedTextColor.WHITE))
        setLabel("color", Component.text("Color", if (mode == ControlMode.COLOR) NamedTextColor.YELLOW else NamedTextColor.WHITE))
    }

    // ── Part / Colour cycling ───────────────────────────────────────────────────

    private fun cyclePart(layer: LayerDefinition, mannequin: Mannequin, state: ControlState, backwards: Boolean): String? {
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
        return "Part: ${chosen.displayName}"
    }

    private fun cycleColor(layer: LayerDefinition, mannequin: Mannequin, state: ControlState, backwards: Boolean): String? {
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
        return when (idx) {
            0 -> "Color: Default"
            else -> "Color: ${optionsList[idx]}"
        }
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

    private fun prettyName(raw: String): String =
        raw.trim()
            .split(Regex("[_\\-\\s]+"))
            .filter { it.isNotBlank() }
            .joinToString(" ") { part ->
                part.lowercase().replaceFirstChar { ch -> ch.titlecase() }
            }
            .ifEmpty { raw }
}
