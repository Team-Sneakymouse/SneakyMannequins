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

private data class ControlRef(val id: UUID, val location: Location)
private data class ControlState(
    var layerIndex: Int = 0,
    val partIndex: MutableMap<String, Int> = mutableMapOf(),
    val colorIndex: MutableMap<String, Int> = mutableMapOf(),
    val channelIndex: MutableMap<String, Int> = mutableMapOf(),
    var mode: ControlMode = ControlMode.NONE
)

private enum class ControlMode { NONE, PART, COLOR }

class MannequinManager(
    private val plugin: SneakyMannequins,
    private val layerManager: LayerManager,
    private val handler: VolatileHandler,
    private val persistence: MannequinPersistence
) {
    private val mannequins = mutableMapOf<UUID, Mannequin>()
    private val controlLocations = mutableMapOf<UUID, MutableList<ControlRef>>()
    private val sentTo = mutableMapOf<UUID, MutableSet<UUID>>() // viewerId -> mannequins seen
    private val statusText = mutableMapOf<UUID, String>() // mannequinId -> last action
    private val poseState = mutableMapOf<UUID, Boolean>() // mannequinId -> true = T-pose
    private val controlState = mutableMapOf<UUID, ControlState>()
    private val interactionDebounce = mutableMapOf<Pair<UUID, String>, Long>() // (playerId, key) -> epochMillis

    fun loadFromDisk() {
        val (loaded, controls) = persistence.load()
        controlLocations.clear()
        controls.forEach { (id, list) ->
            controlLocations[id] = list.map { ControlRef(UUID.randomUUID(), it.clone()) }.toMutableList()
        }
        loaded.forEach { (id, loc) ->
            val selection = bootstrapSelection()
            mannequins[id] = Mannequin(id = id, location = loc.clone(), selection = selection)
            controlState[id] = ControlState()
            // Clean any stray control entities for this mannequin, then spawn what we expect
            cleanupControls(id)
            spawnControlsIfMissing(id)
            spawnAreaInteraction(id)
        }
    }

    fun persist() {
        val controlLocs = controlLocations.mapValues { entry -> entry.value.map { it.location } }
        persistence.save(mannequins.values, controlLocs)
    }

    fun create(location: Location): Mannequin {
        val selection = bootstrapSelection()
        val mannequin = Mannequin(location = location.clone(), selection = selection)
        mannequins[mannequin.id] = mannequin
        controlState[mannequin.id] = ControlState()
        spawnAreaInteraction(mannequin.id)
        persist()
        return mannequin
    }

    fun get(id: UUID): Mannequin? = mannequins[id]

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
            logSampleColors(composed)
            dumpDebugImage(composed)
        }
        val projected = PixelProjector.project(
            origin = mannequin.location,
            changes = diff,
            pixelScale = 1.0 / 16.0,
            scaleMultiplier = handler.pixelScaleMultiplier(),
            slimArms = isSlimModel(mannequin)
        )
        viewers.forEach { viewer ->
            handler.applyProjectedPixels(viewer, mannequin.id, projected)
        }
        return diff.size
    }

    private fun renderFull(mannequin: Mannequin, viewers: Collection<Player>) {
        val definitions = layerManager.definitionsInOrder()
        val composed = SkinComposer.compose(definitions, mannequin.selection, useSlimModel = isSlimModel(mannequin))
        // Snapshot as last frame so future diffs are against this render
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

    fun remove(mannequinId: UUID, viewers: Collection<Player>) {
        viewers.forEach { viewer -> handler.destroyMannequin(viewer, mannequinId) }
        // Cleanup control & area entities while the mannequin location is still known
        cleanupControls(mannequinId)
        removeAreaInteraction(mannequinId)
        mannequins.remove(mannequinId)
        controlLocations.remove(mannequinId)
        controlState.remove(mannequinId)
        statusText.remove(mannequinId)
        poseState.remove(mannequinId)
        persist()
    }

    fun forgetViewer(viewerId: UUID) {
        sentTo.remove(viewerId)
    }

    fun shutdown() {
        val viewers = plugin.server.onlinePlayers
        mannequins.keys.forEach { id ->
            viewers.forEach { viewer -> handler.destroyMannequin(viewer, id) }
        }
        persist()
        mannequins.clear()
    }

    fun reloadAll() {
        // Destroy existing displays for all viewers
        val viewers = plugin.server.onlinePlayers
        mannequins.keys.forEach { id ->
            viewers.forEach { viewer -> handler.destroyMannequin(viewer, id) }
        }

        // Remove all control entities
        mannequins.keys.forEach { id -> cleanupControls(id) }

        mannequins.clear()
        controlLocations.clear()
        sentTo.clear()
        statusText.clear()
        poseState.clear()
        controlState.clear()
        interactionDebounce.clear()

        loadFromDisk()

        // Re-render for all online players to mimic fresh login
        plugin.server.onlinePlayers.forEach { viewer ->
            renderVisibleTo(viewer)
        }
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
                option = chosen,
                colorMask = null
            )
        }
        return SkinSelection(selections)
    }

    fun nearestMannequin(location: Location, radius: Double = 10.0): Mannequin? {
        return mannequins.values.minByOrNull { man ->
            if (man.location.world != location.world) Double.MAX_VALUE else man.location.distance(location)
        }?.takeIf { it.location.world == location.world && it.location.distance(location) <= radius }
    }

    fun addControls(mannequin: Mannequin, controlLoc: Location) {
        val loc = controlLoc.clone()
        loc.yaw = computeControlYaw(loc, mannequin.location)
        val ref = ControlRef(UUID.randomUUID(), loc)
        controlLocations.computeIfAbsent(mannequin.id) { mutableListOf() }.add(ref)
        // Clean any stray controls around the new location before spawning
        cleanupControls(mannequin.id, loc, ref.id)
        spawnControls(mannequin.id, ref)
        persist()
    }

    fun removeNearestControl(at: Location, radius: Double = 10.0): Boolean {
        var best: Pair<UUID, Int>? = null
        var bestDist = Double.MAX_VALUE
        controlLocations.forEach { (manId, list) ->
            list.forEachIndexed { idx, loc ->
                if (loc.location.world == at.world) {
                    val d = loc.location.distance(at)
                    if (d <= radius && d < bestDist) {
                        bestDist = d
                        best = manId to idx
                    }
                }
            }
        }
        val found = best ?: return false
        val list = controlLocations[found.first] ?: return false
        val ref = list[found.second]
        cleanupControls(found.first, ref.location, ref.id)
        list.removeAt(found.second)
        if (list.isEmpty()) {
            controlLocations.remove(found.first)
        }
        persist()
        return true
    }

    /** Ensure a single Interaction entity exists centred on the mannequin.
     *  If one already exists (found by tags) it is kept; otherwise a new one is spawned. */
    private fun spawnAreaInteraction(mannequinId: UUID) {
        val man = mannequins[mannequinId] ?: return
        val world = man.location.world ?: return
        val existing = world.getNearbyEntities(man.location, 8.0, 8.0, 8.0).any {
            it is org.bukkit.entity.Interaction
                    && it.scoreboardTags.contains("sneakymannequin_control")
                    && it.scoreboardTags.contains("mannequin:$mannequinId")
                    && it.scoreboardTags.contains("button:area")
        }
        if (existing) return
        world.spawn(man.location.clone(), org.bukkit.entity.Interaction::class.java) { inter ->
            inter.interactionWidth = 2.0f   // 1-block radius
            inter.interactionHeight = 2.0f
            inter.isResponsive = true
            inter.scoreboardTags.add("sneakymannequin_control")
            inter.scoreboardTags.add("mannequin:$mannequinId")
            inter.scoreboardTags.add("control:area")
            inter.scoreboardTags.add("button:area")
        }
    }

    /** Remove the area Interaction entity for a mannequin. */
    private fun removeAreaInteraction(mannequinId: UUID) {
        val man = mannequins[mannequinId] ?: return
        val world = man.location.world ?: return
        world.getNearbyEntities(man.location, 8.0, 8.0, 8.0).forEach {
            if (it is org.bukkit.entity.Interaction
                && it.scoreboardTags.contains("sneakymannequin_control")
                && it.scoreboardTags.contains("mannequin:$mannequinId")
                && it.scoreboardTags.contains("button:area")
            ) {
                it.remove()
            }
        }
    }

    private fun spawnControlsIfMissing(mannequinId: UUID) {
        val list = controlLocations[mannequinId] ?: return
        list.forEach { ref ->
            val loc = ref.location
            val world = loc.world ?: return@forEach
            // Clean any leftover controls for this controlId near this location
            cleanupControls(mannequinId, loc, ref.id)
            val existing = world.getNearbyEntities(loc, 1.0, 1.0, 1.0).firstOrNull {
                it.scoreboardTags.contains("sneakymannequin_control")
                        && it.scoreboardTags.contains("mannequin:$mannequinId")
                        && it.scoreboardTags.contains("control:${ref.id}")
            }
            if (existing == null) spawnControls(mannequinId, ref)
        }
    }

    private fun spawnControls(mannequinId: UUID, ref: ControlRef) {
        val loc = ref.location
        val world = loc.world ?: return
        val man = mannequins[mannequinId] ?: return
        val yaw = computeControlYaw(loc, man.location)

        fun spawnButton(
            text: String,
            offsetY: Double,
            offsetX: Double,
            offsetZ: Double,
            buttonTag: String,
            width: Double = 1.2,
            height: Double = 0.6,
            lineWidth: Int = 200
        ): Entity? {
            val yawRad = Math.toRadians(yaw.toDouble())
            val cos = kotlin.math.cos(yawRad)
            val sin = kotlin.math.sin(yawRad)
            val dx = offsetX * cos - offsetZ * sin
            val dz = offsetX * sin + offsetZ * cos
            val p = loc.clone().add(dx, offsetY, dz).apply {
                this.yaw = yaw
                this.pitch = 0f
            }
            // Interaction hitbox (small footprint to reduce overlap)
            world.spawn(p, org.bukkit.entity.Interaction::class.java) { inter ->
                inter.interactionWidth = (width * 0.4).toFloat()
                inter.interactionHeight = (height * 0.35).toFloat()
                inter.isResponsive = true
                inter.scoreboardTags.add("sneakymannequin_control")
                inter.scoreboardTags.add("mannequin:$mannequinId")
                inter.scoreboardTags.add("control:${ref.id}")
                inter.scoreboardTags.add("button:$buttonTag")
            }
            return world.spawn(p, TextDisplay::class.java) { td ->
                td.text(Component.text(text))
                td.billboard = Display.Billboard.FIXED
                td.backgroundColor = Color.fromARGB(120, 0, 0, 0)
                td.isShadowed = false
                td.viewRange = 32f
                td.textOpacity = 255.toByte()
                td.lineWidth = lineWidth
                td.displayWidth = width.toFloat()
                td.displayHeight = height.toFloat()
                td.isPersistent = false
                td.scoreboardTags.add("sneakymannequin_control")
                td.scoreboardTags.add("mannequin:$mannequinId")
                td.scoreboardTags.add("control:${ref.id}")
                td.scoreboardTags.add("button:$buttonTag")
            }
        }

        val status = statusText[mannequinId] ?: "Controls"
        // Layout window: status on top, left column model/pose/layer, right column part/channel/palette/color
        spawnButton(status, 2.4, 0.0, 0.2, "status", width = 2.8, height = 0.8, lineWidth = 256)
        spawnButton("Model", 1.8, -1.0, 0.2, "model", height = 0.6)
        spawnButton("Pose", 1.3, -1.0, 0.2, "pose", height = 0.6)
        spawnButton("Layer", 0.8, -1.0, 0.2, "layer", height = 0.6)

        spawnButton("Part", 1.8, 1.0, 0.2, "part", height = 0.6)
        spawnButton("Channel", 1.3, 1.0, 0.2, "channel", height = 0.6)
        spawnButton("Color", 0.8, 1.0, 0.2, "color", height = 0.6)

        // Initial label state for channel/palette depending on current selection
        val state = controlState.getOrPut(mannequinId) { ControlState() }
        val definitions = layerManager.definitionsInOrder()
        val layer = definitions.getOrNull(state.layerIndex % definitions.size) ?: definitions.firstOrNull()
        val option = layer?.let {
            mannequins[mannequinId]?.selection?.selections?.get(it.id)?.option ?: layerManager.optionsFor(it.id).firstOrNull()
        }
        refreshChannelColorLabels(mannequinId, option, layer)
    }

    private fun cleanupControls(mannequinId: UUID) {
        val list = controlLocations[mannequinId] ?: emptyList()
        list.forEach { loc -> cleanupControls(mannequinId, loc.location, loc.id) }
        mannequins[mannequinId]?.let { man ->
            val world = man.location.world ?: return@let
            world.getNearbyEntities(man.location, 10.0, 10.0, 10.0).forEach {
                if (it.scoreboardTags.contains("sneakymannequin_control") && it.scoreboardTags.contains("mannequin:$mannequinId")) {
                    it.remove()
                }
            }
        }
    }

    private fun cleanupControls(mannequinId: UUID, loc: Location, controlId: UUID? = null) {
        val world = loc.world ?: return
        world.getNearbyEntities(loc, 8.0, 8.0, 8.0).forEach {
            if (it.scoreboardTags.contains("sneakymannequin_control")
                && it.scoreboardTags.contains("mannequin:$mannequinId")
                && (controlId == null || it.scoreboardTags.contains("control:$controlId"))
            ) {
                it.remove()
            }
        }
    }

    private fun computeControlYaw(controlLoc: Location, mannequinLoc: Location): Float {
        val dir: Vector = mannequinLoc.toVector().subtract(controlLoc.toVector())
        return (Math.toDegrees(Math.atan2(-dir.x, dir.z)) + 180.0).toFloat()
    }

    fun handleControlInteract(entity: Entity, player: Player, backwards: Boolean) {
        // Only process the interaction hitboxes to avoid duplicate toggles from TextDisplay
        if (entity !is org.bukkit.entity.Interaction) return
        val tags = entity.scoreboardTags
        val manTag = tags.firstOrNull { it.startsWith("mannequin:") } ?: return
        val controlTag = tags.firstOrNull { it.startsWith("control:") } ?: return
        val buttonTag = tags.firstOrNull { it.startsWith("button:") } ?: return
        val manId = runCatching { UUID.fromString(manTag.removePrefix("mannequin:")) }.getOrNull() ?: return
        val controlId = controlTag.removePrefix("control:")
        val button = buttonTag.removePrefix("button:")
        val debounceKey = player.uniqueId to "$manId:$controlId:$button"
        val now = System.currentTimeMillis()
        val last = interactionDebounce[debounceKey]
        if (last != null && now - last < 200) return
        interactionDebounce[debounceKey] = now
        val mannequin = mannequins[manId] ?: return
        val state = controlState.getOrPut(manId) { ControlState() }
        val definitions = layerManager.definitionsInOrder()
        if (definitions.isEmpty()) return
        val layer = definitions.getOrNull(state.layerIndex % definitions.size) ?: definitions.first()
        fun updateStatus(msg: String) {
            statusText[manId] = msg
            // Update status displays for all controls
            controlLocations[manId]?.forEach { ref ->
                val world = ref.location.world ?: return@forEach
                world.getNearbyEntities(ref.location, 3.5, 3.5, 3.5).forEach {
                    if (it is TextDisplay && it.scoreboardTags.contains("button:status") && it.scoreboardTags.contains("control:${ref.id}")) {
                        it.text(Component.text(msg))
                    }
                }
            }
        }
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
                plugin.logger.info("Model toggle: current=${current?.id} -> next=${next?.id}")
                mannequin.selection = mannequin.selection.copy(
                    selections = mannequin.selection.selections + (baseLayerId to (mannequin.selection.selections[baseLayerId]?.copy(option = next, colorMask = null)
                        ?: LayerSelection(baseLayerId, next, null)))
                )
                // Force full rerender to avoid remnants from previous model
                mannequin.lastFrame = PixelFrame.blank()
                state.colorIndex[baseLayerId] = 0
                val modelLabel = when (next?.id?.lowercase()) {
                    "default_slim" -> "Slim"
                    "default" -> "Default"
                    else -> next?.displayName ?: next?.id ?: "Default"
                }
                updateStatus("Model: $modelLabel")
                val viewers = plugin.server.onlinePlayers.filter {
                    it.world == mannequin.location.world && it.location.distanceSquared(mannequin.location) <= VISIBLE_RANGE_SQ
                }
                // Clear previous pixels for viewers before full redraw to avoid remnants
                viewers.forEach { viewer -> handler.destroyMannequin(viewer, mannequin.id) }
                renderFull(mannequin, viewers)
                return
            }
            "pose" -> {
                val newPose = !(poseState[manId] ?: false)
                poseState[manId] = newPose
                updateStatus(if (newPose) "Pose: T-Pose" else "Pose: Default")
            }
            "layer" -> {
                val delta = if (backwards) -1 else 1
                state.layerIndex = (state.layerIndex + delta + definitions.size) % definitions.size
                val newLayer = definitions[state.layerIndex]
                updateStatus("Layer: ${newLayer.displayName}")
                // reset per-layer indices when switching layers
                state.partIndex[newLayer.id] = 0
                state.channelIndex[newLayer.id] = 0
                state.colorIndex[newLayer.id] = 0
                state.mode = ControlMode.NONE
                val option = mannequin.selection.selections[newLayer.id]?.option ?: layerManager.optionsFor(newLayer.id).firstOrNull()
                refreshChannelColorLabels(manId, option, newLayer)
            }
            "part" -> {
                if (state.mode == ControlMode.PART) {
                    cyclePart(layer, mannequin, state, backwards)?.let { updateStatus(it) }
                } else {
                    state.mode = ControlMode.PART
                    refreshChannelColorLabels(
                        manId,
                        mannequin.selection.selections[layer.id]?.option ?: layerManager.optionsFor(layer.id).firstOrNull(),
                        layer
                    )
                    updateStatus("Mode: Part")
                }
            }
            "channel" -> {
                val option = mannequin.selection.selections[layer.id]?.option ?: layerManager.optionsFor(layer.id).firstOrNull()
                val channels = option?.masks?.keys?.sorted() ?: emptyList()
                val channelDisabled = channels.size <= 1
                if (channels.isEmpty()) {
                    updateStatus("Channel: N/A")
                } else if (!channelDisabled) {
                    val delta = if (backwards) -1 else 1
                    val idx = (state.channelIndex.getOrDefault(layer.id, 0) + delta + channels.size) % channels.size
                    state.channelIndex[layer.id] = idx
                    val selectedChannel = channels[idx]
                    val currentSel = mannequin.selection.selections[layer.id]
                    if (currentSel != null) {
                        mannequin.selection = mannequin.selection.copy(
                            selections = mannequin.selection.selections + (layer.id to currentSel.copy(maskIndex = selectedChannel))
                        )
                    }
                    updateStatus("Channel: $selectedChannel")
                } else {
                    updateStatus("Channel: Locked")
                }
                refreshChannelColorLabels(manId, option, layer)
            }
            "color" -> {
                if (state.mode == ControlMode.COLOR) {
                    cycleColor(layer, mannequin, state, backwards)?.let { updateStatus(it) }
                } else {
                    state.mode = ControlMode.COLOR
                    refreshChannelColorLabels(
                        manId,
                        mannequin.selection.selections[layer.id]?.option ?: layerManager.optionsFor(layer.id).firstOrNull(),
                        layer
                    )
                    updateStatus("Mode: Color")
                }
            }
            "area" -> {
                // Big surrounding interaction – behaves like an air-click for the active mode
                when (state.mode) {
                    ControlMode.PART -> cyclePart(layer, mannequin, state, backwards)?.let { updateStatus(it) }
                    ControlMode.COLOR -> cycleColor(layer, mannequin, state, backwards)?.let { updateStatus(it) }
                    else -> {} // no active mode, ignore
                }
            }
        }
        // render to nearby players
        val viewers = plugin.server.onlinePlayers.filter {
            it.world == mannequin.location.world && it.location.distanceSquared(mannequin.location) <= VISIBLE_RANGE_SQ
        }
        render(mannequin, viewers)
    }

    fun handleEmptyClick(player: Player, backwards: Boolean) {
        val loc = player.location
        var nearestId: UUID? = null
        var nearestDist = Double.MAX_VALUE
        controlLocations.forEach { (manId, refs) ->
            refs.forEach { ref ->
                if (ref.location.world == loc.world) {
                    val d = ref.location.distanceSquared(loc)
                    if (d < nearestDist) {
                        nearestDist = d
                        nearestId = manId
                    }
                }
            }
        }
        val mannequinId = nearestId ?: return
        if (nearestDist > 25.0) return // >5 blocks
        val state = controlState[mannequinId] ?: return
        val mannequin = mannequins[mannequinId] ?: return
        val definitions = layerManager.definitionsInOrder()
        if (definitions.isEmpty()) return
        val layer = definitions.getOrNull(state.layerIndex % definitions.size) ?: definitions.first()

        when (state.mode) {
            ControlMode.PART -> cyclePart(layer, mannequin, state, backwards)?.let { statusText[mannequinId] = it; refreshStatusDisplays(mannequinId) }
            ControlMode.COLOR -> cycleColor(layer, mannequin, state, backwards)?.let { statusText[mannequinId] = it; refreshStatusDisplays(mannequinId) }
            else -> {}
        }
    }

    private fun logSampleColors(image: java.awt.image.BufferedImage) {
        val histo = mutableMapOf<Int, Int>()
        for (x in 0 until image.width) {
            for (y in 0 until image.height) {
                val argb = image.getRGB(x, y)
                if ((argb ushr 24) != 0) {
                    histo[argb] = (histo[argb] ?: 0) + 1
                }
            }
        }
        val totalNonZero = histo.values.sum()
        val top = histo.entries.sortedByDescending { it.value }.take(5)
        val topStr = top.joinToString { "${it.key.toUInt().toString(16)}:${it.value}" }
        plugin.logger.info("Composed image non-transparent pixels=$totalNonZero top=$topStr")
    }

    private fun dumpDebugImage(image: java.awt.image.BufferedImage) {
        try {
            val out = plugin.dataFolder.toPath().resolve("debug-composed.png")
            javax.imageio.ImageIO.write(image, "png", out.toFile())
            plugin.logger.info("Wrote debug composed skin to $out")
        } catch (ex: Exception) {
            plugin.logger.warning("Failed to write debug composed image: ${ex.message}")
        }
    }

    private fun refreshChannelColorLabels(mannequinId: UUID, option: LayerOption?, layer: LayerDefinition?) {
        val channels = option?.masks?.keys?.sorted() ?: emptyList()
        val channelDisabled = channels.size <= 1
        val channelText = "Channel"

        controlLocations[mannequinId]?.forEach { ref ->
            val world = ref.location.world ?: return@forEach
            world.getNearbyEntities(ref.location, 3.5, 3.5, 3.5).forEach {
                if (it is TextDisplay && it.scoreboardTags.contains("control:${ref.id}")) {
                    val mode = controlState[mannequinId]?.mode
                    if (it.scoreboardTags.contains("button:channel")) {
                        it.text(Component.text(channelText, if (channelDisabled) NamedTextColor.GRAY else NamedTextColor.WHITE))
                    }
                    if (it.scoreboardTags.contains("button:part")) {
                        val isActive = mode == ControlMode.PART
                        it.text(Component.text("Part", if (isActive) NamedTextColor.YELLOW else NamedTextColor.WHITE))
                    }
                    if (it.scoreboardTags.contains("button:color")) {
                        val isActive = mode == ControlMode.COLOR
                        it.text(Component.text("Color", if (isActive) NamedTextColor.YELLOW else NamedTextColor.WHITE))
                    }
                }
            }
        }
    }

    private fun refreshStatusDisplays(mannequinId: UUID) {
        val msg = statusText[mannequinId] ?: return
        controlLocations[mannequinId]?.forEach { ref ->
            val world = ref.location.world ?: return@forEach
            world.getNearbyEntities(ref.location, 3.5, 3.5, 3.5).forEach {
                if (it is TextDisplay && it.scoreboardTags.contains("button:status") && it.scoreboardTags.contains("control:${ref.id}")) {
                    it.text(Component.text(msg))
                }
            }
        }
    }

    private fun cyclePart(layer: LayerDefinition, mannequin: Mannequin, state: ControlState, backwards: Boolean): String? {
        val opts = layerManager.optionsFor(layer.id)
        if (opts.isEmpty()) return null
        val delta = if (backwards) -1 else 1
        val idx = (state.partIndex.getOrDefault(layer.id, 0) + delta + opts.size) % opts.size
        state.partIndex[layer.id] = idx
        val chosen = opts[idx]
        mannequin.selection = mannequin.selection.copy(
            selections = mannequin.selection.selections + (layer.id to LayerSelection(layer.id, chosen, null, maskIndex = null))
        )
        // reset per-part indices
        state.channelIndex[layer.id] = 0
        state.colorIndex[layer.id] = 0
        refreshChannelColorLabels(mannequin.id, chosen, layer)
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

        val selection = if (idx == 0) {
            current?.copy(colorMask = null, maskIndex = null)
                ?: LayerSelection(layer.id, option, null, maskIndex = null)
        } else {
            val color = colors.getOrNull(idx - 1)?.color
            current?.copy(colorMask = color, maskIndex = selectedChannel)
                ?: LayerSelection(layer.id, option, color, maskIndex = selectedChannel)
        }
        mannequin.selection = mannequin.selection.copy(
            selections = mannequin.selection.selections + (layer.id to selection)
        )
        return when (idx) {
            0 -> "Color: Default"
            else -> "Color: ${optionsList[idx]}"
        }
    }

    private fun prettyName(raw: String): String =
        raw.trim()
            .split(Regex("[_\\-\\s]+"))
            .filter { it.isNotBlank() }
            .joinToString(" ") { part ->
                part.lowercase().replaceFirstChar { ch -> ch.titlecase() }
            }
            .ifEmpty { raw }

    companion object {
        private const val VISIBLE_RANGE_SQ = 32.0 * 32.0
    }
}

