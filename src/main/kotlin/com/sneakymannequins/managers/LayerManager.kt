package com.sneakymannequins.managers

import com.sneakymannequins.SneakyMannequins
import com.sneakymannequins.model.ColorPalette
import com.sneakymannequins.model.NamedColor
import com.sneakymannequins.model.LayerDefinition
import com.sneakymannequins.model.LayerOption
import com.sneakymannequins.model.PaletteRef
import com.sneakymannequins.model.PaletteSpec
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.Player
import java.awt.Color
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.name

class LayerManager(
    private val plugin: SneakyMannequins
) {
    private val loadedLayers = mutableMapOf<String, Pair<LayerDefinition, List<LayerOption>>>()
    private val layerOrder = mutableListOf<String>()
    private val palettes = mutableMapOf<String, ColorPalette>()
    private var defaultPaletteSpec = PaletteSpec.INHERIT

    fun reload() {
        loadedLayers.clear()
        layerOrder.clear()
        palettes.clear()
        defaultPaletteSpec = PaletteSpec.INHERIT
        val root = plugin.config.getConfigurationSection("layers") ?: run {
            plugin.logger.warning("No 'layers' section found in config.")
            return
        }

        loadPalettes(root.getConfigurationSection("palettes"))

        val definitions = root.getConfigurationSection("definitions") ?: run {
            plugin.logger.warning("No layer definitions found in config.")
            return
        }
        defaultPaletteSpec = parsePaletteSpec(definitions)
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

        return loadLayerOptions(directory, definition, optionConfig)
    }

    private fun loadLayerOptions(
        directory: Path,
        definition: LayerDefinition,
        optionConfig: ConfigurationSection?
    ): List<LayerOption> {
        val pngs = Files.list(directory).use { stream ->
            stream.iterator().asSequence()
                .filter { Files.isRegularFile(it) && it.fileName.toString().lowercase().endsWith(".png") }
                .filterNot { it.fileName.toString().lowercase().matches(Regex(".*_mask_\\d+\\.png")) }
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
            val optSpec = optionConfig?.getConfigurationSection(agg.id)
                ?.let { parsePaletteSpec(it) }
                ?: PaletteSpec.INHERIT

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
                paletteSpec = optSpec,
                masks = masks
            )
        }
    }

    private fun loadOptionPair(path: Path, definition: LayerDefinition, optionConfig: ConfigurationSection?): LayerOption? {
        val base = path.nameWithoutExtension
        val id = slugify(base)
        val displayName = toDisplayName(base)
        val optSpec = optionConfig?.getConfigurationSection(id)
            ?.let { parsePaletteSpec(it) }
            ?: PaletteSpec.INHERIT

        val image = loadImage(path, definition.id) ?: return null
        return LayerOption(
            id = id,
            displayName = displayName,
            fileDefault = path,
            fileSlim = path,
            imageDefault = image,
            imageSlim = image,
            paletteSpec = optSpec
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
            if (!isSlimAsset(path)) fixSlimArmGaps(image)
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
        if (path.fileName.toString().lowercase().matches(Regex(".*_mask_\\d+\\.png"))) return
        val mask1 = path.parent.resolve("${fileName}_mask_1.png")
        if (Files.exists(mask1)) return

        preprocessImage(path)
    }

    private fun preprocessImage(sourcePath: Path, strategy: MaskStrategy = defaultStrategy(), k: Int = defaultChannels()) {
        val image = ImageIO.read(sourcePath.toFile()) ?: return
        val sanitized = sanitizeUv(image)
        if (!isSlimAsset(sourcePath)) fixSlimArmGaps(sanitized)

        val clusters = clusterColors(sanitized, strategy, k)
        writeMasks(sourcePath, sanitized, clusters)
        // overwrite source with sanitized (remove UV junk)
        ImageIO.write(sanitized, "png", sourcePath.toFile())
    }

    /**
     * Re-mask a specific part with the given strategy.
     * Deletes existing masks, re-preprocesses, and reloads the layer.
     * Returns a human-readable status message.
     */
    fun remask(strategy: MaskStrategy? = null, layerId: String, partId: String, channels: Int? = null): String {
        @Suppress("NAME_SHADOWING")
        val strategy = strategy ?: defaultStrategy()
        val k = (channels ?: defaultChannels()).coerceIn(1, 8)
        val (definition, options) = loadedLayers[layerId]
            ?: return "Unknown layer: $layerId"

        val option = options.find { it.id.equals(partId, ignoreCase = true) }
            ?: return "Unknown part '$partId' in layer '$layerId'"

        // Find the source image path(s)
        val sourcePaths = listOfNotNull(option.fileDefault, option.fileSlim).distinct()
        if (sourcePaths.isEmpty()) return "No image files for part '$partId'"

        var totalMasks = 0
        for (srcPath in sourcePaths) {
            // Delete existing mask files
            val baseName = srcPath.nameWithoutExtension
            val dir = srcPath.parent
            Files.list(dir).use { stream ->
                stream.iterator().asSequence()
                    .filter { it.name.matches(Regex("${Regex.escape(baseName)}_mask_\\d+\\.png")) }
                    .forEach { Files.deleteIfExists(it) }
            }

            // Re-preprocess with the chosen strategy
            preprocessImage(srcPath, strategy, k)

            // Count generated masks
            totalMasks += Files.list(dir).use { stream ->
                stream.iterator().asSequence()
                    .filter { it.name.matches(Regex("${Regex.escape(baseName)}_mask_\\d+\\.png")) }
                    .count()
            }
        }

        // Reload this layer so the new masks are picked up in memory
        reloadLayer(layerId)

        return "Remasked '$partId' in '$layerId' using ${strategy.name} (k=$k): $totalMasks mask(s) generated"
    }

    /**
     * Reload a single layer's options (re-reads files from disk).
     */
    private fun reloadLayer(layerId: String) {
        val (definition, _) = loadedLayers[layerId] ?: return
        val root = plugin.config.getConfigurationSection("layers") ?: return
        val definitions = root.getConfigurationSection("definitions") ?: return
        val optionConfig = definitions.getConfigurationSection(layerId)?.getConfigurationSection("options")
        val newOptions = loadOptions(definition, optionConfig)
        loadedLayers[layerId] = definition to newOptions
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
            // Left leg base
            Rect(16, 48, 32, 64),
            // Left leg overlay
            Rect(0, 48, 16, 64)
        )
        return regions.any { it.contains(x, y) }
    }

    private data class Rect(val x0: Int, val y0: Int, val x1: Int, val y1: Int) {
        fun contains(x: Int, y: Int): Boolean = x in x0 until x1 && y in y0 until y1
    }

    // ── Slim → default arm-column fix ─────────────────────────────────────

    /**
     * Describes one arm's UV region.  All four arm textures (right/left ×
     * base/overlay) share the same internal layout, offset by [frontX].
     *
     * Relative to [frontX] (abbreviated `fx`):
     * ```
     * Top row (topY):   [Top fx..fx+2] [Bottom fx+3..fx+5]   (slim, 3px wide)
     *                   [Top fx..fx+3] [Bottom fx+4..fx+7]   (default, 4px wide)
     *
     * Main row (mainY): [Front fx..fx+2] [Side fx+3..fx+6] [Back fx+7..fx+9]  (slim)
     *                   [Front fx..fx+3] [Side fx+4..fx+7] [Back fx+8..fx+11] (default)
     * ```
     * The depth side faces (right side: fx-4..fx-1) are 4px in both models.
     */
    private data class ArmRegion(
        val frontX: Int,
        val mainY: IntRange,
        val topY: IntRange
    )

    private val SLIM_ARM_REGIONS = listOf(
        ArmRegion(frontX = 44, mainY = 20..31, topY = 16..19),   // right arm base
        ArmRegion(frontX = 44, mainY = 36..47, topY = 32..35),   // right arm overlay
        ArmRegion(frontX = 36, mainY = 52..63, topY = 48..51),   // left arm base
        ArmRegion(frontX = 52, mainY = 52..63, topY = 48..51),   // left arm overlay
    )

    /**
     * Detect and fix slim arm-texture artefacts in a non-`_slim` asset.
     *
     * Two authoring styles are handled:
     *
     * **Type A – Default UV, 4th column empty.**
     * The texture uses default-model UV positions but only fills 3 of the 4
     * width-columns on each arm face.  Fix: stretch the 3rd column into the
     * 4th for every affected face (front, top, bottom, back).
     *
     * **Type B – Shifted UV (true slim layout).**
     * The side, bottom, and back faces start 1 pixel earlier because
     * `side_start = front_x + 3` instead of `front_x + 4`.  Fix: shift
     * those faces right by 1, then stretch the last width-column to fill
     * the new 4th position.
     */
    private fun fixSlimArmGaps(image: java.awt.image.BufferedImage) {
        for (arm in SLIM_ARM_REGIONS) {
            val fx = arm.frontX

            // ── Detect Type B (shifted UV) ──────────────────────────────
            // Indicators: the front-gap column (fx+3) has data (side face
            // shifted there), the slim back start (fx+7) has data, and the
            // default back-end column (fx+11) is empty.
            val frontGapHasData  = arm.mainY.any { y -> (image.getRGB(fx + 3, y) ushr 24) != 0 }
            val slimBackHasData  = arm.mainY.any { y -> (image.getRGB(fx + 7, y) ushr 24) != 0 }
            val defaultBackEmpty = arm.mainY.all { y -> (image.getRGB(fx + 11, y) ushr 24) == 0 }

            if (frontGapHasData && slimBackHasData && defaultBackEmpty) {
                remapShiftedSlim(image, arm)
                continue
            }

            // ── Type A: simple gap-fills ─────────────────────────────────
            // Front + top face gap (fx+3)
            fillGapColumn(image, fx + 3, fx + 2, fx + 4, arm.mainY)
            fillGapColumn(image, fx + 3, fx + 2, fx + 4, arm.topY)
            // Bottom face gap (fx+7)
            fillGapColumn(image, fx + 7, fx + 6, fx + 6, arm.topY)
            // Back face gap (fx+11)
            fillGapColumn(image, fx + 11, fx + 10, fx + 8, arm.mainY)
        }
    }

    /** Stretch a single gap column if it is fully transparent and the
     *  verification column confirms data exists. */
    private fun fillGapColumn(
        image: java.awt.image.BufferedImage,
        gapX: Int, sourceX: Int, verifyX: Int, yRange: IntRange
    ) {
        if (gapX >= image.width || sourceX >= image.width) return
        val gapEmpty     = yRange.all { y -> (image.getRGB(gapX, y) ushr 24) == 0 }
        if (!gapEmpty) return
        val verifyFilled = yRange.any { y -> (image.getRGB(verifyX, y) ushr 24) != 0 }
        if (!verifyFilled) return
        for (y in yRange) {
            image.setRGB(gapX, y, image.getRGB(sourceX, y))
        }
    }

    /**
     * Full remap for a Type-B slim arm: every face after the front is
     * shifted 1 pixel to the left of where the default model expects it.
     *
     * Operations are ordered right-to-left to avoid overwriting:
     * 1. Back face (3 cols at fx+7) → shift to fx+8, stretch to fx+11
     * 2. Side face (4 cols at fx+3) → shift to fx+4
     * 3. Front gap → stretch fx+2 into fx+3
     * Same pattern for the top row (bottom face + top gap).
     */
    private fun remapShiftedSlim(image: java.awt.image.BufferedImage, arm: ArmRegion) {
        val fx = arm.frontX

        // ── Main row (front / side / back) ──────────────────────────────

        // 1. Back face: read 3 cols at fx+7..fx+9, write to fx+8..fx+10,
        //    stretch last column to fx+11.
        for (y in arm.mainY) {
            val b1 = image.getRGB(fx + 7, y)
            val b2 = image.getRGB(fx + 8, y)
            val b3 = image.getRGB(fx + 9, y)
            image.setRGB(fx + 8,  y, b1)
            image.setRGB(fx + 9,  y, b2)
            image.setRGB(fx + 10, y, b3)
            image.setRGB(fx + 11, y, b3)   // stretch
        }

        // 2. Side face: 4 cols at fx+3..fx+6 → shift right to fx+4..fx+7.
        //    Process right-to-left so each read happens before the write
        //    that would overwrite it.
        for (y in arm.mainY) {
            for (col in (fx + 6) downTo (fx + 3)) {
                image.setRGB(col + 1, y, image.getRGB(col, y))
            }
        }

        // 3. Front gap: stretch fx+2 into fx+3.
        for (y in arm.mainY) {
            image.setRGB(fx + 3, y, image.getRGB(fx + 2, y))
        }

        // ── Top row (top / bottom) ──────────────────────────────────────

        // 1. Bottom face: 3 cols at fx+3..fx+5 → shift to fx+4..fx+6,
        //    stretch to fx+7.
        for (y in arm.topY) {
            val b1 = image.getRGB(fx + 3, y)
            val b2 = image.getRGB(fx + 4, y)
            val b3 = image.getRGB(fx + 5, y)
            image.setRGB(fx + 4, y, b1)
            image.setRGB(fx + 5, y, b2)
            image.setRGB(fx + 6, y, b3)
            image.setRGB(fx + 7, y, b3)   // stretch
        }

        // 2. Top gap: stretch fx+2 into fx+3.
        for (y in arm.topY) {
            image.setRGB(fx + 3, y, image.getRGB(fx + 2, y))
        }
    }

    private fun isSlimAsset(path: Path): Boolean =
        path.nameWithoutExtension.endsWith("_slim", ignoreCase = true)

    // ── Masking strategies ────────────────────────────────────────────────

    enum class MaskStrategy { HSB, HUE, RGB }

    companion object {
        /** All valid strategy names for tab-completion and config validation. */
        val STRATEGY_NAMES: List<String> = MaskStrategy.entries.map { it.name }
    }

    private data class Cluster(val pixels: MutableList<Pair<Int, Int>>)

    /** Pixel with full HSB + RGB + position, shared across strategies. */
    private data class ColorPixel(
        val h: Float, val s: Float, val b: Float,
        val r: Int, val g: Int, val bl: Int,
        val x: Int, val y: Int
    )

    /** Returns the configured default masking strategy. */
    fun defaultStrategy(): MaskStrategy {
        val name = plugin.config.getString("plugin.preprocessing.default-strategy", "HSB") ?: "HSB"
        return try { MaskStrategy.valueOf(name.uppercase()) } catch (_: Exception) { MaskStrategy.HSB }
    }

    /** Returns the configured default number of colour channels (masks) per part. */
    fun defaultChannels(): Int {
        return plugin.config.getInt("plugin.preprocessing.default-channels", 2).coerceIn(1, 8)
    }

    /** Collect all chromatic (non-neutral) pixels from an image. */
    private fun collectChromatic(image: java.awt.image.BufferedImage): List<ColorPixel> {
        val neutralSat    = plugin.config.getDouble("plugin.preprocessing.neutral-saturation", 0.12).toFloat()
        val neutralBriLow = plugin.config.getDouble("plugin.preprocessing.neutral-brightness-low", 0.10).toFloat()
        val result = mutableListOf<ColorPixel>()
        for (x in 0 until image.width) {
            for (y in 0 until image.height) {
                val argb = image.getRGB(x, y)
                if ((argb ushr 24 and 0xFF) == 0) continue
                val r = argb ushr 16 and 0xFF
                val g = argb ushr 8 and 0xFF
                val bl = argb and 0xFF
                val hsb = java.awt.Color.RGBtoHSB(r, g, bl, null)
                if (hsb[1] < neutralSat || hsb[2] < neutralBriLow) continue
                result += ColorPixel(hsb[0], hsb[1], hsb[2], r, g, bl, x, y)
            }
        }
        return result
    }

    private fun clusterColors(image: java.awt.image.BufferedImage, strategy: MaskStrategy = defaultStrategy(), k: Int = defaultChannels()): List<Cluster> {
        val chromatic = collectChromatic(image)
        if (chromatic.isEmpty()) return emptyList()
        if (chromatic.size == 1 || k <= 1) return listOf(Cluster(mutableListOf<Pair<Int,Int>>().apply { chromatic.forEach { add(it.x to it.y) } }))
        val effectiveK = k.coerceAtMost(chromatic.size)
        return when (strategy) {
            MaskStrategy.HSB -> clusterKMeansHsb(chromatic, effectiveK)
            MaskStrategy.HUE -> clusterHueGap(chromatic, effectiveK)
            MaskStrategy.RGB -> clusterKMeansRgb(chromatic, effectiveK)
        }
    }

    // ── Strategy: k-means in HSB space (circular hue) ───────────────────

    private fun clusterKMeansHsb(chromatic: List<ColorPixel>, k: Int): List<Cluster> {
        fun distSq(p: ColorPixel, ch: Float, cs: Float, cb: Float): Float {
            val hDiff = kotlin.math.abs(p.h - ch)
            val hDist = kotlin.math.min(hDiff, 1f - hDiff)
            val sDist = p.s - cs; val bDist = p.b - cb
            return hDist * hDist + sDist * sDist + bDist * bDist
        }
        fun circularMeanHue(pixels: List<ColorPixel>): Float {
            var sinSum = 0.0; var cosSum = 0.0
            for (p in pixels) {
                val angle = p.h.toDouble() * 2.0 * Math.PI
                sinSum += kotlin.math.sin(angle); cosSum += kotlin.math.cos(angle)
            }
            val mean = kotlin.math.atan2(sinSum, cosSum) / (2.0 * Math.PI)
            return (if (mean < 0) mean + 1.0 else mean).toFloat()
        }

        // k-means++ seeding: pick k initial centroids with distance-weighted random selection
        val rng = java.util.Random(chromatic.hashCode().toLong())
        val centroidIndices = mutableListOf(rng.nextInt(chromatic.size))
        while (centroidIndices.size < k) {
            val distances = FloatArray(chromatic.size) { i ->
                centroidIndices.minOf { ci -> distSq(chromatic[i], chromatic[ci].h, chromatic[ci].s, chromatic[ci].b) }
            }
            val totalDist = distances.sum()
            if (totalDist <= 0f) break
            var r = rng.nextFloat() * totalDist
            var chosen = 0
            for (i in distances.indices) {
                r -= distances[i]
                if (r <= 0f) { chosen = i; break }
            }
            centroidIndices += chosen
        }

        // Centroid arrays: h, s, b for each centroid
        val cH = FloatArray(k) { chromatic[centroidIndices.getOrElse(it) { 0 }].h }
        val cS = FloatArray(k) { chromatic[centroidIndices.getOrElse(it) { 0 }].s }
        val cB = FloatArray(k) { chromatic[centroidIndices.getOrElse(it) { 0 }].b }
        val assignments = IntArray(chromatic.size)

        for (iter in 0 until 30) {
            var changed = false
            for (i in chromatic.indices) {
                var bestC = 0; var bestD = Float.MAX_VALUE
                for (c in 0 until k) {
                    val d = distSq(chromatic[i], cH[c], cS[c], cB[c])
                    if (d < bestD) { bestD = d; bestC = c }
                }
                if (assignments[i] != bestC) { changed = true; assignments[i] = bestC }
            }
            if (!changed && iter > 0) break
            for (c in 0 until k) {
                val members = chromatic.indices.filter { assignments[it] == c }.map { chromatic[it] }
                if (members.isNotEmpty()) {
                    cH[c] = circularMeanHue(members)
                    cS[c] = members.map { it.s.toDouble() }.average().toFloat()
                    cB[c] = members.map { it.b.toDouble() }.average().toFloat()
                }
            }
        }
        return buildClusters(chromatic, assignments, k)
    }

    // ── Strategy: largest hue gaps on the colour wheel ──────────────────

    private fun clusterHueGap(chromatic: List<ColorPixel>, k: Int): List<Cluster> {
        val sorted = chromatic.sortedBy { it.h }
        val n = sorted.size
        if (n <= k) return sorted.map { Cluster(mutableListOf(it.x to it.y)) }

        // Compute interior gaps (between consecutive sorted pixels).
        data class Gap(val size: Float, val afterIndex: Int)
        val interiorGaps = (0 until n - 1).map { Gap(sorted[it + 1].h - sorted[it].h, it) }
        val largestInteriorGap = interiorGaps.maxOf { it.size }

        // Wrap gap: distance between the last and first hue going through 1.0.
        // When the wrap gap is larger than every interior gap, all hues are
        // concentrated in one region — distribute split points evenly across
        // the sorted array (matches the original k=2 "split in half" fallback).
        val wrapGap = 1f - sorted.last().h + sorted.first().h

        val splitPoints = if (wrapGap > largestInteriorGap) {
            // Evenly space k-1 splits.  For k=2 this gives (n/2)-1, identical
            // to the original behaviour.
            (1 until k).map { (n * it / k) - 1 }.sorted()
        } else {
            // Use the k-1 largest interior gaps as split points
            interiorGaps.sortedByDescending { it.size }
                .take(k - 1)
                .map { it.afterIndex }
                .sorted()
        }

        // Build clusters by walking the sorted array and splitting at each point
        val clusters = mutableListOf<Cluster>()
        var start = 0
        for (sp in splitPoints) {
            val cluster = Cluster(mutableListOf())
            for (i in start..sp) cluster.pixels += sorted[i].x to sorted[i].y
            clusters += cluster
            start = sp + 1
        }
        // Final segment
        val last = Cluster(mutableListOf())
        for (i in start until n) last.pixels += sorted[i].x to sorted[i].y
        clusters += last

        return clusters.filter { it.pixels.isNotEmpty() }
    }

    // ── Strategy: k-means in RGB space ──────────────────────────────────

    private fun clusterKMeansRgb(chromatic: List<ColorPixel>, k: Int): List<Cluster> {
        fun distSq(p: ColorPixel, cr: Float, cg: Float, cb: Float): Float {
            val dr = p.r - cr; val dg = p.g - cg; val db = p.bl - cb
            return dr * dr + dg * dg + db * db
        }

        // k-means++ seeding
        val rng = java.util.Random(chromatic.hashCode().toLong())
        val centroidIndices = mutableListOf(rng.nextInt(chromatic.size))
        while (centroidIndices.size < k) {
            val distances = FloatArray(chromatic.size) { i ->
                centroidIndices.minOf { ci -> distSq(chromatic[i], chromatic[ci].r.toFloat(), chromatic[ci].g.toFloat(), chromatic[ci].bl.toFloat()) }
            }
            val totalDist = distances.sum()
            if (totalDist <= 0f) break
            var r = rng.nextFloat() * totalDist
            var chosen = 0
            for (i in distances.indices) {
                r -= distances[i]
                if (r <= 0f) { chosen = i; break }
            }
            centroidIndices += chosen
        }

        // Centroid arrays: r, g, b for each centroid
        val cR = FloatArray(k) { chromatic[centroidIndices.getOrElse(it) { 0 }].r.toFloat() }
        val cG = FloatArray(k) { chromatic[centroidIndices.getOrElse(it) { 0 }].g.toFloat() }
        val cB = FloatArray(k) { chromatic[centroidIndices.getOrElse(it) { 0 }].bl.toFloat() }
        val assignments = IntArray(chromatic.size)

        for (iter in 0 until 30) {
            var changed = false
            for (i in chromatic.indices) {
                var bestC = 0; var bestD = Float.MAX_VALUE
                for (c in 0 until k) {
                    val d = distSq(chromatic[i], cR[c], cG[c], cB[c])
                    if (d < bestD) { bestD = d; bestC = c }
                }
                if (assignments[i] != bestC) { changed = true; assignments[i] = bestC }
            }
            if (!changed && iter > 0) break
            for (c in 0 until k) {
                val members = chromatic.indices.filter { assignments[it] == c }.map { chromatic[it] }
                if (members.isNotEmpty()) {
                    cR[c] = members.map { it.r.toDouble() }.average().toFloat()
                    cG[c] = members.map { it.g.toDouble() }.average().toFloat()
                    cB[c] = members.map { it.bl.toDouble() }.average().toFloat()
                }
            }
        }
        return buildClusters(chromatic, assignments, k)
    }

    // ── Shared helper ───────────────────────────────────────────────────

    private fun buildClusters(chromatic: List<ColorPixel>, assignments: IntArray, k: Int): List<Cluster> {
        val clusters = (0 until k).map { Cluster(mutableListOf()) }
        for (i in chromatic.indices) {
            val px = chromatic[i]
            clusters[assignments[i]].pixels += px.x to px.y
        }
        return clusters.filter { it.pixels.isNotEmpty() }
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


    // ── Palette spec parsing ──────────────────────────────────────────────

    /**
     * Parse a [PaletteSpec] from the given config section.
     * Looks for keys `${prefix}palettes-first`, `${prefix}palettes`,
     * `${prefix}palettes-last`.  Each value is a list whose entries may be
     * plain strings (`"neon"`) or maps (`{palette: "neon", permission: "group.admin"}`).
     * Returns `null` fields for any key that is absent.
     */
    private fun parsePaletteSpec(section: ConfigurationSection, prefix: String = ""): PaletteSpec {
        fun readRefs(key: String): List<PaletteRef>? {
            if (!section.contains(key)) return null
            return parsePaletteRefList(section, key)
        }
        return PaletteSpec(
            first = readRefs("${prefix}palettes-first"),
            palettes = readRefs("${prefix}palettes"),
            last = readRefs("${prefix}palettes-last")
        )
    }

    /**
     * Parse a list at [key] whose entries may be plain strings or maps with
     * `palette` and optional `permission` keys.
     */
    @Suppress("UNCHECKED_CAST")
    private fun parsePaletteRefList(section: ConfigurationSection, key: String): List<PaletteRef> {
        val raw = section.getList(key) ?: return emptyList()
        return raw.mapNotNull { entry ->
            when (entry) {
                is String -> PaletteRef(entry)
                is Map<*, *> -> {
                    val map = entry as Map<String, Any?>
                    val id = map["palette"]?.toString() ?: return@mapNotNull null
                    val perm = map["permission"]?.toString()
                    PaletteRef(id, perm)
                }
                else -> null
            }
        }
    }

    // ── Palette resolution ──────────────────────────────────────────────

    /**
     * Resolve the final ordered, deduplicated list of palette IDs available
     * to a specific option on a specific layer for a given player.
     *
     * Resolution order per category (first / palettes / last):
     *   part-level  →  layer-level  →  default-level
     * The first non-null wins (empty list **is** non-null).
     *
     * Then the three resolved lists are concatenated (first + palettes + last)
     * and deduplicated (first occurrence wins).  Entries whose permission
     * the player lacks are silently removed.
     */
    fun resolvePalettes(layerId: String, optionId: String, player: Player?): List<String> {
        val (layerDef, options) = loadedLayers[layerId] ?: return emptyList()
        val option = options.firstOrNull { it.id == optionId } ?: return emptyList()
        return resolvePalettes(layerDef, option, player)
    }

    fun resolvePalettes(layerDef: LayerDefinition, option: LayerOption, player: Player?): List<String> {
        val def = defaultPaletteSpec
        val lay = layerDef.paletteSpec
        val opt = option.paletteSpec

        val first   = opt.first    ?: lay.first    ?: def.first    ?: emptyList()
        val middle  = opt.palettes ?: lay.palettes ?: def.palettes ?: emptyList()
        val last    = opt.last     ?: lay.last     ?: def.last     ?: emptyList()

        val seen = mutableSetOf<String>()
        return (first + middle + last).filter { ref ->
            val allowed = ref.permission == null || (player?.hasPermission(ref.permission) ?: true)
            allowed && seen.add(ref.id)
        }.map { it.id }
    }

    private fun ConfigurationSection.toDefinition(dataFolder: Path): LayerDefinition {
        val id = this.name
        val displayName = getString("display-name", id) ?: id
        val allowMask = getBoolean("allow-color-mask", false)
        val directory = dataFolder.resolve(getString("directory", "layers/$id") ?: "layers/$id").normalize()

        val spec = parsePaletteSpec(this)

        return LayerDefinition(
            id = id,
            displayName = displayName,
            directory = directory,
            allowColorMask = allowMask,
            paletteSpec = spec
        )
    }
}

