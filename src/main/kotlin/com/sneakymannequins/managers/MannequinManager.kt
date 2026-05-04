package com.sneakymannequins.managers

import com.sneakymannequins.SneakyMannequins
import com.sneakymannequins.events.*
import com.sneakymannequins.integrations.CharacterManagerBridge
import com.sneakymannequins.model.ColorTabMode
import com.sneakymannequins.model.ChannelSlot
import com.sneakymannequins.model.LayerDefinition
import com.sneakymannequins.model.LayerOption
import com.sneakymannequins.model.LayerSelection
import com.sneakymannequins.model.Mannequin
import com.sneakymannequins.model.MenuLayout
import com.sneakymannequins.model.HudButton
import com.sneakymannequins.model.PixelChange
import com.sneakymannequins.model.PixelFrame
import com.sneakymannequins.model.SessionData
import com.sneakymannequins.model.SkinSelection
import com.sneakymannequins.model.TextDisplayBrightnessSetting
import com.sneakymannequins.model.TextureDefinition
import com.sneakymannequins.model.buildChannelSlots
import com.sneakymannequins.model.hexToColor
import com.sneakymannequins.nms.VolatileHandler
import com.sneakymannequins.render.AnimationManager
import com.sneakymannequins.render.TextDisplayLightSupplier
import com.sneakymannequins.render.PixelProjector
import com.sneakymannequins.render.RenderMode
import com.sneakymannequins.render.RenderSettings
import com.sneakymannequins.util.SkinComposer
import com.sneakymannequins.util.SkinUv
import com.sneakymouse.sneakyholos.*
import com.sneakymouse.sneakyholos.util.HoloGridBuilder
import com.sneakymouse.sneakyholos.util.TextUtility
import java.awt.image.BufferedImage
import java.net.URI
import java.util.UUID
import java.util.logging.Level
import kotlin.math.roundToInt
import kotlin.math.sqrt
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.profile.PlayerTextures.SkinModel

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



/** Canonical per-button visual state (shared across all viewers). */
private data class ButtonVisual(var textJson: String, var bgColor: Int)

// ── Manager ─────────────────────────────────────────────────────────────────────

class MannequinManager(
        private val plugin: SneakyMannequins,
        private val layerManager: LayerManager,
        private val styleManager: StyleManager,
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
    /** (viewerId, mannequinId) -> per-layer page index for paginated color_grid palettes (`max-rows`). */
    private val palettePageByViewer = mutableMapOf<Pair<UUID, UUID>, MutableMap<String, Int>>()
    /** mannequin -> layerId -> partId(optionId) -> last selection used for that part */
    private val partSelectionMemory =
            mutableMapOf<UUID, MutableMap<String, MutableMap<String, LayerSelection>>>()
    private val interactionDebounce = mutableMapOf<Pair<UUID, String>, Long>()
    /** playerId → expiry timestamp for random confirmation */
    private val overrideFrames = mutableMapOf<UUID, PixelFrame>()
    private val randomConfirm = mutableMapOf<UUID, Long>()

    /** mannequinId → expiry timestamp for random cooldown */
    private val randomCooldown = mutableMapOf<UUID, Long>()
    /** playerId -> expiry timestamp for apply button cooldown */
    private val applyCooldown = mutableMapOf<UUID, Long>()

    /** Manages INSTANT / BUILD pixel delivery to viewers. */
    private val animationManager = AnimationManager(plugin, handler)

    fun getMannequin(mannequinId: UUID): Mannequin? = mannequins[mannequinId]

    private fun palettePages(viewerId: UUID, mannequinId: UUID): MutableMap<String, Int> {
        return palettePageByViewer.getOrPut(viewerId to mannequinId) { mutableMapOf() }
    }

    fun currentPartId(mannequinId: UUID, layerId: String): String? {
        val mannequin = mannequins[mannequinId] ?: return null
        return mannequin.selection.selections[layerId]?.option?.id
    }

    /**
     * After mask channel rewrites (merge/delete), the channel slots list can shrink/reorder.
     * Reset UI indices so the HUD doesn't point at a non-existent old slot.
     */
    fun resetUiIndices(mannequinId: UUID, layerId: String) {
        val state = controlState[mannequinId] ?: return
        state.channelIndex[layerId] = 0
        state.colorIndex[layerId] = 0
        // textureIndex is left as-is; it is independent of mask channel count.
    }

    fun findPartById(layerId: String, partId: String): LayerOption? {
        return layerManager.findPartById(layerId, partId)
    }

    // ── Config-driven radii ─────────────────────────────────────────────────────


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
    }


    init {
        // Hud buttons now loaded per-style via StyleManager
    }

    /** Look up a button config by name. Searches recursively through submenus. */
    private fun buttonByName(name: String, list: List<HudButton>): HudButton? {
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
            list: List<HudButton>,
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

    private fun findColorGridConfigInStyle(hudButtons: List<HudButton>): HudButton? {
        val menuId = findMenuIdForType("color_grid", hudButtons) ?: return null
        val menu = buttonByName(menuId, hudButtons) ?: return null
        return menu.items?.values?.firstOrNull { it.type == "color_grid" }
    }

    private fun colorGridPaletteIds(
            layer: LayerDefinition,
            option: LayerOption,
            player: Player
    ): List<String> = layerManager.resolvePalettes(layer, option, player).filter { it != "default" }

    private fun colorGridPageCountForLayer(
            layer: LayerDefinition?,
            option: LayerOption?,
            player: Player,
            maxRows: Int
    ): Int {
        if (layer == null || option == null) return 1
        val all = colorGridPaletteIds(layer, option, player)
        if (all.isEmpty()) return 1
        val mr = maxOf(1, maxRows)
        return (all.size + mr - 1) / mr
    }

    private fun advancePalettePageOnColorSubmenuClose(
            mannequin: Mannequin,
            state: ControlState,
            player: Player,
            submenuBtn: HudButton
    ) {
        if (submenuBtn.items?.values?.any { it.type == "color_grid" } != true) return
        val style = styleManager.getStyle(mannequin.styleId) ?: return
        val gridConfig = findColorGridConfigInStyle(style.hudButtons) ?: return
        val maxRows = gridConfig.maxRows.coerceAtLeast(1)
        val layers = getAvailableLayers(mannequin)
        val layer = layers.getOrNull(state.layerIndex % layers.size) ?: return
        val option = freshOption(layer.id, mannequin) ?: return
        val pageCount = colorGridPageCountForLayer(layer, option, player, maxRows)
        if (pageCount <= 1) return
        val pages = palettePages(player.uniqueId, mannequin.id)
        val cur = pages.getOrDefault(layer.id, 0)
        pages[layer.id] = (cur + 1) % pageCount
    }

    /**
     * Resolve the current (fresh) [LayerOption] for a layer on a mannequin. The mannequin's
     * selection may hold a stale reference after a layer reload / remask, so we always look up the
     * option by ID from the layer manager and fall back to the stale copy only if it was removed.
     */
    private fun freshOption(layerId: String, mannequin: Mannequin): LayerOption? {
        val selOption = mannequin.selection.selections[layerId]?.option
        if (selOption != null) {
            return layerManager.findPartById(layerId, selOption.id) ?: selOption
        }
        return layerManager.optionsFor(layerId).firstOrNull()
    }


    // ── Lifecycle ───────────────────────────────────────────────────────────────

    fun loadFromDisk() {
        partSelectionMemory.clear()
        val loaded = persistence.load()
        loaded.forEach { data ->
            val selection = bootstrapSelection(data.styleId)
            val mannequin =
                    Mannequin(
                            id = data.id,
                            location = data.location.clone(),
                            selection = selection,
                            slimModel = data.slim,
                            savedUid = data.savedUid,
                            styleId = data.styleId
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

    fun create(location: Location, styleId: String): Mannequin {
        val selection = bootstrapSelection(styleId)
        val mannequin = Mannequin(location = location.clone(), selection = selection, styleId = styleId)
        mannequins[mannequin.id] = mannequin
        controlState[mannequin.id] = ControlState()
        randomize(mannequin, randomizeModel = true)
        initButtonVisuals(mannequin.id)
        registerTrigger(mannequin)
        persist()

        // First appearance only within view-radius (matches checkFirstSeen / preset docs).
        val viewers = viewRadiusViewers(mannequin)
        if (viewers.isNotEmpty()) {
            renderFull(mannequin, viewers, isFirstSeen = true)
            viewers.forEach { viewer ->
                sentTo.getOrPut(viewer.uniqueId) { mutableSetOf() }.add(mannequin.id)
            }
        }
        return mannequin
    }

    private fun getAvailableLayers(mannequin: Mannequin): List<LayerDefinition> {
        val style = styleManager.getStyle(mannequin.styleId) ?: return layerManager.definitionsInOrder()
        val allDefs = layerManager.definitionsInOrder()
        return allDefs.filter { it.id in style.availableLayers }
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
        palettePageByViewer.keys.removeIf { it.second == mannequinId }
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
     *
     * Sets the mannequin's saved UID and in-memory save fingerprint to this session so an immediate
     * save (HUD, chat load, Copy Me, or `/mannequin debug save`) correctly reports "Session unchanged".
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

        // Treat loaded session as the new save baseline so "save" right after load reports unchanged + UID.
        mannequin.savedUid = session.uid
        lastSavedFingerprint[mannequinId] = sessionManager.fingerprint(mannequin)
        persist()

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

        // StyleManager handles reloading style-based buttons
        loadFromDisk()
        animationManager.start()
        startTickLoop()
        // HoloController handles its own tick task

        plugin.server.onlinePlayers.forEach { viewer -> renderVisibleTo(viewer) }
    }

    // ── Rendering ───────────────────────────────────────────────────────────────

    fun renderVisibleTo(viewer: Player) {
        val seen = sentTo.getOrPut(viewer.uniqueId) { mutableSetOf() }
        for (man in mannequins.values) {
            val style = styleManager.getStyle(man.styleId) ?: continue
            val viewRadiusSq = style.rendering.viewRadius * style.rendering.viewRadius
            val updateRadiusSq = style.rendering.updateRadius * style.rendering.updateRadius

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
        val seen = sentTo.getOrPut(viewer.uniqueId) { mutableSetOf() }
        for (man in mannequins.values) {
            val style = styleManager.getStyle(man.styleId) ?: continue
            val viewRadiusSq = style.rendering.viewRadius * style.rendering.viewRadius
            val updateRadiusSq = style.rendering.updateRadius * style.rendering.updateRadius
            
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
        layerManager.findPartById(layerId, optionId)
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

    private val saturationInfluenceResolver: (String, LayerOption) -> Float = { layerId, option ->
        val layerDef = layerManager.definitionsInOrder().find { it.id == layerId }
        if (layerDef != null) layerManager.resolveSaturationInfluence(layerDef, option) else 1f
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
            forceArmPixels: Boolean = false,
            forceAll: Boolean = false,
            fullColorMaskInfluence: Boolean = false
    ): Int {
        val definitions = layerManager.definitionsInOrder()
        val composed =
                SkinComposer.compose(
                        definitions,
                        mannequin.selection,
                        useSlimModel = isSlimModel(mannequin),
                        optionResolver = optionResolver,
                        textureResolver = textureResolver(mannequin),
                        brightnessInfluenceResolver = brightnessInfluenceResolver,
                        saturationInfluenceResolver = saturationInfluenceResolver,
                        blinkEnabled =
                        	plugin.config.getBoolean(
                        		"integrations.entity-texture-features.blink-enabled",
                        		false
                        	),
                        jacketEnabled =
			plugin.config.getBoolean(
				"integrations.entity-texture-features.jacket-enabled", 
				false
			),
                        showOverlay = mannequin.showOverlay, defaultJacketStyle =
                        	plugin.config.getInt(
                        		"integrations.entity-texture-features.jacket-dress-style",
                        		5
                        	),
                        fullColorMaskInfluence = fullColorMaskInfluence
                )
        val nextFrame = PixelFrame.fromImage(composed)
        val diff =
                if (forceAll) {
                    PixelFrame.blank().diff(nextFrame)
                } else if (forceArmPixels) {
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
        val style = styleManager.getStyle(mannequin.styleId)
        val settings =
                if (forceInstant) RenderSettings(RenderMode.INSTANT)
                else style?.rendering?.update ?: RenderSettings()
        val light = textDisplayLightProvider(mannequin)
        viewers.forEach { viewer ->
            animationManager.deliver(viewer, mannequin.id, projected, settings, light)
        }
        return diff.size
    }

    /**
     * Sends a temporary frame to [viewers] without updating the mannequin's canonical state (
     * [Mannequin.lastFrame]). Useful for interactive previews (like remask mode).
     */
    fun renderOverride(
            mannequin: Mannequin,
            image: BufferedImage,
            viewers: Collection<Player>,
            force: Boolean = false
    ) {
        val nextFrame = PixelFrame.fromImage(image)
        val lastOverride = overrideFrames[mannequin.id]
        
        // If we have a last override, diff against it to find what changed since then.
        // Otherwise diff against the canonical frame.
        val diff = (lastOverride ?: mannequin.lastFrame).diff(nextFrame)
        
        if (diff.isEmpty()) return
        overrideFrames[mannequin.id] = nextFrame

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
        val settings = RenderSettings(RenderMode.INSTANT)
        val light = textDisplayLightProvider(mannequin)
        viewers.forEach { viewer ->
            animationManager.deliver(viewer, mannequin.id, projected, settings, light)
        }
    }

    /** Clears any active override for a mannequin and reverts to canonical state. */
    fun clearOverride(mannequin: Mannequin) {
        val lastOverride = overrideFrames.remove(mannequin.id) ?: return
        val diff = lastOverride.diff(mannequin.lastFrame)
        if (diff.isEmpty()) return

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
        val settings = RenderSettings(RenderMode.INSTANT)
        val light = textDisplayLightProvider(mannequin)
        nearbyViewers(mannequin).forEach { viewer ->
            animationManager.deliver(viewer, mannequin.id, projected, settings, light)
        }
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
                        brightnessInfluenceResolver = brightnessInfluenceResolver,
                        saturationInfluenceResolver = saturationInfluenceResolver,
                        blinkEnabled =
                        	plugin.config.getBoolean(
                        		"integrations.entity-texture-features.blink-enabled",
                        		false
                        	),
                        jacketEnabled =
			plugin.config.getBoolean(
				"integrations.entity-texture-features.jacket-enabled", 
				false
			),
                        showOverlay = mannequin.showOverlay, defaultJacketStyle =
                        	plugin.config.getInt(
                        		"integrations.entity-texture-features.jacket-dress-style",
                        		5
                        	)
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
        val style = styleManager.getStyle(mannequin.styleId)
        val settings =
                if (forceInstant) RenderSettings(RenderMode.INSTANT)
                else if (isFirstSeen) style?.rendering?.firstSeen ?: RenderSettings()
                else style?.rendering?.update ?: RenderSettings()
        val light = textDisplayLightProvider(mannequin)
        viewers.forEach { viewer ->
            animationManager.deliver(viewer, mannequin.id, projected, settings, light)
        }
    }

    private fun isSlimModel(mannequin: Mannequin): Boolean = mannequin.slimModel

    /** Block/sky light (0–15) for mannequin pixel TextDisplays and HUD text; matches style preset. */
    private fun textDisplayLightProvider(mannequin: Mannequin): TextDisplayLightSupplier {
        val rendering = styleManager.getStyle(mannequin.styleId)?.rendering
        val bri = rendering?.textDisplayBrightness ?: TextDisplayBrightnessSetting.Auto
        val autoMult = rendering?.textDisplayBrightnessAutoMultiplier?.coerceIn(0f, 4f) ?: 1f
        return when (bri) {
            is TextDisplayBrightnessSetting.Fixed -> {
                val block = bri.block
                val sky = bri.sky
                { block to sky }
            }
            TextDisplayBrightnessSetting.Auto -> {
                {
                    fun scaleLight(level: Int): Int =
                            (level * autoMult).roundToInt().coerceIn(0, 15)

                    val world = mannequin.location.world
                    val block = mannequin.location.block
                    if (world == null || !block.chunk.isLoaded) {
                        scaleLight(15) to scaleLight(15)
                    } else {
                        scaleLight(block.lightFromBlocks.toInt()) to
                                scaleLight(block.lightFromSky.toInt())
                    }
                }
            }
        }
    }

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

    private fun initButtonVisuals(mannequinId: UUID) {
        val mannequin = mannequins[mannequinId] ?: return
        val style = styleManager.getStyle(mannequin.styleId) ?: return
        val visuals = mutableMapOf<String, ButtonVisual>()
        for (btn in style.hudButtons) {
            val json =
                    if (btn.name == "status") {
                        formatStatusText(statusText[mannequinId], mannequinId)
                    } else {
                        btn.textJson
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
    private fun formatStatusText(msg: String?, mannequinId: UUID): String {
        val man = mannequins[mannequinId] ?: return TextUtility.mmToJson(msg ?: "Controls")
        val style = styleManager.getStyle(man.styleId) ?: return TextUtility.mmToJson(msg ?: "Controls")
        val btn = style.hudButtons.find { it.type == "status" }
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
        val style = styleManager.getStyle(mannequin.styleId) ?: return mutableListOf()
        return style.hudButtons
                .map { btn ->
                    val isStatus = btn.type == "status"
                    val initialJson =
                            if (isStatus) formatStatusText(statusText[mannequin.id], mannequin.id)
                            else btn.textJson

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
        val style = styleManager.getStyle(mannequin.styleId) ?: return 
        val buttons = buildHoloButtons(mannequin)
        val frame = style.hudFrame
        val frameEnabled = frame.enabled
        val frameItem = if (frameEnabled) frame.item else null
        val frameCmd = frame.customModelData
        val frameCtx = frame.displayContext
        val frameTx = frame.tx
        val frameTy = frame.ty
        val frameTz = frame.tz
        val frameSx = frame.sx
        val frameSy = frame.sy
        val frameSz = frame.sz

        val hud =
                HoloHUD(
                        viewer = player,
                        origin = mannequin.location,
                        mannequinId = mannequin.id,
                        handler = holoController.handler,
                        textDisplayLight = textDisplayLightProvider(mannequin),
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
            style.hudButtons.forEach { btn ->
                if (btn.type == "submenu" && btn.openByDefault) {
                    spawnMenu(btn, player, mannequin, state, hud, quiet = false)
                }
            }
        }

        // Ensure placeholder-driven button text (e.g. {selectedChannel}) is applied immediately
        // after HUD creation.
        refreshDynamicLabels(mannequin.id)
    }

    internal fun handleButtonClick(
            buttonName: String,
            mannequinId: UUID,
            player: Player,
            backwards: Boolean,
            uncraig: Boolean = false
    ) {
        val mannequin = mannequins[mannequinId] ?: return
        val state = controlState[mannequinId] ?: return
        val layers = getAvailableLayers(mannequin)
        val layer = layers.getOrNull(state.layerIndex % layers.size)
        val hud = holoController.getHud(player.uniqueId) ?: return
        val style = styleManager.getStyle(mannequin.styleId) ?: return
        val configBtn = buttonByName(buttonName, style.hudButtons) ?: return

        val event = MannequinClickEvent(mannequinId, mannequin.location, player, buttonName, backwards = backwards)
        if (configBtn.type != "color" && configBtn.type != "config") {
            plugin.server.pluginManager.callEvent(event)
        }
        if (event.isCancelled) return

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
                val expires = randomConfirm[player.uniqueId] ?: 0L

                // Extend confirmation window on every click
                randomConfirm[player.uniqueId] = now + 5000L

                if (now < expires) {
                    randomize(mannequin, randomizeModel = true)
                    updateStatus(mannequinId, "Randomized")
                    renderFull(mannequin, nearbyViewers(mannequin), forceInstant = true)
                    refreshDynamicLabels(mannequinId)
                    val mStyle = styleManager.getStyle(mannequin.styleId) ?: return
                    val colorMenuId = findMenuIdForType("color_grid", mStyle.hudButtons)
                    if (colorMenuId != null) {
                        val colorMenuBtn = buttonByName(colorMenuId, mStyle.hudButtons)
                        if (colorMenuBtn != null &&
                                        hud.buttons.any { it.id.startsWith("${colorMenuId}_") }
                        ) {
                            spawnMenu(colorMenuBtn, player, mannequin, state, hud, quiet = true)
                        }
                    }
                } else {
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
                val styleLayers = getAvailableLayers(mannequin)
                val prevLayerIndex = state.layerIndex
                if (configBtn.targetLayer != null) {
                    val targetIdx = styleLayers.indexOfFirst { it.id == configBtn.targetLayer }
                    if (targetIdx != -1) {
                        val currentLayerId =
                                styleLayers.getOrNull(state.layerIndex % styleLayers.size)?.id
                        if (currentLayerId == configBtn.targetLayer) {
                            // If already on the target layer, behave like clicking the mannequin:
                            // cycle parts forward/backward.
                            val layerDef = styleLayers[targetIdx]
                            val chosen = cyclePart(layerDef, mannequin, state, player, backwards)
                            render(mannequin, nearbyViewers(mannequin))
                            refreshDynamicLabels(mannequinId)
                            if (chosen != null) {
                                updateStatus(mannequinId, "${prettyName(chosen)}")
                            }
                        } else {
                            state.layerIndex = targetIdx
                            updateStatus(mannequinId, "Layer: ${prettyName(configBtn.targetLayer)}")
                        }
                    }
                } else {
                    val cyclingLayers =
                            if (configBtn.allowedLayers != null) {
                                styleLayers.filter { it.id in configBtn.allowedLayers }
                            } else {
                                styleLayers
                            }
                    if (cyclingLayers.isNotEmpty()) {
                        val currentLayerId =
                                styleLayers.getOrNull(state.layerIndex % styleLayers.size)?.id
                        val currentCyclingIdxRaw =
                                cyclingLayers.indexOfFirst { it.id == currentLayerId }
                        // If the current layer is not in the allowed subset, default to the first
                        // allowed layer instead of treating it as -1 (which makes forward clicks
                        // always land on index 0).
                        val currentCyclingIdx =
                                if (currentCyclingIdxRaw >= 0) currentCyclingIdxRaw else 0

                        val nextCyclingIdx =
                                if (backwards)
                                        (currentCyclingIdx - 1 + cyclingLayers.size) %
                                                cyclingLayers.size
                                else (currentCyclingIdx + 1) % cyclingLayers.size

                        val nextLayer = cyclingLayers[nextCyclingIdx]
                        // nextLayer comes from styleLayers filtering, so indexOf should succeed; fall
                        // back to 0 rather than leaving layerIndex=-1 (breaks left-click cycling).
                        state.layerIndex = styleLayers.indexOf(nextLayer).takeIf { it >= 0 } ?: 0
                        updateStatus(mannequinId, "Layer: ${prettyName(nextLayer.id)}")

                        if (plugin.config.getBoolean("plugin.debug", false)) {
                            plugin.logger.info(
                                    "[DEBUG] layerClick: btn=${configBtn.name} backwards=$backwards layerIndex=${state.layerIndex} currentLayerId=$currentLayerId currentCyclingIdxRaw=$currentCyclingIdxRaw nextCyclingIdx=$nextCyclingIdx cyclingLayers=${cyclingLayers.map { it.id }}"
                            )
                        }
                    }
                }
                val layerChanged = state.layerIndex != prevLayerIndex
                if (layerChanged) {
                    styleLayers
                            .getOrNull(state.layerIndex % styleLayers.size)
                            ?.id
                            ?.let { palettePages(player.uniqueId, mannequinId)[it] = 0 }
                }
                refreshDynamicLabels(mannequinId)

                // Only respawn the color grid + flash highlight when the layer actually changes.
                if (layerChanged) {
                    val mStyle = styleManager.getStyle(mannequin.styleId) ?: return
                    val colorMenuId = findMenuIdForType("color_grid", mStyle.hudButtons)
                    if (colorMenuId != null) {
                        val colorMenuBtn = buttonByName(colorMenuId, mStyle.hudButtons)
                        if (colorMenuBtn != null &&
                                        hud.buttons.any { it.id.startsWith("${colorMenuId}_") }
                        ) {
                            spawnMenu(colorMenuBtn, player, mannequin, state, hud, quiet = true)
                        }
                    }

                    // Flash the newly selected layer white for 10 ticks
                    val nextLayerDef = styleLayers.getOrNull(state.layerIndex % styleLayers.size)
                    if (nextLayerDef != null) {
                        val option = freshOption(nextLayerDef.id, mannequin)
                        if (option != null) {
                            val slots = resolveChannelSlots(nextLayerDef, option, state, player)
                            val currentSel = mannequin.selection.selections[nextLayerDef.id]

                            val flashColors =
                                    (currentSel?.channelColors ?: emptyMap()).toMutableMap()
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
                            render(
                                    mannequin,
                                    viewers,
                                    forceInstant = true,
                                    fullColorMaskInfluence = true
                            )

                            plugin.server.scheduler.runTaskLater(
                                    plugin,
                                    Runnable {
                                        if (mannequins[mannequinId] != mannequin) return@Runnable
                                        // Restore exactly what was there before the flash. If there
                                        // was no selection entry yet, remove ours so the layer falls
                                        // back to its normal defaults (instead of staying "all
                                        // white").
                                        mannequin.selection =
                                                if (currentSel != null) {
                                                    mannequin.selection.copy(
                                                            selections =
                                                                    mannequin.selection.selections +
                                                                            (nextLayerDef.id to currentSel)
                                                    )
                                                } else {
                                                    mannequin.selection.copy(
                                                            selections =
                                                                    mannequin.selection.selections -
                                                                            nextLayerDef.id
                                                    )
                                                }
                                        render(
                                                mannequin,
                                                nearbyViewers(mannequin),
                                                forceInstant = true
                                        )
                                    },
                                    10L
                            )
                        }
                    }
                }
            }
            "submenu" -> {
                val isVisible = hud.buttons.any { it.id.startsWith("${buttonName}_") }
                if (isVisible) {
                    advancePalettePageOnColorSubmenuClose(mannequin, state, player, configBtn)
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
                            render(
                                    mannequin,
                                    viewers,
                                    forceInstant = true,
                                    fullColorMaskInfluence = true
                            )

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
                            render(
                                    mannequin,
                                    viewers,
                                    forceInstant = true,
                                    fullColorMaskInfluence = true
                            )

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
            "colortab" -> {
                val gridConfig = findColorGridConfigInStyle(style.hudButtons) ?: return
                val maxRows = gridConfig.maxRows.coerceAtLeast(1)
                if (layer == null) return
                val option = freshOption(layer.id, mannequin) ?: return
                val pageCount = colorGridPageCountForLayer(layer, option, player, maxRows)
                if (pageCount <= 1) return
                val pages = palettePages(player.uniqueId, mannequinId)
                val cur = pages.getOrDefault(layer.id, 0)
                val delta =
                        when (configBtn.colorTabMode) {
                            ColorTabMode.FORWARD -> 1
                            ColorTabMode.BACKWARD -> -1
                            ColorTabMode.ALTERNATE -> if (backwards) -1 else 1
                        }
                pages[layer.id] = (cur + delta + pageCount) % pageCount
                refreshColorGrid(player, mannequin, state, hud)
                refreshDynamicLabels(mannequinId)
            }
            "save", "load", "apply" -> {
                executeConfigAction(configBtn.type, mannequinId, player, state)
            }
            "copyMe" -> {
                val skinUrl = player.playerProfile.textures.skin
                val skinModel = player.playerProfile.textures.skinModel
                if (skinUrl == null) {
                    updateStatus(mannequinId, "No skin found")
                    return
                }

                updateStatus(mannequinId, "Downloading skin...")
                sessionManager.skinTextureSessionCache.getOrStartDecode(skinUrl).thenAccept { decoded ->
                    val uid = decoded.uid
                    if (uid != null) {
                        val session = sessionManager.load(uid)
                        if (session != null) {
                            plugin.server.scheduler.runTask(
                                    plugin,
                                    Runnable {
                                        applySession(mannequinId, session)
                                        updateStatus(mannequinId, "Copied")
                                    }
                            )
                            return@thenAccept
                        }
                    }

                    // Fallback: upload skin to base layer
                    plugin.server.scheduler.runTask(
                            plugin,
                            Runnable {
                                val baseLayer = findBaseLayer() ?: return@Runnable
                                val partName = layerManager.nextBasePartName(player, baseLayer.id)

                                updateStatus(mannequinId, "Uploading $partName...")
                                layerManager
                                        .uploadPart(
                                                player,
                                                baseLayer.id,
                                                skinUrl,
                                                partName,
                                                sessionManager,
                                                uncraig = uncraig
                                        )
                                        .thenAccept { _ ->
                                            plugin.server.scheduler.runTask(
                                                    plugin,
                                                    Runnable {
                                                        // Re-find the part we just uploaded to get
                                                        // its actual ID
                                                        val opts =
                                                                layerManager.optionsFor(
                                                                        baseLayer.id,
                                                                        player
                                                                )
                                                        val internalKey =
                                                                layerManager.slugify(partName)
                                                        val uploaded =
                                                                opts.find {
                                                                    it.owner == player.uniqueId &&
                                                                            it.internalKey ==
                                                                                    internalKey
                                                                }

                                                        if (uploaded != null) {
                                                            val newSelections = mannequin.selection.selections.toMutableMap()
                                                            val currentBaseSel = newSelections[baseLayer.id]
                                                            newSelections[baseLayer.id] = currentBaseSel?.copy(option = uploaded)
                                                                ?: LayerSelection(baseLayer.id, uploaded)

                                                            layerManager.definitionsInOrder().forEach { otherDef ->
                                                                if (otherDef.id != baseLayer.id && otherDef.allowEmpty) {
                                                                    val noneOpt = layerManager.allOptions(otherDef.id).find { it.id == "none" }
                                                                    if (noneOpt != null) {
                                                                        val oldSel = newSelections[otherDef.id]
                                                                        newSelections[otherDef.id] = oldSel?.copy(option = noneOpt)
                                                                            ?: LayerSelection(otherDef.id, noneOpt)
                                                                    }
                                                                }
                                                            }

                                                            mannequin.selection = mannequin.selection.copy(selections = newSelections)
                                                            mannequin.slimModel =
                                                                    (skinModel == SkinModel.SLIM)
                                                            renderFull(
                                                                    mannequin,
                                                                    nearbyViewers(mannequin)
                                                            )
                                                            updateStatus(mannequinId, "Copied Skin")
                                                            refreshDynamicLabels(mannequinId)
                                                        } else {
                                                            updateStatus(mannequinId, "Upload failed")
                                                        }
                                                    }
                                            )
                                        }
                                        .exceptionally { ex ->
                                            plugin.server.scheduler.runTask(
                                                    plugin,
                                                    Runnable {
                                                        updateStatus(
                                                                mannequinId,
                                                                "Error: ${ex.message}"
                                                        )
                                                    }
                                            )
                                            null
                                        }
                            }
                    )
                }.exceptionally { _ ->
                    plugin.server.scheduler.runTask(
                            plugin,
                            Runnable { updateStatus(mannequinId, "Download failed") }
                    )
                    null
                }
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
        val style = styleManager.getStyle(mannequin.styleId) ?: return
        val colorMenuId = findMenuIdForType("color_grid", style.hudButtons) ?: return
        val gridVisible = hud.buttons.any { it.id.startsWith("${colorMenuId}_") }
        if (gridVisible) {
            val colorMenuBtn = buttonByName(colorMenuId, style.hudButtons) ?: return
            despawnMenu(colorMenuId, player, hud, quiet = true)
            spawnMenu(colorMenuBtn, player, mannequin, state, hud, quiet = true)
        }
    }

    private fun findBaseLayer(): LayerDefinition? {
        val definitions = layerManager.definitionsInOrder()
        return definitions.find { it.isBase } ?: definitions.firstOrNull()
    }

    // ── Status & label helpers ──────────────────────────────────────────────────

    private fun updateStatus(mannequinId: UUID, msg: String) {
        statusText[mannequinId] = msg
        val json = formatStatusText(msg, mannequinId)
        for (player in plugin.server.onlinePlayers) {
            val hud = holoController.getHud(player.uniqueId) ?: continue
            val man = mannequins[mannequinId] ?: continue
            if (hud.origin.world == man.location.world &&
                            hud.origin.distanceSquared(man.location) < 0.1
            ) {
                hud.updateButtonText("status", json)
            }
        }
    }

    private fun refreshDynamicLabels(mannequinId: UUID) {
        val state = controlState[mannequinId] ?: return
        val mode = state.mode
        val mannequin = mannequins[mannequinId] ?: return

        val layers = getAvailableLayers(mannequin)
        val layersOnMannequin = layers.filter { it.id in mannequin.selection.selections.keys }
        val layerCount = layersOnMannequin.size
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

            val slots =
                    if (currentLayer != null && currentOption != null)
                            resolveChannelSlots(currentLayer, currentOption, state, player)
                    else emptyList()
            val channelDisabled = slots.size <= 1
            val selectedChannel =
                    slots.getOrNull(state.channelIndex.getOrDefault(currentLayer?.id ?: "", 0))
                            ?: slots.firstOrNull()

            val style = styleManager.getStyle(mannequin.styleId) ?: continue
            val gridConfig = findColorGridConfigInStyle(style.hudButtons)
            val colorMenuId = findMenuIdForType("color_grid", style.hudButtons)
            val maxRowsForColorGrid = gridConfig?.maxRows?.coerceAtLeast(1) ?: 1
            val colorGridPageCount =
                    colorGridPageCountForLayer(currentLayer, currentOption, player, maxRowsForColorGrid)
            val colorGridVisible =
                    colorMenuId != null &&
                            hud.buttons.any { it.id.startsWith("${colorMenuId}_") }
            val colortabShouldHide = colorGridPageCount <= 1 || !colorGridVisible

            fun applyPlaceholders(mm: String): String {
                var out = mm
                if (selectedChannel != null) {
                    out = out.replace("{selectedChannel}", selectedChannel.label)
                } else {
                    out = out.replace("{selectedChannel}", "")
                }
                return out
            }

            fun processBtn(btn: HudButton, parentName: String? = null) {
                val activeId = if (parentName != null) "${parentName}_${btn.name}" else btn.name

                val isLayerType = btn.type == "layer"
                val isTextureType = btn.type == "texture"
                val isChannelType = btn.type == "channel"

                val isButtonDisabled =
                        (isLayerType && layerDisabled) ||
                                (isTextureType && textureDisabled) ||
                                (isChannelType && channelDisabled)
                val hideColortab =
                        parentName == null &&
                                btn.type == "colortab" &&
                                colortabShouldHide &&
                                btn.disabledTextMM == null
                val hideThis =
                        (isButtonDisabled && btn.disabledTextMM == null) || hideColortab

                if (hideThis) {
                    if (hud.isButtonActive(activeId)) {
                        hud.removeButtons(listOf(activeId), instant = true)
                    }
                } else if (!hud.isButtonActive(activeId)) {
                    // Only auto-add if it's a top-level button (submenus manage their own
                    // visibility)
                    if (parentName == null) {
                        hud.addButtons(
                                listOf(
                                        HoloButton(
                                                id = activeId,
                                                textJson =
                                                        TextUtility.mmToJson(
                                                                applyPlaceholders(
                                                                        btn.disabledTextMM
                                                                                ?: btn.textMM
                                                                )
                                                        ),
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
                }

                if (hud.isButtonActive(activeId)) {
                    val isActive =
                            when (btn.type) {
                                "submenu" ->
                                        hud.isButtonActive("${activeId}_") ||
                                                (btn.name == "config" && mode == ControlMode.LOAD)
                                "layer" -> {
                                    if (btn.targetLayer != null) {
                                        btn.targetLayer == currentLayer?.id
                                    } else false
                                }
                                else -> false
                            }

                    val mm =
                            if (btn.type == "status") {
                                null
                            } else if (isButtonDisabled && btn.disabledTextJson != null) {
                                btn.disabledTextMM
                            } else if (isActive && btn.activeTextMM != null) {
                                btn.activeTextMM
                            } else {
                                btn.textMM
                            }

                    val textJson =
                            if (btn.type == "status") {
                                formatStatusText(statusText[mannequinId], mannequinId)
                            } else {
                                TextUtility.mmToJson(applyPlaceholders(mm ?: btn.textMM))
                            }

                    hud.updateButtonText(activeId, textJson)
                    hud.updateButtonBg(activeId, if (isActive) btn.bgHighlight else btn.bgDefault)
                }

                // Recursively process children if this is a submenu and it's open
                if (hud.isButtonActive("${activeId}_") && btn.items != null) {
                    for (item in btn.items.values) {
                        processBtn(item, activeId)
                    }
                }
            }

            for (btn in style.hudButtons) {
                processBtn(btn)
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
        palettePages(player.uniqueId, mannequin.id)[layer.id] = 0
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

        val layers = getAvailableLayers(mannequin)
        val layersOnMannequin = layers.filter { it.id in mannequin.selection.selections.keys }
        val layerDisabled = layersOnMannequin.size <= 1
        val currentLayer = layers.getOrNull(state.layerIndex % layers.size)
        val currentOption = currentLayer?.let { freshOption(it.id, mannequin) }

        val texs =
                if (currentLayer != null && currentOption != null)
                        layerManager.resolveTextures(currentLayer, currentOption, player)
                else emptyList<String>()
        val textureDisabled = texs.size <= 1

        val slots =
                if (currentLayer != null && currentOption != null)
                        resolveChannelSlots(currentLayer, currentOption, state, player)
                else emptyList()
        val channelDisabled = slots.size <= 1
        val selectedChannel =
                slots.getOrNull(state.channelIndex.getOrDefault(currentLayer?.id ?: "", 0))
                        ?: slots.firstOrNull()

        fun applyPlaceholders(mm: String): String {
            var out = mm
            if (selectedChannel != null) {
                out = out.replace("{selectedChannel}", selectedChannel.label)
            } else {
                out = out.replace("{selectedChannel}", "")
            }
            return out
        }

        menuBtn.items?.values?.forEach { itemConf ->
            val isLayerType = itemConf.type == "layer"
            val isTextureType = itemConf.type == "texture"
            val isChannelType = itemConf.type == "channel"

            val isButtonDisabled =
                    (isLayerType && layerDisabled) ||
                            (isTextureType && textureDisabled) ||
                            (isChannelType && channelDisabled)
            val hideThis = isButtonDisabled && itemConf.disabledTextMM == null
            if (hideThis) return@forEach

            if (itemConf.type == "color_grid") {
                injectColorGrid(itemConf, grid, menuBtn.name, player, mannequin, state)
            } else {
                val idPrefix = "${menuBtn.name}_${itemConf.name}"
                val itemText =
                        if (isButtonDisabled && itemConf.disabledTextMM != null) {
                            itemConf.disabledTextMM
                        } else {
                            itemConf.textMM
                        }
                grid.addButtonManual(
                        id = idPrefix,
                        textMM = applyPlaceholders(itemText),
                        offsetX = itemConf.tx,
                        offsetY = itemConf.ty,
                        bgDefault =
                                if (itemConf.bgHeader != null && itemConf.bgHeader != 0)
                                        itemConf.bgHeader
                                else itemConf.bgDefault,
                        bgHighlight = HUD_BG_HIGHLIGHT,
                        lineWidth = itemConf.lineWidth,
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
        val layers = getAvailableLayers(mannequin)
        val layer = layers.getOrNull(state.layerIndex % layers.size) ?: return
        val option = freshOption(layer.id, mannequin) ?: return
        val rawPaletteIds = layerManager.resolvePalettes(layer, option, player)
        val allPaletteIds = rawPaletteIds.filter { it != "default" }

        if (allPaletteIds.isEmpty()) {
            updateStatus(mannequin.id, "No palettes available")
            return
        }

        val maxRows = config.maxRows.coerceAtLeast(1)
        val pageCount = (allPaletteIds.size + maxRows - 1) / maxRows
        val pages = palettePages(player.uniqueId, mannequin.id)
        val rawPage = pages.getOrDefault(layer.id, 0)
        val page = rawPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        pages[layer.id] = page
        val visiblePaletteIds = allPaletteIds.drop(page * maxRows).take(maxRows)

        grid.cellSpacingX = config.cellSpacingX
        grid.cellSpacingY = config.cellSpacingY

        val selectedColor = currentSelectedGridColor(mannequin, state)
        val slots = resolveChannelSlots(layer, option, state, player)

        // Palette rows (one row per palette on the current page)
        for ((row, palId) in visiblePaletteIds.withIndex()) {
            val palette = layerManager.palette(palId) ?: continue

            // Palette header
            var finalName = prettyName(palId)
            if (config.headerPaddingLen > 0) {
                finalName = when (config.headerPaddingSide) {
                    "left" -> finalName.padStart(config.headerPaddingLen, '\u00A0')
                    "right" -> finalName.padEnd(config.headerPaddingLen, '\u00A0')
                    else -> finalName
                }
            }

            // Use manual offsets so headers can be nudged vertically without affecting row spacing.
            grid.addButtonManual(
                    id = "${parentId}_pal_header_$palId",
                    textMM = config.headerTextMM.replace("{message}", finalName),
                    offsetX = config.headerColumn * grid.cellSpacingX,
                    offsetY = (-row * grid.cellSpacingY) + config.headerOffsetY,
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
                val isSelected =
                        selectedColor != null &&
                                (baseRgb.rgb and 0xFFFFFF) == (selectedColor.rgb and 0xFFFFFF)
                val luma =
                        (0.299 * baseRgb.red + 0.587 * baseRgb.green + 0.114 * baseRgb.blue) / 255.0
                val xColor = if (luma > 0.6) "black" else "white"

                grid.addButton(
                        id = "${parentId}_color_${palId}_${namedColor.name}",
                        textMM = if (isSelected) "<$xColor><b>•</b></$xColor>" else " ",
                        column = col,
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
        val layers = getAvailableLayers(mannequin)
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
        refreshColorGrid(player, mannequin, state, hud)
    }

    private fun currentSelectedGridColor(
            mannequin: Mannequin,
            state: ControlState
    ): java.awt.Color? {
        val layers = getAvailableLayers(mannequin)
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
                    sendSessionSavedChat(player, session.uid)
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
            val viewers = trackingViewers(mannequin)
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

            val style = styleManager.getStyle(mannequin.styleId)
            val settings = (style?.rendering?.firstSeen ?: RenderSettings()).copy(reversed = true)
            val light = textDisplayLightProvider(mannequin)
            viewers.forEach { viewer ->
                animationManager.deliver(viewer, manId, projected, settings, light)
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
            sessionOverride: SessionData? = null,
            craig: Boolean = false
    ) {
        var actualSessionOverride = sessionOverride

        var savedInternally = false
        if (sessionOverride == null) {
            val currentFingerprint = sessionManager.fingerprint(mannequin)
            if (lastSavedFingerprint[mannequin.id] != currentFingerprint) {
                val saved = saveMannequinState(mannequin, requester)
                sendSessionSavedChat(requester, saved.uid)
                actualSessionOverride = saved
                savedInternally = true
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
                        contextPlayer = contextPlayer,
                        craig = craig,
                        recordStats = !savedInternally
                )
                .thenAccept { result ->
                    val url = ConfigManager.instance.getImageUrl(result.file.name)
                    val slim = result.slim
                    plugin.server.scheduler.runTask(
                            plugin,
                            Runnable {
                                sessionManager.load(result.uid)?.let { appliedSession ->
                                    mannequin.savedUid = result.uid
                                    lastSavedFingerprint[mannequin.id] =
                                            sessionManager.fingerprint(appliedSession)
                                    persist()
                                }

                                if (!contextPlayer.isOnline) return@Runnable

                                if (characterManagerBridge.active) {
                                    val charContext =
                                            characterManagerBridge.currentCharacter(contextPlayer)
                                    if (charContext != null) {
                                        characterManagerBridge.updateSkin(
                                                contextPlayer,
                                                charContext.characterUuid,
                                                url,
                                                slim
                                        )
                                        requester.sendMessage(
                                                TextUtility.convertToComponent(
                                                        "&aSkin applied to character &d${charContext.characterName}&a!"
                                                )
                                        )
                                        return@Runnable
                                    }
                                }

                                runCatching {
                                    applyFinalizedSkinToPlayerProfile(contextPlayer, url, slim)
                                }.onFailure {
                                    plugin.logger.log(
                                            Level.WARNING,
                                            "Could not set in-game skin profile for ${contextPlayer.name}",
                                            it
                                    )
                                }

                                val message =
                                        Component.text("Skin finalized! ")
                                                .color(NamedTextColor.GREEN)
                                                .append(
                                                        Component.text("[Open skin URL]")
                                                                .color(NamedTextColor.YELLOW)
                                                                .decorate(TextDecoration.UNDERLINED)
                                                                .clickEvent(ClickEvent.openUrl(url))
                                                                .hoverEvent(
                                                                        HoverEvent.showText(
                                                                                Component.text(url)
                                                                        )
                                                                )
                                                )
                                requester.sendMessage(message)
                            }
                    )
                }
                .exceptionally { ex ->
                    requester.sendMessage(
                            TextUtility.convertToComponent("&cFinalization failed: ${ex.message}")
                    )
                    null
                }
    }

    /**
     * Points the player's Paper profile texture at the finalized PNG so [org.bukkit.profile.PlayerTextures.getSkin]
     * (and e.g. debug checkskin) download the image that carries the encoded session UID.
     */
    private fun applyFinalizedSkinToPlayerProfile(player: Player, skinUrl: String, slim: Boolean) {
        val url = URI.create(skinUrl).toURL()
        val profile = player.playerProfile
        val textures = profile.textures
        textures.setSkin(url, if (slim) SkinModel.SLIM else SkinModel.CLASSIC)
        profile.setTextures(textures)
        player.playerProfile = profile
    }

    /**
     * Applies a saved [session] to [contextPlayer]'s skin via [SessionManager.finalizeSessionFromSessionData]
     * (download skin, detect encoded session, merge per layer, composite, new UID). Same pipeline as
     * mannequin Apply without a mannequin entity — used for outfit items and similar.
     */
    fun finalizeAndApplySession(
            requester: Player,
            contextPlayer: Player,
            session: SessionData,
            craig: Boolean = false
    ) {
        sessionManager
                .finalizeSessionFromSessionData(
                        requester = requester,
                        session = session,
                        contextPlayer = contextPlayer,
                        craig = craig
                )
                .thenAccept { result ->
                    val url = ConfigManager.instance.getImageUrl(result.file.name)
                    val slim = result.slim
                    plugin.server.scheduler.runTask(
                            plugin,
                            Runnable {
                                if (!contextPlayer.isOnline) return@Runnable

                                if (characterManagerBridge.active) {
                                    val charContext =
                                            characterManagerBridge.currentCharacter(contextPlayer)
                                    if (charContext != null) {
                                        characterManagerBridge.updateSkin(
                                                contextPlayer,
                                                charContext.characterUuid,
                                                url,
                                                slim
                                        )
                                        requester.sendMessage(
                                                TextUtility.convertToComponent(
                                                        "&aSkin applied to character &d${charContext.characterName}&a!"
                                                )
                                        )
                                        return@Runnable
                                    }
                                }

                                runCatching {
                                    applyFinalizedSkinToPlayerProfile(contextPlayer, url, slim)
                                }.onFailure {
                                    plugin.logger.log(
                                            Level.WARNING,
                                            "Could not set in-game skin profile for ${contextPlayer.name}",
                                            it
                                    )
                                }

                                val message =
                                        Component.text("Skin finalized! ")
                                                .color(NamedTextColor.GREEN)
                                                .append(
                                                        Component.text("[Open skin URL]")
                                                                .color(NamedTextColor.YELLOW)
                                                                .decorate(TextDecoration.UNDERLINED)
                                                                .clickEvent(ClickEvent.openUrl(url))
                                                                .hoverEvent(
                                                                        HoverEvent.showText(
                                                                                Component.text(url)
                                                                        )
                                                                )
                                                )
                                requester.sendMessage(message)
                            }
                    )
                }
                .exceptionally { ex ->
                    requester.sendMessage(
                            TextUtility.convertToComponent("&cFinalization failed: ${ex.message}")
                    )
                    null
                }
    }

    /** @see finalizeAndApplySession */
    fun applyOutfitSession(player: Player, session: SessionData) {
        finalizeAndApplySession(player, player, session)
    }

    private fun sendSessionSavedChat(player: Player, uid: String) {
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
        updateStatus(mannequin.id, "Saved Session")
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
        val debug = plugin.config.getBoolean("plugin.debug", false)
        if (debug) {
            val pLoc = player.location
            val mLoc = mannequin.location
            val dist = if (pLoc.world == mLoc.world) runCatching { pLoc.distance(mLoc) }.getOrNull() else null
            plugin.logger.info(
                    "[DEBUG] handleInteract: man=$mannequinId player=${player.name} gm=${player.gameMode} backwards=$backwards dist=${dist ?: "?"}"
            )
        }

        if (mannequin.isHidden) {
            mannequin.isHidden = false
            val viewers = viewersForUnhide(mannequin)
            if (viewers.isNotEmpty()) {
                renderFull(mannequin, viewers, isFirstSeen = true)
                viewers.forEach { v ->
                    sentTo.getOrPut(v.uniqueId) { mutableSetOf() }.add(mannequin.id)
                }
            }
            spawnPlayerHud(player, mannequin)
            return
        }

        val hud = holoController.getHud(player.uniqueId)
        if (debug) {
            plugin.logger.info(
                    "[DEBUG] handleInteract: hud=${hud != null} hudMan=${hud?.mannequinId} thisMan=$mannequinId"
            )
        }

        if (hud != null && hud.mannequinId == mannequinId) {
            val hover = hud.isAnyButtonHovered
            val tolerance = meetsInteractionTolerances(player, mannequin)

            if (debug) {
                plugin.logger.info(
                        "[DEBUG] handleInteract: hudOpen=true hoverButton=$hover tolerance=$tolerance"
                )
            }

            // Check for event cancellation (blocks regular interaction during ETF / remask configure)
            val event = MannequinClickEvent(mannequinId, mannequin.location, player, "_SURFACE_", backwards = backwards)
            plugin.server.pluginManager.callEvent(event)
            if (debug) {
                plugin.logger.info("[DEBUG] handleInteract: clickEvent cancelled=${event.isCancelled}")
            }
            if (event.isCancelled) return

            // Only cycle if not hovering a button and within tolerance
            if (!hover && tolerance) {
                val state = controlState[mannequinId] ?: return
                // Bug 3: Interacting with mannequin cycles parts, not layers
                val layers = getAvailableLayers(mannequin)
                val layer = layers.getOrNull(state.layerIndex % layers.size)
                if (layer != null) {
                    val chosen = cyclePart(layer, mannequin, state, player, backwards)
                    render(mannequin, nearbyViewers(mannequin))
                    refreshDynamicLabels(mannequinId)
                    if (chosen != null) {
                        updateStatus(mannequinId, "${prettyName(chosen)}")
                    }
                }
            } else if (debug) {
                plugin.logger.info("[DEBUG] handleInteract: NOOP hover=$hover tolerance=$tolerance")
            }
            return
        }

        if (debug) {
            plugin.logger.info("[DEBUG] handleInteract: spawning HUD (no hud or other mannequin)")
        }
        spawnPlayerHud(player, mannequin)
    }

    fun isPlayerInLoadMode(viewerId: UUID): Boolean {
        val hud = holoController.getHud(viewerId) ?: return false
        val state = controlState[hud.mannequinId] ?: return false
        return state.mode == ControlMode.LOAD
    }

    private fun meetsInteractionTolerances(player: Player, mannequin: Mannequin): Boolean {
        val debug = plugin.config.getBoolean("plugin.debug", false)
        val pLoc = player.location
        val mLoc = mannequin.location
        val dist =
                if (pLoc.world == mLoc.world) runCatching { pLoc.distance(mLoc) }.getOrNull()
                else null
        if (dist == null) return false
        if (dist > interactRange) {
            if (debug) {
                plugin.logger.info(
                        "[DEBUG] tolerance: dist=$dist > interactRange=$interactRange (gm=${player.gameMode})"
                )
            }
            return false
        }

        val toMannequin = mLoc.toVector().subtract(pLoc.toVector()).setY(0).normalize()
        val facing = pLoc.direction.setY(0).normalize()

        val angle = facing.angle(toMannequin)
        val deg = Math.toDegrees(angle.toDouble())
        val ok = deg <= partFacingToleranceDeg
        if (debug) {
            plugin.logger.info(
                    "[DEBUG] tolerance: dist=$dist deg=${"%.2f".format(deg)} tolDeg=$partFacingToleranceDeg ok=$ok gm=${player.gameMode}"
            )
        }
        return ok
    }

    fun startHoverTask() {
        /* No-op, handled by HoloController */
    }
    fun stopHoverTask() {
        /* No-op, handled by HoloController */
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
                },
                saturationInfluenceResolver = { layerId, option ->
                    val def = layerManager.definitionsInOrder().find { it.id == layerId }
                    if (def != null) layerManager.resolveSaturationInfluence(def, option) else 1f
                },
                blinkEnabled =
                	plugin.config.getBoolean(
                		"integrations.entity-texture-features.blink-enabled",
                		false
                	),
                jacketEnabled =
                	plugin.config.getBoolean(
                		"integrations.entity-texture-features.jacket-enabled",
                		false
                	),
                defaultJacketStyle =
                	plugin.config.getInt(
                		"integrations.entity-texture-features.jacket-dress-style",
                		5
                	),
                showOverlay = mannequin.showOverlay
        )
    }

    /**
     * Players who should receive incremental [render] / [renderFull] updates: same world, within
     * **update-radius**, and already tracking this mannequin ([sentTo]). Matches [renderVisibleTo]
     * / [checkFirstSeen] teardown at update-radius.
     */
    fun nearbyViewers(mannequin: Mannequin): List<Player> {
        val style = styleManager.getStyle(mannequin.styleId)
        val radius = style?.rendering?.updateRadius ?: 30.0
        val radiusSq = radius * radius
        val mid = mannequin.id
        return plugin.server.onlinePlayers.filter { p ->
            p.world == mannequin.location.world &&
                    mid in sentTo.getOrDefault(p.uniqueId, emptySet()) &&
                    p.location.distanceSquared(mannequin.location) <= radiusSq
        }
    }

    /** Within view-radius only; used for initial spawn (before [sentTo] is populated). */
    private fun viewRadiusViewers(mannequin: Mannequin): List<Player> {
        val style = styleManager.getStyle(mannequin.styleId)
        val radius = style?.rendering?.viewRadius ?: 8.0
        val radiusSq = radius * radius
        return plugin.server.onlinePlayers.filter {
            it.world == mannequin.location.world &&
                    it.location.distanceSquared(mannequin.location) <= radiusSq
        }
    }

    /** Everyone still marked as tracking this mannequin (any distance). Used for apply-hide fade. */
    private fun trackingViewers(mannequin: Mannequin): List<Player> {
        val mid = mannequin.id
        return plugin.server.onlinePlayers.filter { mid in sentTo.getOrDefault(it.uniqueId, emptySet()) }
    }

    /**
     * Unhide: first-seen animation for players in view-radius, plus full respawn for anyone still
     * tracking within update-radius (they had pixels before hide).
     */
    private fun viewersForUnhide(mannequin: Mannequin): List<Player> {
        val style = styleManager.getStyle(mannequin.styleId) ?: return emptyList()
        val viewSq = style.rendering.viewRadius * style.rendering.viewRadius
        val updateSq = style.rendering.updateRadius * style.rendering.updateRadius
        val mid = mannequin.id
        val byId = LinkedHashMap<UUID, Player>()
        for (p in plugin.server.onlinePlayers) {
            if (p.world != mannequin.location.world) continue
            val dSq = p.location.distanceSquared(mannequin.location)
            val tracking = mid in sentTo.getOrDefault(p.uniqueId, emptySet())
            if (dSq <= viewSq || (tracking && dSq <= updateSq)) {
                byId[p.uniqueId] = p
            }
        }
        return byId.values.toList()
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

    private fun bootstrapSelection(styleId: String? = null): SkinSelection {
        val style = styleManager.getStyle(styleId)
        val allDefs = layerManager.definitionsInOrder()
        val definitions = if (style != null) allDefs.filter { it.id in style.availableLayers } else allDefs
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
        val allDefinitions = layerManager.definitionsInOrder()
        val availableDefs = getAvailableLayers(mannequin).map { it.id }.toSet()
        val rng = java.util.concurrent.ThreadLocalRandom.current()
        val newSelections = mutableMapOf<String, LayerSelection>()

        for (def in allDefinitions) {
            if (def.id in availableDefs) {
                val options = layerManager.optionsFor(def.id)
                if (options.isEmpty()) continue
                val allViable =
                        options.filter { opt ->
                            val pal = layerManager.resolvePalettes(def, opt, null)
                            val tex = layerManager.resolveTextures(def, opt, null)
                            pal.isNotEmpty() && tex.isNotEmpty()
                        }
                val nonNoneViable = allViable.filter { it.id != "none" }
                val chosen =
                        when {
                            nonNoneViable.isNotEmpty() -> nonNoneViable[rng.nextInt(nonNoneViable.size)]
                            allViable.isNotEmpty() -> allViable[rng.nextInt(allViable.size)]
                            else -> options[rng.nextInt(options.size)]
                        }
                newSelections[def.id] = buildInitialSelection(def, chosen)
            } else {
                // Keep current selection for unavailable layers
                val current = mannequin.selection.selections[def.id]
                if (current != null) {
                    newSelections[def.id] = current
                }
            }
        }

        mannequin.selection = SkinSelection(newSelections)
        for (def in allDefinitions) rememberCurrentPartSelection(mannequin, def)
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
