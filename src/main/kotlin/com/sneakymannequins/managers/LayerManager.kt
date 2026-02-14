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

            LayerOption(
                id = agg.id,
                displayName = agg.displayName,
                fileDefault = defaultPath,
                fileSlim = slimPath,
                imageDefault = defaultImage,
                imageSlim = slimImage,
                allowedPalettes = allowedPalettes
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

