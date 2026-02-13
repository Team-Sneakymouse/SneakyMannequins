package com.sneakymannequins.managers

import com.sneakymannequins.SneakyMannequins
import com.sneakymannequins.model.LayerSelection
import com.sneakymannequins.model.Mannequin
import com.sneakymannequins.model.PixelFrame
import com.sneakymannequins.model.SkinSelection
import com.sneakymannequins.nms.VolatileHandler
import com.sneakymannequins.util.SkinComposer
import org.bukkit.Location
import org.bukkit.entity.Player
import java.util.UUID

class MannequinManager(
    private val plugin: SneakyMannequins,
    private val layerManager: LayerManager,
    private val handler: VolatileHandler
) {
    private val mannequins = mutableMapOf<UUID, Mannequin>()

    fun create(location: Location): Mannequin {
        val selection = bootstrapSelection()
        val mannequin = Mannequin(location = location.clone(), selection = selection)
        mannequins[mannequin.id] = mannequin
        return mannequin
    }

    fun get(id: UUID): Mannequin? = mannequins[id]

    fun render(mannequin: Mannequin, viewers: Collection<Player>): Int {
        val definitions = layerManager.definitionsInOrder()
        val composed = SkinComposer.compose(definitions, mannequin.selection)
        val nextFrame = PixelFrame.fromImage(composed)
        val changes = mannequin.lastFrame.diff(nextFrame)
        mannequin.lastFrame = nextFrame
        if (plugin.config.getBoolean("plugin.debug", false)) {
            plugin.logger.info("Rendering mannequin ${mannequin.id} with ${changes.size} pixel changes to ${viewers.size} viewers")
            logSampleColors(composed)
            dumpDebugImage(composed)
        }
        viewers.forEach { viewer ->
            handler.applyPixelChanges(viewer, mannequin.id, mannequin.location, changes)
        }
        return changes.size
    }

    fun remove(mannequinId: UUID, viewers: Collection<Player>) {
        mannequins.remove(mannequinId)
        viewers.forEach { viewer -> handler.destroyMannequin(viewer, mannequinId) }
    }

    fun shutdown() {
        val viewers = plugin.server.onlinePlayers
        mannequins.keys.forEach { id ->
            viewers.forEach { viewer -> handler.destroyMannequin(viewer, id) }
        }
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
}

