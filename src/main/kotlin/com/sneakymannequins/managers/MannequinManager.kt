package com.sneakymannequins.managers

import com.sneakymannequins.SneakyMannequins
import com.sneakymannequins.model.LayerSelection
import com.sneakymannequins.model.Mannequin
import com.sneakymannequins.model.PixelChange
import com.sneakymannequins.model.PixelFrame
import com.sneakymannequins.model.SkinSelection
import com.sneakymannequins.nms.VolatileHandler
import com.sneakymannequins.render.PixelProjector
import com.sneakymannequins.util.SkinComposer
import org.bukkit.Location
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Player
import org.bukkit.util.Vector
import java.util.UUID

class MannequinManager(
    private val plugin: SneakyMannequins,
    private val layerManager: LayerManager,
    private val handler: VolatileHandler,
    private val persistence: MannequinPersistence
) {
    private val mannequins = mutableMapOf<UUID, Mannequin>()
    private val controlLocations = mutableMapOf<UUID, MutableList<Location>>()
    private val sentTo = mutableMapOf<UUID, MutableSet<UUID>>() // viewerId -> mannequins seen

    fun loadFromDisk() {
        val (loaded, controls) = persistence.load()
        controlLocations.clear()
        controls.forEach { (id, list) -> controlLocations[id] = list.toMutableList() }
        loaded.forEach { (id, loc) ->
            val selection = bootstrapSelection()
            mannequins[id] = Mannequin(id = id, location = loc.clone(), selection = selection)
            spawnControlsIfMissing(id)
        }
    }

    fun persist() {
        persistence.save(mannequins.values, controlLocations)
    }

    fun create(location: Location): Mannequin {
        val selection = bootstrapSelection()
        val mannequin = Mannequin(location = location.clone(), selection = selection)
        mannequins[mannequin.id] = mannequin
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
        val composed = SkinComposer.compose(definitions, mannequin.selection)
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
            scaleMultiplier = handler.pixelScaleMultiplier()
        )
        viewers.forEach { viewer ->
            handler.applyProjectedPixels(viewer, mannequin.id, projected)
        }
        return diff.size
    }

    private fun renderFull(mannequin: Mannequin, viewers: Collection<Player>) {
        val definitions = layerManager.definitionsInOrder()
        val composed = SkinComposer.compose(definitions, mannequin.selection)
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
            scaleMultiplier = handler.pixelScaleMultiplier()
        )
        viewers.forEach { viewer -> handler.applyProjectedPixels(viewer, mannequin.id, projected) }
    }

    fun remove(mannequinId: UUID, viewers: Collection<Player>) {
        mannequins.remove(mannequinId)
        viewers.forEach { viewer -> handler.destroyMannequin(viewer, mannequinId) }
        cleanupControls(mannequinId)
        controlLocations.remove(mannequinId)
        persist()
    }

    fun shutdown() {
        val viewers = plugin.server.onlinePlayers
        mannequins.keys.forEach { id ->
            viewers.forEach { viewer -> handler.destroyMannequin(viewer, id) }
        }
        persist()
        mannequins.clear()
    }

    private fun bootstrapSelection(): SkinSelection {
        val definitions = layerManager.definitionsInOrder()
        val preferredModel = plugin.config.getString("plugin.default-skin-model", "CLASSIC")?.uppercase() ?: "CLASSIC"
        val selections = definitions.associate { def ->
            val options = layerManager.optionsFor(def.id)
            val chosen = when {
                def.id.equals("base", ignoreCase = true) && preferredModel == "SLIM" ->
                    options.firstOrNull { it.id.equals("alex_slim", ignoreCase = true) } ?: options.firstOrNull()
                def.id.equals("base", ignoreCase = true) ->
                    options.firstOrNull { it.id.equals("steve", ignoreCase = true) } ?: options.firstOrNull()
                else -> options.firstOrNull()
            }
            def.id to LayerSelection(
                layerId = def.id,
                option = chosen,
                colorMask = def.defaultColorMask
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
        controlLocations.computeIfAbsent(mannequin.id) { mutableListOf() }.add(loc)
        spawnControls(mannequin.id, loc)
        persist()
    }

    fun removeNearestControl(at: Location, radius: Double = 10.0): Boolean {
        var best: Pair<UUID, Int>? = null
        var bestDist = Double.MAX_VALUE
        controlLocations.forEach { (manId, list) ->
            list.forEachIndexed { idx, loc ->
                if (loc.world == at.world) {
                    val d = loc.distance(at)
                    if (d <= radius && d < bestDist) {
                        bestDist = d
                        best = manId to idx
                    }
                }
            }
        }
        val found = best ?: return false
        val list = controlLocations[found.first] ?: return false
        val loc = list[found.second]
        cleanupControls(found.first, loc)
        list.removeAt(found.second)
        if (list.isEmpty()) {
            controlLocations.remove(found.first)
        }
        persist()
        return true
    }

    private fun spawnControlsIfMissing(mannequinId: UUID) {
        val list = controlLocations[mannequinId] ?: return
        list.forEach { loc ->
            val world = loc.world ?: return@forEach
            val existing = world.getNearbyEntities(loc, 1.0, 1.0, 1.0).firstOrNull {
                it.scoreboardTags.contains("sneakymannequin_control") && it.scoreboardTags.contains("mannequin:$mannequinId")
            }
            if (existing == null) {
                spawnControls(mannequinId, loc)
            }
        }
    }

    private fun spawnControls(mannequinId: UUID, loc: Location) {
        val world = loc.world ?: return
        val stand = world.spawn(loc, ArmorStand::class.java) { asd ->
            asd.isInvisible = true
            asd.isMarker = true
            asd.isSmall = true
            asd.setGravity(false)
            asd.customName = "Mannequin Controls"
            asd.isCustomNameVisible = true
            asd.scoreboardTags.add("sneakymannequin_control")
            asd.scoreboardTags.add("mannequin:$mannequinId")
        }
        mannequins[mannequinId]?.let { man ->
            val dir: Vector = man.location.toVector().subtract(loc.toVector())
            val yaw = Math.toDegrees(Math.atan2(-dir.x, dir.z)).toFloat()
            stand.teleport(loc.clone().apply { this.yaw = yaw })
        }
    }

    private fun cleanupControls(mannequinId: UUID) {
        val list = controlLocations[mannequinId] ?: emptyList()
        list.forEach { loc -> cleanupControls(mannequinId, loc) }
        // Also remove any stray tagged entities nearby the mannequin origin as a fallback
        mannequins[mannequinId]?.let { man ->
            val world = man.location.world ?: return@let
            world.getNearbyEntities(man.location, 10.0, 10.0, 10.0).forEach {
                if (it.scoreboardTags.contains("sneakymannequin_control") && it.scoreboardTags.contains("mannequin:$mannequinId")) {
                    it.remove()
                }
            }
        }
    }

    private fun cleanupControls(mannequinId: UUID, loc: Location) {
        val world = loc.world ?: return
        world.getNearbyEntities(loc, 5.0, 5.0, 5.0).forEach {
            if (it.scoreboardTags.contains("sneakymannequin_control") && it.scoreboardTags.contains("mannequin:$mannequinId")) {
                it.remove()
            }
        }
    }

    private fun computeControlYaw(controlLoc: Location, mannequinLoc: Location): Float {
        val dir: Vector = mannequinLoc.toVector().subtract(controlLoc.toVector())
        return Math.toDegrees(Math.atan2(-dir.x, dir.z)).toFloat()
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

    companion object {
        private const val VISIBLE_RANGE_SQ = 32.0 * 32.0
    }
}

