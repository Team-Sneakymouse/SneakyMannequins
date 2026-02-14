package com.sneakymannequins.managers

import com.sneakymannequins.SneakyMannequins
import com.sneakymannequins.model.ColorPalette
import com.sneakymannequins.model.NamedColor
import com.sneakymannequins.model.LayerDefinition
import com.sneakymannequins.model.LayerOption
import org.bukkit.configuration.ConfigurationSection
import java.awt.Color
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.name
import kotlin.math.pow
import kotlin.math.sqrt

class LayerManager(
    private val plugin: SneakyMannequins
) {
    private val loadedLayers = mutableMapOf<String, Pair<LayerDefinition, List<LayerOption>>>()
    private val layerOrder = mutableListOf<String>()
    private val palettes = mutableMapOf<String, ColorPalette>()

    fun reload() {
        loadedLayers.clear()
        layerOrder.clear()
        palettes.clear()
        // Always ensure bundled defaults exist in the data folder
        ensureDefaultSkinVariants()
        val root = plugin.config.getConfigurationSection("layers")
            ?: return seedDefaultBaseLayer()

        loadPalettes(root.getConfigurationSection("palettes"))

        val definitions = root.getConfigurationSection("definitions")
            ?: return seedDefaultBaseLayer()
        val configuredOrder = root.getStringList("order")
        if (configuredOrder.isNotEmpty()) {
            layerOrder.addAll(configuredOrder)
        } else {
            // Fallback: use definition keys if no explicit order
            layerOrder.addAll(definitions.getKeys(false))
        }

        layerOrder.forEach { layerId ->
            val definitionSection = definitions.getConfigurationSection(layerId)
            if (definitionSection == null) {
                plugin.logger.warning("Layer '$layerId' listed in order but has no definition.")
                return@forEach
            }
            val definition = definitionSection.toDefinition(plugin.dataFolder.toPath())
            val options = loadOptions(definition, definitionSection.getConfigurationSection("options"))
            loadedLayers[layerId] = definition to options
        }

        if (loadedLayers.isEmpty()) {
            seedDefaultBaseLayer()
        }
    }

    private fun seedDefaultBaseLayer() {
        plugin.logger.warning("No layers configured; seeding default base layer with bundled defaults")
        val baseDef = LayerDefinition(
            id = "base",
            displayName = "Base",
            // Read defaults directly from the plugin data root (default.png / default_slim.png)
            directory = plugin.dataFolder.toPath(),
            allowColorMask = false,
            defaultPalettes = emptyList()
        )
        val options = loadOptions(baseDef, null)
        layerOrder.clear()
        layerOrder.add(baseDef.id)
        loadedLayers.clear()
        loadedLayers[baseDef.id] = baseDef to options
    }

    fun definitionsInOrder(): List<LayerDefinition> =
        layerOrder.mapNotNull { loadedLayers[it]?.first }

    fun optionsFor(layerId: String): List<LayerOption> =
        loadedLayers[layerId]?.second.orEmpty()

    fun palette(id: String): ColorPalette? = palettes[id]

    private fun loadPalettes(section: ConfigurationSection?) {
        section ?: return
        section.getKeys(false).forEach { paletteId ->
            val paletteSection = section.getConfigurationSection(paletteId)
            val namedColors = if (paletteSection != null) {
                paletteSection.getKeys(false).mapNotNull { name ->
                    val hex = paletteSection.getString(name) ?: return@mapNotNull null
                    decodeColor(hex)?.let { color -> NamedColor(name, color) }
                        ?: run {
                            plugin.logger.warning("Invalid color '$hex' in palette '$paletteId' entry '$name'")
                            null
                        }
                }
            } else {
                // Fallback: allow legacy list format without names
                section.getStringList(paletteId).mapIndexedNotNull { idx, hex ->
                    decodeColor(hex)?.let { color -> NamedColor("color$idx", color) }
                        ?: run {
                            plugin.logger.warning("Invalid color '$hex' in palette '$paletteId'")
                            null
                        }
                }
            }
            if (namedColors.isNotEmpty()) {
                palettes[paletteId] = ColorPalette(paletteId, namedColors)
            } else {
                plugin.logger.warning("Palette '$paletteId' has no valid colors; skipping.")
            }
        }
    }

    private fun decodeColor(hex: String): Color? {
        return try {
            Color.decode("#${hex.trim('#')}")
        } catch (_: Exception) {
            null
        }
    }

    private fun loadOptions(definition: LayerDefinition, optionConfig: ConfigurationSection?): List<LayerOption> {
        val directory = definition.directory
        if (!Files.exists(directory)) {
            Files.createDirectories(directory)
        }

        // For the base layer, make sure the bundled defaults exist before we try to read them
        if (definition.id.equals("base", ignoreCase = true)) {
            ensureDefaultSkinVariants()
        }

        var options = loadLayerOptions(directory, definition, optionConfig)

        // For the base layer, also load defaults that live in the plugin data root
        if (definition.id.equals("base", ignoreCase = true)) {
            val existingIds = options.map { it.id.lowercase() }.toSet()
            val rootDefaults = listOf("default.png", "default_slim.png")
                .mapNotNull { loadOptionPair(plugin.dataFolder.toPath().resolve(it), definition, optionConfig) }
                .filterNot { existingIds.contains(it.id.lowercase()) }
            options = options + rootDefaults
        }

        return options
    }

    private fun loadLayerOptions(
        directory: Path,
        definition: LayerDefinition,
        optionConfig: ConfigurationSection?
    ): List<LayerOption> {
        val pngs = Files.list(directory).use { stream ->
            stream.iterator().asSequence()
                .filter { Files.isRegularFile(it) && it.fileName.toString().lowercase().endsWith(".png") }
                .toList()
        }

        val grouped = mutableMapOf<String, OptionAggregate>()
        pngs.forEach { path ->
            maybePreprocess(path)
            val rawName = path.nameWithoutExtension
            val base: String
            val variant: Variant
            when {
                rawName.endsWith("_slim", ignoreCase = true) -> {
                    base = rawName.dropLast(5)
                    variant = Variant.SLIM
                }
                rawName.endsWith("_default", ignoreCase = true) -> {
                    base = rawName.dropLast(8)
                    variant = Variant.DEFAULT
                }
                else -> {
                    base = rawName
                    variant = Variant.BOTH
                }
            }
            val id = slugify(base)
            val displayName = toDisplayName(base)
            val agg = grouped.getOrPut(id) { OptionAggregate(id, displayName) }
            when (variant) {
                Variant.DEFAULT -> agg.defaultPath = path
                Variant.SLIM -> agg.slimPath = path
                Variant.BOTH -> agg.sharedPath = path
            }
        }

        return grouped.values.mapNotNull { agg ->
            val allowedPalettes = optionConfig?.getStringList("${agg.id}.palettes")
                ?.takeIf { it.isNotEmpty() }
                ?: definition.defaultPalettes

            val defaultPath = agg.defaultPath ?: agg.sharedPath
            val slimPath = agg.slimPath ?: agg.sharedPath
            val defaultImage = defaultPath?.let { loadImage(it, definition.id) }
            val slimImage = slimPath?.let { loadImage(it, definition.id) }

            if (defaultImage == null && slimImage == null) {
                plugin.logger.warning("Layer ${definition.id} option ${agg.id} has no readable images; skipping.")
                return@mapNotNull null
            }

            val masks = buildMaskMap(directory, agg)

            LayerOption(
                id = agg.id,
                displayName = agg.displayName,
                fileDefault = defaultPath,
                fileSlim = slimPath,
                imageDefault = defaultImage,
                imageSlim = slimImage,
                allowedPalettes = allowedPalettes,
                masks = masks
            )
        }
    }

    private fun loadOptionPair(path: Path, definition: LayerDefinition, optionConfig: ConfigurationSection?): LayerOption? {
        val base = path.nameWithoutExtension
        val id = slugify(base)
        val displayName = toDisplayName(base)
        val allowedPalettes = optionConfig?.getStringList("$id.palettes")
            ?.takeIf { it.isNotEmpty() }
            ?: definition.defaultPalettes

        val image = loadImage(path, definition.id) ?: return null
        return LayerOption(
            id = id,
            displayName = displayName,
            fileDefault = path,
            fileSlim = path,
            imageDefault = image,
            imageSlim = image,
            allowedPalettes = allowedPalettes
        )
    }

    private fun buildMaskMap(directory: Path, agg: OptionAggregate): Map<Int, Path> {
        val baseNames = listOfNotNull(agg.defaultPath, agg.slimPath, agg.sharedPath).map { it.nameWithoutExtension }.toSet()
        val maskFiles = Files.list(directory).use { stream ->
            stream.iterator().asSequence()
                .filter { Files.isRegularFile(it) && it.fileName.toString().lowercase().endsWith(".png") }
                .filter { path ->
                    val name = path.nameWithoutExtension.lowercase()
                    baseNames.any { base -> name.startsWith("${base.lowercase()}_mask_") }
                }
                .toList()
        }
        return maskFiles.mapNotNull { path ->
            val name = path.nameWithoutExtension
            val idxPart = name.substringAfterLast("_mask_", missingDelimiterValue = "")
            val idx = idxPart.toIntOrNull() ?: return@mapNotNull null
            idx to path
        }.toMap()
    }

    private fun loadImage(path: Path, layerId: String): java.awt.image.BufferedImage? {
        return try {
            val image = ImageIO.read(path.toFile()) ?: return null
            if (image.width != 64 || image.height != 64) {
                plugin.logger.severe("Layer $layerId option ${path.fileName} is not 64x64. Skipping.")
                return null
            }
            if (!imageHasNonTransparentPixels(image)) {
                plugin.logger.warning("Layer $layerId option ${path.fileName} is fully transparent; skipping.")
                return null
            }
            image
        } catch (ex: Exception) {
            plugin.logger.severe("Failed to load layer option from $path: ${ex.message}")
            null
        }
    }

    private data class OptionAggregate(
        val id: String,
        val displayName: String,
        var defaultPath: Path? = null,
        var slimPath: Path? = null,
        var sharedPath: Path? = null
    )

    private enum class Variant { DEFAULT, SLIM, BOTH }

    private fun slugify(raw: String): String =
        raw.trim().lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifEmpty { "option" }

    private fun toDisplayName(raw: String): String =
        raw.trim().split(Regex("[_\\-\\s]+")).filter { it.isNotBlank() }.joinToString(" ") { part ->
            part.lowercase().replaceFirstChar { ch -> ch.titlecase() }
        }.ifEmpty { "Option" }

    private fun maybePreprocess(path: Path) {
        if (!plugin.config.getBoolean("plugin.preprocessing.enabled", true)) return
        val fileName = path.nameWithoutExtension
        val mask1 = path.parent.resolve("${fileName}_mask_1.png")
        if (Files.exists(mask1)) return

        preprocessImage(path)
    }

    private fun preprocessImage(sourcePath: Path) {
        val image = ImageIO.read(sourcePath.toFile()) ?: return
        val sanitized = sanitizeUv(image)

        val config = plugin.config
        val maxClusters = config.getInt("plugin.preprocessing.max-clusters", 8).coerceAtLeast(1)
        val colorThreshold = config.getInt("plugin.preprocessing.color-distance-threshold", 24).coerceAtLeast(1)
        val minClusterSize = config.getInt("plugin.preprocessing.min-cluster-size", 3).coerceAtLeast(1)

        val clusters = clusterColors(sanitized, colorThreshold, minClusterSize, maxClusters)
        writeMasks(sourcePath, sanitized, clusters)
        // overwrite source with sanitized (remove UV junk)
        ImageIO.write(sanitized, "png", sourcePath.toFile())
    }

    private fun sanitizeUv(image: java.awt.image.BufferedImage): java.awt.image.BufferedImage {
        val out = java.awt.image.BufferedImage(image.width, image.height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        for (x in 0 until image.width) {
            for (y in 0 until image.height) {
                val argb = image.getRGB(x, y)
                if ((argb ushr 24) == 0) continue
                if (isInUv(x, y)) {
                    out.setRGB(x, y, argb)
                }
            }
        }
        return out
    }

    private fun isInUv(x: Int, y: Int): Boolean {
        // Vanilla 64x64 skin layout (both layers)
        // Head: base 0..31 x 0..15, overlay 32..63 x 0..15
        // Body: base 16..39 x 16..31, overlay 16..55 x 32..47
        // Right arm: base 40..55 x 16..31, overlay 40..55 x 32..47
        // Left arm:  same as right arm but 48..63 x 48..63 overlay (for legacy) omitted; use 32..47 x 48..63? (vanilla uses mirrored)
        // Legs: base 0..15 x 16..31 and 0..15 x 32..47 overlay; left leg at 16..31 x 48..63 overlay region
        // We’ll accept all standard second-layer regions:
        val regions = listOf(
            // Head base
            Rect(0, 0, 32, 16),
            // Head overlay
            Rect(32, 0, 64, 16),
            // Body base
            Rect(16, 16, 40, 32),
            // Body overlay
            Rect(16, 32, 56, 48),
            // Right arm base
            Rect(40, 16, 56, 32),
            // Right arm overlay
            Rect(40, 32, 56, 48),
            // Left arm base/overlay area (mirrored; accept both)
            Rect(32, 48, 48, 64),
            Rect(48, 48, 64, 64),
            // Right leg base
            Rect(0, 16, 16, 32),
            // Right leg overlay
            Rect(0, 32, 16, 48),
            // Left leg base/overlay area
            Rect(16, 48, 32, 64)
        )
        return regions.any { it.contains(x, y) }
    }

    private data class Rect(val x0: Int, val y0: Int, val x1: Int, val y1: Int) {
        fun contains(x: Int, y: Int): Boolean = x in x0 until x1 && y in y0 until y1
    }

    private data class Cluster(var r: Long, var g: Long, var b: Long, var count: Int, val pixels: MutableList<Pair<Int, Int>>)

    private fun clusterColors(
        image: java.awt.image.BufferedImage,
        threshold: Int,
        minClusterSize: Int,
        maxClusters: Int
    ): List<Cluster> {
        val clusters = mutableListOf<Cluster>()
        for (x in 0 until image.width) {
            for (y in 0 until image.height) {
                val argb = image.getRGB(x, y)
                val a = argb ushr 24 and 0xFF
                if (a == 0) continue
                val r = argb ushr 16 and 0xFF
                val g = argb ushr 8 and 0xFF
                val b = argb and 0xFF
                val match = clusters.firstOrNull { dist(it.centroidR(), it.centroidG(), it.centroidB(), r, g, b) <= threshold }
                if (match != null) {
                    match.r += r
                    match.g += g
                    match.b += b
                    match.count += 1
                    match.pixels += x to y
                } else if (clusters.size < maxClusters) {
                    clusters += Cluster(r.toLong(), g.toLong(), b.toLong(), 1, mutableListOf(x to y))
                }
            }
        }
        return clusters
            .filter { it.count >= minClusterSize }
            .sortedByDescending { it.count }
            .take(maxClusters)
    }

    private fun Cluster.centroidR() = (r / count).toInt()
    private fun Cluster.centroidG() = (g / count).toInt()
    private fun Cluster.centroidB() = (b / count).toInt()

    private fun dist(r1: Int, g1: Int, b1: Int, r2: Int, g2: Int, b2: Int): Double {
        val dr = (r1 - r2).toDouble()
        val dg = (g1 - g2).toDouble()
        val db = (b1 - b2).toDouble()
        return sqrt(dr.pow(2) + dg.pow(2) + db.pow(2))
    }

    private fun writeMasks(sourcePath: Path, sanitized: java.awt.image.BufferedImage, clusters: List<Cluster>) {
        clusters.forEachIndexed { idx, cluster ->
            val mask = java.awt.image.BufferedImage(sanitized.width, sanitized.height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
            cluster.pixels.forEach { (x, y) ->
                val alpha = sanitized.getRGB(x, y) ushr 24 and 0xFF
                val value = (alpha shl 24) or 0x00FFFFFF
                mask.setRGB(x, y, value)
            }
            val outPath = sourcePath.parent.resolve("${sourcePath.nameWithoutExtension}_mask_${idx + 1}.png")
            ImageIO.write(mask, "png", outPath.toFile())
        }
    }

    private fun imageHasNonTransparentPixels(image: java.awt.image.BufferedImage): Boolean {
        val data = IntArray(image.width * image.height)
        image.getRGB(0, 0, image.width, image.height, data, 0, image.width)
        return data.any { (it ushr 24) != 0 }
    }

    private fun ensureDefaultSkinVariants(): Map<String, Path> {
        val seeds = mutableMapOf<String, Path>()
        copyResourceIfMissing("default.png")?.let { seeds["default"] = it }
        copyResourceIfMissing("default_slim.png")?.let { seeds["default_slim"] = it }
        return seeds
    }

    private fun copyResourceIfMissing(resourceName: String): Path? {
        val target = plugin.dataFolder.toPath().resolve(resourceName)
        if (!java.nio.file.Files.exists(target)) {
            val stream = plugin.getResource(resourceName)
            if (stream == null) {
                plugin.logger.warning("$resourceName not found in resources; cannot seed default skin variant")
                return null
            }
            stream.use { input ->
                java.nio.file.Files.createDirectories(target.parent)
                java.nio.file.Files.copy(input, target)
                plugin.logger.info("Seeded $resourceName at $target")
            }
        }
        return target
    }

    private fun ConfigurationSection.toDefinition(dataFolder: Path): LayerDefinition {
        val id = this.name
        val displayName = getString("display-name", id) ?: id
        val allowMask = getBoolean("allow-color-mask", false)
        val defaultPalettes = getStringList("default-palettes")
        val directory = dataFolder.resolve(getString("directory", "layers/$id") ?: "layers/$id").normalize()
        return LayerDefinition(
            id = id,
            displayName = displayName,
            directory = directory,
            allowColorMask = allowMask,
            defaultPalettes = defaultPalettes
        )
    }
}

