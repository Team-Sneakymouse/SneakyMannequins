package com.sneakymannequins.managers

import com.sneakymannequins.SneakyMannequins
import com.sneakymannequins.events.*
import com.sneakymannequins.integrations.CharacterManagerBridge
import com.sneakymannequins.model.ChannelSlot
import com.sneakymannequins.model.LayerDefinition
import com.sneakymannequins.model.LayerOption
import com.sneakymannequins.model.LayerSelection
import com.sneakymannequins.model.Mannequin
import com.sneakymannequins.model.PixelChange
import com.sneakymannequins.model.PixelFrame
import com.sneakymannequins.model.SessionData
import com.sneakymannequins.model.SkinSelection
import com.sneakymannequins.model.TextureDefinition
import com.sneakymannequins.model.buildChannelSlots
import com.sneakymannequins.model.hexToColor
import com.sneakymannequins.nms.VolatileHandler
import com.sneakymannequins.render.AnimationManager
import com.sneakymannequins.render.PixelProjector
import com.sneakymannequins.render.RenderMode
import com.sneakymannequins.render.RenderSettings
import com.sneakymannequins.util.SkinComposer
import com.sneakymouse.sneakyholos.*
import com.sneakymouse.sneakyholos.util.HoloGridBuilder
import com.sneakymouse.sneakyholos.util.TextUtility
import java.util.UUID
import kotlin.math.sqrt
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.entity.Player

// ── Data classes ────────────────────────────────────────────────────────────────

private data class ControlState(
        var layerIndex: Int = 0,
        val partIndex: MutableMap<String, Int> = mutableMapOf(),
        val colorIndex: MutableMap<String, Int> = mutableMapOf(),
        /**
         * Per-layer index into the flattened [ChannelSlot] list (covers both mask channels and
         * sub-channels).
         */
        val channelIndex: MutableMap<String, Int> = mutableMapOf(),
        /**
         * Per-layer selected texture index (into the resolved texture list). -1 = "Default" (flat
         * colour, no texture), 0+ = index into the resolved texture list.
         */
        val textureIndex: MutableMap<String, Int> = mutableMapOf(),
        var mode: ControlMode = ControlMode.NONE
)

private enum class ControlMode {
    NONE,
    LOAD
}

// ── HUD button layout ───────────────────────────────────────────────────────

private data class HudButton(
        val name: String,
        val textMM: String, // raw MiniMessage string (for generating variants)
        val activeTextJson: String?, // JSON component for part/color active mode
        val disabledTextJson:
                String?, // JSON component when button is disabled (e.g. channel with no masks)
        val tx: Float,
        val ty: Float,
        val tz: Float,
        val lineWidth: Int,
        val bgDefault: Int,
        val bgHighlight: Int
)

/** Canonical per-button visual state (shared across all viewers). */
private data class ButtonVisual(var textJson: String, var bgColor: Int)

// ── Manager ─────────────────────────────────────────────────────────────────────

class MannequinManager(
        private val plugin: SneakyMannequins,
        private val layerManager: LayerManager,
        private val handler: VolatileHandler,
        private val persistence: MannequinPersistence,
        private val sessionManager: SessionManager,
        private val characterManagerBridge: CharacterManagerBridge,
        private val appliedSessionRegistry: AppliedSessionRegistry,
        private val holoController: HoloController
) {
    private val mannequins = mutableMapOf<UUID, Mannequin>()
    private val sentTo = mutableMapOf<UUID, MutableSet<UUID>>() // viewerId → mannequins seen
    private val statusText = mutableMapOf<UUID, String>() // mannequinId → last action
    private val poseState = mutableMapOf<UUID, Boolean>() // mannequinId → true = T-pose
    private val controlState = mutableMapOf<UUID, ControlState>()
    /** mannequin -> layerId -> partId(optionId) -> last selection used for that part */
    private val partSelectionMemory =
            mutableMapOf<UUID, MutableMap<String, MutableMap<String, LayerSelection>>>()
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

    /** Radius of the mannequin Interaction hitbox (blocks). */
    private val interactRadius: Double
        get() = plugin.config.getDouble("controls.interact-radius", 8.0).coerceAtLeast(0.5)

    /** Distance required for control interaction logic (blocks). */
    private val interactRange: Double
        get() =
                plugin.config
                        .getDouble("controls.interact-range", INTERACT_RANGE_DEFAULT)
                        .coerceAtLeast(0.5)

    /** Horizontal facing tolerance for default part control (degrees). */
    private val partFacingToleranceDeg: Double
        get() =
                plugin.config
                        .getDouble(
                                "controls.interaction-facing-tolerance-horizontal-deg",
                                PART_FACING_TOLERANCE_DEG_DEFAULT
                        )
                        .coerceIn(0.0, 180.0)

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

    private var tickTaskId: Int = -1
    private var viewCheckCounter: Int = 0

    // ── HUD button layout ───────────────────────────────────────────────────────

    companion object {
        private const val HOVER_RANGE = 6.0
        private const val INTERACT_RADIUS_DEFAULT = 10.0
        private const val INTERACT_RANGE_DEFAULT = 2.0
        private const val PART_FACING_TOLERANCE_DEG_DEFAULT = 60.0
        private const val HUD_BG_DEFAULT = 0x78000000.toInt() // fallback semi-transparent black
        private const val HUD_BG_HIGHLIGHT = 0xB8336699.toInt() // fallback translucent blue
        private const val BUTTON_TOLERANCE = 0.35
        private const val ROTATION_INTERP_TICKS = 3
        private const val YAW_THRESHOLD = 0.02f // radians (~1°)
        private const val DIST_THRESHOLD = 0.05f // blocks – triggers grid Z update
        private const val FRAME_Y_OFFSET = 10.0
        private const val HUD_FLY_Z_OFFSET =
                -10.0f // local-Z offset for fly-in / fly-out (negative = behind the HUD face, away
        // from player)
        private const val HUD_FLY_INTERP_TICKS = 10 // interpolation duration (ticks)
        private const val HUD_DISMISS_RANGE = 8.0 // dismiss HUD when player is this far (blocks)

        /** Canonical ordered list of button names. */
        private val BUTTON_ORDER =
                listOf(
                        "status",
                        "model",
                        "pose",
                        "layer",
                        "random",
                        "texture",
                        "channel",
                        "color",
                        "config"
                )

        /** Button names that respond to clicks. "status" is display-only. */
        private val CLICKABLE_BUTTONS =
                setOf("model", "pose", "random", "layer", "channel", "texture", "color", "config")

        /** Hardcoded defaults used when a key is absent from config. */
        private data class BtnDefault(
                val text: String,
                val activeText: String?,
                val tx: Float,
                val ty: Float,
                val tz: Float,
                val lineWidth: Int
        )
        private val BUTTON_DEFAULTS =
                mapOf(
                        "status" to BtnDefault("<white>{message}", null, 0.0f, 2.8f, -2.0f, 256),
                        "model" to BtnDefault("<white>Model", null, -1.1f, 2.2f, -2.0f, 200),
                        "pose" to BtnDefault("<white>Pose", null, -1.1f, 1.7f, -2.0f, 200),
                        "random" to BtnDefault("<white>Random", null, -1.1f, 0.7f, -2.0f, 200),
                        "layer" to BtnDefault("<white>Layer", null, 1.1f, 2.2f, -2.0f, 200),
                        "texture" to BtnDefault("<white>Texture", null, 1.1f, 1.7f, -2.0f, 200),
                        "channel" to BtnDefault("<white>Channel", null, 1.1f, 1.2f, -2.0f, 200),
                        "color" to
                                BtnDefault("<white>Color", "<yellow>Color", 1.1f, 0.7f, -2.0f, 200),
                        "config" to
                                BtnDefault(
                                        "<white>Config",
                                        "<yellow>Config",
                                        -1.1f,
                                        0.2f,
                                        -2.0f,
                                        200
                                ),
                )

        /** Parse an ARGB hex string (e.g. "B8336699") to an Int. */
        private fun parseArgb(hex: String?): Int? {
            if (hex.isNullOrBlank()) return null
            return hex.removePrefix("#").toLongOrNull(16)?.toInt()
        }
    }

    /** Buttons loaded from config (refreshed on reload). */
    private var hudButtons: List<HudButton> = emptyList()

    init {
        hudButtons = loadHudButtons()
    }

    /** Look up a button config by name. */
    private fun buttonByName(name: String): HudButton? = hudButtons.firstOrNull { it.name == name }

    /**
     * Resolve the current (fresh) [LayerOption] for a layer on a mannequin. The mannequin's
     * selection may hold a stale reference after a layer reload / remask, so we always look up the
     * option by ID from the layer manager and fall back to the stale copy only if it was removed.
     */
    private fun freshOption(layerId: String, mannequin: Mannequin): LayerOption? {
        val selOption = mannequin.selection.selections[layerId]?.option
        val opts = layerManager.optionsFor(layerId)
        return if (selOption != null) opts.find { it.id == selOption.id } ?: selOption
        else opts.firstOrNull()
    }

    /** Read the hud-buttons config section and build the button list. */
    private fun loadHudButtons(): List<HudButton> {
        val globalBgDef =
                parseArgb(plugin.config.getString("hud-buttons.bg-default")) ?: HUD_BG_DEFAULT
        val globalBgHi =
                parseArgb(plugin.config.getString("hud-buttons.bg-highlight")) ?: HUD_BG_HIGHLIGHT

        return BUTTON_ORDER.map { name ->
            val def = BUTTON_DEFAULTS[name]!!
            val sec = plugin.config.getConfigurationSection("hud-buttons.$name")
            val textMM = sec?.getString("text") ?: def.text
            val activeMM = sec?.getString("active-text") ?: def.activeText
            val disabledMM = sec?.getString("disabled-text")
            val tx = sec?.getDouble("translation.x", def.tx.toDouble())?.toFloat() ?: def.tx
            val ty = sec?.getDouble("translation.y", def.ty.toDouble())?.toFloat() ?: def.ty
            val tz = sec?.getDouble("translation.z", def.tz.toDouble())?.toFloat() ?: def.tz
            val lw = sec?.getInt("line-width", def.lineWidth) ?: def.lineWidth
            val bgDef = parseArgb(sec?.getString("bg-default")) ?: globalBgDef
            val bgHi = parseArgb(sec?.getString("bg-highlight")) ?: globalBgHi

            HudButton(
                    name = name,
                    textMM = textMM,
                    activeTextJson = activeMM?.let { TextUtility.mmToJson(it) },
                    disabledTextJson = disabledMM?.let { TextUtility.mmToJson(it) },
                    tx = tx,
                    ty = ty,
                    tz = tz,
                    lineWidth = lw,
                    bgDefault = bgDef,
                    bgHighlight = bgHi
            )
        }
    }

    // ── Lifecycle ───────────────────────────────────────────────────────────────

    fun loadFromDisk() {
        partSelectionMemory.clear()
        val loaded = persistence.load()
        loaded.forEach { (id, loc, slim) ->
            val selection = bootstrapSelection()
            val mannequin =
                    Mannequin(
                            id = id,
                            location = loc.clone(),
                            selection = selection,
                            slimModel = slim
                    )
            mannequins[id] = mannequin
            controlState[id] = ControlState()
            randomize(mannequin, randomizeModel = true)
            initButtonVisuals(id)
            cleanupControlEntities(id)
            registerTrigger(mannequin)
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
        registerTrigger(mannequin)
        persist()

        // Sync to all nearby viewers immediately
        val viewers = nearbyViewers(mannequin)
        if (viewers.isNotEmpty()) {
            renderFull(mannequin, viewers, isFirstSeen = true)
            viewers.forEach { viewer ->
                sentTo.getOrPut(viewer.uniqueId) { mutableSetOf() }.add(mannequin.id)
            }
        }

        return mannequin
    }

    fun get(id: UUID): Mannequin? = mannequins[id]

    fun remove(mannequinId: UUID, viewers: Collection<Player>) {
        animationManager.cancelMannequin(mannequinId)
        viewers.forEach { viewer -> handler.destroyMannequin(viewer, mannequinId) }
        // Destroy virtual HUDs for all players viewing this mannequin
        holoController.destroyHUDs(mannequinId)
        holoController.removeTrigger("mannequin:$mannequinId")
        cleanupControlEntities(mannequinId)
        mannequins.remove(mannequinId)
        buttonVisuals.remove(mannequinId)
        controlState.remove(mannequinId)
        statusText.remove(mannequinId)
        poseState.remove(mannequinId)
        partSelectionMemory.remove(mannequinId)
        persist()
    }

    fun forgetViewer(viewerId: UUID) {
        sentTo.remove(viewerId)
        animationManager.cleanupPlayer(viewerId)
        holoController.closeHud(viewerId)
    }

    fun shutdown() {
        stopTickLoop()
        animationManager.stop()
        val viewers = plugin.server.onlinePlayers
        mannequins.keys.forEach { id ->
            viewers.forEach { viewer -> handler.destroyMannequin(viewer, id) }
        }
        persist()
        mannequins.clear()
        partSelectionMemory.clear()
    }

    // ── Tick Loop ───────────────────────────────────────────────────────────────

    fun startTickLoop() {
        if (tickTaskId != -1) return
        animationManager.start()
        tickTaskId =
                plugin.server.scheduler.scheduleSyncRepeatingTask(
                        plugin,
                        Runnable { tick() },
                        0L,
                        1L
                )
    }

    fun stopTickLoop() {
        if (tickTaskId != -1) {
            plugin.server.scheduler.cancelTask(tickTaskId)
            tickTaskId = -1
        }
    }

    private fun tick() {
        viewCheckCounter++

        if (viewCheckCounter % 100 == 0 && plugin.config.getBoolean("plugin.debug", false)) {
            plugin.logger.info(
                    "[DEBUG] MannequinManager tick loop running (count: $viewCheckCounter)"
            )
        }

        // Check first-seen and range-based HUD removal every 10 ticks (~0.5s)
        if (viewCheckCounter % 10 == 0) {
            val now = System.currentTimeMillis()
            val expiredConfirms = randomConfirm.filterValues { now >= it }.keys
            for (uid in expiredConfirms) {
                randomConfirm.remove(uid)
                val hud = holoController.getHud(uid)
                val btn = hud?.buttons?.find { it.id == "random" }
                if (btn != null) {
                    val baseJson =
                            plugin.config.getString("hud-buttons.random.text", "<white>Random")!!
                    btn.textJson = TextUtility.mmToJson(baseJson)
                    hud.updateButtonText("random", btn.textJson)
                }
            }

            plugin.server.onlinePlayers.forEach { player ->
                checkFirstSeen(player)

                // Bug 4: Range-based HUD removal
                val hud = holoController.getHud(player.uniqueId)
                if (hud != null) {
                    val mannequin = mannequins[hud.mannequinId]
                    if (mannequin == null ||
                                    player.world != mannequin.location.world ||
                                    player.location.distanceSquared(mannequin.location) > 8.0 * 8.0
                    ) {
                        holoController.closeHud(player.uniqueId, animate = true)
                    }
                }
            }
        }
    }

    // ── Session save/load ────────────────────────────────────────────────────────

    /**
     * Apply a loaded [SessionData] to the specified mannequin and re-render. Layers in the session
     * that don't match a current definition are silently skipped. Layers not present in the session
     * keep their current selection (partial load).
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
            val option =
                    if (layerData.option != null) opts.find { it.id == layerData.option } else null
            if (option == null && layerData.option != null) continue

            val channelColors =
                    layerData
                            .channelColors
                            .mapNotNull { (k, v) ->
                                val idx = k.toIntOrNull() ?: return@mapNotNull null
                                val color = hexToColor(v) ?: return@mapNotNull null
                                idx to color
                            }
                            .toMap()

            val texturedColors =
                    layerData
                            .texturedColors
                            .mapNotNull { (k, subMap) ->
                                val idx = k.toIntOrNull() ?: return@mapNotNull null
                                val subs =
                                        subMap
                                                .mapNotNull inner@{ (sk, sv) ->
                                                    val si = sk.toIntOrNull() ?: return@inner null
                                                    val sc = hexToColor(sv) ?: return@inner null
                                                    si to sc
                                                }
                                                .toMap()
                                idx to subs
                            }
                            .toMap()

            newSelections[layerId] =
                    LayerSelection(
                            layerId = layerId,
                            option = option ?: opts.firstOrNull(),
                            channelColors = channelColors,
                            texturedColors = texturedColors,
                            selectedTexture = layerData.selectedTexture
                    )
        }

        mannequin.selection = SkinSelection(newSelections)
        syncControlState(mannequin, state)
        for (def in definitions) rememberCurrentPartSelection(mannequin, def)
        mannequin.lastFrame = PixelFrame.blank()

        state.mode = ControlMode.NONE
        val layers = layerManager.definitionsInOrder()
        val curLayer = layers.getOrNull(state.layerIndex % layers.size)
        val curOption = curLayer?.let { freshOption(it.id, mannequin) }
        refreshDynamicLabels(mannequinId, curOption, curLayer)

        renderFull(mannequin, nearbyViewers(mannequin))
    }

    /**
     * Handle a chat message from a player in LOAD mode. Returns true if the message was consumed
     * (should be cancelled).
     */
    fun handleLoadChat(player: Player, message: String): Boolean {
        val hud = holoController.getHud(player.uniqueId) ?: return false
        val manId = hud.mannequinId
        val state = controlState[manId] ?: return false
        if (state.mode != ControlMode.LOAD) return false

        val mannequin = mannequins[manId] ?: return false
        val session = sessionManager.load(message.trim())
        if (session != null) {
            val loadEvent =
                    MannequinSessionLoadEvent(manId, mannequin.location, player, uid = session.uid)
            plugin.server.pluginManager.callEvent(loadEvent)
            if (loadEvent.isCancelled) {
                player.sendMessage(Component.text("Load blocked.").color(NamedTextColor.RED))
                return true
            }
            applySession(manId, session, player)
            player.sendMessage(
                    Component.text("Loaded: ")
                            .color(NamedTextColor.GREEN)
                            .append(
                                    Component.text(session.uid)
                                            .color(NamedTextColor.YELLOW)
                                            .clickEvent(ClickEvent.copyToClipboard(session.uid))
                                            .hoverEvent(
                                                    HoverEvent.showText(
                                                            Component.text("Click to copy UID")
                                                    )
                                            )
                            )
            )
        } else {
            player.sendMessage(Component.text("Session not found.").color(NamedTextColor.RED))
        }
        state.mode = ControlMode.NONE
        val layers = layerManager.definitionsInOrder()
        val curLayer = layers.getOrNull(state.layerIndex % layers.size)
        val curOption = curLayer?.let { freshOption(it.id, mannequin) }
        refreshDynamicLabels(manId, curOption, curLayer)
        return true
    }

    fun reloadAll() {
        stopTickLoop()
        animationManager.stop()
        val viewers = plugin.server.onlinePlayers
        viewers.forEach { viewer -> holoController.closeHud(viewer.uniqueId, animate = false) }
        mannequins.keys.forEach { id ->
            viewers.forEach { viewer -> handler.destroyMannequin(viewer, id) }
            cleanupControlEntities(id)
        }
        mannequins.clear()
        buttonVisuals.clear()
        sentTo.clear()
        statusText.clear()
        poseState.clear()
        controlState.clear()
        partSelectionMemory.clear()
        interactionDebounce.clear()

        hudButtons = loadHudButtons()
        loadFromDisk()
        animationManager.start()
        startTickLoop()
        // HoloController handles its own tick task

        plugin.server.onlinePlayers.forEach { viewer -> renderVisibleTo(viewer) }
    }

    // ── Rendering ───────────────────────────────────────────────────────────────

    fun renderVisibleTo(viewer: Player) {
        val viewRadiusSq = viewRadius * viewRadius
        val updateRadiusSq = updateRadius * updateRadius

        for (man in mannequins.values) {
            if (man.location.world != viewer.world) continue
            val distSq = man.location.distanceSquared(viewer.location)

            val seen = sentTo.getOrPut(viewer.uniqueId) { mutableSetOf() }
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
     * Lightweight first-seen check: renders any mannequin within view-radius that the player hasn't
     * seen yet. Called periodically from the tick handler so BUILD animations trigger reliably when
     * a player walks into range.
     */
    private fun checkFirstSeen(viewer: Player) {
        val viewRadiusSq = viewRadius * viewRadius
        val seen = sentTo.getOrPut(viewer.uniqueId) { mutableSetOf() }
        for (man in mannequins.values) {
            if (man.id in seen) continue
            if (man.location.world != viewer.world) continue
            val distSq = man.location.distanceSquared(viewer.location)
            if (distSq > viewRadiusSq) continue

            if (plugin.config.getBoolean("plugin.debug", false)) {
                plugin.logger.info(
                        "[DEBUG] Mannequin ${man.id} first seen by ${viewer.name} (dist: ${Math.sqrt(distSq)})"
                )
            }

            renderFull(man, listOf(viewer), isFirstSeen = true)
            seen += man.id
            plugin.server.pluginManager.callEvent(
                    MannequinFirstSeenEvent(man.id, man.location, viewer)
            )
        }
    }

    /**
     * Resolves the current (fresh) [LayerOption] for a layer+option ID, so the composer always
     * reads up-to-date mask paths after a remask.
     */
    private val optionResolver: (String, String) -> LayerOption? = { layerId, optionId ->
        layerManager.optionsFor(layerId).find { it.id == optionId }
    }

    /**
     * Build a texture resolver that returns the [TextureDefinition] selected for a given layer
     * (based on the mannequin's current selection). Returns null when the layer uses "Default"
     * (flat colour, no texture).
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
     * Build the flat list of [ChannelSlot]s for a layer, taking the currently selected texture into
     * account. When the texture has a blend map with multiple active sub-channels, each mask
     * channel expands (1a, 1b, …).
     */
    private fun resolveChannelSlots(
            layer: LayerDefinition,
            option: LayerOption?,
            state: ControlState,
            player: Player
    ): List<ChannelSlot> {
        val maskChannels = option?.masks?.keys?.sorted() ?: emptyList()
        val rawTexResolved =
                if (option != null) layerManager.resolveTextures(layer, option, player)
                else emptyList()
        val texIdx =
                state.textureIndex
                        .getOrDefault(layer.id, 0)
                        .coerceIn(0, (rawTexResolved.size - 1).coerceAtLeast(0))
        val rawTexId = rawTexResolved.getOrNull(texIdx)
        val texId = if (rawTexId == "default") null else rawTexId
        val texDef = texId?.let { layerManager.texture(it) }
        val activeSubs = if (texDef?.blendMapImage != null) texDef.activeSubChannels else null
        return buildChannelSlots(maskChannels, activeSubs)
    }

    fun render(
            mannequin: Mannequin,
            viewers: Collection<Player>,
            forceInstant: Boolean = false
    ): Int {
        val definitions = layerManager.definitionsInOrder()
        val composed =
                SkinComposer.compose(
                        definitions,
                        mannequin.selection,
                        useSlimModel = isSlimModel(mannequin),
                        optionResolver = optionResolver,
                        textureResolver = textureResolver(mannequin),
                        brightnessInfluenceResolver = brightnessInfluenceResolver
                )
        val nextFrame = PixelFrame.fromImage(composed)
        val diff = mannequin.lastFrame.diff(nextFrame)
        mannequin.lastFrame = nextFrame
        if (plugin.config.getBoolean("plugin.debug", false)) {
            val changeStatus =
                    if (diff.isEmpty()) "ZERO (potential geometry only change)" else "${diff.size}"
            plugin.logger.info(
                    "Rendering mannequin ${mannequin.id} with $changeStatus pixel changes to ${viewers.size} viewers"
            )
        }
        val projected =
                PixelProjector.project(
                        origin = mannequin.location,
                        changes = diff,
                        pixelScale = 1.0 / 16.0,
                        scaleMultiplier = handler.pixelScaleMultiplier(),
                        slimArms = isSlimModel(mannequin),
                        showOverlay = mannequin.showOverlay,
                        tPose = poseState[mannequin.id] == true
                )
        val settings =
                if (forceInstant) RenderSettings(RenderMode.INSTANT)
                else readRenderSettings(isFirstSeen = false)
        viewers.forEach { viewer ->
            animationManager.deliver(viewer, mannequin.id, projected, settings)
        }
        return diff.size
    }

    private fun renderFull(
            mannequin: Mannequin,
            viewers: Collection<Player>,
            isFirstSeen: Boolean = false,
            forceInstant: Boolean = false
    ) {
        if (!isFirstSeen) {
            // Cancel current build/animations and wipe previous entities to prevent residual pixels
            // when model or pose geometry changes.
            animationManager.cancelMannequin(mannequin.id)
            viewers.forEach { viewer -> handler.destroyMannequin(viewer, mannequin.id) }
        }

        if (plugin.config.getBoolean("plugin.debug", false)) {
            plugin.logger.info(
                    "[DEBUG] renderFull for mannequin ${mannequin.id} to ${viewers.size} viewers (firstSeen: $isFirstSeen)"
            )
        }
        val definitions = layerManager.definitionsInOrder()
        val composed =
                SkinComposer.compose(
                        definitions,
                        mannequin.selection,
                        useSlimModel = isSlimModel(mannequin),
                        optionResolver = optionResolver,
                        textureResolver = textureResolver(mannequin),
                        brightnessInfluenceResolver = brightnessInfluenceResolver
                )
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
        val projected =
                PixelProjector.project(
                        origin = mannequin.location,
                        changes = changes,
                        pixelScale = 1.0 / 16.0,
                        scaleMultiplier = handler.pixelScaleMultiplier(),
                        slimArms = isSlimModel(mannequin),
                        showOverlay = mannequin.showOverlay,
                        tPose = poseState[mannequin.id] == true
                )
        val settings =
                if (forceInstant) RenderSettings(RenderMode.INSTANT)
                else readRenderSettings(isFirstSeen)
        viewers.forEach { viewer ->
            animationManager.deliver(viewer, mannequin.id, projected, settings)
        }
    }

    private fun isSlimModel(mannequin: Mannequin): Boolean = mannequin.slimModel

    fun nearestMannequin(location: Location, radius: Double = 10.0): Mannequin? {
        return mannequins.values
                .minByOrNull { man ->
                    if (man.location.world != location.world) Double.MAX_VALUE
                    else man.location.distance(location)
                }
                ?.takeIf {
                    it.location.world == location.world && it.location.distance(location) <= radius
                }
    }

    // ── Interaction entity (real, server-side) ──────────────────────────────────

    private fun registerTrigger(mannequin: Mannequin) {
        val trigger =
                com.sneakymouse.sneakyholos.HoloTrigger(
                        id = "mannequin:${mannequin.id}",
                        location = mannequin.location,
                        radius = interactRadius.toFloat(),
                        onTrigger = { player, backwards ->
                            handleInteract(mannequin.id, player, backwards)
                        }
                )
        holoController.addTrigger(trigger)
    }

    /** Remove all control entities (Interaction + any legacy TextDisplays) for a mannequin. */
    private fun cleanupControlEntities(mannequinId: UUID) {
        val man = mannequins[mannequinId] ?: return
        val world = man.location.world ?: return
        world.getNearbyEntities(man.location, 10.0, 10.0, 10.0).forEach {
            if (it.scoreboardTags.contains("sneakymannequin_control") &&
                            it.scoreboardTags.contains("mannequin:$mannequinId")
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
            val json =
                    if (btn.name == "status") {
                        formatStatusText(statusText[mannequinId])
                    } else {
                        TextUtility.mmToJson(btn.textMM)
                    }
            visuals[btn.name] = ButtonVisual(textJson = json, bgColor = btn.bgDefault)
        }
        buttonVisuals[mannequinId] = visuals
    }

    /**
     * Apply the status button's MiniMessage template to a message. If the template contains
     * `{message}`, the placeholder is substituted; otherwise the message is wrapped in the template
     * formatting.
     */
    private fun formatStatusText(msg: String?): String {
        val btn = buttonByName("status")
        val template = btn?.textMM ?: "<white>{message}"
        val defaultMsg = plugin.config.getString("hud-buttons.status.default-message") ?: "Controls"
        val text = msg ?: defaultMsg
        val formatted = if ("{message}" in template) template.replace("{message}", text) else text
        return TextUtility.mmToJson(formatted)
    }

    /**
     * Spawn the full virtual HUD for a player viewing a mannequin. All elements start with a
     * local-Z offset; the tick loop drives them toward their final position one step per tick
     * (server-side animation).
     */
    private fun buildHoloButtons(mannequin: Mannequin, player: Player): MutableList<HoloButton> {
        val state = controlState[mannequin.id] ?: ControlState()
        return hudButtons
                .map { btn ->
                    val initialJson =
                            if (btn.name == "status") formatStatusText(statusText[mannequin.id])
                            else TextUtility.mmToJson(btn.textMM)
                    HoloButton(
                            id = btn.name,
                            textJson = initialJson,
                            tx = btn.tx,
                            ty = btn.ty,
                            tz = btn.tz,
                            lineWidth = btn.lineWidth,
                            bgDefault = btn.bgDefault,
                            bgHighlight = btn.bgHighlight,
                            onClick = { viewer, backwards ->
                                handleButtonClick(btn.name, mannequin.id, viewer, backwards)
                            },
                            onHover = { viewer, entering ->
                                if (entering) {
                                    plugin.server.pluginManager.callEvent(
                                            MannequinHoverEvent(
                                                    mannequin.id,
                                                    mannequin.location,
                                                    viewer,
                                                    btn.name
                                            )
                                    )
                                }
                            }
                    )
                }
                .toMutableList()
    }

    private fun spawnPlayerHud(player: Player, mannequin: Mannequin, yaw: Float) {
        val buttons = buildHoloButtons(mannequin, player)
        val frameItem = plugin.config.getString("hud-buttons.frame.item")
        val frameCmd = plugin.config.getInt("hud-buttons.frame.custom-model-data", 0)

        val hud =
                HoloHUD(
                        viewer = player,
                        origin = mannequin.location,
                        mannequinId = mannequin.id,
                        handler = holoController.handler,
                        buttons = buttons,
                        frameItem = frameItem,
                        frameCustomModelData = frameCmd,
                        onClose = { p ->
                            plugin.server.pluginManager.callEvent(
                                    MannequinControlClosedEvent(mannequin.id, mannequin.location, p)
                            )
                        }
                )
        holoController.openHud(hud)
        plugin.server.pluginManager.callEvent(
                MannequinControlOpenEvent(mannequin.id, mannequin.location, player)
        )
    }

    private fun handleButtonClick(
            buttonName: String,
            mannequinId: UUID,
            player: Player,
            backwards: Boolean
    ) {
        val mannequin = mannequins[mannequinId] ?: return
        val state = controlState[mannequinId] ?: return
        val layers = layerManager.definitionsInOrder()
        val layer = layers.getOrNull(state.layerIndex % layers.size)
        val hud = holoController.getHud(player.uniqueId) ?: return

        if (buttonName != "color" && buttonName != "config") {
            plugin.server.pluginManager.callEvent(
                    MannequinClickEvent(mannequinId, mannequin.location, player, buttonName)
            )
        }

        when (buttonName) {
            "model" -> {
                mannequin.slimModel = !mannequin.slimModel
                updateStatus(
                        mannequinId,
                        if (mannequin.slimModel) "Model: Slim" else "Model: Steve"
                )
                renderFull(mannequin, nearbyViewers(mannequin))
            }
            "pose" -> {
                poseState[mannequinId] = !(poseState[mannequinId] ?: false)
                updateStatus(
                        mannequinId,
                        if (poseState[mannequinId] == true) "Pose: T-Pose" else "Pose: Standard"
                )
                renderFull(mannequin, nearbyViewers(mannequin), forceInstant = true)
            }
            "random" -> {
                val now = System.currentTimeMillis()
                val expires = randomConfirm[player.uniqueId] ?: 0L
                if (now < expires) {
                    randomConfirm[player.uniqueId] = now + 5000L
                    randomize(mannequin, randomizeModel = true)
                    updateStatus(mannequinId, "Randomized")
                    renderFull(mannequin, nearbyViewers(mannequin), forceInstant = true)
                    refreshDynamicLabels(mannequinId)
                    refreshColorGrid(player, mannequin, state, hud)

                    val btn = hud.buttons.find { it.id == "random" }
                    if (btn != null) {
                        val baseJson =
                                plugin.config.getString(
                                        "hud-buttons.random.text",
                                        "<white>Random"
                                )!!
                        btn.textJson = TextUtility.mmToJson(baseJson)
                        hud.updateButtonText("random", btn.textJson)
                    }
                } else {
                    randomConfirm[player.uniqueId] = now + 5000L
                    val btn = hud.buttons.find { it.id == "random" }
                    if (btn != null) {
                        val confirmJson =
                                plugin.config.getString(
                                        "hud-buttons.random.confirm-text",
                                        "<yellow>Confirm?"
                                )!!
                        btn.textJson = TextUtility.mmToJson(confirmJson)
                        hud.updateButtonText("random", btn.textJson)
                    }
                }
            }
            "layer" -> {
                state.layerIndex =
                        if (backwards) (state.layerIndex - 1 + layers.size) % layers.size
                        else (state.layerIndex + 1) % layers.size
                val nextLayer = layers[state.layerIndex]
                val nextOption = freshOption(nextLayer.id, mannequin)
                updateStatus(mannequinId, "Layer: ${prettyName(nextLayer.id)}")
                refreshDynamicLabels(mannequinId, nextOption, nextLayer)
                refreshColorGrid(player, mannequin, state, hud)
            }
            "texture" -> {
                if (layer == null) return
                val option = freshOption(layer.id, mannequin) ?: return
                val texs = layerManager.resolveTextures(layer, option, player)
                if (texs.size <= 1) return
                val currentIdx = state.textureIndex.getOrDefault(layer.id, 0)
                val nextIdx =
                        if (backwards) (currentIdx - 1 + texs.size) % texs.size
                        else (currentIdx + 1) % texs.size
                state.textureIndex[layer.id] = nextIdx
                val nextTex = texs[nextIdx]
                val currentSel = mannequin.selection.selections[layer.id]
                val nextSel =
                        currentSel?.copy(
                                selectedTexture = if (nextTex == "default") null else nextTex
                        )
                                ?: LayerSelection(
                                        layer.id,
                                        option,
                                        selectedTexture =
                                                if (nextTex == "default") null else nextTex
                                )
                mannequin.selection =
                        mannequin.selection.copy(
                                selections = mannequin.selection.selections + (layer.id to nextSel)
                        )
                updateStatus(mannequinId, "Texture: ${prettyName(nextTex)}")
                render(mannequin, nearbyViewers(mannequin))
                refreshDynamicLabels(mannequinId, option, layer)
                refreshColorGrid(player, mannequin, state, hud)
            }
            "channel" -> {
                if (layer == null) return
                val option = freshOption(layer.id, mannequin) ?: return
                val slots = resolveChannelSlots(layer, option, state, player)
                if (slots.size <= 1) return
                val currentIdx = state.channelIndex.getOrDefault(layer.id, 0)
                val nextIdx =
                        if (backwards) (currentIdx - 1 + slots.size) % slots.size
                        else (currentIdx + 1) % slots.size
                state.channelIndex[layer.id] = nextIdx
                updateStatus(mannequinId, "Channel: ${slots[nextIdx].label}")
                refreshColorGrid(player, mannequin, state, hud)

                // Flash the selected channel white for 10 ticks
                val slot = slots[nextIdx]
                val currentSel = mannequin.selection.selections[layer.id]
                val flashColors: MutableMap<Int, java.awt.Color>
                val flashTextured: MutableMap<Int, Map<Int, java.awt.Color>>
                if (slot.subChannel != null) {
                    flashColors = (currentSel?.channelColors ?: emptyMap()).toMutableMap()
                    flashTextured = (currentSel?.texturedColors ?: emptyMap()).toMutableMap()
                    val sub = flashTextured.getOrPut(slot.maskIdx) { emptyMap() }.toMutableMap()
                    sub[slot.subChannel] = java.awt.Color.WHITE
                    flashTextured[slot.maskIdx] = sub
                } else {
                    flashColors = (currentSel?.channelColors ?: emptyMap()).toMutableMap()
                    flashColors[slot.maskIdx] = java.awt.Color.WHITE
                    flashTextured = (currentSel?.texturedColors ?: emptyMap()).toMutableMap()
                }

                val flashSel =
                        currentSel?.copy(
                                channelColors = flashColors,
                                texturedColors = flashTextured
                        )
                                ?: LayerSelection(
                                        layer.id,
                                        option,
                                        channelColors = flashColors,
                                        texturedColors = flashTextured
                                )
                mannequin.selection =
                        mannequin.selection.copy(
                                selections = mannequin.selection.selections + (layer.id to flashSel)
                        )

                val viewers = nearbyViewers(mannequin)
                render(mannequin, viewers, forceInstant = true)

                // Restore original colors after 10 ticks (500ms)
                val restoreSel = currentSel ?: LayerSelection(layer.id, option)
                plugin.server.scheduler.runTaskLater(
                        plugin,
                        Runnable {
                            mannequin.selection =
                                    mannequin.selection.copy(
                                            selections =
                                                    mannequin.selection.selections +
                                                            (layer.id to restoreSel)
                                    )
                            render(mannequin, viewers, forceInstant = true)
                        },
                        10L
                )
            }
            "color" -> {
                val gridVisible = hud.buttons.any { it.id.startsWith("color_") }
                if (gridVisible) {
                    despawnColorGrid(player, hud)
                } else {
                    spawnColorGrid(player, mannequin, state, hud)
                }
                refreshDynamicLabels(
                        mannequinId,
                        layer?.let { freshOption(it.id, mannequin) },
                        layer
                )
            }
            "config" -> {
                val configVisible = hud.buttons.any { it.id.startsWith("config_") }
                if (configVisible) {
                    despawnConfigGrid(player, hud)
                } else {
                    spawnConfigGrid(player, mannequin, state, hud)
                }
                refreshDynamicLabels(
                        mannequinId,
                        layer?.let { freshOption(it.id, mannequin) },
                        layer
                )
            }
            else -> {
                if (buttonName.startsWith("color_")) {
                    // Logic for color swatch clicks
                } else if (buttonName.startsWith("config_")) {
                    // Logic for config submenu clicks
                }
            }
        }
    }

    private fun refreshColorGrid(
            player: Player,
            mannequin: Mannequin,
            state: ControlState,
            hud: HoloHUD
    ) {
        val gridVisible = hud.buttons.any { it.id.startsWith("color_") }
        if (gridVisible) {
            // Note: refreshing skips submenu close/open triggers to avoid noise
            despawnColorGrid(player, hud, quiet = true)
            spawnColorGrid(player, mannequin, state, hud, quiet = true)
        }
    }

    // ── Status & label helpers ──────────────────────────────────────────────────

    private fun updateStatus(mannequinId: UUID, msg: String) {
        statusText[mannequinId] = msg
        val json = formatStatusText(msg)
        for (player in plugin.server.onlinePlayers) {
            val hud = holoController.getHud(player.uniqueId) ?: continue
            if (hud.origin.world == mannequins[mannequinId]?.location?.world &&
                            hud.origin.distanceSquared(
                                    mannequins[mannequinId]?.location ?: continue
                            ) < 0.1
            ) {
                hud.updateButtonText("status", json)
            }
        }
    }

    private fun refreshDynamicLabels(
            mannequinId: UUID,
            option: LayerOption? = null,
            layer: LayerDefinition? = null
    ) {
        val state = controlState[mannequinId] ?: return
        val mode = state.mode
        val mannequin = mannequins[mannequinId] ?: return

        val (finalLayer, finalOption) =
                if (option != null && layer != null) {
                    layer to option
                } else {
                    val layers = layerManager.definitionsInOrder()
                    val def = layers.getOrNull(state.layerIndex % layers.size)
                    val opt = def?.let { freshOption(it.id, mannequin) }
                    def to opt
                }

        val channelSlotCount =
                if (finalOption != null && finalLayer != null) {
                    resolveChannelSlots(
                                    finalLayer,
                                    finalOption,
                                    state,
                                    plugin.server.onlinePlayers.firstOrNull() ?: return
                            )
                            .size
                } else 0
        val channelDisabled = channelSlotCount <= 1

        val chBtn = buttonByName("channel")
        val channelJson =
                if (channelDisabled && chBtn?.disabledTextJson != null) {
                    chBtn.disabledTextJson
                } else {
                    chBtn?.textMM?.let { TextUtility.mmToJson(it) }
                            ?: TextUtility.mmToJson("Channel")
                }

        val texBtn = buttonByName("texture")
        val texCount =
                if (finalOption != null && finalLayer != null)
                        layerManager.resolveTextures(finalLayer, finalOption, null).size
                else 0
        val textureJson =
                if (texCount <= 1 && texBtn?.disabledTextJson != null) {
                    texBtn.disabledTextJson
                } else {
                    texBtn?.textMM?.let { TextUtility.mmToJson(it) }
                            ?: TextUtility.mmToJson("Texture")
                }

        val colorBtn = buttonByName("color")
        val configBtn = buttonByName("config")

        for (player in plugin.server.onlinePlayers) {
            val hud = holoController.getHud(player.uniqueId) ?: continue
            if (hud.origin.world != mannequin.location.world ||
                            hud.origin.distanceSquared(mannequin.location) > 0.1
            )
                    continue

            hud.updateButtonText("channel", channelJson)
            hud.updateButtonText("texture", textureJson)

            val gridVisible = hud.isButtonActive("color_")
            val colorJson =
                    if (gridVisible && colorBtn?.activeTextJson != null) {
                        colorBtn.activeTextJson
                    } else {
                        colorBtn?.textMM?.let { TextUtility.mmToJson(it) }
                                ?: TextUtility.mmToJson("Color")
                    }
            hud.updateButtonText("color", colorJson)

            val configGridVisible = hud.isButtonActive("config_")
            val configJson =
                    if ((configGridVisible || mode == ControlMode.LOAD) &&
                                    configBtn?.activeTextJson != null
                    ) {
                        configBtn.activeTextJson
                    } else {
                        configBtn?.textMM?.let { TextUtility.mmToJson(it) }
                                ?: TextUtility.mmToJson("Config")
                    }
            hud.updateButtonText("config", configJson)
        }
    }

    // ── Part cycling ────────────────────────────────────────────────────────────

    private fun rememberCurrentPartSelection(mannequin: Mannequin, layer: LayerDefinition) {
        val sel = mannequin.selection.selections[layer.id] ?: return
        val option = sel.option ?: return
        val byLayer = partSelectionMemory.getOrPut(mannequin.id) { mutableMapOf() }
        val byPart = byLayer.getOrPut(layer.id) { mutableMapOf() }
        byPart[option.id] = sel.copy(layerId = layer.id, option = option)
    }

    private fun canRestoreRememberedSelection(
            layer: LayerDefinition,
            option: LayerOption,
            remembered: LayerSelection,
            player: Player
    ): Boolean {
        val rawPal = layerManager.resolvePalettes(layer, option, player)
        val hasDefaultColor = "default" in rawPal
        val actualPal = rawPal.filter { it != "default" }
        val allowedColors = mutableSetOf<Int>()
        for (palId in actualPal) {
            val palette = layerManager.palette(palId) ?: continue
            for (entry in palette.colors) {
                allowedColors += (entry.color.rgb and 0x00FFFFFF)
            }
        }

        val rawTex = layerManager.resolveTextures(layer, option, player)
        val hasDefaultTex = "default" in rawTex
        val actualTex = rawTex.filter { it != "default" }
        val texOk =
                when (val tex = remembered.selectedTexture) {
                    null -> hasDefaultTex
                    else -> tex in actualTex
                }
        if (!texOk) return false

        val hasAnyChosenColor =
                remembered.channelColors.isNotEmpty() ||
                        remembered.texturedColors.values.any { it.isNotEmpty() }
        if (!hasAnyChosenColor && !hasDefaultColor) return false

        val flatOk =
                remembered.channelColors.values.all { c -> (c.rgb and 0x00FFFFFF) in allowedColors }
        if (!flatOk) return false

        val texturedOk =
                remembered.texturedColors.values.flatMap { it.values }.all { c ->
                    (c.rgb and 0x00FFFFFF) in allowedColors
                }
        if (!texturedOk) return false

        return true
    }

    private fun cyclePart(
            layer: LayerDefinition,
            mannequin: Mannequin,
            state: ControlState,
            player: Player,
            backwards: Boolean
    ): String? {
        rememberCurrentPartSelection(mannequin, layer)

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

        val remembered = partSelectionMemory[mannequin.id]?.get(layer.id)?.get(chosen.id)
        val sel =
                if (remembered != null &&
                                canRestoreRememberedSelection(layer, chosen, remembered, player)
                ) {
                    remembered.copy(layerId = layer.id, option = chosen)
                } else {
                    buildInitialSelection(layer, chosen, player)
                }
        mannequin.selection =
                mannequin.selection.copy(
                        selections = mannequin.selection.selections + (layer.id to sel)
                )
        rememberCurrentPartSelection(mannequin, layer)

        state.channelIndex[layer.id] = 0
        state.colorIndex[layer.id] = 0
        val rawTex = layerManager.resolveTextures(layer, chosen, player)
        state.textureIndex[layer.id] =
                if (sel.selectedTexture != null) {
                    rawTex.indexOf(sel.selectedTexture).coerceAtLeast(0)
                } else {
                    rawTex.indexOf("default").coerceAtLeast(0)
                }

        refreshDynamicLabels(mannequin.id, chosen, layer)
        val hud = holoController.getHud(player.uniqueId)
        if (hud != null) refreshColorGrid(player, mannequin, state, hud)

        val prettyPart = prettyName(chosen.displayName)
        val partEvent =
                MannequinPartChangeEvent(
                        mannequin.id,
                        mannequin.location,
                        player,
                        layer = layer.id,
                        part = prettyPart.replace(' ', '\u00A0')
                )
        plugin.server.pluginManager.callEvent(partEvent)
        if (partEvent.isCancelled) return "Part: $prettyPart"

        return "Part: $prettyPart"
    }

    // ── Grid & Submenu Management (SneakyHolos compatible) ──────────────────────────

    /** Spawns the color picker grid for a player. */
    private fun spawnColorGrid(
            player: Player,
            mannequin: Mannequin,
            state: ControlState,
            hud: HoloHUD,
            quiet: Boolean = false
    ) {
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

        val config = loadGridConfig()
        val selectedColor = currentSelectedGridColor(mannequin, state)

        val grid =
                HoloGridBuilder(
                        config.originX,
                        config.originY,
                        config.originZ,
                        config.cellSpacingX,
                        config.cellSpacingY,
                        config.yawOffset,
                        config.pitch,
                        true
                )

        // Default button
        if (hasDefaultColor) {
            grid.addButton(
                    id = "color_default",
                    textMM = config.headerTextMM.replace("{message}", "Default"),
                    column = 0,
                    row = -1,
                    bgDefault = config.bgHeader,
                    bgHighlight = HUD_BG_HIGHLIGHT,
                    lineWidth = config.headerLineWidth,
                    scaleX = config.headerScale,
                    scaleY = config.headerScale,
                    interactionWidth = 0.6f,
                    interactionHeight = 0.3f,
                    onClick = { p, _ ->
                        applyGridCellColor(null, "Default", null, mannequin.id, mannequin, state, p)
                    }
            )
        }

        // Palette rows
        for ((row, palId) in allPaletteIds.withIndex()) {
            val palette = layerManager.palette(palId) ?: continue

            // Palette header
            grid.addButton(
                    id = "pal_header_$palId",
                    textMM = config.headerTextMM.replace("{message}", prettyName(palId)),
                    column = 0,
                    row = row,
                    bgDefault = config.bgHeader,
                    bgHighlight = config.bgHeader,
                    lineWidth = config.headerLineWidth,
                    scaleX = config.headerScale,
                    scaleY = config.headerScale
            )

            // Color swatches
            for ((col, namedColor) in palette.colors.withIndex()) {
                val rgb = namedColor.color
                val bgNormal =
                        (0xFF shl 24) or
                                ((rgb.red and 0xFF) shl 16) or
                                ((rgb.green and 0xFF) shl 8) or
                                (rgb.blue and 0xFF)
                val isSelected = selectedColor != null && rgb == selectedColor

                grid.addButton(
                        id = "color_${palId}_${namedColor.name}",
                        textMM = " ",
                        column = (config.headerGap / config.cellSpacingX).toInt() + col,
                        row = row,
                        bgDefault = if (isSelected) config.bgSelected else bgNormal,
                        bgHighlight = HUD_BG_HIGHLIGHT,
                        lineWidth = config.cellLineWidth,
                        scaleX = config.cellScaleX,
                        scaleY = config.cellScaleY,
                        interactionWidth = 0.1f,
                        interactionHeight = 0.15f,
                        onClick = { p, _ ->
                            applyGridCellColor(
                                    palId,
                                    prettyName(namedColor.name),
                                    rgb,
                                    mannequin.id,
                                    mannequin,
                                    state,
                                    p
                            )
                        }
                )
            }
        }

        hud.addButtons(grid.build(), instant = quiet)
        if (!quiet) {
            plugin.server.pluginManager.callEvent(
                    MannequinSubmenuOpenEvent(mannequin.id, mannequin.location, player)
            )
        }
    }

    private fun applyGridCellColor(
            palId: String?,
            colorName: String,
            color: java.awt.Color?,
            manId: UUID,
            mannequin: Mannequin,
            state: ControlState,
            player: Player
    ) {
        val layers = layerManager.definitionsInOrder()
        val layer = layers.getOrNull(state.layerIndex % layers.size) ?: return
        val option = freshOption(layer.id, mannequin) ?: return
        val current = mannequin.selection.selections[layer.id]
        val slots = resolveChannelSlots(layer, option, state, player)
        val slotIdx = state.channelIndex.getOrDefault(layer.id, 0)
        val slot = slots.getOrNull(slotIdx) ?: return

        if (slot.subChannel != null) {
            val prevTextured = current?.texturedColors ?: emptyMap()
            val prevSub = prevTextured[slot.maskIdx] ?: emptyMap()
            val newSub =
                    if (color == null) prevSub - slot.subChannel
                    else prevSub + (slot.subChannel to color)
            val newTextured =
                    if (newSub.isEmpty()) prevTextured - slot.maskIdx
                    else prevTextured + (slot.maskIdx to newSub)
            val selection =
                    current?.copy(texturedColors = newTextured)
                            ?: LayerSelection(layer.id, option, texturedColors = newTextured)
            mannequin.selection =
                    mannequin.selection.copy(
                            selections = mannequin.selection.selections + (layer.id to selection)
                    )
        } else {
            val prevColors = current?.channelColors ?: emptyMap()
            val newColors =
                    if (color == null) prevColors - slot.maskIdx
                    else prevColors + (slot.maskIdx to color)
            val selection =
                    current?.copy(channelColors = newColors)
                            ?: LayerSelection(layer.id, option, channelColors = newColors)
            mannequin.selection =
                    mannequin.selection.copy(
                            selections = mannequin.selection.selections + (layer.id to selection)
                    )
        }

        val colorChangeEvent =
                MannequinColorChangeEvent(
                        manId,
                        mannequin.location,
                        player,
                        layer.id,
                        slot.label,
                        color,
                        colorName.replace(' ', '\u00A0')
                )
        plugin.server.pluginManager.callEvent(colorChangeEvent)
        if (colorChangeEvent.isCancelled) return

        rememberCurrentPartSelection(mannequin, layer)
        updateStatus(manId, "Color: $colorName")
        render(mannequin, nearbyViewers(mannequin))

        // Update grid highlights
        val hud = holoController.getHud(player.uniqueId) ?: return
        val config = loadGridConfig()
        val allColorIds = hud.buttons.filter { it.id.startsWith("color_") }.map { it.id }
        for (id in allColorIds) {
            if (id == "color_default") {
                hud.updateButtonBg(id, config.bgHeader)
                continue
            }
            val idParts = id.removePrefix("color_").split('_')
            if (idParts.size < 2) continue
            val partPalId = idParts[0]
            val colorPart = idParts[1]
            val palette = layerManager.palette(partPalId) ?: continue
            val namedColor = palette.colors.find { it.name == colorPart } ?: continue
            val rgb = namedColor.color
            val bgNormal =
                    (0xFF shl 24) or
                            ((rgb.red and 0xFF) shl 16) or
                            ((rgb.green and 0xFF) shl 8) or
                            (rgb.blue and 0xFF)
            val isMatch = color != null && rgb == color
            hud.updateButtonBg(id, if (isMatch) config.bgSelected else bgNormal)
        }
    }

    private fun despawnColorGrid(player: Player, hud: HoloHUD, quiet: Boolean = false) {
        val toRemove =
                hud.buttons
                        .filter { it.id.startsWith("color_") || it.id.startsWith("pal_header_") }
                        .map { it.id }
        if (toRemove.isNotEmpty()) {
            hud.removeButtons(toRemove, instant = quiet)
            if (!quiet) {
                val mannequin = mannequins[hud.mannequinId] ?: return
                plugin.server.pluginManager.callEvent(
                        MannequinSubmenuCloseEvent(mannequin.id, mannequin.location, player)
                )
            }
        }
    }

    private fun currentSelectedGridColor(
            mannequin: Mannequin,
            state: ControlState
    ): java.awt.Color? {
        val layers = layerManager.definitionsInOrder()
        val layer = layers.getOrNull(state.layerIndex % layers.size) ?: return null
        val option = freshOption(layer.id, mannequin) ?: return null
        val slots =
                resolveChannelSlots(
                        layer,
                        option,
                        state,
                        plugin.server.onlinePlayers.firstOrNull() ?: return null
                )
        val slot = slots.getOrNull(state.channelIndex.getOrDefault(layer.id, 0)) ?: return null
        val selection = mannequin.selection.selections[layer.id]
        return if (slot.subChannel != null) {
            selection?.texturedColors?.get(slot.maskIdx)?.get(slot.subChannel)
        } else {
            selection?.channelColors?.get(slot.maskIdx)
        }
    }

    /** Spawns the configuration submenu. */
    private fun spawnConfigGrid(
            player: Player,
            mannequin: Mannequin,
            state: ControlState,
            hud: HoloHUD,
            quiet: Boolean = false
    ) {
        val config = loadGridConfig("hud-buttons.config-menu")
        val grid =
                HoloGridBuilder(
                        config.originX,
                        config.originY,
                        config.originZ,
                        config.cellSpacingX,
                        config.cellSpacingY,
                        config.yawOffset,
                        config.pitch,
                        true
                )

        val options = listOf("Save", "Load", "Clear", "Overlay")
        for ((i, opt) in options.withIndex()) {
            grid.addButton(
                    id = "config_${opt.lowercase()}",
                    textMM = config.headerTextMM.replace("{message}", opt),
                    column = 0,
                    row = i,
                    bgDefault = config.bgHeader,
                    bgHighlight = HUD_BG_HIGHLIGHT,
                    lineWidth = config.headerLineWidth,
                    scaleX = config.headerScale,
                    scaleY = config.headerScale,
                    onClick = { p, _ -> executeConfigAction(opt, mannequin.id, p, state, hud) }
            )
        }
        hud.addButtons(grid.build(), instant = quiet)
        if (!quiet) {
            plugin.server.pluginManager.callEvent(
                    MannequinSubmenuOpenEvent(mannequin.id, mannequin.location, player)
            )
        }
    }

    private fun despawnConfigGrid(player: Player, hud: HoloHUD, quiet: Boolean = false) {
        val toRemove = hud.buttons.filter { it.id.startsWith("config_") }.map { it.id }
        if (toRemove.isNotEmpty()) {
            hud.removeButtons(toRemove, instant = quiet)
            if (!quiet) {
                val mannequin = mannequins[hud.mannequinId] ?: return
                plugin.server.pluginManager.callEvent(
                        MannequinSubmenuCloseEvent(mannequin.id, mannequin.location, player)
                )
            }
        }
    }

    private fun executeConfigAction(
            action: String,
            manId: UUID,
            player: Player,
            state: ControlState,
            hud: HoloHUD
    ) {
        val mannequin = mannequins[manId] ?: return
        when (action) {
            "Save" -> {
                val uid = sessionManager.save(mannequin, player)
                plugin.server.pluginManager.callEvent(
                        MannequinSessionSaveEvent(mannequin.id, mannequin.location, player, uid)
                )
                updateStatus(manId, "Saved to session '$uid'")
                player.sendMessage(
                        Component.text("Session saved: ")
                                .color(NamedTextColor.GREEN)
                                .append(
                                        Component.text(uid)
                                                .color(NamedTextColor.YELLOW)
                                                .hoverEvent(
                                                        HoverEvent.showText(
                                                                Component.text("Click to copy UID")
                                                        )
                                                )
                                                .clickEvent(ClickEvent.copyToClipboard(uid))
                                )
                )
            }
            "Load" -> {
                state.mode = ControlMode.LOAD
                updateStatus(manId, "Type UID in chat")
                player.sendMessage(
                        Component.text("Enter session UID in chat to load.")
                                .color(net.kyori.adventure.text.format.NamedTextColor.YELLOW)
                )
            }
            "Clear" -> {
                mannequin.selection = bootstrapSelection()
                updateStatus(manId, "Cleared")
                renderFull(mannequin, nearbyViewers(mannequin))
                refreshDynamicLabels(manId)
            }
            "Overlay" -> {
                mannequin.showOverlay = !mannequin.showOverlay
                updateStatus(manId, "Outer Layer: ${if (mannequin.showOverlay) "ON" else "OFF"}")
                renderFull(mannequin, nearbyViewers(mannequin))
            }
        }
        // despawnConfigGrid(player, hud) // Removed: keep menu open
        refreshDynamicLabels(manId)
    }

    fun handleInteract(mannequinId: UUID, player: Player, backwards: Boolean) {
        val mannequin = mannequins[mannequinId] ?: return
        val hud = holoController.getHud(player.uniqueId)

        if (hud != null && hud.mannequinId == mannequinId) {
            val hover = hud.isAnyButtonHovered
            val tolerance = meetsInteractionTolerances(player, mannequin)

            if (plugin.config.getBoolean("plugin.debug", false)) {
                plugin.logger.info(
                        "[DEBUG] handleInteract: hudOpen=true hoverButton=$hover tolerance=$tolerance"
                )
            }

            // Only cycle if not hovering a button and within tolerance
            if (!hover && tolerance) {
                val state = controlState[mannequinId] ?: return
                // Bug 3: Interacting with mannequin cycles parts, not layers
                val layers = layerManager.definitionsInOrder()
                val layer = layers.getOrNull(state.layerIndex % layers.size)
                if (layer != null) {
                    val chosen = cyclePart(layer, mannequin, state, player, backwards)
                    render(mannequin, nearbyViewers(mannequin))
                    refreshDynamicLabels(mannequinId, freshOption(layer.id, mannequin), layer)
                    if (chosen != null) {
                        updateStatus(mannequinId, "${prettyName(chosen)}")
                    }
                }
            }
            return
        }

        spawnPlayerHud(player, mannequin, player.location.yaw)
    }

    fun isPlayerInLoadMode(viewerId: UUID): Boolean {
        val hud = holoController.getHud(viewerId) ?: return false
        val state = controlState[hud.mannequinId] ?: return false
        return state.mode == ControlMode.LOAD
    }

    private fun meetsInteractionTolerances(player: Player, mannequin: Mannequin): Boolean {
        val pLoc = player.location
        val mLoc = mannequin.location
        if (pLoc.distance(mLoc) > interactRange) return false

        val toMannequin = mLoc.toVector().subtract(pLoc.toVector()).setY(0).normalize()
        val facing = pLoc.direction.setY(0).normalize()

        val angle = facing.angle(toMannequin)
        return Math.toDegrees(angle.toDouble()) <= partFacingToleranceDeg
    }

    fun startHoverTask() {
        /* No-op, handled by HoloController */
    }
    fun stopHoverTask() {
        /* No-op, handled by HoloController */
    }

    private data class GridConfig(
            val maxRows: Int,
            val cellSpacingX: Float,
            val cellSpacingY: Float,
            val originX: Float,
            val originY: Float,
            val originZ: Float,
            val pitch: Float,
            val yawOffset: Float,
            val cellLineWidth: Int,
            val cellScaleX: Float,
            val cellScaleY: Float,
            val headerLineWidth: Int,
            val headerScale: Float,
            val headerGap: Float,
            val headerTextMM: String,
            val bgHeader: Int,
            val bgSelected: Int
    )

    private fun loadGridConfig(path: String = "hud-buttons.color-grid"): GridConfig {
        val sec = plugin.config.getConfigurationSection(path)
        val defaultHeaderText =
                if (path.contains("config")) "{message}"
                else "<white><font:minecraft:uniform>{message}"

        return GridConfig(
                maxRows = sec?.getInt("max-rows", 6) ?: 6,
                cellSpacingX = sec?.getDouble("cell-spacing-x", 0.12)?.toFloat() ?: 0.12f,
                cellSpacingY =
                        sec
                                ?.let {
                                    if (it.contains("item-spacing-y"))
                                            it.getDouble("item-spacing-y")
                                    else it.getDouble("cell-spacing-y", 0.18)
                                }
                                ?.toFloat()
                                ?: 0.18f,
                originX = sec?.getDouble("origin-x", 0.3)?.toFloat() ?: 0.3f,
                originY = sec?.getDouble("origin-y", -0.3)?.toFloat() ?: -0.3f,
                originZ = sec?.getDouble("origin-z", -1.8)?.toFloat() ?: -1.8f,
                pitch = sec?.getDouble("pitch", -0.35)?.toFloat() ?: -0.35f,
                yawOffset = sec?.getDouble("yaw", 0.0)?.toFloat() ?: 0f,
                cellLineWidth =
                        sec?.let {
                            if (it.contains("item-line-width")) it.getInt("item-line-width")
                            else it.getInt("cell-line-width", 18)
                        }
                                ?: 18,
                cellScaleX = sec?.getDouble("cell-scale-x", 1.0)?.toFloat() ?: 1f,
                cellScaleY = sec?.getDouble("cell-scale-y", 1.0)?.toFloat() ?: 1f,
                headerLineWidth = 80, // Default for label
                headerScale = sec?.getDouble("header-scale", 1.0)?.toFloat() ?: 1f,
                headerGap = sec?.getDouble("header-gap", 0.35)?.toFloat() ?: 0.35f,
                headerTextMM = sec?.getString("header-text") ?: defaultHeaderText,
                bgHeader = parseArgb(sec?.getString("bg-header")) ?: 0x60000000,
                bgSelected = parseArgb(sec?.getString("bg-selected")) ?: 0xFF44AA44.toInt()
        )
    }

    private fun composeCurrentSkin(mannequin: Mannequin): java.awt.image.BufferedImage {
        val definitions = layerManager.definitionsInOrder()
        return SkinComposer.compose(
                definitions,
                mannequin.selection,
                useSlimModel = isSlimModel(mannequin),
                optionResolver = { lid, oid -> layerManager.optionsFor(lid).find { it.id == oid } },
                textureResolver = { tid: String -> layerManager.texture(tid) },
                brightnessInfluenceResolver = { layerId, option ->
                    val def = layerManager.definitionsInOrder().find { it.id == layerId }
                    if (def != null) layerManager.resolveBrightnessInfluence(def, option) else 0f
                }
        )
    }

    private fun nearbyViewers(mannequin: Mannequin): List<Player> {
        val radiusSq = viewRadius * viewRadius
        return plugin.server.onlinePlayers.filter {
            it.world == mannequin.location.world &&
                    it.location.distanceSquared(mannequin.location) <= radiusSq
        }
    }

    private fun getFallbackColor(
            def: LayerDefinition,
            chosen: LayerOption,
            player: Player? = null,
            rng: java.util.Random = java.util.concurrent.ThreadLocalRandom.current()
    ): java.awt.Color? {
        val rawPal = layerManager.resolvePalettes(def, chosen, player)
        val actualPal = rawPal.filter { it != "default" }
        if (actualPal.isNotEmpty()) {
            val palette = layerManager.palette(actualPal.first())
            if (palette != null && palette.colors.isNotEmpty()) {
                return palette.colors[rng.nextInt(palette.colors.size)].color
            }
        }
        return null
    }

    private fun resolveInitialTexture(
            def: LayerDefinition,
            option: LayerOption,
            player: Player?
    ): String? {
        val rawTex = layerManager.resolveTextures(def, option, player)
        val hasDefaultTex = "default" in rawTex
        if (hasDefaultTex) return null
        val actualTex = rawTex.filter { it != "default" }
        return actualTex.firstOrNull()
    }

    private fun resolveInitialColor(
            def: LayerDefinition,
            option: LayerOption,
            player: Player?
    ): java.awt.Color? {
        val palettes = layerManager.resolvePalettes(def, option, player)
        val defaultAllowed = "default" in palettes
        if (defaultAllowed) return null
        return getFallbackColor(def, option, player)
    }

    private fun buildSlots(option: LayerOption, texId: String?): List<ChannelSlot> {
        val maskChannels = option.masks.keys.sorted()
        val texDef = texId?.let { layerManager.texture(it) }
        val activeSubs = if (texDef?.blendMapImage != null) texDef.activeSubChannels else null
        return buildChannelSlots(maskChannels, activeSubs)
    }

    private fun migrateColors(
            layer: LayerDefinition,
            option: LayerOption,
            currentSel: LayerSelection,
            newTexId: String?,
            player: Player?
    ): LayerSelection {
        val channelColors = currentSel.channelColors.toMutableMap()
        val texturedColors =
                currentSel.texturedColors.mapValues { it.value.toMutableMap() }.toMutableMap()

        val allMasks = (channelColors.keys + texturedColors.keys).toSet()
        for (mask in allMasks) {
            val flat = channelColors[mask]
            val sub0 = texturedColors[mask]?.get(0)
            if (flat != null && sub0 == null)
                    texturedColors.getOrPut(mask) { mutableMapOf() }[0] = flat
            else if (sub0 != null && flat == null) channelColors[mask] = sub0
        }

        val newSlots = buildSlots(option, newTexId)
        val fallback = resolveInitialColor(layer, option, player)
        if (fallback != null) {
            for (slot in newSlots) {
                if (slot.subChannel != null) {
                    val maskMap = texturedColors.getOrPut(slot.maskIdx) { mutableMapOf() }
                    if (!maskMap.containsKey(slot.subChannel)) maskMap[slot.subChannel] = fallback
                } else {
                    if (!channelColors.containsKey(slot.maskIdx))
                            channelColors[slot.maskIdx] = fallback
                }
            }
        }

        return currentSel.copy(
                selectedTexture = newTexId,
                channelColors = channelColors,
                texturedColors = texturedColors
        )
    }

    private fun buildInitialSelection(
            def: LayerDefinition,
            chosen: LayerOption,
            player: Player? = null
    ): LayerSelection {
        val selectedTexture = resolveInitialTexture(def, chosen, player)
        val sel =
                LayerSelection(layerId = def.id, option = chosen, selectedTexture = selectedTexture)
        return migrateColors(def, chosen, sel, selectedTexture, player)
    }

    private fun bootstrapSelection(): SkinSelection {
        val definitions = layerManager.definitionsInOrder()
        val selections =
                definitions.associate { def ->
                    val options = layerManager.optionsFor(def.id)
                    val chosen =
                            options.firstOrNull { opt ->
                                val pal = layerManager.resolvePalettes(def, opt, null)
                                val tex = layerManager.resolveTextures(def, opt, null)
                                pal.isNotEmpty() && tex.isNotEmpty()
                            }
                                    ?: options.firstOrNull()
                    if (chosen != null) def.id to buildInitialSelection(def, chosen)
                    else def.id to LayerSelection(layerId = def.id, option = null)
                }
        return SkinSelection(selections)
    }

    private fun randomize(mannequin: Mannequin, randomizeModel: Boolean = false) {
        val definitions = layerManager.definitionsInOrder()
        val rng = java.util.concurrent.ThreadLocalRandom.current()
        val newSelections = mutableMapOf<String, LayerSelection>()

        for (def in definitions) {
            val options = layerManager.optionsFor(def.id)
            if (options.isEmpty()) continue
            val viable =
                    options.filter { opt ->
                        val pal = layerManager.resolvePalettes(def, opt, null)
                        val tex = layerManager.resolveTextures(def, opt, null)
                        pal.isNotEmpty() && tex.isNotEmpty()
                    }
            val chosen =
                    if (viable.isNotEmpty()) viable[rng.nextInt(viable.size)]
                    else options[rng.nextInt(options.size)]
            newSelections[def.id] = buildInitialSelection(def, chosen)
        }

        mannequin.selection = SkinSelection(newSelections)
        for (def in definitions) rememberCurrentPartSelection(mannequin, def)
        if (randomizeModel) mannequin.slimModel = rng.nextBoolean()

        val state = controlState[mannequin.id]
        if (state != null) {
            syncControlState(mannequin, state)
            state.mode = ControlMode.NONE
        }
    }

    private fun syncControlState(mannequin: Mannequin, state: ControlState) {
        val definitions = layerManager.definitionsInOrder()
        for (def in definitions) {
            val sel = mannequin.selection.selections[def.id]
            val opts = layerManager.optionsFor(def.id)
            state.partIndex[def.id] =
                    opts.indexOfFirst { it.id == sel?.option?.id }.coerceAtLeast(0)
            state.channelIndex[def.id] = 0
            state.colorIndex[def.id] = 0
            val rawTex =
                    if (sel?.option != null) layerManager.resolveTextures(def, sel.option, null)
                    else emptyList()
            state.textureIndex[def.id] =
                    if (sel?.selectedTexture != null)
                            rawTex.indexOf(sel.selectedTexture).coerceAtLeast(0)
                    else rawTex.indexOf("default").coerceAtLeast(0)
        }
    }

    private fun prettyName(raw: String): String =
            raw.trim()
                    .split(Regex("[_\\-\\s]+"))
                    .filter { it.isNotBlank() }
                    .joinToString(" ") { it.lowercase().replaceFirstChar { ch -> ch.titlecase() } }
                    .ifEmpty { raw }
}
