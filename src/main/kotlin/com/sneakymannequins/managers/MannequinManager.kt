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
import com.sneakymannequins.render.AnimationManager
import com.sneakymannequins.render.PixelProjector
import com.sneakymannequins.render.RenderMode
import com.sneakymannequins.render.RenderSettings
import com.sneakymannequins.util.SkinComposer
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
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
    /** Per-layer selected palette index (into the resolved palette list). -1 = "All" (use every palette). */
    val paletteIndex: MutableMap<String, Int> = mutableMapOf(),
    var mode: ControlMode = ControlMode.NONE
)

private enum class ControlMode { NONE, PART, COLOR }

/**
 * Describes one HUD button loaded from config.
 * Translations are in entity-local space (billboard FIXED, rotated by yaw):
 *   tx: positive = right from the viewer's POV
 *   ty: positive = up
 *   tz: negative = behind the mannequin
 */
private data class HudButton(
    val name: String,
    val textMM: String,            // raw MiniMessage string (for generating variants)
    val textJson: String,          // pre-serialised JSON component
    val activeTextJson: String?,   // JSON component for part/color active mode
    val disabledTextJson: String?, // JSON component when button is disabled (e.g. channel with no masks)
    val tx: Float,
    val ty: Float,
    val tz: Float,
    val lineWidth: Int,
    val bgDefault: Int,
    val bgHighlight: Int
)

/** Canonical per-button visual state (shared across all viewers). */
private data class ButtonVisual(
    var textJson: String,
    var bgColor: Int
)

/** Per-player virtual HUD tracking. */
private data class PlayerHudState(
    val mannequinId: UUID,
    val entityIds: Map<String, Int>,  // buttonName → virtual entity ID
    val frameEntityId: Int? = null,   // optional backdrop ItemDisplay
    var lastYaw: Float = Float.NaN,
    var hoveredButton: String? = null,
    /** True once the fly-in animation has finished and the HUD accepts rotation / hover updates. */
    var ready: Boolean = false,
    /** True while the HUD is interpolating away before being destroyed. */
    var flyingAway: Boolean = false,
    /** Remaining ticks in the server-driven fly-in animation (0 = done / not animating). */
    var flyInTicksLeft: Int = 0
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
    /** playerId → expiry timestamp for random confirmation */
    private val randomConfirm = mutableMapOf<UUID, Long>()

    /** Manages INSTANT / BUILD pixel delivery to viewers. */
    private val animationManager = AnimationManager(plugin, handler)

    // ── Config-driven radii ─────────────────────────────────────────────────────

    /** Radius at which a mannequin first appears for a player. */
    private val viewRadius: Double
        get() = plugin.config.getDouble("rendering.view-radius", 32.0)

    /** Radius within which a player keeps receiving updates. */
    private val updateRadius: Double
        get() = plugin.config.getDouble("rendering.update-radius", 48.0)

    /** Read [RenderSettings] from config for first-seen or update context. */
    private fun readRenderSettings(isFirstSeen: Boolean): RenderSettings {
        val path = if (isFirstSeen) "rendering.first-seen" else "rendering.update"
        val modeStr = plugin.config.getString("$path.mode", "INSTANT")?.uppercase() ?: "INSTANT"
        val mode = runCatching { RenderMode.valueOf(modeStr) }.getOrDefault(RenderMode.INSTANT)
        val interval = plugin.config.getInt("$path.tick-interval", 1).coerceAtLeast(1)
        val skip = plugin.config.getDouble("$path.skip-chance", 0.5).coerceIn(0.0, 1.0)
        val flyIn = plugin.config.getInt("$path.fly-in-count", 0).coerceAtLeast(0)
        return RenderSettings(mode, interval, skip, flyIn)
    }

    /** Per-mannequin canonical button visuals. */
    private val buttonVisuals = mutableMapOf<UUID, MutableMap<String, ButtonVisual>>()

    /** Per-player virtual HUD state. */
    private val playerHuds = mutableMapOf<UUID, PlayerHudState>()

    private var hoverTaskId: Int = -1
    private var viewCheckCounter: Int = 0

    // ── HUD button layout ───────────────────────────────────────────────────────

    companion object {
        private const val HOVER_RANGE = 6.0
        private const val INTERACT_RADIUS = 3.0f
        private const val HUD_BG_DEFAULT = 0x78000000.toInt()       // fallback semi-transparent black
        private const val HUD_BG_HIGHLIGHT = 0xB8336699.toInt()     // fallback translucent blue
        private const val BUTTON_TOLERANCE = 0.35
        private const val ROTATION_INTERP_TICKS = 3
        private const val YAW_THRESHOLD = 0.02f                     // radians (~1°)
        private const val FRAME_Y_OFFSET = 10.0
        private const val HUD_FLY_Z_OFFSET = -10.0f                 // local-Z offset for fly-in / fly-out (negative = behind the HUD face, away from player)
        private const val HUD_FLY_INTERP_TICKS = 10                 // interpolation duration (ticks)
        private const val HUD_DISMISS_RANGE = 8.0                   // dismiss HUD when player is this far (blocks)

        /** Canonical ordered list of button names. */
        private val BUTTON_ORDER = listOf("status", "model", "pose", "layer", "random", "part", "channel", "palette", "color")

        /** Button names that respond to clicks.  "status" is display-only. */
        private val CLICKABLE_BUTTONS = setOf("model", "pose", "random", "layer", "part", "channel", "palette", "color")

        /** Hardcoded defaults used when a key is absent from config. */
        private data class BtnDefault(val text: String, val activeText: String?, val tx: Float, val ty: Float, val tz: Float, val lineWidth: Int)
        private val BUTTON_DEFAULTS = mapOf(
            "status"  to BtnDefault("<white>{message}", null,            0.0f,  2.8f, -2.0f, 256),
            "model"   to BtnDefault("<white>Model",    null,           -1.1f,  2.2f, -2.0f, 200),
            "pose"    to BtnDefault("<white>Pose",     null,           -1.1f,  1.7f, -2.0f, 200),
            "layer"   to BtnDefault("<white>Layer",    null,           -1.1f,  1.2f, -2.0f, 200),
            "random"  to BtnDefault("<white>Random",   null,           -1.1f,  0.7f, -2.0f, 200),
            "part"    to BtnDefault("<white>Part",     "<yellow>Part",  1.1f,  2.2f, -2.0f, 200),
            "channel" to BtnDefault("<white>Channel",  null,            1.1f,  1.7f, -2.0f, 200),
            "palette" to BtnDefault("<white>Palette",  null,            1.1f,  1.2f, -2.0f, 200),
            "color"   to BtnDefault("<white>Color",    "<yellow>Color", 1.1f,  0.7f, -2.0f, 200),
        )

        private val mm = MiniMessage.miniMessage()
        private val gsonSer = GsonComponentSerializer.gson()

        /** Parse a MiniMessage string and serialise to JSON (for NMS). */
        fun mmToJson(miniMsg: String): String = gsonSer.serialize(mm.deserialize(miniMsg))

        /** Build a JSON component from plain text + RGB colour. */
        fun textToJson(text: String, color: Int = 0xFFFFFF): String =
            gsonSer.serialize(Component.text(text).color(TextColor.color(color)))

        /** Parse an ARGB hex string (e.g. "B8336699") to an Int. */
        private fun parseArgb(hex: String?): Int? {
            if (hex.isNullOrBlank()) return null
            return hex.removePrefix("#").toLongOrNull(16)?.toInt()
        }
    }

    /** Buttons loaded from config (refreshed on reload). */
    private var hudButtons: List<HudButton> = emptyList()

    init { hudButtons = loadHudButtons() }

    /** Look up a button config by name. */
    private fun buttonByName(name: String): HudButton? = hudButtons.firstOrNull { it.name == name }

    /**
     * Resolve the current (fresh) [LayerOption] for a layer on a mannequin.
     * The mannequin's selection may hold a stale reference after a layer
     * reload / remask, so we always look up the option by ID from the layer
     * manager and fall back to the stale copy only if it was removed.
     */
    private fun freshOption(layerId: String, mannequin: Mannequin): LayerOption? {
        val selOption = mannequin.selection.selections[layerId]?.option
        val opts = layerManager.optionsFor(layerId)
        return if (selOption != null) opts.find { it.id == selOption.id } ?: selOption
        else opts.firstOrNull()
    }

    /** Read the hud-buttons config section and build the button list. */
    private fun loadHudButtons(): List<HudButton> {
        val globalBgDef = parseArgb(plugin.config.getString("hud-buttons.bg-default")) ?: HUD_BG_DEFAULT
        val globalBgHi  = parseArgb(plugin.config.getString("hud-buttons.bg-highlight")) ?: HUD_BG_HIGHLIGHT

        return BUTTON_ORDER.map { name ->
            val def = BUTTON_DEFAULTS[name]!!
            val sec = plugin.config.getConfigurationSection("hud-buttons.$name")
            val textMM      = sec?.getString("text") ?: def.text
            val activeMM    = sec?.getString("active-text") ?: def.activeText
            val disabledMM  = sec?.getString("disabled-text")
            val tx = sec?.getDouble("translation.x", def.tx.toDouble())?.toFloat() ?: def.tx
            val ty = sec?.getDouble("translation.y", def.ty.toDouble())?.toFloat() ?: def.ty
            val tz = sec?.getDouble("translation.z", def.tz.toDouble())?.toFloat() ?: def.tz
            val lw = sec?.getInt("line-width", def.lineWidth) ?: def.lineWidth
            val bgDef = parseArgb(sec?.getString("bg-default")) ?: globalBgDef
            val bgHi  = parseArgb(sec?.getString("bg-highlight")) ?: globalBgHi

            HudButton(
                name = name,
                textMM = textMM,
                textJson = mmToJson(textMM),
                activeTextJson = activeMM?.let { mmToJson(it) },
                disabledTextJson = disabledMM?.let { mmToJson(it) },
                tx = tx, ty = ty, tz = tz,
                lineWidth = lw,
                bgDefault = bgDef,
                bgHighlight = bgHi
            )
        }
    }

    // ── Lifecycle ───────────────────────────────────────────────────────────────

    fun loadFromDisk() {
        val loaded = persistence.load()
        loaded.forEach { (id, loc, slim) ->
            val selection = bootstrapSelection()
            val mannequin = Mannequin(id = id, location = loc.clone(), selection = selection, slimModel = slim)
            mannequins[id] = mannequin
            controlState[id] = ControlState()
            randomize(mannequin, randomizeModel = true)
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
        randomize(mannequin, randomizeModel = true)
        initButtonVisuals(mannequin.id)
        spawnInteractionEntity(mannequin.id)
        persist()
        return mannequin
    }

    fun get(id: UUID): Mannequin? = mannequins[id]

    fun remove(mannequinId: UUID, viewers: Collection<Player>) {
        animationManager.cancelMannequin(mannequinId)
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
        animationManager.cleanupPlayer(viewerId)
        destroyPlayerHud(viewerId)
    }

    fun shutdown() {
        stopHoverTask()
        animationManager.stop()
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
        animationManager.stop()
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

        hudButtons = loadHudButtons()
        loadFromDisk()
        animationManager.start()
        startHoverTask()

        plugin.server.onlinePlayers.forEach { viewer -> renderVisibleTo(viewer) }
    }

    // ── Rendering ───────────────────────────────────────────────────────────────

    fun renderVisibleTo(viewer: Player) {
        val viewRadiusSq = viewRadius * viewRadius
        val updateRadiusSq = updateRadius * updateRadius

        for (man in mannequins.values) {
            if (man.location.world != viewer.world) continue
            val distSq = man.location.distanceSquared(viewer.location)

            val seen = sentTo.computeIfAbsent(viewer.uniqueId) { mutableSetOf() }
            if (man.id !in seen) {
                if (distSq <= viewRadiusSq) {
                    renderFull(man, listOf(viewer), isFirstSeen = true)
                    seen += man.id
                }
            } else {
                if (distSq <= updateRadiusSq) {
                    render(man, listOf(viewer))
                }
            }
        }
    }

    /**
     * Lightweight first-seen check: renders any mannequin within view-radius
     * that the player hasn't seen yet.  Called periodically from the tick handler
     * so BUILD animations trigger reliably when a player walks into range.
     */
    private fun checkFirstSeen(viewer: Player) {
        val viewRadiusSq = viewRadius * viewRadius
        val seen = sentTo.computeIfAbsent(viewer.uniqueId) { mutableSetOf() }
        for (man in mannequins.values) {
            if (man.id in seen) continue
            if (man.location.world != viewer.world) continue
            if (man.location.distanceSquared(viewer.location) > viewRadiusSq) continue
            renderFull(man, listOf(viewer), isFirstSeen = true)
            seen += man.id
            fireTrigger("first-seen", basePlaceholders(viewer, man))
        }
    }

    /** Resolves the current (fresh) [LayerOption] for a layer+option ID,
     *  so the composer always reads up-to-date mask paths after a remask. */
    private val optionResolver: (String, String) -> com.sneakymannequins.model.LayerOption? = { layerId, optionId ->
        layerManager.optionsFor(layerId).find { it.id == optionId }
    }

    fun render(mannequin: Mannequin, viewers: Collection<Player>, forceInstant: Boolean = false): Int {
        val definitions = layerManager.definitionsInOrder()
        val composed = SkinComposer.compose(definitions, mannequin.selection, useSlimModel = isSlimModel(mannequin), optionResolver = optionResolver)
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
            slimArms = isSlimModel(mannequin),
            tPose = poseState[mannequin.id] == true
        )
        val settings = if (forceInstant) RenderSettings(RenderMode.INSTANT)
                        else readRenderSettings(isFirstSeen = false)
        viewers.forEach { viewer -> animationManager.deliver(viewer, mannequin.id, projected, settings) }
        return diff.size
    }

    private fun renderFull(mannequin: Mannequin, viewers: Collection<Player>, isFirstSeen: Boolean = false) {
        val definitions = layerManager.definitionsInOrder()
        val composed = SkinComposer.compose(definitions, mannequin.selection, useSlimModel = isSlimModel(mannequin), optionResolver = optionResolver)
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
            slimArms = isSlimModel(mannequin),
            tPose = poseState[mannequin.id] == true
        )
        val settings = readRenderSettings(isFirstSeen)
        viewers.forEach { viewer -> animationManager.deliver(viewer, mannequin.id, projected, settings) }
    }

    private fun isSlimModel(mannequin: Mannequin): Boolean = mannequin.slimModel

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
        for (btn in hudButtons) {
            val json = if (btn.name == "status") {
                formatStatusText(statusText[mannequinId])
            } else {
                btn.textJson
            }
            visuals[btn.name] = ButtonVisual(textJson = json, bgColor = btn.bgDefault)
        }
        buttonVisuals[mannequinId] = visuals
    }

    /**
     * Apply the status button's MiniMessage template to a message.
     * If the template contains `{message}`, the placeholder is substituted;
     * otherwise the message is wrapped in the template formatting.
     */
    private fun formatStatusText(msg: String?): String {
        val btn = buttonByName("status")
        val template = btn?.textMM ?: "<white>{message}"
        val defaultMsg = plugin.config.getString("hud-buttons.status.default-message") ?: "Controls"
        val text = msg ?: defaultMsg
        val formatted = if ("{message}" in template) template.replace("{message}", text) else text
        return mmToJson(formatted)
    }

    /** Spawn the full virtual HUD for a player viewing a mannequin.
     *  All elements start with a local-Z offset; the tick loop drives them
     *  toward their final position one step per tick (server-side animation).
     */
    private fun spawnPlayerHud(player: Player, mannequin: Mannequin, yaw: Float) {
        val manId = mannequin.id
        val loc = mannequin.location
        val visuals = buttonVisuals[manId] ?: return
        val ids = mutableMapOf<String, Int>()

        for (btn in hudButtons) {
            val entityId = handler.allocateEntityId()
            val vis = visuals[btn.name] ?: ButtonVisual(textJson = btn.textJson, bgColor = btn.bgDefault)
            // Spawn at the offset Z position (far away); the tick loop will step it forward
            handler.spawnHudTextDisplay(
                viewer = player, entityId = entityId,
                x = loc.x, y = loc.y, z = loc.z,
                textJson = vis.textJson, bgColor = vis.bgColor,
                tx = btn.tx, ty = btn.ty, tz = btn.tz + HUD_FLY_Z_OFFSET,
                yaw = yaw, lineWidth = btn.lineWidth
            )
            ids[btn.name] = entityId
        }

        // Spawn optional backdrop ItemDisplay from config (also at offset Z)
        val frameId = spawnHudFrame(player, loc, yaw, zOffset = HUD_FLY_Z_OFFSET)

        playerHuds[player.uniqueId] = PlayerHudState(
            mannequinId = manId,
            entityIds = ids,
            frameEntityId = frameId,
            lastYaw = yaw,
            flyInTicksLeft = HUD_FLY_INTERP_TICKS
        )

        fireTrigger("control-open", basePlaceholders(player, mannequin))
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

    /**
     * Animate the HUD flying away (local +Z), then destroy it after the
     * interpolation completes.  The [hud] is marked as [flyingAway] so the
     * tick loop stops processing it while the animation plays.
     */
    private fun flyAwayPlayerHud(player: Player, hud: PlayerHudState) {
        if (hud.flyingAway) return
        hud.flyingAway = true

        val manId = hud.mannequinId
        val mannequin = mannequins[manId]
        if (mannequin != null) {
            fireTrigger("control-closed", basePlaceholders(player, mannequin))
        }
        val visuals = buttonVisuals[manId]
        val yaw = hud.lastYaw

        if (visuals != null) {
            // Send fly-away updates (push Z further from the player)
            for (btn in hudButtons) {
                val entityId = hud.entityIds[btn.name] ?: continue
                val vis = visuals[btn.name] ?: continue
                handler.updateHudTextDisplay(
                    viewer = player, entityId = entityId,
                    textJson = vis.textJson, bgColor = vis.bgColor,
                    tx = btn.tx, ty = btn.ty, tz = btn.tz + HUD_FLY_Z_OFFSET,
                    yaw = yaw, lineWidth = btn.lineWidth,
                    interpolationTicks = HUD_FLY_INTERP_TICKS
                )
            }
            updateHudFrame(player, hud, yaw, zOffset = HUD_FLY_Z_OFFSET, interpolationTicks = HUD_FLY_INTERP_TICKS)
        }

        // Schedule destroy after the interpolation finishes
        val playerId = player.uniqueId
        plugin.server.scheduler.scheduleSyncDelayedTask(plugin, Runnable {
            // Only destroy if this exact HUD instance is still active
            val currentHud = playerHuds[playerId]
            if (currentHud === hud) {
                destroyPlayerHud(playerId, plugin.server.getPlayer(playerId))
            }
        }, (HUD_FLY_INTERP_TICKS + 1).toLong())
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

    /** Push the current visual state of one button to all players viewing this mannequin.
     *  Skips HUDs that are mid-animation (fly-in or fly-out) to avoid overriding
     *  the interpolation. */
    private fun pushButtonToViewers(mannequinId: UUID, buttonName: String) {
        val vis = buttonVisuals[mannequinId]?.get(buttonName) ?: return
        val btn = buttonByName(buttonName) ?: return
        playerHuds.forEach { (playerId, state) ->
            if (state.mannequinId != mannequinId) return@forEach
            if (state.flyingAway || !state.ready) return@forEach
            val player = plugin.server.getPlayer(playerId) ?: return@forEach
            val entityId = state.entityIds[buttonName] ?: return@forEach
            handler.updateHudTextDisplay(
                viewer = player, entityId = entityId,
                textJson = vis.textJson,
                bgColor = if (state.hoveredButton == buttonName) btn.bgHighlight else vis.bgColor,
                tx = btn.tx, ty = btn.ty, tz = btn.tz,
                yaw = state.lastYaw, lineWidth = btn.lineWidth,
                interpolationTicks = 0 // instant text update
            )
        }
    }

    // ── HUD frame (ItemDisplay backdrop) ───────────────────────────────────────

    /** Read hud-frame config and spawn the ItemDisplay if enabled. Returns entity ID or null. */
    private fun spawnHudFrame(player: Player, loc: Location, yaw: Float, zOffset: Float = 0f): Int? {
        if (!plugin.config.getBoolean("hud-frame.enabled", false)) return null
        val item = plugin.config.getString("hud-frame.item") ?: "minecraft:glass_pane"
        val cmd = plugin.config.getInt("hud-frame.custom-model-data", 0)
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
            item = item, customModelData = cmd, displayContext = displayCtx,
            tx = tx, ty = ty - FRAME_Y_OFFSET.toFloat(), tz = tz + zOffset,
            sx = sx, sy = sy, sz = sz,
            yaw = yaw
        )
        return entityId
    }

    /** Update the frame's rotation (and optionally Z position) with interpolation. */
    private fun updateHudFrame(
        player: Player, hud: PlayerHudState, yaw: Float,
        zOffset: Float = 0f,
        interpolationTicks: Int = ROTATION_INTERP_TICKS
    ) {
        val frameId = hud.frameEntityId ?: return
        if (!plugin.config.getBoolean("hud-frame.enabled", false)) return
        val item = plugin.config.getString("hud-frame.item") ?: "minecraft:glass_pane"
        val cmd = plugin.config.getInt("hud-frame.custom-model-data", 0)
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
            item = item, customModelData = cmd, displayContext = displayCtx,
            tx = tx, ty = ty - FRAME_Y_OFFSET.toFloat(), tz = tz + zOffset,
            sx = sx, sy = sy, sz = sz,
            yaw = yaw,
            interpolationTicks = interpolationTicks
        )
    }

    // ── Hover + rotation tick ───────────────────────────────────────────────────

    fun startHoverTask() {
        if (hoverTaskId != -1) return
        animationManager.start()
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
     *  0. (every 10 ticks) Check for mannequins entering view-radius.
     *  1. If the player has a HUD, check whether it should be dismissed
     *     (mannequin removed or player moved beyond [HUD_DISMISS_RANGE]).
     *  2. Update the HUD rotation (with interpolation) if the player moved.
     *  3. Determine which button the crosshair is over → highlight it.
     *
     * The HUD is **not** spawned here — it is only created when the player
     * interacts with the Interaction entity (see [handleInteract]).
     */
    private fun tickHoverAndRotation() {
        // ── Periodic first-seen check (every 10 ticks = 0.5 s) ──────────
        val doViewCheck = (++viewCheckCounter % 10 == 0)

        for (player in plugin.server.onlinePlayers) {
            if (doViewCheck) checkFirstSeen(player)

            val currentHud = playerHuds[player.uniqueId] ?: continue

            // Skip all processing while flying away
            if (currentHud.flyingAway) continue

            val mannequin = mannequins[currentHud.mannequinId]

            // ── Dismiss HUD if mannequin was removed ────────────────────────
            if (mannequin == null) {
                destroyPlayerHud(player)
                continue
            }

            // ── Dismiss HUD if player moved too far away ────────────────────
            val sameWorld = mannequin.location.world == player.location.world
            if (!sameWorld || mannequin.location.distance(player.location) > HUD_DISMISS_RANGE) {
                flyAwayPlayerHud(player, currentHud)
                continue
            }

            // ── Compute facing yaw (radians, from mannequin toward player) ───
            val dx = player.location.x - mannequin.location.x
            val dz = player.location.z - mannequin.location.z
            val yaw = atan2(dx, dz).toFloat()

            // ── Server-driven fly-in animation ──────────────────────────────
            if (currentHud.flyInTicksLeft > 0) {
                currentHud.flyInTicksLeft--
                // Lerp Z offset from HUD_FLY_Z_OFFSET → 0 over HUD_FLY_INTERP_TICKS
                val progress = 1.0f - currentHud.flyInTicksLeft.toFloat() / HUD_FLY_INTERP_TICKS
                val currentZOffset = HUD_FLY_Z_OFFSET * (1.0f - progress) // offset shrinks to 0

                val visuals = buttonVisuals[mannequin.id] ?: continue
                for (btn in hudButtons) {
                    val entityId = currentHud.entityIds[btn.name] ?: continue
                    val vis = visuals[btn.name] ?: continue
                    handler.updateHudTextDisplay(
                        viewer = player, entityId = entityId,
                        textJson = vis.textJson, bgColor = vis.bgColor,
                        tx = btn.tx, ty = btn.ty, tz = btn.tz + currentZOffset,
                        yaw = yaw, lineWidth = btn.lineWidth,
                        interpolationTicks = 2 // micro-interpolation for smoothness
                    )
                }
                updateHudFrame(player, currentHud, yaw, zOffset = currentZOffset, interpolationTicks = 2)
                currentHud.lastYaw = yaw

                if (currentHud.flyInTicksLeft == 0) {
                    currentHud.ready = true
                }
                continue // skip normal rotation / hover during fly-in
            }

            // ── Update rotation if yaw changed ──────────────────────────────
            if (abs(yaw - currentHud.lastYaw) > YAW_THRESHOLD) {
                val visuals = buttonVisuals[mannequin.id] ?: continue
                for (btn in hudButtons) {
                    val entityId = currentHud.entityIds[btn.name] ?: continue
                    val vis = visuals[btn.name] ?: continue
                    val bg = if (currentHud.hoveredButton == btn.name) btn.bgHighlight else vis.bgColor
                    handler.updateHudTextDisplay(
                        viewer = player, entityId = entityId,
                        textJson = vis.textJson, bgColor = bg,
                        tx = btn.tx, ty = btn.ty, tz = btn.tz,
                        yaw = yaw, lineWidth = btn.lineWidth,
                        interpolationTicks = ROTATION_INTERP_TICKS
                    )
                }
                // Rotate the backdrop frame alongside the buttons
                updateHudFrame(player, currentHud, yaw)
                currentHud.lastYaw = yaw
            }

            // ── Hover detection ─────────────────────────────────────────────
            val hovered = computeHoveredButton(player, mannequin)
            val prev = currentHud.hoveredButton

            if (hovered != prev) {
                // Un-highlight previous
                if (prev != null) {
                    val prevBtn = buttonByName(prev)
                    sendButtonBg(player, currentHud, mannequin.id, prev, prevBtn?.bgDefault ?: HUD_BG_DEFAULT)
                }
                // Highlight new
                if (hovered != null) {
                    val hovBtn = buttonByName(hovered)
                    sendButtonBg(player, currentHud, mannequin.id, hovered, hovBtn?.bgHighlight ?: HUD_BG_HIGHLIGHT)
                    val ph = basePlaceholders(player, mannequin).apply { put("button", hovered) }
                    fireTrigger("hover", ph)
                }
                currentHud.hoveredButton = hovered
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

        for (btn in hudButtons) {
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
     *
     * - If the player has no HUD (or it belongs to a different mannequin, or
     *   it is flying away), the first click spawns the HUD with a fly-in
     *   animation and does nothing else.
     * - Subsequent clicks use the hover state to determine which button was
     *   targeted and execute it.
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

        // ── Open HUD on first interaction ────────────────────────────────
        val currentHud = playerHuds[player.uniqueId]
        if (currentHud == null || currentHud.mannequinId != manId || currentHud.flyingAway) {
            // Destroy any stale / wrong-mannequin / flying-away HUD first
            if (currentHud != null) destroyPlayerHud(player)
            val dx = player.location.x - mannequin.location.x
            val dz = player.location.z - mannequin.location.z
            val yaw = atan2(dx, dz).toFloat()
            spawnPlayerHud(player, mannequin, yaw)
            return // first click only opens the panel
        }

        // Ignore clicks while the HUD is still flying in
        if (!currentHud.ready) return

        // ── Execute button / mode action ─────────────────────────────────
        val hoveredButton = currentHud.hoveredButton

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
        // Fire the click trigger unless the click will cycle a part/colour
        // (those have their own dedicated triggers).
        val willCycle = (button == "part" && state.mode == ControlMode.PART)
                || (button == "color" && state.mode == ControlMode.COLOR)
        if (!willCycle) {
            val clickPh = basePlaceholders(player, mannequin).apply { put("button", button) }
            fireTrigger("click", clickPh)
        }

        val layers = layerManager.definitionsInOrder()
        if (layers.isEmpty()) return
        val layer = layers.getOrNull(state.layerIndex % layers.size) ?: layers.first()

        when (button) {
            "model" -> {
                mannequin.slimModel = !mannequin.slimModel
                mannequin.lastFrame = PixelFrame.blank()
                val modelLabel = if (mannequin.slimModel) "Slim" else "Default"
                updateStatus(manId, "Model: $modelLabel")
                val viewers = nearbyViewers(mannequin)
                animationManager.cancelMannequin(mannequin.id)
                viewers.forEach { viewer -> handler.destroyMannequin(viewer, mannequin.id) }
                renderFull(mannequin, viewers)
                return
            }
            "pose" -> {
                val newPose = !(poseState[manId] ?: false)
                poseState[manId] = newPose
                updateStatus(manId, if (newPose) "Pose: T-Pose" else "Pose: Default")
                mannequin.lastFrame = PixelFrame.blank()
                val viewers = nearbyViewers(mannequin)
                animationManager.cancelMannequin(mannequin.id)
                viewers.forEach { v -> handler.destroyMannequin(v, mannequin.id) }
                return
            }
            "random" -> {
                val now = System.currentTimeMillis()
                val expiry = randomConfirm[player.uniqueId]
                if (expiry != null && now < expiry) {
                    // Confirmed — randomise and keep the window open
                    randomConfirm[player.uniqueId] = now + 5000L
                    randomize(mannequin)
                    val layer = layers.firstOrNull()
                    val option = if (layer != null) freshOption(layer.id, mannequin) else null
                    if (layer != null) refreshDynamicLabels(manId, option, layer)
                    updateStatus(manId, "Randomised!")
                    val viewers = nearbyViewers(mannequin)
                    animationManager.cancelMannequin(mannequin.id)
                    viewers.forEach { v -> handler.destroyMannequin(v, mannequin.id) }
                    renderFull(mannequin, viewers)
                } else {
                    // Prompt for confirmation (5 seconds)
                    randomConfirm[player.uniqueId] = now + 5000L
                    updateStatus(manId, "Click again to randomise")
                }
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
                state.mode = ControlMode.PART
                val option = freshOption(newLayer.id, mannequin)
                validatePaletteIndex(state, newLayer, option, player)
                refreshDynamicLabels(manId, option, newLayer)
            }
            "part" -> {
                if (state.mode == ControlMode.PART) {
                    cyclePart(layer, mannequin, state, player, backwards)?.let { updateStatus(manId, it) }
                } else {
                    state.mode = ControlMode.PART
                    refreshDynamicLabels(manId, freshOption(layer.id, mannequin), layer)
                    updateStatus(manId, "Mode: Part")
                }
            }
            "channel" -> {
                val option = freshOption(layer.id, mannequin)
                val channels = option?.masks?.keys?.sorted() ?: emptyList()
                val channelDisabled = channels.size <= 1
                if (channels.isEmpty()) {
                    updateStatus(manId, "Channel: N/A")
                } else if (!channelDisabled) {
                    val delta = if (backwards) -1 else 1
                    val idx = (state.channelIndex.getOrDefault(layer.id, 0) + delta + channels.size) % channels.size
                    state.channelIndex[layer.id] = idx
                    state.mode = ControlMode.COLOR
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
                    render(mannequin, viewers, forceInstant = true)

                    val restoreSel = flashSel.copy(channelColors = savedColors)
                    plugin.server.scheduler.runTaskLater(plugin, Runnable {
                        mannequin.selection = mannequin.selection.copy(
                            selections = mannequin.selection.selections + (layer.id to restoreSel)
                        )
                        render(mannequin, nearbyViewers(mannequin), forceInstant = true)
                    }, 10L)
                    refreshDynamicLabels(manId, option, layer)
                    return // already rendered
                } else {
                    updateStatus(manId, "Channel: Locked")
                }
                refreshDynamicLabels(manId, option, layer)
            }
            "palette" -> {
                val option = freshOption(layer.id, mannequin)
                val palIds = if (option != null) layerManager.resolvePalettes(layer, option, player) else emptyList()
                if (palIds.isEmpty()) {
                    updateStatus(manId, "Palette: N/A")
                } else {
                    // Cycle: All → palette0 → palette1 → … → All …
                    val current = state.paletteIndex.getOrDefault(layer.id, -1)
                    val delta = if (backwards) -1 else 1
                    // Range is [-1, palIds.lastIndex], total size = palIds.size + 1
                    val total = palIds.size + 1
                    val next = ((current + 1) + delta + total) % total - 1 // back to [-1 .. size-1]
                    state.paletteIndex[layer.id] = next
                    state.colorIndex[layer.id] = 0 // reset colour selection when palette changes
                    val label = if (next == -1) "All" else prettyName(palIds[next])
                    updateStatus(manId, "Palette: $label")
                }
                refreshDynamicLabels(manId, freshOption(layer.id, mannequin), layer)
            }
            "color" -> {
                if (state.mode == ControlMode.COLOR) {
                    cycleColor(layer, mannequin, state, player, backwards)?.let { updateStatus(manId, it) }
                } else {
                    state.mode = ControlMode.COLOR
                    refreshDynamicLabels(manId, freshOption(layer.id, mannequin), layer)
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
        visuals["status"]?.textJson = formatStatusText(msg)
        pushButtonToViewers(mannequinId, "status")
    }

    private fun refreshDynamicLabels(mannequinId: UUID, option: LayerOption?, layer: LayerDefinition?) {
        val channels = option?.masks?.keys?.sorted() ?: emptyList()
        val channelDisabled = channels.size <= 1
        val mode = controlState[mannequinId]?.mode
        val visuals = buttonVisuals[mannequinId] ?: return

        val chBtn = buttonByName("channel")
        visuals["channel"]?.let {
            it.textJson = if (channelDisabled && chBtn?.disabledTextJson != null) {
                chBtn.disabledTextJson
            } else {
                chBtn?.textJson ?: textToJson("Channel")
            }
        }

        val partBtn = buttonByName("part")
        visuals["part"]?.let {
            it.textJson = if (mode == ControlMode.PART && partBtn?.activeTextJson != null) {
                partBtn.activeTextJson
            } else {
                partBtn?.textJson ?: textToJson("Part")
            }
        }

        val colorBtn = buttonByName("color")
        visuals["color"]?.let {
            it.textJson = if (mode == ControlMode.COLOR && colorBtn?.activeTextJson != null) {
                colorBtn.activeTextJson
            } else {
                colorBtn?.textJson ?: textToJson("Color")
            }
        }

        pushButtonToViewers(mannequinId, "channel")
        pushButtonToViewers(mannequinId, "part")
        pushButtonToViewers(mannequinId, "color")
    }

    // ── Palette validation ────────────────────────────────────────────────────────

    /**
     * If the player had a specific palette selected (-1 = All, 0+ = index into the
     * resolved list), check whether that palette still exists in the new context.
     * If not, reset to -1 (All).
     */
    private fun validatePaletteIndex(state: ControlState, layer: LayerDefinition, option: LayerOption?, player: Player) {
        val prev = state.paletteIndex[layer.id] ?: return // never selected → nothing to validate
        if (prev == -1) return // "All" is always valid
        val palIds = if (option != null) layerManager.resolvePalettes(layer, option, player) else emptyList()
        if (prev !in palIds.indices) {
            state.paletteIndex[layer.id] = -1
            state.colorIndex[layer.id] = 0 // palette changed, reset colour
        }
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
        validatePaletteIndex(state, layer, chosen, player)
        refreshDynamicLabels(mannequin.id, chosen, layer)

        // Fire per-layer part-change trigger
        val prettyPart = prettyName(chosen.displayName)
        val ph = basePlaceholders(player, mannequin).apply {
            put("layer", layer.id)
            put("part", prettyPart.replace(' ', '\u00A0'))
        }
        fireLayerTrigger("part-change", layer.id, ph)

        return "Part: $prettyPart"
    }

    private fun cycleColor(layer: LayerDefinition, mannequin: Mannequin, state: ControlState, player: Player, backwards: Boolean): String? {
        val current = mannequin.selection.selections[layer.id]
        val option = freshOption(layer.id, mannequin) ?: return "Color: N/A"
        val allPaletteIds = layerManager.resolvePalettes(layer, option, player)
        val selectedPalIdx = state.paletteIndex.getOrDefault(layer.id, -1)
        val activePaletteIds = if (selectedPalIdx in allPaletteIds.indices)
            listOf(allPaletteIds[selectedPalIdx]) else allPaletteIds
        val colors = activePaletteIds.flatMap { palId -> layerManager.palette(palId)?.colors.orEmpty() }
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
        val colorObj = if (idx > 0) colors.getOrNull(idx - 1)?.color else null
        val colorCode = colorObj?.let { String.format("#%02X%02X%02X", it.red, it.green, it.blue) } ?: ""

        // Fire color-change trigger
        val ph = basePlaceholders(player, mannequin).apply {
            put("layer", layer.id)
            put("color", colorLabel.replace(' ', '\u00A0'))
            put("color_code", colorCode)
            put("color_r", colorObj?.let { String.format("%.3f", it.red / 255.0) } ?: "1.000")
            put("color_g", colorObj?.let { String.format("%.3f", it.green / 255.0) } ?: "1.000")
            put("color_b", colorObj?.let { String.format("%.3f", it.blue / 255.0) } ?: "1.000")
            put("channel", (selectedChannel ?: 0).toString())
        }
        fireTrigger("color-change", ph)

        return "Color: $colorLabel"
    }

    // ── Utilities ───────────────────────────────────────────────────────────────

    private fun nearbyViewers(mannequin: Mannequin): List<Player> {
        val radiusSq = updateRadius * updateRadius
        return plugin.server.onlinePlayers.filter {
            it.world == mannequin.location.world && it.location.distanceSquared(mannequin.location) <= radiusSq
        }
    }

    private fun bootstrapSelection(): SkinSelection {
        val definitions = layerManager.definitionsInOrder()
        val selections = definitions.associate { def ->
            val chosen = layerManager.optionsFor(def.id).firstOrNull()
            def.id to LayerSelection(
                layerId = def.id,
                option = chosen
            )
        }
        return SkinSelection(selections)
    }

    /**
     * Randomise the mannequin's parts: pick a random option for every layer.
     * Colours are left untouched. If [randomizeModel] is true, also randomise
     * slim vs default model.
     */
    private fun randomize(mannequin: Mannequin, randomizeModel: Boolean = false) {
        val definitions = layerManager.definitionsInOrder()
        val rng = java.util.concurrent.ThreadLocalRandom.current()
        val newSelections = mutableMapOf<String, LayerSelection>()

        for (def in definitions) {
            val options = layerManager.optionsFor(def.id)
            if (options.isEmpty()) continue
            val chosen = options[rng.nextInt(options.size)]

            newSelections[def.id] = LayerSelection(
                layerId = def.id,
                option = chosen
            )
        }

        mannequin.selection = SkinSelection(newSelections)
        mannequin.lastFrame = PixelFrame.blank()

        if (randomizeModel) {
            mannequin.slimModel = rng.nextBoolean()
        }

        // Sync control state indices to match randomised choices
        val state = controlState[mannequin.id]
        if (state != null) {
            for (def in definitions) {
                val opts = layerManager.optionsFor(def.id)
                val sel = newSelections[def.id]
                val idx = opts.indexOfFirst { it.id == sel?.option?.id }.coerceAtLeast(0)
                state.partIndex[def.id] = idx
                state.colorIndex[def.id] = 0
                state.channelIndex[def.id] = 0
            }
            state.mode = ControlMode.NONE
        }
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


