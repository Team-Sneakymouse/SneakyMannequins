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

        var options = loadPngOptions(directory, definition, optionConfig)

        // For the base layer, also load defaults that live in the plugin data root
        if (definition.id.equals("base", ignoreCase = true)) {
            val existingIds = options.map { it.id.lowercase() }.toSet()
            val rootDefaults = listOf("default.png", "default_slim.png")
                .mapNotNull { loadOption(plugin.dataFolder.toPath().resolve(it), definition, optionConfig) }
                .filterNot { existingIds.contains(it.id.lowercase()) }
            options = options + rootDefaults
        }

        return options
    }

    private fun loadPngOptions(
        directory: Path,
        definition: LayerDefinition,
        optionConfig: ConfigurationSection?
    ): List<LayerOption> {
        return Files.list(directory).use { stream ->
            stream.iterator().asSequence()
                .filter { Files.isRegularFile(it) && it.fileName.toString().lowercase().endsWith(".png") }
                .mapNotNull { path -> loadOption(path, definition, optionConfig) }
                .toList()
        }
    }

    private fun loadOption(path: Path, definition: LayerDefinition, optionConfig: ConfigurationSection?): LayerOption? {
        return try {
            val image = ImageIO.read(path.toFile()) ?: return null
            if (image.width != 64 || image.height != 64) {
                plugin.logger.severe("Layer ${definition.id} option ${path.fileName} is not 64x64. Skipping.")
                return null
            }
            if (!imageHasNonTransparentPixels(image)) {
                plugin.logger.warning("Layer ${definition.id} option ${path.fileName} is fully transparent; skipping.")
                return null
            }
            val id = path.fileName.toString().substringBeforeLast(".")
            val allowedPalettes = optionConfig?.getStringList("$id.palettes")
                ?.takeIf { it.isNotEmpty() }
                ?: definition.defaultPalettes
            LayerOption(id = id, file = path, image = image, allowedPalettes = allowedPalettes)
        } catch (ex: Exception) {
            plugin.logger.severe("Failed to load layer option from $path: ${ex.message}")
            null
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

