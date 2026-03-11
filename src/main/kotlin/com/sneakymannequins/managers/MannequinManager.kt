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
import com.sneakymannequins.util.SkinUv
import com.sneakymouse.sneakyholos.*
import com.sneakymouse.sneakyholos.util.HoloGridBuilder
import com.sneakymouse.sneakyholos.util.TextUtility
import java.util.UUID
import kotlin.math.sqrt
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
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

private data class MenuLayout(
        val originX: Float,
        val originY: Float,
        val originZ: Float,
        val pitch: Float,
        val yaw: Float
)

private data class HudButton(
        val name: String,
        val textMM: String, // raw MiniMessage string (for generating variants)
        val activeTextJson: String?, // JSON component for part/color active mode
        val disabledTextJson: String?, // JSON component when button is disabled
        val confirmTextJson: String?, // JSON component for "confirm?" (random only)
        val tx: Float,
        val ty: Float,
        val tz: Float,
        val lineWidth: Int,
        val bgDefault: Int,
        val bgHighlight: Int,
        val scaleX: Float? = null,
        val scaleY: Float? = null,
        val type: String? = null,
        val targetLayer: String? = null,
        val palette: String? = null,
        val colorHex: String? = null,
        // Menu specific
        val openByDefault: Boolean = false,
        val submenuLayout: MenuLayout? = null,
        val items: Map<String, HudButton>? = null,
        // Color Grid specific
        val maxRows: Int = 4,
        val cellSpacingX: Float = 0.12f,
        val cellSpacingY: Float = 0.18f,
        val cellLineWidth: Int = 18,
        val cellScaleX: Float = 1f,
        val cellScaleY: Float = 1f,
        val headerLineWidth: Int = 80,
        val headerScale: Float = 0.6f,
        val headerGap: Float = 0.35f,
        val headerTextMM: String = "<white>{message}",
        val bgHeader: Int? = null
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
        private val holoController: HoloController
) {
    private val mannequins = mutableMapOf<UUID, Mannequin>()
    private val sentTo = mutableMapOf<UUID, MutableSet<UUID>>() // viewerId → mannequins seen
    /** mannequinId -> last action */
    private val statusText = mutableMapOf<UUID, String>()
    /** mannequinId -> last saved fingerprint */
    private val lastSavedFingerprint = mutableMapOf<UUID, String>()
    /** mannequinId -> true = T-pose */
    private val poseState = mutableMapOf<UUID, Boolean>()
    private val controlState = mutableMapOf<UUID, ControlState>()
    /** mannequin -> layerId -> partId(optionId) -> last selection used for that part */
    private val partSelectionMemory =
            mutableMapOf<UUID, MutableMap<String, MutableMap<String, LayerSelection>>>()
    private val interactionDebounce = mutableMapOf<Pair<UUID, String>, Long>()
    /** playerId → expiry timestamp for random confirmation */
    private val randomConfirm = mutableMapOf<UUID, Long>()

    /** mannequinId → expiry timestamp for random cooldown */
    private val randomCooldown = mutableMapOf<UUID, Long>()
    /** playerId -> expiry timestamp for apply button cooldown */
    private val applyCooldown = mutableMapOf<UUID, Long>()

    /** Manages INSTANT / BUILD pixel delivery to viewers. */
    private val animationManager = AnimationManager(plugin, handler)

    // ── Config-driven radii ─────────────────────────────────────────────────────

    /** Radius at which a mannequin first appears for a player. */
    private val viewRadius: Double
        get() = plugin.config.getDouble("rendering.view-radius", 8.0)

    /** Radius within which a player keeps receiving updates. */
    private val updateRadius: Double
        get() = plugin.config.getDouble("rendering.update-radius", 30.0)

    /** Radius of the mannequin Interaction hitbox (blocks). */
    private val interactRadius: Double
        get() = plugin.config.getDouble("controls.interact-radius", 5.0).coerceAtLeast(0.5)

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
        val modeStr = plugin.config.getString("$path.mode", "BUILD")?.uppercase() ?: "INSTANT"
        val mode = runCatching { RenderMode.valueOf(modeStr) }.getOrDefault(RenderMode.INSTANT)
        val interval = plugin.config.getInt("$path.tick-interval", 1).coerceAtLeast(1)
        val skip = plugin.config.getDouble("$path.skip-chance", 0.5).coerceIn(0.0, 1.0)
        val flyIn = plugin.config.getInt("$path.fly-in-count", 5).coerceAtLeast(0)
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
        private const val INTERACT_RANGE_DEFAULT = 4.0
        private const val PART_FACING_TOLERANCE_DEG_DEFAULT = 20.0
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

    /** Look up a button config by name. Searches recursively through submenus. */
    private fun buttonByName(name: String, list: List<HudButton> = hudButtons): HudButton? {
        for (btn in list) {
            if (btn.name == name) return btn
            if (btn.items != null) {
                val found = buttonByName(name, btn.items.values.toList())
                if (found != null) return found
            }
        }
        return null
    }

    /** Finds the ID of the menu (the submenu button's ID) containing a button of the given type. */
    private fun findMenuIdForType(
            type: String,
            list: List<HudButton> = hudButtons,
            parentId: String? = null
    ): String? {
        for (btn in list) {
            if (btn.type == type) return parentId
            if (btn.items != null) {
                val found = findMenuIdForType(type, btn.items.values.toList(), btn.name)
                if (found != null) return found
            }
        }
        return null
    }

    /**
     * Resolve the current (fresh) [LayerOption] for a layer on a mannequin. The mannequin's
     * selection may hold a stale reference after a layer reload / remask, so we always look up the
     * option by ID from the layer manager and fall back to the stale copy only if it was removed.
     */
    private fun freshOption(layerId: String, mannequin: Mannequin): LayerOption? {
        val selOption = mannequin.selection.selections[layerId]?.option
        if (selOption != null) {
            return layerManager.findOptionById(layerId, selOption.id) ?: selOption
        }
        return layerManager.optionsFor(layerId).firstOrNull()
    }

    /** Parse a single HudButton recursively. */
    private fun parseHudButton(
            name: String,
            sec: org.bukkit.configuration.ConfigurationSection?,
            globalBgDef: Int,
            globalBgHi: Int
    ): HudButton? {
        if (sec == null) return null

        val type = sec.getString("type") ?: name
        val textMM = sec.getString("text") ?: "<white>${name.replaceFirstChar { it.uppercase() }}"
        val activeMM = sec.getString("active-text")
        val disabledMM = sec.getString("disabled-text")
        val confirmMM = sec.getString("confirm-text")

        val tx = sec.getDouble("translation.x", 0.0).toFloat()
        val ty = sec.getDouble("translation.y", 0.0).toFloat()
        val tz = sec.getDouble("translation.z", 0.0).toFloat()

        val lw = sec.getInt("line-width", 200)
        val bgDef = parseArgb(sec.getString("bg-default")) ?: globalBgDef
        val bgHi = parseArgb(sec.getString("bg-highlight")) ?: globalBgHi

        val sx = if (sec.contains("scale-x")) sec.getDouble("scale-x").toFloat() else null
        val sy = if (sec.contains("scale-y")) sec.getDouble("scale-y").toFloat() else null

        val targetLayer = sec.getString("target-layer")
        val palette = sec.getString("palette")
        val colorHex = sec.getString("color")

        val openByDefault = sec.getBoolean("open-by-default", false)

        val submenuLayout =
                sec.getConfigurationSection("submenu-layout")?.let { layoutSec ->
                    MenuLayout(
                            originX = layoutSec.getDouble("origin-x", 0.0).toFloat(),
                            originY = layoutSec.getDouble("origin-y", 0.0).toFloat(),
                            originZ = layoutSec.getDouble("origin-z", 0.0).toFloat(),
                            pitch = layoutSec.getDouble("pitch", 0.0).toFloat(),
                            yaw = layoutSec.getDouble("yaw", 0.0).toFloat()
                    )
                }

        val itemsSec = sec.getConfigurationSection("items")
        val itemsMap =
                if (itemsSec != null) {
                    val map = mutableMapOf<String, HudButton>()
                    for (key in itemsSec.getKeys(false)) {
                        val itemBtn =
                                parseHudButton(
                                        key,
                                        itemsSec.getConfigurationSection(key),
                                        globalBgDef,
                                        globalBgHi
                                )
                        if (itemBtn != null) {
                            map[key] = itemBtn
                        }
                    }
                    map.ifEmpty { null }
                } else null

        val bgHeader = parseArgb(sec.getString("bg-header"))

        return HudButton(
                name = name,
                textMM = textMM,
                activeTextJson = activeMM?.let { TextUtility.mmToJson(it) },
                disabledTextJson = disabledMM?.let { TextUtility.mmToJson(it) },
                confirmTextJson = confirmMM?.let { TextUtility.mmToJson(it) },
                tx = tx,
                ty = ty,
                tz = tz,
                lineWidth = lw,
                bgDefault = bgDef,
                bgHighlight = bgHi,
                scaleX = sx,
                scaleY = sy,
                type = type,
                targetLayer = targetLayer,
                palette = palette,
                colorHex = colorHex,
                openByDefault = openByDefault,
                submenuLayout = submenuLayout,
                items = itemsMap,
                maxRows = sec.getInt("max-rows", 4),
                cellSpacingX = sec.getDouble("cell-spacing-x", 0.12).toFloat(),
                cellSpacingY = sec.getDouble("cell-spacing-y", 0.18).toFloat(),
                cellLineWidth = sec.getInt("cell-line-width", 18),
                cellScaleX = sec.getDouble("cell-scale-x", 1.0).toFloat(),
                cellScaleY = sec.getDouble("cell-scale-y", 1.0).toFloat(),
                headerLineWidth = sec.getInt("header-line-width", 80),
                headerScale = sec.getDouble("header-scale", 0.6).toFloat(),
                headerGap = sec.getDouble("header-gap", 0.35).toFloat(),
                headerTextMM = sec.getString("header-text", "<white>{message}")
                                ?: "<white>{message}",
                bgHeader = bgHeader
        )
    }

    /** Read the hud-buttons config section and build the button list. */
    private fun loadHudButtons(): List<HudButton> {
        val globalBgDef =
                parseArgb(plugin.config.getString("hud-buttons.bg-default")) ?: HUD_BG_DEFAULT
        val globalBgHi =
                parseArgb(plugin.config.getString("hud-buttons.bg-highlight")) ?: HUD_BG_HIGHLIGHT

        val parentSec = plugin.config.getConfigurationSection("hud-buttons") ?: return emptyList()
        val keys =
                parentSec.getKeys(false).filter {
                    it != "bg-default" &&
                            it != "bg-highlight" &&
                            it != "config-menu" &&
                            it != "color-grid"
                }

        return keys.mapNotNull { name ->
            parseHudButton(name, parentSec.getConfigurationSection(name), globalBgDef, globalBgHi)
        }
    }

    // ── Lifecycle ───────────────────────────────────────────────────────────────

    fun loadFromDisk() {
        partSelectionMemory.clear()
        val loaded = persistence.load()
        loaded.forEach { data ->
            val selection = bootstrapSelection()
            val mannequin =
                    Mannequin(
                            id = data.id,
                            location = data.location.clone(),
                            selection = selection,
                            slimModel = data.slim,
                            savedUid = data.savedUid
                    )
            mannequins[data.id] = mannequin
            controlState[data.id] = ControlState()
            randomize(mannequin, randomizeModel = true)
            initButtonVisuals(data.id)
            cleanupControlEntities(data.id)
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
    fun applySession(mannequinId: UUID, session: SessionData) {
        val mannequin = mannequins[mannequinId] ?: return
        val state = controlState.getOrPut(mannequinId) { ControlState() }

        session.slimModel?.let { mannequin.slimModel = it }

        val definitions = layerManager.definitionsInOrder()
        val defMap = definitions.associateBy { it.id }
        val newSelections = mannequin.selection.selections.toMutableMap()

        for ((layerId, layerData) in session.layers) {
            if (!defMap.containsKey(layerId)) continue
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
        refreshDynamicLabels(mannequinId)

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
            applySession(manId, session)
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
        refreshDynamicLabels(manId)
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

        val seen = sentTo.getOrPut(viewer.uniqueId) { mutableSetOf() }
        for (man in mannequins.values) {
            val isSameWorld = man.location.world == viewer.world
            val distSq =
                    if (isSameWorld) man.location.distanceSquared(viewer.location)
                    else Double.MAX_VALUE

            if (man.id !in seen) {
                if (isSameWorld && distSq <= viewRadiusSq) {
                    renderFull(man, listOf(viewer), isFirstSeen = true)
                    seen += man.id
                }
            } else {
                if (!isSameWorld || distSq > updateRadiusSq) {
                    seen -= man.id
                    animationManager.cancelMannequinForPlayer(viewer.uniqueId, man.id)
                    handler.destroyMannequin(viewer, man.id)
                } else {
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
        val updateRadiusSq = updateRadius * updateRadius
        val seen = sentTo.getOrPut(viewer.uniqueId) { mutableSetOf() }
        for (man in mannequins.values) {
            val isSameWorld = man.location.world == viewer.world
            val distSq =
                    if (isSameWorld) man.location.distanceSquared(viewer.location)
                    else Double.MAX_VALUE

            if (man.id in seen) {
                if (!isSameWorld || distSq > updateRadiusSq) {
                    seen -= man.id
                    animationManager.cancelMannequinForPlayer(viewer.uniqueId, man.id)
                    handler.destroyMannequin(viewer, man.id)
                }
                continue
            }

            if (!isSameWorld || distSq > viewRadiusSq) continue

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
        layerManager.findOptionById(layerId, optionId)
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
            forceInstant: Boolean = false,
            forceArmPixels: Boolean = false
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
        val diff =
                if (forceArmPixels) {
                    mannequin.lastFrame.diff(nextFrame) { x, y -> SkinUv.isArmPixel(x, y) }
                } else {
                    mannequin.lastFrame.diff(nextFrame)
                }
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
        val btn = hudButtons.find { it.type == "status" }
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
    private fun buildHoloButtons(mannequin: Mannequin): MutableList<HoloButton> {
        return hudButtons
                .map { btn ->
                    val isStatus = btn.type == "status"
                    val initialJson =
                            if (isStatus) formatStatusText(statusText[mannequin.id])
                            else TextUtility.mmToJson(btn.textMM)
                    HoloButton(
                            id = btn.name,
                            textJson = initialJson,
                            tx = btn.tx,
                            ty = btn.ty,
                            tz = btn.tz,
                            lineWidth = btn.lineWidth,
                            bgDefault = btn.bgDefault,
                            bgHighlight = if (isStatus) btn.bgDefault else btn.bgHighlight,
                            scaleX = btn.scaleX ?: 1f,
                            scaleY = btn.scaleY ?: 1f,
                            interactionWidth = if (isStatus) 0.0f else null,
                            interactionHeight = if (isStatus) 0.0f else null,
                            onClick = { viewer, backwards ->
                                if (!isStatus) {
                                    handleButtonClick(btn.name, mannequin.id, viewer, backwards)
                                }
                            },
                            onHover = { viewer, entering ->
                                if (entering && !isStatus) {
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

    private fun spawnPlayerHud(player: Player, mannequin: Mannequin) {
        val buttons = buildHoloButtons(mannequin)
        val frameEnabled = plugin.config.getBoolean("hud-frame.enabled", false)
        val frameItem = if (frameEnabled) plugin.config.getString("hud-frame.item") else null
        val frameCmd = plugin.config.getInt("hud-frame.custom-model-data", 0)
        val frameCtx = plugin.config.getString("hud-frame.display-context", "GUI") ?: "GUI"
        val frameTx = plugin.config.getDouble("hud-frame.translation.x", 0.0).toFloat()
        val frameTy = plugin.config.getDouble("hud-frame.translation.y", 2.1).toFloat()
        val frameTz = plugin.config.getDouble("hud-frame.translation.z", -1.0).toFloat()
        val frameSx = plugin.config.getDouble("hud-frame.scale.x", 3.0).toFloat()
        val frameSy = plugin.config.getDouble("hud-frame.scale.y", 3.0).toFloat()
        val frameSz = plugin.config.getDouble("hud-frame.scale.z", 3.0).toFloat()

        val hud =
                HoloHUD(
                        viewer = player,
                        origin = mannequin.location,
                        mannequinId = mannequin.id,
                        handler = holoController.handler,
                        buttons = buttons,
                        frameItem = frameItem,
                        frameCustomModelData = frameCmd,
                        frameDisplayContext = frameCtx,
                        frameTx = frameTx,
                        frameTy = frameTy,
                        frameTz = frameTz,
                        frameSx = frameSx,
                        frameSy = frameSy,
                        frameSz = frameSz,
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

        val state = controlState[mannequin.id]
        if (state != null) {
            hudButtons.forEach { btn ->
                if (btn.type == "submenu" && btn.openByDefault) {
                    spawnMenu(btn, player, mannequin, state, hud, quiet = false)
                }
            }
        }
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
        val configBtn = buttonByName(buttonName) ?: return

        if (configBtn.type != "color" && configBtn.type != "config") {
            plugin.server.pluginManager.callEvent(
                    MannequinClickEvent(mannequinId, mannequin.location, player, buttonName)
            )
        }

        when (configBtn.type) {
            "model" -> {
                mannequin.slimModel = !mannequin.slimModel
                updateStatus(
                        mannequinId,
                        if (mannequin.slimModel) "Model: Slim" else "Model: Steve"
                )
                render(mannequin, nearbyViewers(mannequin), forceArmPixels = true)
                refreshDynamicLabels(mannequinId)
            }
            "pose" -> {
                poseState[mannequinId] = !(poseState[mannequinId] ?: false)
                updateStatus(
                        mannequinId,
                        if (poseState[mannequinId] == true) "Pose: T-Pose" else "Pose: Standard"
                )
                renderFull(mannequin, nearbyViewers(mannequin), forceInstant = true)
                refreshDynamicLabels(mannequinId)
            }
            "random" -> {
                val now = System.currentTimeMillis()
                val cooldownExpires = randomCooldown[mannequinId] ?: 0L
                if (now < cooldownExpires) return
                randomCooldown[mannequinId] = now + 500L

                val expires = randomConfirm[player.uniqueId] ?: 0L
                if (now < expires) {
                    randomConfirm[player.uniqueId] = 0L
                    randomize(mannequin, randomizeModel = true)
                    updateStatus(mannequinId, "Randomized")
                    renderFull(mannequin, nearbyViewers(mannequin), forceInstant = true)
                    refreshDynamicLabels(mannequinId)
                    val colorMenuId = findMenuIdForType("color_grid")
                    if (colorMenuId != null) {
                        val colorMenuBtn = buttonByName(colorMenuId)
                        if (colorMenuBtn != null &&
                                        hud.buttons.any { it.id.startsWith("${colorMenuId}_") }
                        ) {
                            spawnMenu(colorMenuBtn, player, mannequin, state, hud, quiet = true)
                        }
                    }
                } else {
                    randomConfirm[player.uniqueId] = now + 5000L
                    val btn = hud.buttons.find { it.id == buttonName }
                    if (btn != null) {
                        btn.textJson =
                                configBtn.confirmTextJson
                                        ?: TextUtility.mmToJson("<yellow>Confirm?")
                        hud.updateButtonText(buttonName, btn.textJson)
                    }
                }
            }
            "layer" -> {
                if (configBtn.targetLayer != null) {
                    val targetIdx = layers.indexOfFirst { it.id == configBtn.targetLayer }
                    if (targetIdx != -1) {
                        state.layerIndex = targetIdx
                        updateStatus(mannequinId, "Layer: ${prettyName(configBtn.targetLayer)}")
                    }
                } else {
                    state.layerIndex =
                            if (backwards) (state.layerIndex - 1 + layers.size) % layers.size
                            else (state.layerIndex + 1) % layers.size
                    val nextLayer = layers.getOrNull(state.layerIndex % layers.size) ?: return
                    updateStatus(mannequinId, "Layer: ${prettyName(nextLayer.id)}")
                }
                refreshDynamicLabels(mannequinId)
                val colorMenuId = findMenuIdForType("color_grid")
                if (colorMenuId != null) {
                    val colorMenuBtn = buttonByName(colorMenuId)
                    if (colorMenuBtn != null &&
                                    hud.buttons.any { it.id.startsWith("${colorMenuId}_") }
                    ) {
                        spawnMenu(colorMenuBtn, player, mannequin, state, hud, quiet = true)
                    }
                }

                // Flash the newly selected layer white for 10 ticks
                val nextLayerDef = layers.getOrNull(state.layerIndex % layers.size)
                if (nextLayerDef != null) {
                    val option = freshOption(nextLayerDef.id, mannequin)
                    if (option != null) {
                        val slots = resolveChannelSlots(nextLayerDef, option, state, player)
                        val currentSel = mannequin.selection.selections[nextLayerDef.id]

                        val flashColors = (currentSel?.channelColors ?: emptyMap()).toMutableMap()
                        val flashTextured =
                                (currentSel?.texturedColors ?: emptyMap()).toMutableMap()

                        for (slot in slots) {
                            if (slot.subChannel != null) {
                                val sub =
                                        flashTextured
                                                .getOrPut(slot.maskIdx) { emptyMap() }
                                                .toMutableMap()
                                sub[slot.subChannel] = java.awt.Color.WHITE
                                flashTextured[slot.maskIdx] = sub
                            } else {
                                flashColors[slot.maskIdx] = java.awt.Color.WHITE
                            }
                        }

                        val flashSel =
                                currentSel?.copy(
                                        channelColors = flashColors,
                                        texturedColors = flashTextured
                                )
                                        ?: LayerSelection(
                                                nextLayerDef.id,
                                                option,
                                                channelColors = flashColors,
                                                texturedColors = flashTextured
                                        )

                        mannequin.selection =
                                mannequin.selection.copy(
                                        selections =
                                                mannequin.selection.selections +
                                                        (nextLayerDef.id to flashSel)
                                )

                        val viewers = nearbyViewers(mannequin)
                        render(mannequin, viewers, forceInstant = true)

                        val restoreSel = currentSel ?: LayerSelection(nextLayerDef.id, option)
                        plugin.server.scheduler.runTaskLater(
                                plugin,
                                Runnable {
                                    if (mannequins[mannequinId] != mannequin) return@Runnable
                                    mannequin.selection =
                                            mannequin.selection.copy(
                                                    selections =
                                                            mannequin.selection.selections +
                                                                    (nextLayerDef.id to restoreSel)
                                            )
                                    render(mannequin, nearbyViewers(mannequin), forceInstant = true)
                                },
                                10L
                        )
                    }
                }
            }
            "submenu" -> {
                val isVisible = hud.buttons.any { it.id.startsWith("${buttonName}_") }
                if (isVisible) {
                    despawnMenu(buttonName, player, hud)
                } else {
                    spawnMenu(configBtn, player, mannequin, state, hud)
                }
                refreshDynamicLabels(mannequinId)
            }
            "texture" -> {
                if (layer != null) {
                    val option = freshOption(layer.id, mannequin)
                    if (option != null) {
                        val texs = layerManager.resolveTextures(layer, option, player)
                        if (texs.size > 1) {
                            val currentIdx = state.textureIndex.getOrDefault(layer.id, 0)
                            val nextIdx =
                                    if (backwards) (currentIdx - 1 + texs.size) % texs.size
                                    else (currentIdx + 1) % texs.size
                            state.textureIndex[layer.id] = nextIdx
                            val nextTex = texs[nextIdx]
                            val currentSel = mannequin.selection.selections[layer.id]
                            val nextSel =
                                    currentSel?.copy(
                                            selectedTexture =
                                                    if (nextTex == "default") null else nextTex
                                    )
                                            ?: LayerSelection(
                                                    layer.id,
                                                    option,
                                                    selectedTexture =
                                                            if (nextTex == "default") null
                                                            else nextTex
                                            )

                            updateStatus(mannequinId, "Texture: ${prettyName(nextTex)}")
                            refreshDynamicLabels(mannequinId)
                            refreshColorGrid(player, mannequin, state, hud)

                            // Flash texture channels for 10 ticks
                            val slots = resolveChannelSlots(layer, option, state, player)
                            val texDef = layerManager.texture(nextTex)
                            val hasBlendMap = texDef?.blendMapImage != null

                            val flashColors = nextSel.channelColors.toMutableMap()
                            val flashTextured = nextSel.texturedColors.toMutableMap()

                            if (hasBlendMap) {
                                val distinctColors =
                                        listOf(
                                                java.awt.Color.RED,
                                                java.awt.Color.GREEN,
                                                java.awt.Color.BLUE,
                                                java.awt.Color.YELLOW,
                                                java.awt.Color.CYAN,
                                                java.awt.Color.MAGENTA
                                        )
                                var colorIdx = 0
                                for (slot in slots) {
                                    val c = distinctColors[colorIdx % distinctColors.size]
                                    if (slot.subChannel != null) {
                                        val sub =
                                                flashTextured
                                                        .getOrPut(slot.maskIdx) { emptyMap() }
                                                        .toMutableMap()
                                        sub[slot.subChannel] = c
                                        flashTextured[slot.maskIdx] = sub
                                    } else {
                                        flashColors[slot.maskIdx] = c
                                    }
                                    colorIdx++
                                }
                            } else {
                                for (slot in slots) {
                                    if (slot.subChannel != null) {
                                        val sub =
                                                flashTextured
                                                        .getOrPut(slot.maskIdx) { emptyMap() }
                                                        .toMutableMap()
                                        sub[slot.subChannel] = java.awt.Color.WHITE
                                        flashTextured[slot.maskIdx] = sub
                                    } else {
                                        flashColors[slot.maskIdx] = java.awt.Color.WHITE
                                    }
                                }
                            }

                            val flashSel =
                                    nextSel.copy(
                                            channelColors = flashColors,
                                            texturedColors = flashTextured
                                    )
                            mannequin.selection =
                                    mannequin.selection.copy(
                                            selections =
                                                    mannequin.selection.selections +
                                                            (layer.id to flashSel)
                                    )

                            val viewers = nearbyViewers(mannequin)
                            render(mannequin, viewers, forceInstant = true)

                            plugin.server.scheduler.runTaskLater(
                                    plugin,
                                    Runnable {
                                        if (mannequins[mannequin.id] != mannequin) return@Runnable
                                        mannequin.selection =
                                                mannequin.selection.copy(
                                                        selections =
                                                                mannequin.selection.selections +
                                                                        (layer.id to nextSel)
                                                )
                                        render(mannequin, viewers, forceInstant = true)
                                    },
                                    10L
                            )
                        }
                    }
                }
            }
            "channel" -> {
                if (layer != null) {
                    val option = freshOption(layer.id, mannequin)
                    if (option != null) {
                        val slots = resolveChannelSlots(layer, option, state, player)
                        if (slots.size > 1) {
                            val currentIdx = state.channelIndex.getOrDefault(layer.id, 0)
                            val nextIdx =
                                    if (backwards) (currentIdx - 1 + slots.size) % slots.size
                                    else (currentIdx + 1) % slots.size
                            state.channelIndex[layer.id] = nextIdx
                            updateStatus(mannequinId, "Channel: ${slots[nextIdx].label}")
                            refreshDynamicLabels(mannequinId)
                            refreshColorGrid(player, mannequin, state, hud)

                            // Flash the selected channel white for 10 ticks
                            val slot = slots[nextIdx]
                            val currentSel = mannequin.selection.selections[layer.id]
                            val flashColors: MutableMap<Int, java.awt.Color>
                            val flashTextured: MutableMap<Int, Map<Int, java.awt.Color>>
                            if (slot.subChannel != null) {
                                flashColors =
                                        (currentSel?.channelColors ?: emptyMap()).toMutableMap()
                                flashTextured =
                                        (currentSel?.texturedColors ?: emptyMap()).toMutableMap()
                                val sub =
                                        flashTextured
                                                .getOrPut(slot.maskIdx) { emptyMap() }
                                                .toMutableMap()
                                sub[slot.subChannel] = java.awt.Color.WHITE
                                flashTextured[slot.maskIdx] = sub
                            } else {
                                flashColors =
                                        (currentSel?.channelColors ?: emptyMap()).toMutableMap()
                                flashColors[slot.maskIdx] = java.awt.Color.WHITE
                                flashTextured =
                                        (currentSel?.texturedColors ?: emptyMap()).toMutableMap()
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
                                            selections =
                                                    mannequin.selection.selections +
                                                            (layer.id to flashSel)
                                    )

                            val viewers = nearbyViewers(mannequin)
                            render(mannequin, viewers, forceInstant = true)

                            // Restore original colors after 10 ticks (500ms)
                            val restoreSel = currentSel ?: LayerSelection(layer.id, option)
                            plugin.server.scheduler.runTaskLater(
                                    plugin,
                                    Runnable {
                                        if (mannequins[mannequin.id] != mannequin) return@Runnable
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
                    }
                }
            }
            "default_color" -> {
                val paletteId = configBtn.palette ?: "standard"
                val resolvedPalette = layerManager.palette(paletteId)
                val color = resolvedPalette?.colors?.getOrNull(0)
                if (layer != null) {
                    applyGridCellColor(
                            if (color != null) prettyName(color.name) else "Default",
                            color?.color,
                            mannequinId,
                            mannequin,
                            state,
                            player
                    )
                }
            }
            "save", "load", "apply" -> {
                executeConfigAction(configBtn.type, mannequinId, player, state)
            }
            else -> {
                if (buttonName.startsWith("color_")) {
                    // Logic for color swatch clicks - usually handled via specialized HUD buttons
                    // or event interception
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
        val colorMenuId = findMenuIdForType("color_grid") ?: return
        val gridVisible = hud.buttons.any { it.id.startsWith("${colorMenuId}_") }
        if (gridVisible) {
            val colorMenuBtn = buttonByName(colorMenuId) ?: return
            despawnMenu(colorMenuId, player, hud, quiet = true)
            spawnMenu(colorMenuBtn, player, mannequin, state, hud, quiet = true)
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

    private fun refreshDynamicLabels(mannequinId: UUID) {
        val state = controlState[mannequinId] ?: return
        val mode = state.mode
        val mannequin = mannequins[mannequinId] ?: return

        val layers = layerManager.definitionsInOrder()
        val layerCount = layers.size
        val layerDisabled = layerCount <= 1
        val currentLayer = layers.getOrNull(state.layerIndex % layers.size)

        for (player in plugin.server.onlinePlayers) {
            val hud = holoController.getHud(player.uniqueId) ?: continue
            if (hud.origin.world != mannequin.location.world ||
                            hud.origin.distanceSquared(mannequin.location) > 0.1
            )
                    continue

            val currentOption = currentLayer?.let { freshOption(it.id, mannequin) }
            val texs =
                    if (currentLayer != null && currentOption != null)
                            layerManager.resolveTextures(currentLayer, currentOption, player)
                    else emptyList<String>()
            val textureDisabled = texs.size <= 1

            for (btn in hudButtons) {
                val isLayerType = btn.type == "layer"
                val isTextureType = btn.type == "texture"

                val isButtonDisabled =
                        (isLayerType && layerDisabled) || (isTextureType && textureDisabled)
                val hideThis = isButtonDisabled && btn.disabledTextJson == null

                if (hideThis) {
                    if (hud.isButtonActive(btn.name)) {
                        hud.removeButtons(listOf(btn.name), instant = true)
                    }
                    continue
                }

                if (!hud.isButtonActive(btn.name)) {
                    hud.addButtons(
                            listOf(
                                    HoloButton(
                                            id = btn.name,
                                            textJson = btn.disabledTextJson
                                                            ?: TextUtility.mmToJson(btn.textMM),
                                            tx = btn.tx,
                                            ty = btn.ty,
                                            tz = btn.tz,
                                            lineWidth = btn.lineWidth,
                                            bgDefault = btn.bgDefault,
                                            bgHighlight = btn.bgHighlight,
                                            scaleX = btn.scaleX ?: 1f,
                                            scaleY = btn.scaleY ?: 1f,
                                            onClick = { p, backwards ->
                                                handleButtonClick(
                                                        btn.name,
                                                        mannequinId,
                                                        p,
                                                        backwards
                                                )
                                            }
                                    )
                            ),
                            instant = true
                    )
                }

                val isActive =
                        when (btn.type) {
                            "submenu" ->
                                    hud.isButtonActive("${btn.name}_") ||
                                            (btn.name == "config" && mode == ControlMode.LOAD)
                            "layer" -> {
                                if (btn.targetLayer != null) {
                                    btn.targetLayer == currentLayer?.id
                                } else false
                            }
                            else -> false
                        }

                val textJson =
                        if (btn.type == "status") {
                            formatStatusText(statusText[mannequinId])
                        } else if (isButtonDisabled && btn.disabledTextJson != null) {
                            btn.disabledTextJson
                        } else if (isActive && btn.activeTextJson != null) {
                            btn.activeTextJson
                        } else {
                            TextUtility.mmToJson(btn.textMM)
                        }

                hud.updateButtonText(btn.name, textJson)
                hud.updateButtonBg(btn.name, if (isActive) btn.bgHighlight else btn.bgDefault)
            }
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

        val opts = layerManager.optionsFor(layer.id, player)
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

        refreshDynamicLabels(mannequin.id)
        val hud = holoController.getHud(player.uniqueId)
        if (hud != null) refreshColorGrid(player, mannequin, state, hud)

        val prettyLayer = prettyName(layer.displayName)
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
        if (partEvent.isCancelled) return "$prettyLayer: $prettyPart"

        return "$prettyLayer: $prettyPart"
    }

    // ── Grid & Submenu Management (SneakyHolos compatible) ──────────────────────────

    /** Spawns a generic submenu based on config definitions. */
    private fun spawnMenu(
            menuBtn: HudButton,
            player: Player,
            mannequin: Mannequin,
            state: ControlState,
            hud: HoloHUD,
            quiet: Boolean = false
    ) {
        val layout = menuBtn.submenuLayout
        val grid =
                layout?.let {
                    HoloGridBuilder(
                            it.originX,
                            it.originY,
                            it.originZ,
                            0.1f, // default cell spacing, will be overridden by items
                            0.1f,
                            it.yaw,
                            it.pitch,
                            true
                    )
                }
                        ?: return

        // Note: we clear any previously generated menu buttons with this prefix
        despawnMenu(menuBtn.name, player, hud, quiet = true)

        menuBtn.items?.values?.forEach { itemConf ->
            if (itemConf.type == "color_grid") {
                injectColorGrid(itemConf, grid, menuBtn.name, player, mannequin, state)
            } else {
                val idPrefix = "${menuBtn.name}_${itemConf.name}"
                grid.addButtonManual(
                        id = idPrefix,
                        textMM = itemConf.textMM,
                        offsetX = itemConf.tx,
                        offsetY = itemConf.ty,
                        bgDefault =
                                if (itemConf.bgHeader != null && itemConf.bgHeader != 0)
                                        itemConf.bgHeader
                                else itemConf.bgDefault,
                        bgHighlight = HUD_BG_HIGHLIGHT,
                        lineWidth = itemConf.lineWidth ?: 200,
                        scaleX = itemConf.scaleX ?: 1.0f,
                        scaleY = itemConf.scaleY ?: 1.0f,
                        onClick = { p, backwards ->
                            handleButtonClick(itemConf.name, mannequin.id, p, backwards)
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

    private fun injectColorGrid(
            config: HudButton,
            grid: HoloGridBuilder,
            parentId: String,
            player: Player,
            mannequin: Mannequin,
            state: ControlState
    ) {
        val layers = layerManager.definitionsInOrder()
        val layer = layers.getOrNull(state.layerIndex % layers.size) ?: return
        val option = freshOption(layer.id, mannequin) ?: return
        val rawPaletteIds = layerManager.resolvePalettes(layer, option, player)
        val allPaletteIds = rawPaletteIds.filter { it != "default" }

        if (allPaletteIds.isEmpty()) {
            updateStatus(mannequin.id, "No palettes available")
            return
        }

        grid.cellSpacingX = config.cellSpacingX
        grid.cellSpacingY = config.cellSpacingY

        val selectedColor = currentSelectedGridColor(mannequin, state)
        val slots = resolveChannelSlots(layer, option, state, player)

        // Palette rows
        for ((row, palId) in allPaletteIds.withIndex()) {
            val palette = layerManager.palette(palId) ?: continue

            // Palette header
            grid.addButton(
                    id = "${parentId}_pal_header_$palId",
                    textMM = config.headerTextMM.replace("{message}", prettyName(palId)),
                    column = 0,
                    row = row,
                    bgDefault = config.bgHeader ?: HUD_BG_DEFAULT,
                    bgHighlight = config.bgHeader ?: HUD_BG_DEFAULT,
                    lineWidth = config.headerLineWidth,
                    scaleX = config.headerScale,
                    scaleY = config.headerScale
            )

            // Color swatches
            for ((col, namedColor) in palette.colors.withIndex()) {
                val baseRgb = namedColor.color
                val rgb =
                        if (slots.isNotEmpty()) baseRgb
                        else {
                            val gray =
                                    (baseRgb.red * 0.299 +
                                                    baseRgb.green * 0.587 +
                                                    baseRgb.blue * 0.114)
                                            .toInt()
                            java.awt.Color(gray, gray, gray)
                        }

                val bgNormal =
                        (0xFF shl 24) or
                                ((rgb.red and 0xFF) shl 16) or
                                ((rgb.green and 0xFF) shl 8) or
                                (rgb.blue and 0xFF)
                val isSelected = selectedColor != null && baseRgb == selectedColor
                val luma =
                        (0.299 * baseRgb.red + 0.587 * baseRgb.green + 0.114 * baseRgb.blue) / 255.0
                val xColor = if (luma > 0.6) "black" else "white"

                grid.addButton(
                        id = "${parentId}_color_${palId}_${namedColor.name}",
                        textMM = if (isSelected) "<$xColor><b>•</b></$xColor>" else " ",
                        column = (config.headerGap / config.cellSpacingX).toInt() + col,
                        row = row,
                        bgDefault = bgNormal,
                        bgHighlight = HUD_BG_HIGHLIGHT,
                        lineWidth = config.cellLineWidth,
                        scaleX = config.cellScaleX,
                        scaleY = config.cellScaleY,
                        interactionWidth = 0.1f,
                        interactionHeight = 0.15f,
                        onClick = { p, _ ->
                            applyGridCellColor(
                                    prettyName(namedColor.name),
                                    baseRgb,
                                    mannequin.id,
                                    mannequin,
                                    state,
                                    p
                            )
                        }
                )
            }
        }
    }

    private fun despawnMenu(menuId: String, player: Player, hud: HoloHUD, quiet: Boolean = false) {
        val toRemove = hud.buttons.filter { it.id.startsWith("${menuId}_") }.map { it.id }
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

    private fun applyGridCellColor(
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

        if (color == null) {
            val selection =
                    current?.copy(
                            channelColors = emptyMap(),
                            texturedColors = emptyMap(),
                            selectedTexture = null
                    )
                            ?: LayerSelection(layer.id, option)
            mannequin.selection =
                    mannequin.selection.copy(
                            selections = mannequin.selection.selections + (layer.id to selection)
                    )
            state.textureIndex[layer.id] = 0 // Reset texture index to default
        } else if (slot.subChannel != null) {
            val prevTextured = current?.texturedColors ?: emptyMap()
            val prevSub = prevTextured[slot.maskIdx] ?: emptyMap()
            val newSub = prevSub + (slot.subChannel to color)
            val newTextured = prevTextured + (slot.maskIdx to newSub)
            val selection =
                    current?.copy(texturedColors = newTextured)
                            ?: LayerSelection(layer.id, option, texturedColors = newTextured)
            mannequin.selection =
                    mannequin.selection.copy(
                            selections = mannequin.selection.selections + (layer.id to selection)
                    )
        } else {
            val prevColors = current?.channelColors ?: emptyMap()
            val newColors = prevColors + (slot.maskIdx to color)
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
            val isMatch = color != null && rgb == color

            val luma = (0.299 * rgb.red + 0.587 * rgb.green + 0.114 * rgb.blue) / 255.0
            val xColor = if (luma > 0.6) "black" else "white"
            hud.updateButtonText(
                    id,
                    TextUtility.mmToJson(if (isMatch) "<$xColor><b>•</b></$xColor>" else " ")
            )
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

    private fun executeConfigAction(
            action: String,
            manId: UUID,
            player: Player,
            state: ControlState
    ) {
        val mannequin = mannequins[manId] ?: return
        when (action) {
            "save" -> {
                if (!hasUnsavedChanges(mannequin)) {
                    val uid = mannequin.savedUid!!
                    player.sendMessage(
                            Component.text("Session unchanged. UID: ")
                                    .color(NamedTextColor.GREEN)
                                    .append(
                                            Component.text(uid)
                                                    .color(NamedTextColor.YELLOW)
                                                    .hoverEvent(
                                                            HoverEvent.showText(
                                                                    Component.text(
                                                                            "Click to copy UID"
                                                                    )
                                                            )
                                                    )
                                                    .clickEvent(ClickEvent.copyToClipboard(uid))
                                    )
                    )
                } else {
                    val session = saveMannequinState(mannequin, player)
                    val uid = session.uid
                    player.sendMessage(
                            Component.text("Session saved: ")
                                    .color(NamedTextColor.GREEN)
                                    .append(
                                            Component.text(uid)
                                                    .color(NamedTextColor.YELLOW)
                                                    .hoverEvent(
                                                            HoverEvent.showText(
                                                                    Component.text(
                                                                            "Click to copy UID"
                                                                    )
                                                            )
                                                    )
                                                    .clickEvent(ClickEvent.copyToClipboard(uid))
                                    )
                    )
                }
            }
            "load" -> {
                state.mode = ControlMode.LOAD
                updateStatus(manId, "Type UID in chat")
                player.sendMessage(
                        Component.text("Enter session UID in chat to load.")
                                .color(net.kyori.adventure.text.format.NamedTextColor.YELLOW)
                )
            }
            "apply" -> {
                handleApplyAction(manId, player)
            }
        }
        // despawnConfigGrid(player, hud) // Removed: keep menu open
        refreshDynamicLabels(manId)
    }

    /** Toggles the outer rendering layer (overlay) for a given mannequin. */
    fun toggleOverlay(mannequinId: UUID, requester: Player) {
        val mannequin = mannequins[mannequinId] ?: return
        mannequin.showOverlay = !mannequin.showOverlay
        val state = if (mannequin.showOverlay) "ON" else "OFF"
        requester.sendMessage(
                TextUtility.convertToComponent(
                        "&aMannequin $mannequinId outer layer turned &e$state&a."
                )
        )
        updateStatus(mannequinId, "Outer Layer: $state")
        renderFull(mannequin, nearbyViewers(mannequin))
        refreshDynamicLabels(mannequinId)
    }

    private fun handleApplyAction(manId: UUID, player: Player) {
        val mannequin = mannequins[manId] ?: return
        val now = System.currentTimeMillis()
        val expires = applyCooldown[player.uniqueId] ?: 0L
        if (now < expires) {
            return // ignore click if on cooldown
        }
        applyCooldown[player.uniqueId] = now + 5000L

        val applyHides = plugin.config.getBoolean("rendering.apply-hides-mannequin", true)
        if (applyHides) {
            mannequin.isHidden = true
            val viewers = nearbyViewers(mannequin)
            viewers.forEach { v ->
                val otherHud = holoController.getHud(v.uniqueId)
                if (otherHud?.mannequinId == manId) {
                    holoController.closeHud(v.uniqueId)
                }
            }

            val blankFrame = PixelFrame.blank()
            val diff = mannequin.lastFrame.diff(blankFrame)
            mannequin.lastFrame = blankFrame

            val projected =
                    PixelProjector.project(
                            origin = mannequin.location,
                            changes = diff,
                            pixelScale = 1.0 / 16.0,
                            scaleMultiplier = handler.pixelScaleMultiplier(),
                            slimArms = isSlimModel(mannequin),
                            showOverlay = mannequin.showOverlay,
                            tPose = poseState[manId] == true
                    )

            val settings = readRenderSettings(isFirstSeen = true).copy(reversed = true)
            viewers.forEach { viewer ->
                animationManager.deliver(viewer, manId, projected, settings)
            }
        }

        if (mannequin.selection.selections.isEmpty()) {
            player.sendMessage(TextUtility.convertToComponent("&cNo layers to apply."))
            return
        }

        player.sendMessage(TextUtility.convertToComponent("&eApplying skin..."))
        finalizeAndApply(player, mannequin, player)

        if (!applyHides) {
            updateStatus(manId, "Applied")
            refreshDynamicLabels(manId)
        }
    }

    /**
     * Finalizes the current mannequin session and applies it to a target player.
     * @param requester The player who triggered the process (for feedback messages)
     * @param mannequin The mannequin source
     * @param contextPlayer The player whose skin/character context should be used
     */
    fun finalizeAndApply(
            requester: Player,
            mannequin: Mannequin,
            contextPlayer: Player,
            sessionOverride: SessionData? = null
    ) {
        var actualSessionOverride = sessionOverride

        if (sessionOverride == null) {
            val currentFingerprint = sessionManager.fingerprint(mannequin)
            if (lastSavedFingerprint[mannequin.id] != currentFingerprint) {
                val session = saveMannequinState(mannequin, requester)
                requester.sendMessage(
                        TextUtility.convertToComponent(
                                "&7Unsaved changes detected. Auto-saved to session &e${session.uid}&7."
                        )
                )
                actualSessionOverride = session
            } else {
                val savedUid = mannequin.savedUid
                if (savedUid != null) {
                    actualSessionOverride = sessionManager.load(savedUid)
                }
            }
        }

        sessionManager
                .finalizeSession(
                        requester,
                        mannequin,
                        sessionOverride = actualSessionOverride,
                        contextPlayer = contextPlayer
                )
                .thenAccept { result ->
                    val url = ConfigManager.instance.getImageUrl(result.file.name)

                    if (characterManagerBridge.active) {
                        val charContext = characterManagerBridge.currentCharacter(contextPlayer)
                        if (charContext != null) {
                            characterManagerBridge.updateSkin(
                                    contextPlayer,
                                    charContext.characterUuid,
                                    url,
                                    result.slim
                            )

                            requester.sendMessage(
                                    TextUtility.convertToComponent(
                                            "&aSkin applied to character &d${charContext.characterName}&a!"
                                    )
                            )
                            return@thenAccept
                        }
                    }

                    // Fallback to classic link strategy
                    val message =
                            Component.text("Skin finalized! ")
                                    .color(NamedTextColor.GREEN)
                                    .append(
                                            Component.text("[Click to view]")
                                                    .color(NamedTextColor.YELLOW)
                                                    .decorate(TextDecoration.UNDERLINED)
                                                    .clickEvent(ClickEvent.openUrl(url))
                                                    .hoverEvent(
                                                            HoverEvent.showText(Component.text(url))
                                                    )
                                    )

                    requester.sendMessage(message)
                }
                .exceptionally { ex ->
                    requester.sendMessage(
                            TextUtility.convertToComponent("&cFinalization failed: ${ex.message}")
                    )
                    null
                }
    }

    fun saveMannequinState(mannequin: Mannequin, player: Player): SessionData {
        val charContext = characterManagerBridge.currentCharacter(player)
        val session =
                sessionManager.save(
                        mannequin,
                        player,
                        characterUuid = charContext?.characterUuid,
                        characterName = charContext?.characterName
                )
        val uid = session.uid
        val fingerprint = sessionManager.fingerprint(mannequin)
        lastSavedFingerprint[mannequin.id] = fingerprint
        mannequin.savedUid = uid
        persist()
        plugin.server.pluginManager.callEvent(
                MannequinSessionSaveEvent(mannequin.id, mannequin.location, player, uid)
        )
        updateStatus(mannequin.id, "Saved to session '$uid'")
        return session
    }

    /**
     * Returns true if the mannequin has modifications since its last save, or has never been saved.
     */
    fun hasUnsavedChanges(mannequin: Mannequin): Boolean {
        if (mannequin.savedUid == null) return true
        val currentFingerprint = sessionManager.fingerprint(mannequin)
        return lastSavedFingerprint[mannequin.id] != currentFingerprint
    }

    fun handleInteract(mannequinId: UUID, player: Player, backwards: Boolean) {
        val mannequin = mannequins[mannequinId] ?: return

        if (mannequin.isHidden) {
            mannequin.isHidden = false
            renderFull(mannequin, nearbyViewers(mannequin), isFirstSeen = true)
            spawnPlayerHud(player, mannequin)
            return
        }

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
                    refreshDynamicLabels(mannequinId)
                    if (chosen != null) {
                        updateStatus(mannequinId, "${prettyName(chosen)}")
                    }
                }
            }
            return
        }

        spawnPlayerHud(player, mannequin)
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

    private data class GridButtonConfig(
            val text: String?,
            val activeText: String?,
            val disabledText: String?,
            val column: Int,
            val row: Int,
            val lineWidth: Int?,
            val scaleX: Float?,
            val scaleY: Float?
    )

    private data class GridConfig(
            val openByDefault: Boolean,
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
            val defaultBtn: GridButtonConfig?,
            val textureBtn: GridButtonConfig?,
            val channelBtn: GridButtonConfig?
    )

    private fun loadGridConfig(path: String = "hud-buttons.color-grid"): GridConfig {
        val sec = plugin.config.getConfigurationSection(path)
        val defaultHeaderText =
                if (path.contains("config")) "{message}"
                else "<white><font:minecraft:uniform>{message}"

        fun loadBtn(name: String, defCol: Int): GridButtonConfig? {
            val bsec = sec?.getConfigurationSection(name)
            if (bsec == null && sec?.contains(name) == false) return null
            return GridButtonConfig(
                    text = bsec?.getString("text"),
                    activeText = bsec?.getString("active-text"),
                    disabledText = bsec?.getString("disabled-text"),
                    column = bsec?.getInt("column", defCol) ?: defCol,
                    row = bsec?.getInt("row", -1) ?: -1,
                    lineWidth =
                            if (bsec?.contains("line-width") == true) bsec.getInt("line-width")
                            else null,
                    scaleX =
                            if (bsec?.contains("scale-x") == true)
                                    bsec.getDouble("scale-x").toFloat()
                            else null,
                    scaleY =
                            if (bsec?.contains("scale-y") == true)
                                    bsec.getDouble("scale-y").toFloat()
                            else null
            )
        }

        return GridConfig(
                openByDefault = sec?.getBoolean("open-by-default", false) ?: false,
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
                defaultBtn = loadBtn("default", 0),
                textureBtn = loadBtn("texture", 8),
                channelBtn = loadBtn("channel", 12)
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
