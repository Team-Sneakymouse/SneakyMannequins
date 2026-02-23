package com.sneakymannequins.managers

import com.sneakymannequins.SneakyMannequins
import com.sneakymannequins.model.LayerSelection
import com.sneakymannequins.model.LayerOption
import com.sneakymannequins.model.LayerDefinition
import com.sneakymannequins.model.Mannequin
import com.sneakymannequins.model.PixelChange
import com.sneakymannequins.model.PixelFrame
import com.sneakymannequins.model.SessionData
import com.sneakymannequins.model.SkinSelection
import com.sneakymannequins.model.TextureDefinition
import com.sneakymannequins.model.ChannelSlot
import com.sneakymannequins.model.buildChannelSlots
import com.sneakymannequins.model.hexToColor
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
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// ── Data classes ────────────────────────────────────────────────────────────────

private data class ControlState(
    var layerIndex: Int = 0,
    val partIndex: MutableMap<String, Int> = mutableMapOf(),
    val colorIndex: MutableMap<String, Int> = mutableMapOf(),
    /** Per-layer index into the flattened [ChannelSlot] list (covers both mask channels and sub-channels). */
    val channelIndex: MutableMap<String, Int> = mutableMapOf(),
    /**
     * Per-layer selected texture index (into the resolved texture list).
     * -1 = "Default" (flat colour, no texture), 0+ = index into the resolved texture list.
     */
    val textureIndex: MutableMap<String, Int> = mutableMapOf(),
    var mode: ControlMode = ControlMode.NONE
)

private enum class ControlMode { NONE, PART, LOAD }

// ── Color picker grid structures ────────────────────────────────────────────

/** Clickable cell metadata (palette name, colour, etc.). Null for headers. */
private data class GridCellData(
    val paletteId: String?,
    val colorName: String,
    val color: java.awt.Color?
)

/**
 * Rendering info for a single grid entity (header *or* cell).
 * Stores everything needed to re-send an [updateHudTextDisplay] on rotation.
 */
private data class GridEntityInfo(
    val tx: Float, val ty: Float, val tz: Float,
    val textJson: String,
    val bgNormal: Int,
    val lineWidth: Int,
    val scaleX: Float = 1f, val scaleY: Float = 1f,
    val pitch: Float = 0f,
    val yawOffset: Float = 0f,
    /** Non-null only for clickable colour cells. */
    val cellData: GridCellData? = null,
    /** Non-null only for clickable config-menu items. */
    val menuItemData: MenuItemData? = null
)

/** Tracks the spawned grid for a single player. */
private data class GridState(
    val entities: Map<Int, GridEntityInfo>,
    val allEntityIds: IntArray,
    var page: Int = 0,
    val totalPages: Int = 1,
    /** Remaining ticks for the grid fly-in animation (0 = done). */
    var flyInTicksLeft: Int = 0,
    /** True while the grid is flying out before destruction. */
    var flyingOut: Boolean = false
)

/** Clickable item in the config submenu (Save/Load/Apply). */
private data class MenuItemData(
    val action: String,
    val label: String
)

/** What the crosshair is pointing at. */
private sealed class HoverTarget {
    data class ButtonTarget(val name: String) : HoverTarget()
    data class CellTarget(val entityId: Int, val cell: GridCellData) : HoverTarget()
    data class MenuTarget(val entityId: Int, val item: MenuItemData) : HoverTarget()
}

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
    var lastDist: Float = Float.NaN,
    var hoverTarget: HoverTarget? = null,
    /** True once the fly-in animation has finished and the HUD accepts rotation / hover updates. */
    var ready: Boolean = false,
    /** True while the HUD is interpolating away before being destroyed. */
    var flyingAway: Boolean = false,
    /** Remaining ticks in the server-driven fly-in animation (0 = done / not animating). */
    var flyInTicksLeft: Int = 0,
    /** Colour picker grid state (non-null while the grid is visible). */
    var gridState: GridState? = null,
    /** Config submenu grid state (non-null while the submenu is visible). */
    var configGridState: GridState? = null
)

// ── Manager ─────────────────────────────────────────────────────────────────────

class MannequinManager(
    private val plugin: SneakyMannequins,
    private val layerManager: LayerManager,
    private val handler: VolatileHandler,
    private val persistence: MannequinPersistence,
    val sessionManager: SessionManager
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
        private const val INTERACT_RADIUS = 10.0f
        private const val HUD_SUMMON_RANGE = 4.0
        private const val HUD_BG_DEFAULT = 0x78000000.toInt()       // fallback semi-transparent black
        private const val HUD_BG_HIGHLIGHT = 0xB8336699.toInt()     // fallback translucent blue
        private const val BUTTON_TOLERANCE = 0.35
        private const val ROTATION_INTERP_TICKS = 3
        private const val YAW_THRESHOLD = 0.02f                     // radians (~1°)
        private const val DIST_THRESHOLD = 0.05f                    // blocks – triggers grid Z update
        private const val FRAME_Y_OFFSET = 10.0
        private const val HUD_FLY_Z_OFFSET = -10.0f                 // local-Z offset for fly-in / fly-out (negative = behind the HUD face, away from player)
        private const val HUD_FLY_INTERP_TICKS = 10                 // interpolation duration (ticks)
        private const val HUD_DISMISS_RANGE = 8.0                   // dismiss HUD when player is this far (blocks)

        /** Canonical ordered list of button names. */
        private val BUTTON_ORDER = listOf("status", "model", "pose", "layer", "random", "part", "texture", "channel", "color", "config")

        /** Button names that respond to clicks.  "status" is display-only. */
        private val CLICKABLE_BUTTONS = setOf("model", "pose", "random", "layer", "part", "channel", "texture", "color", "config")

        /** Hardcoded defaults used when a key is absent from config. */
        private data class BtnDefault(val text: String, val activeText: String?, val tx: Float, val ty: Float, val tz: Float, val lineWidth: Int)
        private val BUTTON_DEFAULTS = mapOf(
            "status"  to BtnDefault("<white>{message}", null,            0.0f,  2.8f, -2.0f, 256),
            "model"   to BtnDefault("<white>Model",    null,           -1.1f,  2.2f, -2.0f, 200),
            "pose"    to BtnDefault("<white>Pose",     null,           -1.1f,  1.7f, -2.0f, 200),
            "layer"   to BtnDefault("<white>Layer",    null,           -1.1f,  1.2f, -2.0f, 200),
            "random"  to BtnDefault("<white>Random",   null,           -1.1f,  0.7f, -2.0f, 200),
            "part"    to BtnDefault("<white>Part",     "<yellow>Part",  1.1f,  2.2f, -2.0f, 200),
            "texture" to BtnDefault("<white>Texture",  null,            1.1f,  1.7f, -2.0f, 200),
            "channel" to BtnDefault("<white>Channel",  null,            1.1f,  1.2f, -2.0f, 200),
            "color"   to BtnDefault("<white>Color",    "<yellow>Color", 1.1f,  0.7f, -2.0f, 200),
            "config"  to BtnDefault("<white>Config",   "<yellow>Config", -1.1f, 0.2f, -2.0f, 200),
        )

        private const val GRID_CELL_TOLERANCE = 0.20

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
        for (playerId in playerHuds.keys.toList()) {
            val player = plugin.server.getPlayer(playerId) ?: continue
            destroyPlayerHud(player)
        }
        persist()
        mannequins.clear()
    }

    // ── Session save/load ────────────────────────────────────────────────────────

    /**
     * Returns true if the player currently has the HUD open in LOAD mode
     * (i.e. they clicked the Load button and should type a session ID in chat).
     */
    fun isPlayerInLoadMode(playerId: UUID): Boolean {
        val hud = playerHuds[playerId] ?: return false
        val state = controlState[hud.mannequinId] ?: return false
        return state.mode == ControlMode.LOAD
    }

    /**
     * Returns the mannequin ID the player is currently interacting with via HUD,
     * or null if no HUD is open.
     */
    fun getHudMannequinId(playerId: UUID): UUID? = playerHuds[playerId]?.mannequinId

    /**
     * Apply a loaded [SessionData] to the specified mannequin and re-render.
     * Layers in the session that don't match a current definition are silently skipped.
     * Layers not present in the session keep their current selection (partial load).
     */
    fun applySession(mannequinId: UUID, session: SessionData, player: Player) {
        val mannequin = mannequins[mannequinId] ?: return
        val state = controlState.getOrPut(mannequinId) { ControlState() }

        mannequin.slimModel = session.slimModel

        val definitions = layerManager.definitionsInOrder()
        val defMap = definitions.associateBy { it.id }
        val newSelections = mannequin.selection.selections.toMutableMap()

        for ((layerId, layerData) in session.layers) {
            val def = defMap[layerId] ?: continue
            val opts = layerManager.optionsFor(layerId)
            val option = if (layerData.option != null) opts.find { it.id == layerData.option } else null
            if (option == null && layerData.option != null) continue

            val channelColors = layerData.channelColors.mapNotNull { (k, v) ->
                val idx = k.toIntOrNull() ?: return@mapNotNull null
                val color = hexToColor(v) ?: return@mapNotNull null
                idx to color
            }.toMap()

            val texturedColors = layerData.texturedColors.mapNotNull { (k, subMap) ->
                val idx = k.toIntOrNull() ?: return@mapNotNull null
                val subs = subMap.mapNotNull inner@{ (sk, sv) ->
                    val si = sk.toIntOrNull() ?: return@inner null
                    val sc = hexToColor(sv) ?: return@inner null
                    si to sc
                }.toMap()
                idx to subs
            }.toMap()

            newSelections[layerId] = LayerSelection(
                layerId = layerId,
                option = option ?: opts.firstOrNull(),
                channelColors = channelColors,
                texturedColors = texturedColors,
                selectedTexture = layerData.selectedTexture
            )

            // Sync control state indices
            val selOption = newSelections[layerId]?.option
            state.partIndex[layerId] = opts.indexOfFirst { it.id == selOption?.id }.coerceAtLeast(0)
            state.channelIndex[layerId] = 0
            state.colorIndex[layerId] = 0
            val rawTex = if (selOption != null) layerManager.resolveTextures(def, selOption, null) else emptyList()
            val hasDefaultTex = "default" in rawTex
            val actualTex = rawTex.filter { it != "default" }
            state.textureIndex[layerId] = if (layerData.selectedTexture != null) {
                actualTex.indexOf(layerData.selectedTexture).coerceAtLeast(0)
            } else if (hasDefaultTex) -1 else 0
        }

        mannequin.selection = SkinSelection(newSelections)
        mannequin.lastFrame = PixelFrame.blank()

        state.mode = ControlMode.NONE
        val layers = layerManager.definitionsInOrder()
        val curLayer = layers.getOrNull(state.layerIndex % layers.size)
        val curOption = curLayer?.let { freshOption(it.id, mannequin) }
        refreshDynamicLabels(mannequinId, curOption, curLayer)

        val viewers = nearbyViewers(mannequin)
        animationManager.cancelMannequin(mannequinId)
        viewers.forEach { v -> handler.destroyMannequin(v, mannequinId) }
        renderFull(mannequin, viewers)

        val hud = playerHuds[player.uniqueId]
        if (hud != null) refreshColorGrid(player, mannequin, state, hud)
    }

    /**
     * Handle a chat message from a player in LOAD mode.
     * Returns true if the message was consumed (should be cancelled).
     */
    fun handleLoadChat(player: Player, message: String): Boolean {
        val hud = playerHuds[player.uniqueId] ?: return false
        val manId = hud.mannequinId
        val state = controlState[manId] ?: return false
        if (state.mode != ControlMode.LOAD) return false

        val mannequin = mannequins[manId] ?: return false
        val session = sessionManager.load(message.trim())
        if (session != null) {
            applySession(manId, session, player)
            updateStatus(manId, "Loaded: ${session.uid}")
            val ph = basePlaceholders(player, mannequin).apply { put("uid", session.uid) }
            fireTrigger("session-load", ph)
        } else {
            state.mode = ControlMode.NONE
            val layers = layerManager.definitionsInOrder()
            val curLayer = layers.getOrNull(state.layerIndex % layers.size)
            val curOption = curLayer?.let { freshOption(it.id, mannequin) }
            refreshDynamicLabels(manId, curOption, curLayer)
            updateStatus(manId, "Session not found")
        }
        return true
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
    private val optionResolver: (String, String) -> LayerOption? = { layerId, optionId ->
        layerManager.optionsFor(layerId).find { it.id == optionId }
    }

    /**
     * Build a texture resolver that returns the [TextureDefinition] selected
     * for a given layer (based on the mannequin's current selection).
     * Returns null when the layer uses "Default" (flat colour, no texture).
     */
    private fun textureResolver(mannequin: Mannequin): (String) -> TextureDefinition? = { layerId ->
        val sel = mannequin.selection.selections[layerId]
        sel?.selectedTexture?.let { layerManager.texture(it) }
    }

    private val brightnessInfluenceResolver: (String, LayerOption) -> Float = { layerId, option ->
        val layerDef = layerManager.definitionsInOrder().find { it.id == layerId }
        if (layerDef != null) layerManager.resolveBrightnessInfluence(layerDef, option) else 0f
    }

    /**
     * Build the flat list of [ChannelSlot]s for a layer, taking the currently
     * selected texture into account.  When the texture has a blend map with
     * multiple active sub-channels, each mask channel expands (1a, 1b, …).
     */
    private fun resolveChannelSlots(
        layer: LayerDefinition, option: LayerOption?, state: ControlState, player: Player
    ): List<ChannelSlot> {
        val maskChannels = option?.masks?.keys?.sorted() ?: emptyList()
        val texIdx = state.textureIndex.getOrDefault(layer.id, -1)
        val texId = if (option != null)
            layerManager.resolveTextures(layer, option, player).getOrNull(texIdx) else null
        val texDef = texId?.let { layerManager.texture(it) }
        val activeSubs = if (texDef?.blendMapImage != null) texDef.activeSubChannels else null
        return buildChannelSlots(maskChannels, activeSubs)
    }

    fun render(mannequin: Mannequin, viewers: Collection<Player>, forceInstant: Boolean = false): Int {
        val definitions = layerManager.definitionsInOrder()
        val composed = SkinComposer.compose(definitions, mannequin.selection, useSlimModel = isSlimModel(mannequin), optionResolver = optionResolver, textureResolver = textureResolver(mannequin), brightnessInfluenceResolver = brightnessInfluenceResolver)
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

    private fun renderFull(mannequin: Mannequin, viewers: Collection<Player>, isFirstSeen: Boolean = false, forceInstant: Boolean = false) {
        val definitions = layerManager.definitionsInOrder()
        val composed = SkinComposer.compose(definitions, mannequin.selection, useSlimModel = isSlimModel(mannequin), optionResolver = optionResolver, textureResolver = textureResolver(mannequin), brightnessInfluenceResolver = brightnessInfluenceResolver)
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
        val settings = if (forceInstant) RenderSettings(RenderMode.INSTANT)
                        else readRenderSettings(isFirstSeen)
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
        state.gridState?.allEntityIds?.let { allIds += it.toList() }
        state.configGridState?.allEntityIds?.let { allIds += it.toList() }
        handler.destroyEntities(p, allIds.toIntArray())
    }

    /**
     * Animate the HUD flying away (local +Z), then destroy it after the
     * interpolation completes.  The [hud] is marked as [flyingAway] so the
     * tick loop stops processing it while the animation plays.
     */
    private fun flyAwayPlayerHud(player: Player, hud: PlayerHudState) {
        if (hud.flyingAway) return
        despawnColorGrid(player, hud)
        despawnConfigGrid(player, hud)
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
                state.gridState?.allEntityIds?.let { allIds += it.toList() }
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
                bgColor = if ((state.hoverTarget as? HoverTarget.ButtonTarget)?.name == buttonName) btn.bgHighlight else vis.bgColor,
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
            val playerDist = sqrt((dx * dx + dz * dz).toFloat())

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
                currentHud.lastDist = playerDist

                if (currentHud.flyInTicksLeft == 0) {
                    currentHud.ready = true
                }
                continue // skip normal rotation / hover during fly-in
            }

            // ── Grid fly-in / fly-out animation ─────────────────────────────
            val grid = currentHud.gridState
            if (grid != null && !grid.flyingOut && grid.flyInTicksLeft > 0) {
                grid.flyInTicksLeft--
                val progress = 1.0f - grid.flyInTicksLeft.toFloat() / HUD_FLY_INTERP_TICKS
                val zOff = HUD_FLY_Z_OFFSET * (1.0f - progress)
                for ((eid, info) in grid.entities) {
                    val trackTx = info.tx - playerDist * sin(info.yawOffset)
                    val trackTz = info.tz + playerDist * cos(info.yawOffset)
                    handler.updateHudTextDisplay(
                        viewer = player, entityId = eid,
                        textJson = info.textJson, bgColor = info.bgNormal,
                        tx = trackTx, ty = info.ty, tz = trackTz + zOff,
                        yaw = yaw + info.yawOffset, lineWidth = info.lineWidth,
                        interpolationTicks = 2,
                        pitch = info.pitch,
                        scaleX = info.scaleX, scaleY = info.scaleY
                    )
                }
                currentHud.lastDist = playerDist
            }

            // ── Config grid fly-in animation ──────────────────────────────────
            val configGrid = currentHud.configGridState
            if (configGrid != null && !configGrid.flyingOut && configGrid.flyInTicksLeft > 0) {
                configGrid.flyInTicksLeft--
                val progress = 1.0f - configGrid.flyInTicksLeft.toFloat() / HUD_FLY_INTERP_TICKS
                val zOff = HUD_FLY_Z_OFFSET * (1.0f - progress)
                for ((eid, info) in configGrid.entities) {
                    val trackTx = info.tx - playerDist * sin(info.yawOffset)
                    val trackTz = info.tz + playerDist * cos(info.yawOffset)
                    handler.updateHudTextDisplay(
                        viewer = player, entityId = eid,
                        textJson = info.textJson, bgColor = info.bgNormal,
                        tx = trackTx, ty = info.ty, tz = trackTz + zOff,
                        yaw = yaw + info.yawOffset, lineWidth = info.lineWidth,
                        interpolationTicks = 2,
                        pitch = info.pitch,
                        scaleX = info.scaleX, scaleY = info.scaleY
                    )
                }
                currentHud.lastDist = playerDist
            }

            val yawChanged = abs(yaw - currentHud.lastYaw) > YAW_THRESHOLD
            val hasAnyGrid = grid != null || configGrid != null
            val distChanged = hasAnyGrid && abs(playerDist - currentHud.lastDist) > DIST_THRESHOLD

            // ── Update rotation if yaw changed ──────────────────────────────
            if (yawChanged) {
                val visuals = buttonVisuals[mannequin.id] ?: continue
                val hovBtnName = (currentHud.hoverTarget as? HoverTarget.ButtonTarget)?.name
                for (btn in hudButtons) {
                    val entityId = currentHud.entityIds[btn.name] ?: continue
                    val vis = visuals[btn.name] ?: continue
                    val bg = if (hovBtnName == btn.name) btn.bgHighlight else vis.bgColor
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

            // ── Update grid entities if yaw or distance changed ─────────────
            if ((yawChanged || distChanged) && grid != null && grid.flyInTicksLeft == 0 && !grid.flyingOut) {
                val hovCellId = (currentHud.hoverTarget as? HoverTarget.CellTarget)?.entityId
                val selectedBg = loadGridConfig().bgSelected
                val selectedColor = currentSelectedGridColor(mannequin, controlState[mannequin.id])
                for ((eid, info) in grid.entities) {
                    val trackTx = info.tx - playerDist * sin(info.yawOffset)
                    val trackTz = info.tz + playerDist * cos(info.yawOffset)
                    val bg = when {
                        hovCellId == eid -> HUD_BG_HIGHLIGHT
                        info.cellData != null && selectedColor != null && info.cellData.color == selectedColor -> selectedBg
                        else -> info.bgNormal
                    }
                    handler.updateHudTextDisplay(
                        viewer = player, entityId = eid,
                        textJson = info.textJson, bgColor = bg,
                        tx = trackTx, ty = info.ty, tz = trackTz,
                        yaw = yaw + info.yawOffset, lineWidth = info.lineWidth,
                        interpolationTicks = ROTATION_INTERP_TICKS,
                        pitch = info.pitch,
                        scaleX = info.scaleX, scaleY = info.scaleY
                    )
                }
                currentHud.lastDist = playerDist
            }

            // ── Update config grid entities if yaw or distance changed ────────
            if ((yawChanged || distChanged) && configGrid != null && configGrid.flyInTicksLeft == 0 && !configGrid.flyingOut) {
                val hovMenuId = (currentHud.hoverTarget as? HoverTarget.MenuTarget)?.entityId
                for ((eid, info) in configGrid.entities) {
                    val trackTx = info.tx - playerDist * sin(info.yawOffset)
                    val trackTz = info.tz + playerDist * cos(info.yawOffset)
                    val bg = if (hovMenuId == eid) HUD_BG_HIGHLIGHT else info.bgNormal
                    handler.updateHudTextDisplay(
                        viewer = player, entityId = eid,
                        textJson = info.textJson, bgColor = bg,
                        tx = trackTx, ty = info.ty, tz = trackTz,
                        yaw = yaw + info.yawOffset, lineWidth = info.lineWidth,
                        interpolationTicks = ROTATION_INTERP_TICKS,
                        pitch = info.pitch,
                        scaleX = info.scaleX, scaleY = info.scaleY
                    )
                }
                currentHud.lastDist = playerDist
            }

            // ── Hover detection ─────────────────────────────────────────────
            val hovered = computeHoverTarget(player, mannequin, currentHud)
            val prev = currentHud.hoverTarget

            if (hovered != prev) {
                // Un-highlight previous
                unhighlightTarget(player, currentHud, mannequin, prev)
                // Highlight new
                highlightTarget(player, currentHud, mannequin, hovered)
                currentHud.hoverTarget = hovered
            }
        }
    }

    /** Send a background colour override for a single button to a single player.
     *  Uses a lightweight packet that only touches the background colour,
     *  so it won't interrupt an in-progress rotation interpolation. */
    @Suppress("UNUSED_PARAMETER")
    private fun sendButtonBg(player: Player, hud: PlayerHudState, manId: UUID, buttonName: String, bgColor: Int) {
        val entityId = hud.entityIds[buttonName] ?: return
        handler.sendHudBackground(player, entityId, bgColor)
    }

    // ── Look-direction → hover target resolution ──────────────────────────────

    /**
     * Determine what the player's crosshair is pointing at: a HUD button,
     * a colour-grid cell, or nothing.
     */
    private fun computeHoverTarget(player: Player, mannequin: Mannequin, hud: PlayerHudState): HoverTarget? {
        val eyeLoc = player.eyeLocation
        val lookDir = eyeLoc.direction.normalize()
        val eyeVec = eyeLoc.toVector()
        val manVec = mannequin.location.toVector()

        var bestTarget: HoverTarget? = null
        var bestDist = Double.MAX_VALUE

        for (btn in hudButtons) {
            if (btn.name == "status") continue
            val worldPos = buttonWorldPos(manVec, eyeVec, btn.tx, btn.ty, btn.tz)
            val dist = distanceFromRay(eyeVec, lookDir, worldPos)
            if (dist < bestDist) {
                bestDist = dist
                bestTarget = HoverTarget.ButtonTarget(btn.name)
            }
        }

        val btnOk = bestDist <= BUTTON_TOLERANCE

        // Also check grid cells (tighter tolerance; skip during fly-in/out)
        val grid = hud.gridState
        if (grid != null && grid.flyInTicksLeft == 0 && !grid.flyingOut) {
            val gdx = eyeVec.x - manVec.x
            val gdz = eyeVec.z - manVec.z
            val playerDist = sqrt(gdx * gdx + gdz * gdz).toFloat()
            for ((eid, info) in grid.entities) {
                val cell = info.cellData ?: continue
                val trackTx = info.tx - playerDist * sin(info.yawOffset)
                val trackTz = info.tz + playerDist * cos(info.yawOffset)
                val worldPos = gridWorldPos(manVec, eyeVec, trackTx, info.ty, trackTz, info.yawOffset)
                val dist = distanceFromRay(eyeVec, lookDir, worldPos)
                if (dist < bestDist) {
                    bestDist = dist
                    bestTarget = HoverTarget.CellTarget(eid, cell)
                }
            }
            val cellOk = bestDist <= GRID_CELL_TOLERANCE
            if (cellOk && bestTarget is HoverTarget.CellTarget) return bestTarget
        }

        // Check config submenu items
        val configGrid = hud.configGridState
        if (configGrid != null && configGrid.flyInTicksLeft == 0 && !configGrid.flyingOut) {
            val gdx = eyeVec.x - manVec.x
            val gdz = eyeVec.z - manVec.z
            val playerDist = sqrt(gdx * gdx + gdz * gdz).toFloat()
            for ((eid, info) in configGrid.entities) {
                val menuItem = info.menuItemData ?: continue
                val trackTx = info.tx - playerDist * sin(info.yawOffset)
                val trackTz = info.tz + playerDist * cos(info.yawOffset)
                val worldPos = gridWorldPos(manVec, eyeVec, trackTx, info.ty, trackTz, info.yawOffset)
                val dist = distanceFromRay(eyeVec, lookDir, worldPos)
                if (dist < bestDist) {
                    bestDist = dist
                    bestTarget = HoverTarget.MenuTarget(eid, menuItem)
                }
            }
            val menuOk = bestDist <= GRID_CELL_TOLERANCE
            if (menuOk && bestTarget is HoverTarget.MenuTarget) return bestTarget
        }

        return if (btnOk && bestTarget is HoverTarget.ButtonTarget) bestTarget else null
    }

    private fun unhighlightTarget(player: Player, hud: PlayerHudState, mannequin: Mannequin, target: HoverTarget?) {
        when (target) {
            is HoverTarget.ButtonTarget -> {
                val prevBtn = buttonByName(target.name)
                sendButtonBg(player, hud, mannequin.id, target.name, prevBtn?.bgDefault ?: HUD_BG_DEFAULT)
            }
            is HoverTarget.CellTarget -> {
                val info = hud.gridState?.entities?.get(target.entityId)
                val selectedColor = currentSelectedGridColor(mannequin, controlState[mannequin.id])
                val bg = if (selectedColor != null && target.cell.color == selectedColor)
                    loadGridConfig().bgSelected else (info?.bgNormal ?: HUD_BG_DEFAULT)
                handler.sendHudBackground(player, target.entityId, bg)
            }
            is HoverTarget.MenuTarget -> {
                val info = hud.configGridState?.entities?.get(target.entityId)
                handler.sendHudBackground(player, target.entityId, info?.bgNormal ?: HUD_BG_DEFAULT)
            }
            null -> {}
        }
    }

    private fun highlightTarget(player: Player, hud: PlayerHudState, mannequin: Mannequin, target: HoverTarget?) {
        when (target) {
            is HoverTarget.ButtonTarget -> {
                val hovBtn = buttonByName(target.name)
                sendButtonBg(player, hud, mannequin.id, target.name, hovBtn?.bgHighlight ?: HUD_BG_HIGHLIGHT)
                val ph = basePlaceholders(player, mannequin).apply { put("button", target.name) }
                fireTrigger("hover", ph)
            }
            is HoverTarget.CellTarget -> {
                handler.sendHudBackground(player, target.entityId, HUD_BG_HIGHLIGHT)
            }
            is HoverTarget.MenuTarget -> {
                handler.sendHudBackground(player, target.entityId, HUD_BG_HIGHLIGHT)
                val ph = basePlaceholders(player, mannequin).apply { put("button", target.item.action) }
                fireTrigger("hover", ph)
            }
            null -> {}
        }
    }

    /**
     * Compute the world-space position of a billboard-FIXED button that has been
     * Y-rotated to face the viewer.  The rotation is the same yaw computed from
     * player position → mannequin position, matching the NMS transformation.
     */
    private fun buttonWorldPos(mannequinPos: Vector, viewerPos: Vector, tx: Float, ty: Float, tz: Float): Vector {
        return gridWorldPos(mannequinPos, viewerPos, tx, ty, tz, 0f)
    }

    /**
     * Like [buttonWorldPos] but applies an extra yaw offset (radians) so the
     * computed world position matches entities that are rotated relative to the
     * standard viewer-facing yaw.
     */
    private fun gridWorldPos(mannequinPos: Vector, viewerPos: Vector, tx: Float, ty: Float, tz: Float, yawOffset: Float): Vector {
        val dx = viewerPos.x - mannequinPos.x
        val dz = viewerPos.z - mannequinPos.z
        val horizDist = sqrt(dx * dx + dz * dz)
        if (horizDist < 0.001) {
            return mannequinPos.clone().add(Vector(0.0, ty.toDouble(), 0.0))
        }
        val baseYaw = atan2(dx, dz).toFloat()
        val yaw = baseYaw + yawOffset
        val sinY = kotlin.math.sin(yaw.toDouble())
        val cosY = kotlin.math.cos(yaw.toDouble())
        // Forward = (sinYaw, 0, cosYaw), Right = (cosYaw, 0, -sinYaw)
        return Vector(
            mannequinPos.x + cosY * tx + sinY * tz,
            mannequinPos.y + ty,
            mannequinPos.z - sinY * tx + cosY * tz
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
            if (mannequin.location.distance(player.location) > HUD_SUMMON_RANGE) return
            // Destroy any stale / wrong-mannequin / flying-away HUD first
            if (currentHud != null) destroyPlayerHud(player)
            // Reset button labels before spawning so stale active-text doesn't carry over
            val layers = layerManager.definitionsInOrder()
            val curLayer = layers.getOrNull(state.layerIndex % layers.size)
            val curOption = curLayer?.let { freshOption(it.id, mannequin) }
            refreshDynamicLabels(manId, curOption, curLayer)
            val dx = player.location.x - mannequin.location.x
            val dz = player.location.z - mannequin.location.z
            val yaw = atan2(dx, dz).toFloat()
            spawnPlayerHud(player, mannequin, yaw)
            return // first click only opens the panel
        }

        // Ignore clicks while the HUD is still flying in
        if (!currentHud.ready) return

        // ── Execute button / mode action ─────────────────────────────────
        when (val target = currentHud.hoverTarget) {
            is HoverTarget.ButtonTarget -> {
                if (target.name in CLICKABLE_BUTTONS) {
                    executeButton(target.name, manId, mannequin, state, player, backwards)
                } else {
                    executeModeAction(manId, mannequin, state, player, backwards)
                }
            }
            is HoverTarget.CellTarget -> {
                applyGridCellColor(target.cell, manId, mannequin, state, player)
            }
            is HoverTarget.MenuTarget -> {
                val clickPh = basePlaceholders(player, mannequin).apply { put("button", target.item.action) }
                fireTrigger("click", clickPh)
                executeMenuAction(target.item, manId, mannequin, state, player)
            }
            null -> executeModeAction(manId, mannequin, state, player, backwards)
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
        val willCycle = (button == "part" && state.mode == ControlMode.PART)
        val gridOpening = (button == "color" && playerHuds[player.uniqueId]?.gridState == null)
        val gridClosing = (button == "color" && playerHuds[player.uniqueId]?.gridState?.let { grid ->
            !grid.flyingOut && if (backwards) grid.page == 0 else grid.page >= grid.totalPages - 1
        } == true)
        if (!willCycle && !gridOpening && !gridClosing) {
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
                renderFull(mannequin, viewers, forceInstant = true)
                return
            }
            "random" -> {
                val now = System.currentTimeMillis()
                val expiry = randomConfirm[player.uniqueId]
                if (expiry != null && now < expiry) {
                    // Confirmed — randomise and keep the window open
                    randomConfirm[player.uniqueId] = now + 5000L
                    randomize(mannequin)
                    val firstLayer = layers.firstOrNull()
                    val option = if (firstLayer != null) freshOption(firstLayer.id, mannequin) else null
                    if (firstLayer != null) refreshDynamicLabels(manId, option, firstLayer)
                    val hud = playerHuds[player.uniqueId]
                    if (hud != null) refreshColorGrid(player, mannequin, state, hud)
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
                state.textureIndex.remove(newLayer.id)
                state.colorIndex[newLayer.id] = 0
                state.mode = ControlMode.PART
                val option = freshOption(newLayer.id, mannequin)
                refreshDynamicLabels(manId, option, newLayer)
                val hud = playerHuds[player.uniqueId]
                if (hud != null) refreshColorGrid(player, mannequin, state, hud)
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
                val slots = resolveChannelSlots(layer, option, state, player)
                val channelDisabled = slots.size <= 1
                if (slots.isEmpty()) {
                    updateStatus(manId, "Channel: N/A")
                } else if (!channelDisabled) {
                    val delta = if (backwards) -1 else 1
                    val currentIdx = state.channelIndex.getOrDefault(layer.id, 0)
                    val idx = (currentIdx + delta + slots.size) % slots.size
                    state.channelIndex[layer.id] = idx
                    state.colorIndex[layer.id] = 0

                    val slot = slots[idx]
                    updateStatus(manId, "Channel: ${slot.label}")

                    // Flash selected slot's pixels white for 500ms
                    val sel = mannequin.selection.selections[layer.id]
                    val savedFlat = sel?.channelColors ?: emptyMap()
                    val savedTextured = sel?.texturedColors ?: emptyMap()

                    val flashFlat: Map<Int, java.awt.Color>
                    val flashTextured: Map<Int, Map<Int, java.awt.Color>>
                    if (slot.subChannel != null) {
                        // Textured: flash only this sub-channel; clear others so
                        // the highlight isn't diluted by neighbouring sub-channel colours.
                        flashFlat = savedFlat
                        flashTextured = savedTextured + (slot.maskIdx to mapOf(slot.subChannel to java.awt.Color.WHITE))
                    } else {
                        // Flat channel: flash the whole mask channel
                        flashFlat = savedFlat + (slot.maskIdx to java.awt.Color.WHITE)
                        flashTextured = savedTextured
                    }
                    val flashSel = (sel ?: LayerSelection(layer.id, option)).copy(
                        channelColors = flashFlat, texturedColors = flashTextured
                    )
                    mannequin.selection = mannequin.selection.copy(
                        selections = mannequin.selection.selections + (layer.id to flashSel)
                    )
                    val viewers = nearbyViewers(mannequin)
                    render(mannequin, viewers, forceInstant = true)

                    val restoreSel = flashSel.copy(channelColors = savedFlat, texturedColors = savedTextured)
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
            "texture" -> {
                val option = freshOption(layer.id, mannequin)
                val rawTexIds = if (option != null) layerManager.resolveTextures(layer, option, player) else emptyList()
                val hasDefaultTex = "default" in rawTexIds
                val texIds = rawTexIds.filter { it != "default" }

                if (texIds.isEmpty() && !hasDefaultTex) {
                    updateStatus(manId, "Texture: N/A")
                } else {
                    val minIdx = if (hasDefaultTex) -1 else 0
                    val total = texIds.size + (if (hasDefaultTex) 1 else 0)
                    val current = state.textureIndex.getOrDefault(layer.id, minIdx).coerceIn(minIdx, texIds.size - 1)
                    val delta = if (backwards) -1 else 1
                    val next = ((current - minIdx) + delta + total) % total + minIdx
                    state.textureIndex[layer.id] = next

                    val newTexId = if (next >= 0) texIds[next] else null
                    val sel = mannequin.selection.selections[layer.id]
                    if (sel != null) {
                        val wasPrevTextured = sel.selectedTexture != null
                        val isNowTextured = newTexId != null && layerManager.texture(newTexId)?.blendMapImage != null
                        var newSel = sel.copy(selectedTexture = newTexId)
                        if (wasPrevTextured && !isNowTextured) {
                            newSel = newSel.copy(texturedColors = emptyMap())
                        } else if (!wasPrevTextured && isNowTextured) {
                            newSel = newSel.copy(channelColors = emptyMap())
                        }
                        mannequin.selection = mannequin.selection.copy(
                            selections = mannequin.selection.selections + (layer.id to newSel)
                        )
                    }

                    state.channelIndex[layer.id] = 0
                    state.colorIndex[layer.id] = 0
                    val label = if (next == -1) "Default" else {
                        val texDef = layerManager.texture(texIds[next])
                        texDef?.displayName ?: prettyName(texIds[next])
                    }
                    updateStatus(manId, "Texture: $label")
                }
                refreshDynamicLabels(manId, freshOption(layer.id, mannequin), layer)
            }
            "color" -> {
                val hud = playerHuds[player.uniqueId]
                if (hud != null && hud.gridState != null && !hud.gridState!!.flyingOut) {
                    val grid = hud.gridState!!
                    val canPage = if (backwards) grid.page > 0 else grid.page < grid.totalPages - 1
                    if (canPage) {
                        val delta = if (backwards) -1 else 1
                        pageColorGrid(player, hud, mannequin, state, delta)
                    } else {
                        despawnColorGrid(player, hud)
                        refreshDynamicLabels(manId, freshOption(layer.id, mannequin), layer)
                        updateStatus(manId, "Color picker closed")
                    }
                } else if (hud != null) {
                    spawnColorGrid(player, mannequin, state, hud)
                    refreshDynamicLabels(manId, freshOption(layer.id, mannequin), layer)
                    updateStatus(manId, "Pick a color")
                }
            }
            "config" -> {
                val hud = playerHuds[player.uniqueId]
                if (hud != null && hud.configGridState != null && !hud.configGridState!!.flyingOut) {
                    despawnConfigGrid(player, hud)
                    refreshDynamicLabels(manId, freshOption(layer.id, mannequin), layer)
                    updateStatus(manId, "Config closed")
                } else if (hud != null) {
                    spawnConfigGrid(player, mannequin, hud)
                    refreshDynamicLabels(manId, freshOption(layer.id, mannequin), layer)
                    updateStatus(manId, "Config")
                }
                return
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
        val state = controlState[mannequinId]
        val mode = state?.mode
        // Channel disabled when there are ≤1 slots in the flat channel list
        val channelSlotCount = if (option != null && layer != null && state != null) {
            val maskChannels = option.masks.keys.sorted()
            val texIdx = state.textureIndex.getOrDefault(layer.id, -1)
            val rawTexResolved = layerManager.resolveTextures(layer, option, null)
            val actualTexResolved = rawTexResolved.filter { it != "default" }
            val texId = if (texIdx >= 0) actualTexResolved.getOrNull(texIdx) else null
            val texDef = texId?.let { layerManager.texture(it) }
            val activeSubs = if (texDef?.blendMapImage != null) texDef.activeSubChannels else null
            buildChannelSlots(maskChannels, activeSubs).size
        } else 0
        val channelDisabled = channelSlotCount <= 1
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

        val texBtn = buttonByName("texture")
        visuals["texture"]?.let {
            val texCount = if (option != null && layer != null)
                layerManager.resolveTextures(layer, option, null).size else 0
            it.textJson = if (texCount <= 1 && texBtn?.disabledTextJson != null) {
                texBtn.disabledTextJson
            } else {
                texBtn?.textJson ?: textToJson("Texture")
            }
        }

        val colorBtn = buttonByName("color")
        val gridVisible = playerHuds.values.any {
            it.mannequinId == mannequinId && it.gridState != null && !it.gridState!!.flyingOut
        }
        visuals["color"]?.let {
            it.textJson = if (gridVisible && colorBtn?.activeTextJson != null) {
                colorBtn.activeTextJson
            } else {
                colorBtn?.textJson ?: textToJson("Color")
            }
        }

        val configBtn = buttonByName("config")
        val configGridVisible = playerHuds.values.any {
            it.mannequinId == mannequinId && it.configGridState != null && !it.configGridState!!.flyingOut
        }
        visuals["config"]?.let {
            it.textJson = if ((configGridVisible || mode == ControlMode.LOAD) && configBtn?.activeTextJson != null) {
                configBtn.activeTextJson
            } else {
                configBtn?.textJson ?: textToJson("Config")
            }
        }

        pushButtonToViewers(mannequinId, "channel")
        pushButtonToViewers(mannequinId, "texture")
        pushButtonToViewers(mannequinId, "part")
        pushButtonToViewers(mannequinId, "color")
        pushButtonToViewers(mannequinId, "config")
    }

    // ── Part cycling ────────────────────────────────────────────────────────────

    private fun cyclePart(layer: LayerDefinition, mannequin: Mannequin, state: ControlState, player: Player, backwards: Boolean): String? {
        val opts = layerManager.optionsFor(layer.id)
        if (opts.isEmpty()) return null
        val delta = if (backwards) -1 else 1
        val startIdx = state.partIndex.getOrDefault(layer.id, 0)
        var idx = startIdx
        var attempts = 0
        do {
            idx = (idx + delta + opts.size) % opts.size
            val candidate = opts[idx]
            val pal = layerManager.resolvePalettes(layer, candidate, player)
            val tex = layerManager.resolveTextures(layer, candidate, player)
            if (pal.isNotEmpty() && tex.isNotEmpty()) break
            attempts++
        } while (attempts < opts.size)

        if (attempts >= opts.size) return null

        state.partIndex[layer.id] = idx
        val chosen = opts[idx]

        val sel = buildInitialSelection(layer, chosen, player)
        mannequin.selection = mannequin.selection.copy(
            selections = mannequin.selection.selections + (layer.id to sel)
        )

        state.channelIndex[layer.id] = 0
        state.colorIndex[layer.id] = 0
        val rawTex = layerManager.resolveTextures(layer, chosen, player)
        val hasDefaultTex = "default" in rawTex
        state.textureIndex[layer.id] = if (sel.selectedTexture != null) {
            val actualTex = rawTex.filter { it != "default" }
            actualTex.indexOf(sel.selectedTexture).coerceAtLeast(0)
        } else if (hasDefaultTex) -1 else 0

        refreshDynamicLabels(mannequin.id, chosen, layer)
        val hud = playerHuds[player.uniqueId]
        if (hud != null) refreshColorGrid(player, mannequin, state, hud)

        val prettyPart = prettyName(chosen.displayName)
        val ph = basePlaceholders(player, mannequin).apply {
            put("layer", layer.id)
            put("part", prettyPart.replace(' ', '\u00A0'))
        }
        fireLayerTrigger("part-change", layer.id, ph)

        return "Part: $prettyPart"
    }

    // ── Color grid: apply a picked colour ──────────────────────────────────────

    private fun applyGridCellColor(
        cell: GridCellData, manId: UUID, mannequin: Mannequin,
        state: ControlState, player: Player
    ) {
        val layers = layerManager.definitionsInOrder()
        if (layers.isEmpty()) return
        val layer = layers.getOrNull(state.layerIndex % layers.size) ?: layers.first()
        val option = freshOption(layer.id, mannequin) ?: return
        val current = mannequin.selection.selections[layer.id]
        val slots = resolveChannelSlots(layer, option, state, player)
        val slotIdx = state.channelIndex.getOrDefault(layer.id, 0)
        val slot = slots.getOrNull(slotIdx) ?: return

        if (slot.subChannel != null) {
            val prevTextured = current?.texturedColors ?: emptyMap()
            val prevSub = prevTextured[slot.maskIdx] ?: emptyMap()
            val newSub = if (cell.color == null) {
                prevSub - slot.subChannel
            } else {
                prevSub + (slot.subChannel to cell.color)
            }
            val newTextured = if (newSub.isEmpty()) prevTextured - slot.maskIdx
                              else prevTextured + (slot.maskIdx to newSub)
            val selection = current?.copy(texturedColors = newTextured)
                ?: LayerSelection(layer.id, option, texturedColors = newTextured)
            mannequin.selection = mannequin.selection.copy(
                selections = mannequin.selection.selections + (layer.id to selection)
            )
        } else {
            val prevColors = current?.channelColors ?: emptyMap()
            val newColors = if (cell.color == null) {
                prevColors - slot.maskIdx
            } else {
                prevColors + (slot.maskIdx to cell.color)
            }
            val selection = current?.copy(channelColors = newColors)
                ?: LayerSelection(layer.id, option, channelColors = newColors)
            mannequin.selection = mannequin.selection.copy(
                selections = mannequin.selection.selections + (layer.id to selection)
            )
        }

        val colorLabel = cell.colorName
        val colorCode = cell.color?.let { String.format("#%02X%02X%02X", it.red, it.green, it.blue) } ?: ""
        val ph = basePlaceholders(player, mannequin).apply {
            put("layer", layer.id)
            put("color", colorLabel.replace(' ', '\u00A0'))
            put("color_code", colorCode)
            put("color_r", cell.color?.let { String.format("%.3f", it.red / 255.0) } ?: "1.000")
            put("color_g", cell.color?.let { String.format("%.3f", it.green / 255.0) } ?: "1.000")
            put("color_b", cell.color?.let { String.format("%.3f", it.blue / 255.0) } ?: "1.000")
            put("channel", slot.label)
        }
        fireTrigger("color-change", ph)
        updateStatus(manId, "Color: $colorLabel")

        // Re-render with the new colour; grid stays open
        render(mannequin, nearbyViewers(mannequin))

        // Update the selected-cell highlight in the grid
        val hud = playerHuds[player.uniqueId]
        val grid = hud?.gridState
        if (hud != null && grid != null) {
            val cfg = loadGridConfig()
            for ((eid, info) in grid.entities) {
                val c = info.cellData ?: continue
                val bg = if (c.color != null && c.color == cell.color) cfg.bgSelected else info.bgNormal
                handler.sendHudBackground(player, eid, bg)
            }
        }
    }

    // ── Color picker grid: config, spawn, despawn, page ────────────────────────

    private data class GridConfig(
        val maxRows: Int, val cellSpacingX: Float, val cellSpacingY: Float,
        val originX: Float, val originY: Float, val originZ: Float,
        val pitch: Float, val yawOffset: Float,
        val cellLineWidth: Int, val cellScaleX: Float, val cellScaleY: Float,
        val headerLineWidth: Int, val headerScale: Float, val headerGap: Float,
        val headerTextMM: String, val bgHeader: Int, val bgSelected: Int
    )

    private fun loadGridConfig(): GridConfig {
        val sec = plugin.config.getConfigurationSection("hud-buttons.color-grid")
        return GridConfig(
            maxRows = sec?.getInt("max-rows", 6) ?: 6,
            cellSpacingX = sec?.getDouble("cell-spacing-x", 0.12)?.toFloat() ?: 0.12f,
            cellSpacingY = sec?.getDouble("cell-spacing-y", 0.18)?.toFloat() ?: 0.18f,
            originX = sec?.getDouble("origin-x", 0.3)?.toFloat() ?: 0.3f,
            originY = sec?.getDouble("origin-y", -0.3)?.toFloat() ?: -0.3f,
            originZ = sec?.getDouble("origin-z", -1.8)?.toFloat() ?: -1.8f,
            pitch = sec?.getDouble("pitch", -0.35)?.toFloat() ?: -0.35f,
            yawOffset = sec?.getDouble("yaw", 0.0)?.toFloat() ?: 0f,
            cellLineWidth = sec?.getInt("cell-line-width", 18) ?: 18,
            cellScaleX = sec?.getDouble("cell-scale-x", 1.0)?.toFloat() ?: 1f,
            cellScaleY = sec?.getDouble("cell-scale-y", 1.0)?.toFloat() ?: 1f,
            headerLineWidth = sec?.getInt("header-line-width", 80) ?: 80,
            headerScale = sec?.getDouble("header-scale", 1.0)?.toFloat() ?: 1f,
            headerGap = sec?.getDouble("header-gap", 0.35)?.toFloat() ?: 0.35f,
            headerTextMM = sec?.getString("header-text") ?: "<white><font:minecraft:uniform>{message}",
            bgHeader = parseArgb(sec?.getString("bg-header")) ?: 0x60000000,
            bgSelected = parseArgb(sec?.getString("bg-selected")) ?: 0xFF44AA44.toInt()
        )
    }

    /**
     * Return the [java.awt.Color] currently assigned to the active channel/sub-channel,
     * or null if "Default" (no override).
     */
    private fun currentSelectedGridColor(mannequin: Mannequin, state: ControlState?): java.awt.Color? {
        if (state == null) return null
        val layers = layerManager.definitionsInOrder()
        val layer = layers.getOrNull(state.layerIndex % layers.size) ?: return null
        val sel = mannequin.selection.selections[layer.id] ?: return null
        val option = freshOption(layer.id, mannequin) ?: return null
        val slots = resolveChannelSlots(layer, option, state, plugin.server.onlinePlayers.firstOrNull() ?: return null)
        val slotIdx = state.channelIndex.getOrDefault(layer.id, 0)
        val slot = slots.getOrNull(slotIdx) ?: return null
        return if (slot.subChannel != null) {
            sel.texturedColors[slot.maskIdx]?.get(slot.subChannel)
        } else {
            sel.channelColors[slot.maskIdx]
        }
    }

    /**
     * Build the grid for one page of palettes and spawn every entity.
     * Layout: each palette is a **row** — the label sits on the left and
     * colour swatches extend to the right.  Pagination is by rows.
     */
    private fun buildAndSpawnGrid(
        player: Player, mannequin: Mannequin, state: ControlState,
        yaw: Float, playerDist: Float, page: Int, allPaletteIds: List<String>, cfg: GridConfig,
        animate: Boolean = true, hasDefaultColor: Boolean = true
    ): GridState {
        val totalPages = ((allPaletteIds.size + cfg.maxRows - 1) / cfg.maxRows).coerceAtLeast(1)
        val startIdx = page * cfg.maxRows
        val visiblePalIds = allPaletteIds.subList(startIdx, (startIdx + cfg.maxRows).coerceAtMost(allPaletteIds.size))

        val loc = mannequin.location
        val entities = mutableMapOf<Int, GridEntityInfo>()
        val allIds = mutableListOf<Int>()
        val selectedColor = currentSelectedGridColor(mannequin, state)

        val flyZOff = if (animate) HUD_FLY_Z_OFFSET else 0f
        fun spawn(eid: Int, info: GridEntityInfo) {
            val trackTx = info.tx - playerDist * sin(info.yawOffset)
            val trackTz = info.tz + playerDist * cos(info.yawOffset)
            val bg = when {
                info.cellData != null && selectedColor != null && info.cellData.color == selectedColor -> cfg.bgSelected
                else -> info.bgNormal
            }
            handler.spawnHudTextDisplay(
                viewer = player, entityId = eid,
                x = loc.x, y = loc.y, z = loc.z,
                textJson = info.textJson, bgColor = bg,
                tx = trackTx, ty = info.ty, tz = trackTz + flyZOff,
                yaw = yaw + info.yawOffset, lineWidth = info.lineWidth,
                pitch = info.pitch,
                scaleX = info.scaleX, scaleY = info.scaleY
            )
            entities[eid] = info
            allIds += eid
        }

        val p = cfg.pitch
        val yw = cfg.yawOffset

        if (hasDefaultColor) {
            val defEid = handler.allocateEntityId()
            spawn(defEid, GridEntityInfo(
                tx = cfg.originX, ty = cfg.originY + cfg.cellSpacingY, tz = cfg.originZ,
                textJson = mmToJson(cfg.headerTextMM.replace("{message}", "Default")),
                bgNormal = cfg.bgHeader,
                lineWidth = cfg.headerLineWidth,
                scaleX = cfg.headerScale, scaleY = cfg.headerScale,
                pitch = p, yawOffset = yw,
                cellData = GridCellData(null, "Default", null)
            ))
        }

        for ((row, palId) in visiblePalIds.withIndex()) {
            val palette = layerManager.palette(palId) ?: continue
            val rowY = cfg.originY - row * cfg.cellSpacingY

            // Row header on the left (not clickable)
            val hdrEid = handler.allocateEntityId()
            spawn(hdrEid, GridEntityInfo(
                tx = cfg.originX, ty = rowY, tz = cfg.originZ,
                textJson = mmToJson(cfg.headerTextMM.replace("{message}", prettyName(palId))),
                bgNormal = cfg.bgHeader, lineWidth = cfg.headerLineWidth,
                scaleX = cfg.headerScale, scaleY = cfg.headerScale,
                pitch = p, yawOffset = yw
            ))

            // Colour swatches extend to the right
            for ((col, namedColor) in palette.colors.withIndex()) {
                val rgb = namedColor.color
                val bgNormal = (0xFF shl 24) or ((rgb.red and 0xFF) shl 16) or
                        ((rgb.green and 0xFF) shl 8) or (rgb.blue and 0xFF)
                val cellTx = cfg.originX + cfg.headerGap + col * cfg.cellSpacingX
                val cellEid = handler.allocateEntityId()
                spawn(cellEid, GridEntityInfo(
                    tx = cellTx, ty = rowY, tz = cfg.originZ,
                    textJson = textToJson(" "), bgNormal = bgNormal,
                    lineWidth = cfg.cellLineWidth,
                    scaleX = cfg.cellScaleX, scaleY = cfg.cellScaleY,
                    pitch = p, yawOffset = yw,
                    cellData = GridCellData(palId, prettyName(namedColor.name), rgb)
                ))
            }
        }

        return GridState(
            entities = entities,
            allEntityIds = allIds.toIntArray(),
            page = page,
            totalPages = totalPages,
            flyInTicksLeft = if (animate) HUD_FLY_INTERP_TICKS else 0
        )
    }

    private fun spawnColorGrid(player: Player, mannequin: Mannequin, state: ControlState, hud: PlayerHudState) {
        val cfg = loadGridConfig()
        val layers = layerManager.definitionsInOrder()
        val layer = layers.getOrNull(state.layerIndex % layers.size) ?: return
        val option = freshOption(layer.id, mannequin) ?: return
        val rawPaletteIds = layerManager.resolvePalettes(layer, option, player)
        val hasDefaultColor = "default" in rawPaletteIds
        val allPaletteIds = rawPaletteIds.filter { it != "default" }
        if (allPaletteIds.isEmpty()) {
            updateStatus(mannequin.id, "No palettes available")
            return
        }
        val dx = player.location.x - mannequin.location.x
        val dz = player.location.z - mannequin.location.z
        val dist = sqrt((dx * dx + dz * dz).toFloat())
        hud.lastDist = dist
        hud.gridState = buildAndSpawnGrid(player, mannequin, state, hud.lastYaw, dist, 0, allPaletteIds, cfg,
            hasDefaultColor = hasDefaultColor)
        fireTrigger("submenu-open", basePlaceholders(player, mannequin))
    }

    /**
     * Rebuild the colour grid in-place when available palettes may have changed
     * (e.g. after a layer or part switch).  Destroys old entities instantly
     * and respawns at page 0.  No-op if the grid is not currently visible.
     */
    private fun refreshColorGrid(player: Player, mannequin: Mannequin, state: ControlState, hud: PlayerHudState) {
        val grid = hud.gridState ?: return
        if (grid.flyingOut) return

        handler.destroyEntities(player, grid.allEntityIds)
        hud.gridState = null

        val cfg = loadGridConfig()
        val layers = layerManager.definitionsInOrder()
        val layer = layers.getOrNull(state.layerIndex % layers.size) ?: return
        val option = freshOption(layer.id, mannequin) ?: return
        val rawPaletteIds = layerManager.resolvePalettes(layer, option, player)
        val hasDefaultColor = "default" in rawPaletteIds
        val allPaletteIds = rawPaletteIds.filter { it != "default" }
        if (allPaletteIds.isEmpty()) {
            refreshDynamicLabels(mannequin.id, option, layer)
            return
        }
        val dx = player.location.x - mannequin.location.x
        val dz = player.location.z - mannequin.location.z
        val dist = sqrt((dx * dx + dz * dz).toFloat())
        hud.lastDist = dist
        hud.gridState = buildAndSpawnGrid(player, mannequin, state, hud.lastYaw, dist, 0, allPaletteIds, cfg,
            animate = false, hasDefaultColor = hasDefaultColor)
    }

    /**
     * Animate the grid flying out, then destroy it.
     * If the whole HUD is already flying away, skip the animation and destroy immediately.
     */
    private fun despawnColorGrid(player: Player, hud: PlayerHudState) {
        val grid = hud.gridState ?: return
        if (grid.flyingOut) return

        // If the HUD itself is flying away, just destroy instantly
        if (hud.flyingAway) {
            handler.destroyEntities(player, grid.allEntityIds)
            hud.gridState = null
            return
        }

        grid.flyingOut = true
        val yaw = hud.lastYaw
        val dist = hud.lastDist.let { if (it.isNaN()) 0f else it }

        // Push grid entities to the fly-out Z offset
        for ((eid, info) in grid.entities) {
            val trackTx = info.tx - dist * sin(info.yawOffset)
            val trackTz = info.tz + dist * cos(info.yawOffset)
            handler.updateHudTextDisplay(
                viewer = player, entityId = eid,
                textJson = info.textJson, bgColor = info.bgNormal,
                tx = trackTx, ty = info.ty, tz = trackTz + HUD_FLY_Z_OFFSET,
                yaw = yaw + info.yawOffset, lineWidth = info.lineWidth,
                interpolationTicks = HUD_FLY_INTERP_TICKS,
                pitch = info.pitch,
                scaleX = info.scaleX, scaleY = info.scaleY
            )
        }

        // Fire close trigger
        val mannequin = mannequins[hud.mannequinId]
        if (mannequin != null) {
            fireTrigger("submenu-close", basePlaceholders(player, mannequin))
        }

        // Schedule destruction after the interpolation finishes
        val playerId = player.uniqueId
        plugin.server.scheduler.scheduleSyncDelayedTask(plugin, Runnable {
            val currentHud = playerHuds[playerId]
            if (currentHud === hud && currentHud.gridState === grid) {
                handler.destroyEntities(plugin.server.getPlayer(playerId) ?: return@Runnable, grid.allEntityIds)
                currentHud.gridState = null
            }
        }, (HUD_FLY_INTERP_TICKS + 1).toLong())
    }

    private fun pageColorGrid(player: Player, hud: PlayerHudState, mannequin: Mannequin, state: ControlState, delta: Int) {
        val grid = hud.gridState ?: return
        val newPage = (grid.page + delta + grid.totalPages) % grid.totalPages
        despawnColorGrid(player, hud)

        val cfg = loadGridConfig()
        val layers = layerManager.definitionsInOrder()
        val layer = layers.getOrNull(state.layerIndex % layers.size) ?: return
        val option = freshOption(layer.id, mannequin) ?: return
        val rawPaletteIds = layerManager.resolvePalettes(layer, option, player)
        val hasDefaultColor = "default" in rawPaletteIds
        val allPaletteIds = rawPaletteIds.filter { it != "default" }

        val dx = player.location.x - mannequin.location.x
        val dz = player.location.z - mannequin.location.z
        val dist = sqrt((dx * dx + dz * dz).toFloat())
        hud.lastDist = dist
        hud.gridState = buildAndSpawnGrid(player, mannequin, state, hud.lastYaw, dist, newPage, allPaletteIds, cfg,
            hasDefaultColor = hasDefaultColor)
        updateStatus(mannequin.id, "Page ${newPage + 1} of ${grid.totalPages}")
    }

    // ── Config submenu grid ─────────────────────────────────────────────────────

    private data class ConfigMenuConfig(
        val originX: Float, val originY: Float, val originZ: Float,
        val pitch: Float, val yawOffset: Float,
        val itemSpacingY: Float, val itemLineWidth: Int
    )

    private fun loadConfigMenuConfig(): ConfigMenuConfig {
        val sec = plugin.config.getConfigurationSection("hud-buttons.config-menu")
        return ConfigMenuConfig(
            originX = sec?.getDouble("origin-x", 1.0)?.toFloat() ?: 1.0f,
            originY = sec?.getDouble("origin-y", 1.0)?.toFloat() ?: 1.0f,
            originZ = sec?.getDouble("origin-z", -2.0)?.toFloat() ?: -2.0f,
            pitch = sec?.getDouble("pitch", -0.35)?.toFloat() ?: -0.35f,
            yawOffset = sec?.getDouble("yaw", 1.0)?.toFloat() ?: 1.0f,
            itemSpacingY = sec?.getDouble("item-spacing-y", 0.5)?.toFloat() ?: 0.5f,
            itemLineWidth = sec?.getInt("item-line-width", 200) ?: 200
        )
    }

    private val CONFIG_MENU_ITEMS = listOf(
        MenuItemData("save", "Save"),
        MenuItemData("load", "Load"),
        MenuItemData("apply", "Apply")
    )

    private fun spawnConfigGrid(player: Player, mannequin: Mannequin, hud: PlayerHudState) {
        val cfg = loadConfigMenuConfig()
        val loc = mannequin.location
        val dx = player.location.x - loc.x
        val dz = player.location.z - loc.z
        val dist = sqrt((dx * dx + dz * dz).toFloat())
        hud.lastDist = dist

        val entities = mutableMapOf<Int, GridEntityInfo>()
        val allIds = mutableListOf<Int>()
        val flyZOff = HUD_FLY_Z_OFFSET
        val yaw = hud.lastYaw
        val globalBgDef = parseArgb(plugin.config.getString("hud-buttons.bg-default")) ?: HUD_BG_DEFAULT

        for ((row, item) in CONFIG_MENU_ITEMS.withIndex()) {
            val eid = handler.allocateEntityId()
            val textMM = if (item.action == "apply") "<gray>${item.label}" else "<white>${item.label}"
            val info = GridEntityInfo(
                tx = cfg.originX, ty = cfg.originY - row * cfg.itemSpacingY, tz = cfg.originZ,
                textJson = mmToJson(textMM),
                bgNormal = globalBgDef,
                lineWidth = cfg.itemLineWidth,
                pitch = cfg.pitch,
                yawOffset = cfg.yawOffset,
                menuItemData = item
            )
            val trackTx = info.tx - dist * sin(info.yawOffset)
            val trackTz = info.tz + dist * cos(info.yawOffset)
            handler.spawnHudTextDisplay(
                viewer = player, entityId = eid,
                x = loc.x, y = loc.y, z = loc.z,
                textJson = info.textJson, bgColor = info.bgNormal,
                tx = trackTx, ty = info.ty, tz = trackTz + flyZOff,
                yaw = yaw + info.yawOffset, lineWidth = info.lineWidth,
                pitch = info.pitch
            )
            entities[eid] = info
            allIds += eid
        }

        hud.configGridState = GridState(
            entities = entities,
            allEntityIds = allIds.toIntArray(),
            flyInTicksLeft = HUD_FLY_INTERP_TICKS
        )
        fireTrigger("submenu-open", basePlaceholders(player, mannequin))
    }

    private fun despawnConfigGrid(player: Player, hud: PlayerHudState) {
        val grid = hud.configGridState ?: return
        if (grid.flyingOut) return

        if (hud.flyingAway) {
            handler.destroyEntities(player, grid.allEntityIds)
            hud.configGridState = null
            return
        }

        grid.flyingOut = true
        val yaw = hud.lastYaw
        val dist = hud.lastDist.let { if (it.isNaN()) 0f else it }

        for ((eid, info) in grid.entities) {
            val trackTx = info.tx - dist * sin(info.yawOffset)
            val trackTz = info.tz + dist * cos(info.yawOffset)
            handler.updateHudTextDisplay(
                viewer = player, entityId = eid,
                textJson = info.textJson, bgColor = info.bgNormal,
                tx = trackTx, ty = info.ty, tz = trackTz + HUD_FLY_Z_OFFSET,
                yaw = yaw + info.yawOffset, lineWidth = info.lineWidth,
                interpolationTicks = HUD_FLY_INTERP_TICKS,
                pitch = info.pitch
            )
        }

        val mannequin = mannequins[hud.mannequinId]
        if (mannequin != null) {
            fireTrigger("submenu-close", basePlaceholders(player, mannequin))
        }

        val playerId = player.uniqueId
        plugin.server.scheduler.scheduleSyncDelayedTask(plugin, Runnable {
            val currentHud = playerHuds[playerId]
            if (currentHud === hud && currentHud.configGridState === grid) {
                handler.destroyEntities(plugin.server.getPlayer(playerId) ?: return@Runnable, grid.allEntityIds)
                currentHud.configGridState = null
            }
        }, (HUD_FLY_INTERP_TICKS + 1).toLong())
    }

    private fun executeMenuAction(item: MenuItemData, manId: UUID, mannequin: Mannequin, state: ControlState, player: Player) {
        when (item.action) {
            "save" -> {
                val definitions = layerManager.definitionsInOrder()
                val rendered = SkinComposer.compose(
                    definitions, mannequin.selection,
                    useSlimModel = isSlimModel(mannequin),
                    optionResolver = optionResolver,
                    textureResolver = textureResolver(mannequin),
                    brightnessInfluenceResolver = brightnessInfluenceResolver
                )
                val uid = sessionManager.save(mannequin, player, rendered)
                updateStatus(manId, "Saved: $uid")
                val ph = basePlaceholders(player, mannequin).apply { put("uid", uid) }
                fireTrigger("session-save", ph)
            }
            "load" -> {
                if (state.mode == ControlMode.LOAD) {
                    state.mode = ControlMode.NONE
                    val layers = layerManager.definitionsInOrder()
                    val curLayer = layers.getOrNull(state.layerIndex % layers.size)
                    refreshDynamicLabels(manId, curLayer?.let { freshOption(it.id, mannequin) }, curLayer)
                    updateStatus(manId, "Load cancelled")
                } else {
                    state.mode = ControlMode.LOAD
                    val layers = layerManager.definitionsInOrder()
                    val curLayer = layers.getOrNull(state.layerIndex % layers.size)
                    refreshDynamicLabels(manId, curLayer?.let { freshOption(it.id, mannequin) }, curLayer)
                    updateStatus(manId, "Enter session ID in chat")
                }
            }
            "apply" -> {
                updateStatus(manId, "Apply: Coming soon")
            }
        }
    }

    // ── Utilities ───────────────────────────────────────────────────────────────

    private fun nearbyViewers(mannequin: Mannequin): List<Player> {
        val radiusSq = updateRadius * updateRadius
        return plugin.server.onlinePlayers.filter {
            it.world == mannequin.location.world && it.location.distanceSquared(mannequin.location) <= radiusSq
        }
    }

    /**
     * Build a [LayerSelection] for the given layer/option, auto-assigning an
     * initial colour and texture when "default" is not in the resolved lists.
     */
    private fun buildInitialSelection(
        def: LayerDefinition, chosen: LayerOption,
        player: Player? = null,
        rng: java.util.Random = java.util.concurrent.ThreadLocalRandom.current()
    ): LayerSelection {
        val rawPal = layerManager.resolvePalettes(def, chosen, player)
        val hasDefaultColor = "default" in rawPal
        val actualPal = rawPal.filter { it != "default" }

        val rawTex = layerManager.resolveTextures(def, chosen, player)
        val hasDefaultTex = "default" in rawTex
        val actualTex = rawTex.filter { it != "default" }

        val channelColors: Map<Int, java.awt.Color> = if (!hasDefaultColor && actualPal.isNotEmpty()) {
            val palette = layerManager.palette(actualPal.first())
            if (palette != null && palette.colors.isNotEmpty()) {
                val color = palette.colors[rng.nextInt(palette.colors.size)].color
                chosen.masks.keys.associateWith { color }
            } else emptyMap()
        } else emptyMap()

        val selectedTexture: String? = if (!hasDefaultTex && actualTex.isNotEmpty()) actualTex.first() else null

        return LayerSelection(
            layerId = def.id,
            option = chosen,
            channelColors = channelColors,
            selectedTexture = selectedTexture
        )
    }

    private fun bootstrapSelection(): SkinSelection {
        val definitions = layerManager.definitionsInOrder()
        val selections = definitions.associate { def ->
            val options = layerManager.optionsFor(def.id)
            val chosen = options.firstOrNull { opt ->
                val pal = layerManager.resolvePalettes(def, opt, null)
                val tex = layerManager.resolveTextures(def, opt, null)
                pal.isNotEmpty() && tex.isNotEmpty()
            } ?: options.firstOrNull()
            if (chosen != null) {
                def.id to buildInitialSelection(def, chosen)
            } else {
                def.id to LayerSelection(layerId = def.id, option = null)
            }
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
            val viable = options.filter { opt ->
                val pal = layerManager.resolvePalettes(def, opt, null)
                val tex = layerManager.resolveTextures(def, opt, null)
                pal.isNotEmpty() && tex.isNotEmpty()
            }
            val chosen = if (viable.isNotEmpty()) viable[rng.nextInt(viable.size)]
                         else options[rng.nextInt(options.size)]

            newSelections[def.id] = buildInitialSelection(def, chosen, rng = rng)
        }

        mannequin.selection = SkinSelection(newSelections)
        mannequin.lastFrame = PixelFrame.blank()

        if (randomizeModel) {
            mannequin.slimModel = rng.nextBoolean()
        }

        val state = controlState[mannequin.id]
        if (state != null) {
            for (def in definitions) {
                val opts = layerManager.optionsFor(def.id)
                val sel = newSelections[def.id]
                val idx = opts.indexOfFirst { it.id == sel?.option?.id }.coerceAtLeast(0)
                state.partIndex[def.id] = idx
                state.colorIndex[def.id] = 0
                state.channelIndex[def.id] = 0
                val rawTex = if (sel?.option != null) layerManager.resolveTextures(def, sel.option, null) else emptyList()
                val hasDefaultTex = "default" in rawTex
                state.textureIndex[def.id] = if (sel?.selectedTexture != null) {
                    val actualTex = rawTex.filter { it != "default" }
                    actualTex.indexOf(sel.selectedTexture).coerceAtLeast(0)
                } else if (hasDefaultTex) -1 else 0
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


