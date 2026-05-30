package com.sneakymannequins.managers

import com.sneakymannequins.SneakyMannequins
import com.sneakymannequins.model.ColorPalette
import com.sneakymannequins.model.LayerDefinition
import com.sneakymannequins.model.LayerOption
import com.sneakymannequins.model.NamedColor
import com.sneakymannequins.model.PaletteRef
import com.sneakymannequins.model.PaletteSpec
import com.sneakymannequins.model.TextureDefinition
import com.sneakymannequins.model.SkinSelection
import com.sneakymannequins.model.TextureRef
import com.sneakymannequins.model.TextureSpec
import com.sneakymannequins.util.BlinkEyeGeometry
import com.sneakymannequins.util.SkinComposer
import com.sneakymannequins.util.SkinUv
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import java.awt.Color
import java.awt.image.BufferedImage
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CompletableFuture
import javax.imageio.ImageIO
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension
import kotlin.math.pow
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player

class LayerManager(private val plugin: SneakyMannequins) {
    private val loadedLayers = mutableMapOf<String, Pair<LayerDefinition, List<LayerOption>>>()
    private val layerOrder = mutableListOf<String>()
    private val palettes = mutableMapOf<String, ColorPalette>()
    private val textures = mutableMapOf<String, TextureDefinition>()
    private var defaultPaletteSpec = PaletteSpec.INHERIT
    private var defaultTextureSpec = TextureSpec.INHERIT
    private var defaultBrightnessInfluence = 0.3f
    private var defaultSaturationInfluence = 1.0f

    fun reload() {
        loadedLayers.clear()
        layerOrder.clear()
        palettes.clear()
        textures.clear()
        defaultPaletteSpec = PaletteSpec.INHERIT
        defaultTextureSpec = TextureSpec.INHERIT
        defaultBrightnessInfluence = 0.3f
        defaultSaturationInfluence = 1.0f
        val root =
                plugin.config.getConfigurationSection("layers")
                        ?: run {
                            plugin.logger.warning("No 'layers' section found in config.")
                            return
                        }

        loadPalettes(root.getConfigurationSection("palettes"))
        loadTextures(root.getConfigurationSection("textures"))

        val definitions =
                resolveDefinitionsSection(root)
                        ?: run {
                            plugin.logger.warning(
                                    "No layer definitions found (check layers.definitions-file or layers.definitions)."
                            )
                            return
                        }
        
        val layersSection = definitions.getConfigurationSection("layers")
                        ?: run {
                            plugin.logger.warning("No 'layers' section found in definitions.")
                            return
                        }

        defaultPaletteSpec = parsePaletteSpec(definitions)
        defaultTextureSpec = parseTextureSpec(definitions)
        if (definitions.contains("brightness-influence")) {
            defaultBrightnessInfluence =
                    definitions.getDouble("brightness-influence", 0.3).toFloat()
        }
        if (definitions.contains("saturation-influence")) {
            defaultSaturationInfluence =
                    definitions.getDouble("saturation-influence", 1.0).toFloat()
        }
        val configuredOrder = root.getStringList("order")
        if (configuredOrder.isNotEmpty()) {
            layerOrder.addAll(configuredOrder)
        } else {
            // Fallback: use layersSection keys if no explicit order
            layerOrder.addAll(layersSection.getKeys(false))
        }

        layerOrder.forEach { layerId ->
            val definitionSection = layersSection.getConfigurationSection(layerId)
            if (definitionSection == null) {
                plugin.logger.warning("Layer '$layerId' listed in order but has no definition.")
                return@forEach
            }
            val definition = definitionSection.toDefinition(plugin.dataFolder.toPath())
            val options =
                    loadOptions(definition, definitionSection.getConfigurationSection("options"))
            loadedLayers[layerId] = definition to options
        }
    }

    /**
     * Loads [ConfigurationSection] for layer defaults + per-layer entries from
     * `layers.definitions-file` (YAML under the plugin data folder), or from inline `layers.definitions`
     * when that key is unset. When `definitions-file` is set, inline `definitions` is ignored.
     */
    private fun resolveDefinitionsSection(root: ConfigurationSection): ConfigurationSection? {
        val pathStr = root.getString("definitions-file")?.trim()
        if (!pathStr.isNullOrEmpty()) {
            val base = plugin.dataFolder.toPath().normalize().toAbsolutePath()
            val resolved = base.resolve(pathStr).normalize().toAbsolutePath()
            if (!resolved.startsWith(base)) {
                plugin.logger.severe(
                        "SneakyMannequins: definitions-file escapes plugin data folder: $pathStr"
                )
                return null
            }
            val file = resolved.toFile()
            if (!file.isFile) {
                plugin.logger.severe(
                        "SneakyMannequins: definitions-file not found: ${file.absolutePath}"
                )
                return null
            }
            return YamlConfiguration.loadConfiguration(file)
        }
        return root.getConfigurationSection("definitions")
    }

    fun definitionsInOrder(): List<LayerDefinition> =
            layerOrder.mapNotNull { loadedLayers[it]?.first }

    fun optionsFor(layerId: String, viewer: Player? = null): List<LayerOption> {
        val allOptions = loadedLayers[layerId]?.second.orEmpty()
        val filtered =
                if (viewer == null) {
                    allOptions.filter { it.owner == null }
                } else {
                    allOptions.filter {
                        (it.owner == null || it.owner == viewer.uniqueId) && hasPermission(viewer, it)
                    }
                }
        return filtered.sortedWith(
                compareBy({ it.displayName.lowercase(Locale.ROOT) }, { it.id })
        )
    }

    private fun hasPermission(player: org.bukkit.entity.Player, option: LayerOption): Boolean {
        val perms = option.permissions ?: return true
        if (perms.isEmpty()) return true
        return perms.any { player.hasPermission(it) }
    }

    fun allOptions(layerId: String): List<LayerOption> {
        return loadedLayers[layerId]?.second.orEmpty()
    }

    fun addOption(layerId: String, option: LayerOption) {
        val entry = loadedLayers[layerId] ?: return
        val newOptions = entry.second.toMutableList().also { it.add(option) }
        loadedLayers[layerId] = entry.first to newOptions
    }

    fun palette(id: String): ColorPalette? = palettes[id]

    fun texture(id: String): TextureDefinition? = textures[id]

    // ── Palette loading ──────────────────────────────────────────────────

    private fun loadPalettes(section: ConfigurationSection?) {
        section ?: return
        section.getKeys(false).forEach { paletteId ->
            val paletteSection = section.getConfigurationSection(paletteId)
            val namedColors =
                    if (paletteSection != null) {
                        paletteSection.getKeys(false).mapNotNull { name ->
                            val hex = paletteSection.getString(name) ?: return@mapNotNull null
                            decodeColor(hex)?.let { color -> NamedColor(name, color) }
                                    ?: run {
                                        plugin.logger.warning(
                                                "Invalid color '$hex' in palette '$paletteId' entry '$name'"
                                        )
                                        null
                                    }
                        }
                    } else {
                        // Fallback: allow legacy list format without names
                        section.getStringList(paletteId).mapIndexedNotNull { idx, hex ->
                            decodeColor(hex)?.let { color -> NamedColor("color$idx", color) }
                                    ?: run {
                                        plugin.logger.warning(
                                                "Invalid color '$hex' in palette '$paletteId'"
                                        )
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

    // ── Texture loading ──────────────────────────────────────────────────

    private fun loadTextures(section: ConfigurationSection?) {
        section ?: return
        val dataDir = plugin.dataFolder.toPath()
        section.getKeys(false).forEach { textureId ->
            val texSection = section.getConfigurationSection(textureId)
            if (texSection == null) {
                plugin.logger.warning("Texture '$textureId' is not a valid section; skipping.")
                return@forEach
            }
            val blendRaw = texSection.getString("blend")
            val aoRaw = texSection.getString("ao")
            val roughnessRaw = texSection.getString("roughness")
            val alphaRaw = texSection.getString("alpha")
            val blendPath = blendRaw?.let { dataDir.resolve(it).normalize() }
            val aoPath = aoRaw?.let { dataDir.resolve(it).normalize() }
            val roughnessPath = roughnessRaw?.let { dataDir.resolve(it).normalize() }
            val alphaPath = alphaRaw?.let { dataDir.resolve(it).normalize() }

            fun loadImage(label: String, path: java.nio.file.Path): BufferedImage? {
                if (!Files.exists(path)) {
                    plugin.logger.warning("Texture '$textureId' $label not found: $path")
                    return null
                }
                return try {
                    ImageIO.read(path.toFile())
                } catch (e: Exception) {
                    plugin.logger.warning(
                            "Texture '$textureId' failed to read $label: ${e.message}"
                    )
                    null
                }
            }

            val blendImage = blendPath?.let { loadImage("blend map", it) }
            val aoImage = aoPath?.let { loadImage("AO map", it) }
            val roughnessImage = roughnessPath?.let { loadImage("roughness map", it) }
            val alphaImage = alphaPath?.let { loadImage("alpha map", it) }

            // Auto-detect active sub-channels from the blend map (scan entire image)
            val activeSubChannels =
                    if (blendImage != null) detectSubChannels(blendImage) else emptySet()
            if (activeSubChannels.isNotEmpty()) {
                plugin.logger.info(
                        "Texture '$textureId' detected ${activeSubChannels.size} sub-channels: $activeSubChannels"
                )
            }

            val displayName = toDisplayName(textureId)
            textures[textureId] =
                    TextureDefinition(
                            id = textureId,
                            displayName = displayName,
                            blendMapPath = blendPath,
                            blendMapImage = blendImage,
                            aoMapPath = aoPath,
                            aoMapImage = aoImage,
                            roughnessMapPath = roughnessPath,
                            roughnessMapImage = roughnessImage,
                            alphaMapPath = alphaPath,
                            alphaMapImage = alphaImage,
                            activeSubChannels = activeSubChannels
                    )
        }
    }

    /**
     * Scan all pixels in the blend map and return the set of active sub-channel indices (0=R, 1=G,
     * 2=B) that have at least one non-zero pixel in that colour channel.
     */
    private fun detectSubChannels(blendMap: java.awt.image.BufferedImage): Set<Int> {
        val active = mutableSetOf<Int>()
        for (x in 0 until blendMap.width) {
            for (y in 0 until blendMap.height) {
                val argb = blendMap.getRGB(x, y)
                val r = argb shr 16 and 0xFF
                val g = argb shr 8 and 0xFF
                val b = argb and 0xFF
                if (r > 0) active.add(0)
                if (g > 0) active.add(1)
                if (b > 0) active.add(2)
                if (active.size == 3) break
            }
        }
        return active
    }

    private fun decodeColor(hex: String): Color? {
        return try {
            Color.decode("#${hex.trim('#')}")
        } catch (_: Exception) {
            null
        }
    }

    // ── Option loading ───────────────────────────────────────────────────

    private fun loadOptions(
            definition: LayerDefinition,
            optionConfig: ConfigurationSection?
    ): List<LayerOption> {
        val directory = definition.directory
        if (!Files.exists(directory)) {
            Files.createDirectories(directory)
        }

        val options = loadLayerOptions(directory, definition, optionConfig).toMutableList()
        
        if (definition.allowEmpty) {
            options.add(0, LayerOption(
                id = "none",
                displayName = "None",
                fileDefault = null,
                fileSlim = null,
                fileMaster = null,
                imageDefault = null,
                imageSlim = null,
                imageMaster = null
            ))
        }
        
        return options
    }

    /**
     * After on-disk mask changes (remask / channelmerge / channeldelete), update just the in-memory
     * [LayerOption] for that part using the `metadata.json` we just wrote.
     *
     * This avoids relying on a full layer reload for something we already know is correct.
     */
    private fun refreshLoadedOptionFromMetadata(layerId: String, partId: String, dir: Path): LayerOption? {
        val actualLayerId =
                loadedLayers.keys.firstOrNull { it.equals(layerId, ignoreCase = true) } ?: return null
        val (def, options) = loadedLayers[actualLayerId] ?: return null

        val meta = loadMetadata(dir)
        @Suppress("UNCHECKED_CAST")
        val mappings = meta["mappings"] as? Map<String, Any> ?: emptyMap()

        val masks = mutableMapOf<Int, Path>()
        val masksDefault = mutableMapOf<Int, Path>()
        val masksSlim = mutableMapOf<Int, Path>()

        (mappings["masks"] as? Map<String, String>)?.forEach { (idx, file) ->
            val i = idx.toIntOrNull() ?: return@forEach
            masks[i] = dir.resolve(file)
        }
        (mappings["masksDefault"] as? Map<String, String>)?.forEach { (idx, file) ->
            val i = idx.toIntOrNull() ?: return@forEach
            masksDefault[i] = dir.resolve(file)
        }
        (mappings["masksSlim"] as? Map<String, String>)?.forEach { (idx, file) ->
            val i = idx.toIntOrNull() ?: return@forEach
            masksSlim[i] = dir.resolve(file)
        }

        val idx = options.indexOfFirst { it.id.equals(partId, ignoreCase = true) }
        if (idx < 0) return null

        val existing = options[idx]
        val updated =
                existing.copy(
                        // Only refresh the asset paths + mask maps.
                        fileMaster =
                                (mappings["master"] as? String)?.let { dir.resolve(it) }
                                        ?: existing.fileMaster,
                        fileDefault =
                                (mappings["default"] as? String)?.let { dir.resolve(it) }
                                        ?: existing.fileDefault,
                        fileSlim =
                                (mappings["slim"] as? String)?.let { dir.resolve(it) }
                                        ?: existing.fileSlim,
                        masks = masks,
                        masksDefault = masksDefault,
                        masksSlim = masksSlim,
                        directory = dir,
                        hasArms = meta["hasArms"] as? Boolean ?: existing.hasArms,
                        isAlex = meta["isAlex"] as? Boolean ?: existing.isAlex,
                        isDress = meta["isDress"] as? Boolean ?: existing.isDress,
                        dressLength = (meta["dressLength"] as? Number)?.toInt() ?: existing.dressLength,
                        isBlink = meta["isBlink"] as? Boolean ?: existing.isBlink,
                        blinkStyle = (meta["blinkStyle"] as? Number)?.toInt() ?: existing.blinkStyle,
                        blinkHeight = (meta["blinkHeight"] as? Number)?.toInt() ?: existing.blinkHeight,
                        blinkEyeColumns =
                                (meta["blinkEyeColumns"] as? List<*>)?.mapNotNull { (it as? Number)?.toInt() }
                                        ?: existing.blinkEyeColumns,
                        blinkEyelidX = (meta["blinkEyelidX"] as? Number)?.toInt() ?: existing.blinkEyelidX,
                        blinkEyelidY = (meta["blinkEyelidY"] as? Number)?.toInt() ?: existing.blinkEyelidY,
                        jacketStyle = (meta["jacketStyle"] as? Number)?.toInt() ?: existing.jacketStyle
                )

        val newList = options.toMutableList()
        newList[idx] = updated
        loadedLayers[actualLayerId] = def to newList
        return updated
    }

    private fun loadLayerOptions(
            directory: Path,
            definition: LayerDefinition,
            optionConfig: ConfigurationSection?
    ): List<LayerOption> {
        val entries = Files.list(directory).use { it.toList() }
        val grouped = mutableMapOf<String, OptionAggregate>()

        // 1. Identify preprocessed directories (excluding the 'uploads' folder).
        //    A folder is only a part if it contains metadata.json.
        entries
                .filter {
                    it.isDirectory() && it.name != "uploads" && hasPartMetadataJson(it)
                }
                .forEach { dir ->
            val id = slugify(dir.name)
            val metadata = loadMetadata(dir)
            val displayName = metadata["displayName"] as? String ?: toDisplayName(dir.name)
            val agg = grouped.getOrPut(id) { OptionAggregate(id, displayName, directory = dir) }
            populateAggregate(agg, dir)
        }

        // 2. Identify standalone PNGs that need preprocessing
        entries
                .filter { it.isRegularFile() && it.name.lowercase().endsWith(".png") }
                .filterNot { it.nameWithoutExtension.lowercase().contains("_mask_") }
                .forEach { path ->
                    val id = slugify(path.nameWithoutExtension)
                    if (!grouped.containsKey(id)) {
                        try {
                            // Trigger preprocessing for this new PNG
                            preprocessPart(path)
                        } catch (e: Exception) {
                            plugin.logger.severe("Failed to preprocess $path: ${e.message}")
                            e.printStackTrace()
                            return@forEach
                        }

                        val dir = path.parent.resolve(path.nameWithoutExtension)
                        if (dir.exists() && hasPartMetadataJson(dir)) {
                            val metadata = loadMetadata(dir)
                            val displayName =
                                    metadata["displayName"] as? String ?: toDisplayName(dir.name)
                            val agg =
                                    grouped.getOrPut(id) {
                                        OptionAggregate(id, displayName, directory = dir)
                                    }
                            populateAggregate(agg, dir)
                        }
                    }
                }

        val result =
                grouped.values
                        .mapNotNull { agg ->
                            createOptionFromAggregate(agg, definition, optionConfig)
                        }
                        .toMutableList()

        // 3. Scan uploads directory for user-specific parts
        val uploadsDir = directory.resolve("uploads")
        if (Files.exists(uploadsDir) && Files.isDirectory(uploadsDir)) {
            try {
                Files.list(uploadsDir).use { userStream ->
                    userStream.forEach userLoop@{ userDir ->
                        if (!Files.isDirectory(userDir)) return@userLoop
                        val uuidString = userDir.name
                        val ownerUuid =
                                try {
                                    UUID.fromString(uuidString)
                                } catch (e: Exception) {
                                    null
                                }
                        if (ownerUuid != null) {
                            val userPartDirs = Files.list(userDir).use { it.toList() }
                            userPartDirs.forEach partLoop@{ partDir ->
                                if (!Files.isDirectory(partDir) || !hasPartMetadataJson(partDir)) {
                                    return@partLoop
                                }
                                val id = slugify(partDir.name)
                                val metadata = loadMetadata(partDir)
                                val displayName =
                                        metadata["displayName"] as? String
                                                ?: toDisplayName(partDir.name)
                                val agg = OptionAggregate(id, displayName, directory = partDir)
                                populateAggregate(agg, partDir)

                                val userOpt =
                                        createOptionFromAggregate(agg, definition, null)
                                if (userOpt != null) {
                                    result.add(
                                            userOpt.copy(
                                                    id = "$uuidString:${userOpt.id}",
                                                    owner = ownerUuid,
                                                    internalKey = userOpt.id
                                            )
                                    )
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                plugin.logger.severe(
                        "Failed to scan uploads for layer ${definition.id}: ${e.message}"
                )
            }
        }

        return result
    }

    private fun createOptionFromAggregate(
            agg: OptionAggregate,
            definition: LayerDefinition,
            optionConfig: ConfigurationSection?
    ): LayerOption? {
        val optSection = optionConfig?.getConfigurationSection(agg.id)
        val optPaletteSpec = optSection?.let { parsePaletteSpec(it) } ?: PaletteSpec.INHERIT
        val optTextureSpec = optSection?.let { parseTextureSpec(it) } ?: TextureSpec.INHERIT
        val optBriInf =
                optSection?.let {
                    if (it.contains("brightness-influence"))
                            it.getDouble("brightness-influence").toFloat()
                    else null
                }
        val optSatInf =
                optSection?.let {
                    if (it.contains("saturation-influence"))
                            it.getDouble("saturation-influence").toFloat()
                    else null
                }

        val masterPath = agg.masterPath ?: agg.sharedPath
        val defaultPath = agg.defaultPath ?: masterPath
        val slimPath = agg.slimPath ?: masterPath

        val masterImage = masterPath?.let { loadImage(it, definition.id) }
        val defaultImage = defaultPath?.let { loadImage(it, definition.id) }
        val slimImage = slimPath?.let { loadImage(it, definition.id) }

        if (defaultImage == null && slimImage == null && masterImage == null) {
            plugin.logger.warning(
                    "Layer ${definition.id} option ${agg.id} has no readable images; skipping."
            )
            return null
        }

        val masks = agg.masks
        val masksDefault = agg.masksDefault
        val masksSlim = agg.masksSlim

        return LayerOption(
                id = agg.id,
                displayName = agg.displayName,
                fileDefault = defaultPath,
                fileSlim = slimPath,
                fileMaster = masterPath,
                imageDefault = defaultImage,
                imageSlim = slimImage,
                imageMaster = masterImage,
                paletteSpec = optPaletteSpec,
                textureSpec = optTextureSpec,
                brightnessInfluence = optBriInf,
                saturationInfluence = optSatInf,
                masks = masks,
                masksDefault = masksDefault,
                masksSlim = masksSlim,
                directory = agg.directory,
                hasArms = agg.hasArms,
                isAlex = agg.isAlex,
                permissions = optSection?.getStringList("permissions"),
                isDress = agg.isDress,
                dressLength = agg.dressLength,
                isBlink = agg.isBlink,
                blinkStyle = agg.blinkStyle,
                blinkHeight = agg.blinkHeight,
                blinkEyeColumns =
                        when {
                            !agg.isBlink -> emptyList()
                            agg.blinkEyeColumns.isNotEmpty() ->
                                    agg.blinkEyeColumns.sorted().distinct()
                            else -> listOf(3, 6)
                        },
                blinkEyelidX = agg.blinkEyelidX,
                blinkEyelidY = agg.blinkEyelidY,
                jacketStyle = agg.jacketStyle
        )
    }

    private fun populateAggregate(agg: OptionAggregate, dir: Path) {
        val metadata = loadMetadata(dir)
        agg.hasArms = metadata["hasArms"] as? Boolean ?: false
        agg.isAlex = metadata["isAlex"] as? Boolean ?: false
        agg.isDress = metadata["isDress"] as? Boolean ?: false
        agg.dressLength = (metadata["dressLength"] as? Number)?.toInt() ?: 0
        agg.isBlink = metadata["isBlink"] as? Boolean ?: false
        agg.blinkStyle = (metadata["blinkStyle"] as? Number)?.toInt() ?: 0
        agg.blinkHeight = (metadata["blinkHeight"] as? Number)?.toInt() ?: 0
        @Suppress("UNCHECKED_CAST")
        val cols = metadata["blinkEyeColumns"] as? List<*>
        agg.blinkEyeColumns =
                cols?.mapNotNull { (it as? Number)?.toInt() }?.sorted()?.distinct() ?: emptyList()
        agg.blinkEyelidX = (metadata["blinkEyelidX"] as? Number)?.toInt()
        agg.blinkEyelidY = (metadata["blinkEyelidY"] as? Number)?.toInt()
        agg.jacketStyle = (metadata["jacketStyle"] as? Number)?.toInt() ?: 0

        @Suppress("UNCHECKED_CAST")
        val mappings = metadata["mappings"] as? Map<String, Any> ?: emptyMap()
        val hasExplicitMaskMappings = mappings.isNotEmpty()

        if (mappings.isNotEmpty()) {
            (mappings["master"] as? String)?.let { agg.masterPath = dir.resolve(it) }
            (mappings["default"] as? String)?.let { agg.defaultPath = dir.resolve(it) }
            (mappings["slim"] as? String)?.let { agg.slimPath = dir.resolve(it) }

            (mappings["masks"] as? Map<String, String>)?.forEach { (idx, file) ->
                agg.masks[idx.toIntOrNull() ?: return@forEach] = dir.resolve(file)
            }
            (mappings["masksDefault"] as? Map<String, String>)?.forEach { (idx, file) ->
                agg.masksDefault[idx.toIntOrNull() ?: return@forEach] = dir.resolve(file)
            }
            (mappings["masksSlim"] as? Map<String, String>)?.forEach { (idx, file) ->
                agg.masksSlim[idx.toIntOrNull() ?: return@forEach] = dir.resolve(file)
            }
        }

        // Scan directory for additional assets. When metadata has explicit mask mappings,
        // those mappings are treated as the source of truth for mask indices. This prevents
        // stale/leftover `_mask_*.png` files from incorrectly inflating channel counts.
        Files.list(dir).use { stream ->
            stream.forEach { path ->
                val name = path.nameWithoutExtension.lowercase()
                val filename = path.name.lowercase()
                if (!filename.endsWith(".png")) return@forEach

                if (name.contains("_mask_")) {
                    if (hasExplicitMaskMappings) return@forEach
                    val idx = name.substringAfterLast("_mask_").toIntOrNull() ?: return@forEach
                    if (name.contains("_default_")) {
                        if (!agg.masksDefault.containsKey(idx)) agg.masksDefault[idx] = path
                    } else if (name.contains("_slim_")) {
                        if (!agg.masksSlim.containsKey(idx)) agg.masksSlim[idx] = path
                    } else {
                        if (!agg.masks.containsKey(idx)) agg.masks[idx] = path
                    }
                } else if (mappings.isEmpty()) {
                    if (name.endsWith("_slim")) agg.slimPath = path
                    else if (name.endsWith("_default")) agg.defaultPath = path
                    else agg.masterPath = path
                }
            }
        }
    }

    private fun loadOptionPair(
            path: Path,
            definition: LayerDefinition,
            optionConfig: ConfigurationSection?
    ): LayerOption? {
        val base = path.nameWithoutExtension
        val id = slugify(base)
        val displayName = toDisplayName(base)
        val optSection = optionConfig?.getConfigurationSection(id)
        val optPaletteSpec = optSection?.let { parsePaletteSpec(it) } ?: PaletteSpec.INHERIT
        val optTextureSpec = optSection?.let { parseTextureSpec(it) } ?: TextureSpec.INHERIT
        val optBriInf =
                optSection?.let {
                    if (it.contains("brightness-influence"))
                            it.getDouble("brightness-influence").toFloat()
                    else null
                }
        val optSatInf =
                optSection?.let {
                    if (it.contains("saturation-influence"))
                            it.getDouble("saturation-influence").toFloat()
                    else null
                }

        val image = loadImage(path, definition.id) ?: return null
        return LayerOption(
                id = id,
                displayName = displayName,
                fileDefault = path,
                fileSlim = path,
                fileMaster = path,
                imageDefault = image,
                imageSlim = image,
                imageMaster = image,
                paletteSpec = optPaletteSpec,
                textureSpec = optTextureSpec,
                brightnessInfluence = optBriInf,
                saturationInfluence = optSatInf
        )
    }

    private fun loadImage(path: Path, layerId: String): java.awt.image.BufferedImage? {
        return try {
            val image = ImageIO.read(path.toFile()) ?: return null
            if (image.width != 64 || image.height != 64) {
                plugin.logger.severe(
                        "Layer $layerId option ${path.fileName} is not 64x64. Skipping."
                )
                return null
            }
            if (!imageHasNonTransparentPixels(image)) {
                plugin.logger.warning(
                        "Layer $layerId option ${path.fileName} is fully transparent; skipping."
                )
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
            var sharedPath: Path? = null,
            var masterPath: Path? = null,
            var directory: Path? = null,
            var masks: MutableMap<Int, Path> = mutableMapOf(),
            var masksDefault: MutableMap<Int, Path> = mutableMapOf(),
            var masksSlim: MutableMap<Int, Path> = mutableMapOf(),
            var hasArms: Boolean = false,
            var isAlex: Boolean = false,
            var isDress: Boolean = false,
            var dressLength: Int = 0,
            var isBlink: Boolean = false,
            var blinkStyle: Int = 0,
            var blinkHeight: Int = 0,
            var blinkEyeColumns: List<Int> = emptyList(),
            var blinkEyelidX: Int? = null,
            var blinkEyelidY: Int? = null,
            var jacketStyle: Int = 0
    )

    private enum class Variant {
        DEFAULT,
        SLIM,
        BOTH
    }

    internal fun slugify(raw: String): String =
            raw.trim().lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifEmpty { "option" }

    internal fun toDisplayName(raw: String): String =
            raw.trim()
                    .split(Regex("[_\\-\\s]+"))
                    .filter { it.isNotBlank() }
                    .joinToString(" ") { part ->
                        part.lowercase().replaceFirstChar { ch -> ch.titlecase() }
                    }
                    .ifEmpty { "Option" }

    fun nextBasePartName(player: Player, layerId: String): String {
        val resolvedId =
                loadedLayers.keys.firstOrNull { it.equals(layerId, ignoreCase = true) } ?: layerId
        val entry = loadedLayers[resolvedId] ?: return "Base 1"
        val def = entry.first
        val globalDir = def.directory
        val userDir = globalDir.resolve("uploads").resolve(player.uniqueId.toString())

        var maxIndex = 0
        val regex = Regex("^base[ _](\\d+)(?:\\.png)?$", RegexOption.IGNORE_CASE)

        fun considerBaseNameFile(nameLower: String) {
            val match = regex.find(nameLower) ?: return
            val idx = match.groupValues[1].toIntOrNull() ?: return
            if (idx > maxIndex) maxIndex = idx
        }

        if (Files.exists(globalDir)) {
            Files.list(globalDir).use { stream ->
                stream.forEach { path ->
                    if (Files.isRegularFile(path)) {
                        considerBaseNameFile(path.fileName.toString().lowercase())
                    }
                }
            }
        }

        var dirPartCount = 0
        if (Files.exists(userDir)) {
            val paths = Files.list(userDir).use { it.toList() }
            val dirNamesLower =
                    paths
                            .filter { Files.isDirectory(it) }
                            .map { it.fileName.toString().lowercase() }
                            .toSet()
            for (path in paths) {
                when {
                    Files.isDirectory(path) && hasPartMetadataJson(path) -> dirPartCount++
                    Files.isRegularFile(path) &&
                            path.fileName.toString().lowercase().endsWith(".png") -> {
                        val stem = path.fileName.toString().removeSuffix(".png").lowercase()
                        if (stem in dirNamesLower) {
                            // e.g. `partId.png` next to processed `partId/` (hash or slug); count folder only.
                            continue
                        }
                        considerBaseNameFile(path.fileName.toString().lowercase())
                    }
                }
            }
        }

        val next = maxOf(maxIndex, dirPartCount) + 1
        return "Base $next"
    }

    fun uploadPart(
            player: Player,
            layerId: String,
            url: URL,
            name: String? = null,
            sessionManager: SessionManager,
            uncraig: Boolean = false
    ): CompletableFuture<String> {
        val entry =
                loadedLayers[layerId]
                        ?: return CompletableFuture.failedFuture(
                                Exception("Unknown layer: $layerId")
                        )
        val def = entry.first
        val partId = name?.let { slugify(it) } ?: "upload_${System.currentTimeMillis()}"
        val targetDir = def.directory.resolve("uploads").resolve(player.uniqueId.toString())

        return sessionManager.downloadSkin(url).thenApplyAsync { rawImage ->
            var image = rawImage
            if (uncraig) {
                image = com.sneakymannequins.util.SkinTransform.uncraig(image)
            }
            if (image.width != 64 || image.height != 64) {
                throw Exception("Image must be 64x64")
            }
            Files.createDirectories(targetDir)
            val sourcePath = targetDir.resolve("$partId.png")
            ImageIO.write(image, "PNG", sourcePath.toFile())

            preprocessPart(sourcePath)

            val partDir = targetDir.resolve(partId)

            // Reload just this part
            val metadata = loadMetadata(partDir)
            val displayName = metadata["displayName"] as? String ?: toDisplayName(partId)
            val agg = OptionAggregate(partId, displayName, directory = partDir)

            Files.list(partDir).use { stream ->
                stream.forEach { path ->
                    val n = path.nameWithoutExtension.lowercase()
                    if (n.endsWith("_slim")) agg.slimPath = path
                    else if (n.endsWith("_default")) agg.defaultPath = path
                    else if (path.name.lowercase().endsWith(".png") && !n.contains("_mask_"))
                            agg.sharedPath = path
                }
            }

            val opt = createOptionFromAggregate(agg, def, null)
            if (opt != null) {
                val userOpt =
                        opt.copy(
                                id = "${player.uniqueId}:${opt.id}",
                                owner = player.uniqueId,
                                internalKey = opt.id
                        )
                addOption(layerId, userOpt)
            }

            "Successfully uploaded part '$displayName' to layer '${def.displayName}'."
        }
    }

    /**
     * Ensures a personal base-layer part exists for this player's current profile skin URL (keyed by
     * [SessionManager.skinTextureStorageKey]), downloading and running [preprocessPart] when missing.
     * Returns the full option id (`ownerUuid:internalKey`) or null on failure.
     */
    fun ensurePlayerSkinTextureBasePart(
            player: Player,
            layerId: String,
            skinUrl: URL,
            sessionManager: SessionManager,
            uncraig: Boolean = false
    ): CompletableFuture<String?> {
        val resolvedLayerId =
                loadedLayers.keys.firstOrNull { it.equals(layerId, ignoreCase = true) }
                        ?: return CompletableFuture.completedFuture(null)
        val def = loadedLayers[resolvedLayerId]?.first ?: return CompletableFuture.completedFuture(null)
        val storageKey = SessionManager.skinTextureStorageKey(skinUrl)
        val userRoot = def.directory.resolve("uploads").resolve(player.uniqueId.toString())
        val partDir = userRoot.resolve(storageKey)

        val existingOpt =
                allOptions(resolvedLayerId).find {
                    it.owner == player.uniqueId && it.internalKey == storageKey
                }
        if (existingOpt != null) {
            return CompletableFuture.completedFuture(existingOpt.id)
        }

        if (Files.isDirectory(partDir) && hasPartMetadataJson(partDir)) {
            return CompletableFuture.supplyAsync {
                registerUploadPartFromDirectory(resolvedLayerId, def, player, partDir, storageKey)
            }
        }

        return sessionManager.downloadSkin(skinUrl).thenApplyAsync { rawImage ->
            var image = rawImage
            if (uncraig) {
                image = com.sneakymannequins.util.SkinTransform.uncraig(image)
            }
            if (image.width != 64 || image.height != 64) {
                throw IllegalStateException("Image must be 64x64")
            }
            Files.createDirectories(userRoot)
            val sourcePath = userRoot.resolve("$storageKey.png")
            ImageIO.write(image, "PNG", sourcePath.toFile())

            val displayName = nextBasePartName(player, resolvedLayerId)
            preprocessPart(sourcePath, displayName)

            if (allOptions(resolvedLayerId).any {
                        it.owner == player.uniqueId && it.internalKey == storageKey
                    }
            ) {
                allOptions(resolvedLayerId)
                        .find { it.owner == player.uniqueId && it.internalKey == storageKey }!!
                        .id
            } else {
                val metadata = loadMetadata(partDir)
                val dn = metadata["displayName"] as? String ?: displayName
                val agg = OptionAggregate(storageKey, dn, directory = partDir)
                populateAggregate(agg, partDir)
                val opt = createOptionFromAggregate(agg, def, null)
                if (opt == null) {
                    null
                } else {
                    val userOpt =
                            opt.copy(
                                    id = "${player.uniqueId}:${opt.id}",
                                    owner = player.uniqueId,
                                    internalKey = opt.id
                            )
                    addOption(resolvedLayerId, userOpt)
                    userOpt.id
                }
            }
        }
    }

    private fun registerUploadPartFromDirectory(
            layerId: String,
            def: LayerDefinition,
            player: Player,
            partDir: Path,
            storageKey: String
    ): String? {
        if (allOptions(layerId).any { it.owner == player.uniqueId && it.internalKey == storageKey }) {
            return allOptions(layerId)
                    .find { it.owner == player.uniqueId && it.internalKey == storageKey }
                    ?.id
        }
        val metadata = loadMetadata(partDir)
        val displayName = metadata["displayName"] as? String ?: toDisplayName(storageKey)
        val agg = OptionAggregate(storageKey, displayName, directory = partDir)
        populateAggregate(agg, partDir)
        val opt = createOptionFromAggregate(agg, def, null) ?: return null
        val userOpt =
                opt.copy(
                        id = "${player.uniqueId}:${opt.id}",
                        owner = player.uniqueId,
                        internalKey = opt.id
                )
        addOption(layerId, userOpt)
        return userOpt.id
    }

    fun deletePart(player: Player, layerId: String, partId: String): String {
        val entry = loadedLayers[layerId] ?: return "Unknown layer: $layerId"
        val def = entry.first
        val options = entry.second

        val optOpt = options.find { it.id == partId }
        if (optOpt == null) return "Part not found"

        if (optOpt.owner == null) return "Cannot delete builtin parts"
        if (optOpt.owner != player.uniqueId &&
                        !player.hasPermission("${SneakyMannequins.IDENTIFIER}.admin")
        ) {
            return "You do not own this part"
        }

        val internalKey = optOpt.internalKey ?: return "Missing internal key"
        val targetDir =
                def.directory
                        .resolve("uploads")
                        .resolve(optOpt.owner.toString())
                        .resolve(internalKey)

        try {
            if (targetDir.exists()) {
                targetDir.toFile().deleteRecursively()
            }
        } catch (e: Exception) {
            plugin.logger.severe("Failed to delete ME directory $targetDir: ${e.message}")
        }

        loadedLayers[layerId] = def to options.filter { it.id != partId }
        return "Successfully deleted part '${optOpt.displayName}'"
    }

    private fun hasPartMetadataJson(directory: Path): Boolean =
            Files.isRegularFile(directory.resolve("metadata.json"))

    private fun loadMetadata(directory: Path): Map<String, Any> {
        val file = directory.resolve("metadata.json")
        if (!file.exists()) return emptyMap()
        return runCatching {
            val content = Files.readString(file)
            val rootEl = JsonParser.parseString(content)
            val root = rootEl.asJsonObject

            fun JsonObject.optString(key: String): String? =
                    if (has(key) && get(key).isJsonPrimitive) get(key).asString else null
            fun JsonObject.optInt(key: String): Int? =
                    if (has(key) && get(key).isJsonPrimitive) runCatching { get(key).asInt }.getOrNull() else null
            fun JsonObject.optBool(key: String): Boolean? =
                    if (has(key) && get(key).isJsonPrimitive) runCatching { get(key).asBoolean }.getOrNull() else null

            fun JsonObject.optIntList(key: String): List<Int>? {
                val el = get(key) ?: return null
                if (!el.isJsonArray) return null
                val out = mutableListOf<Int>()
                el.asJsonArray.forEach { e ->
                    if (e.isJsonPrimitive) runCatching { out += e.asInt }.getOrNull()
                }
                return out
            }

            fun parseMaskMap(el: JsonElement?): Map<String, String> {
                val obj = el?.takeIf { it.isJsonObject }?.asJsonObject ?: return emptyMap()
                val out = mutableMapOf<String, String>()
                for ((k, v) in obj.entrySet()) {
                    if (v.isJsonPrimitive) out[k] = v.asString
                }
                return out
            }

            val map = mutableMapOf<String, Any>()
            root.optString("displayName")?.let { map["displayName"] = it }
            root.optString("internalKey")?.let { map["internalKey"] = it }
            root.optBool("hasArms")?.let { map["hasArms"] = it }
            root.optBool("isAlex")?.let { map["isAlex"] = it }
            root.optBool("isDress")?.let { map["isDress"] = it }
            root.optInt("dressLength")?.let { map["dressLength"] = it }
            root.optBool("isBlink")?.let { map["isBlink"] = it }
            root.optInt("blinkStyle")?.let { map["blinkStyle"] = it }
            root.optInt("blinkHeight")?.let { map["blinkHeight"] = it }
            root.optIntList("blinkEyeColumns")?.let { map["blinkEyeColumns"] = it }
            root.optInt("blinkEyelidX")?.let { map["blinkEyelidX"] = it }
            root.optInt("blinkEyelidY")?.let { map["blinkEyelidY"] = it }
            root.optInt("jacketStyle")?.let { map["jacketStyle"] = it }

            val mappingsObj = root.getAsJsonObject("mappings")
            if (mappingsObj != null) {
                val mappings = mutableMapOf<String, Any>()
                mappingsObj.optString("master")?.let { mappings["master"] = it }
                mappingsObj.optString("default")?.let { mappings["default"] = it }
                mappingsObj.optString("slim")?.let { mappings["slim"] = it }
                mappings["masks"] = parseMaskMap(mappingsObj.get("masks"))
                mappings["masksDefault"] = parseMaskMap(mappingsObj.get("masksDefault"))
                mappings["masksSlim"] = parseMaskMap(mappingsObj.get("masksSlim"))
                map["mappings"] = mappings
            }

            map
        }.getOrDefault(emptyMap())
    }

    fun preprocessPart(sourcePath: Path, displayNameOverride: String? = null) {
        val partName = sourcePath.nameWithoutExtension
        val targetDir = sourcePath.parent.resolve(partName)
        if (!targetDir.exists()) Files.createDirectories(targetDir)

        val image = ImageIO.read(sourcePath.toFile()) ?: return

        // Detect blinking on RAW image to prevent stripping markers
        val blink = detectBlink(image)

        val sanitized = sanitizeUv(image)

        val hasArms = hasArmPixels(sanitized)
        val isSlim = if (hasArms) isSlimArmModel(sanitized) else false

        val (isDress, dressLength) = detectDress(sanitized)

        // 1. Save Master Sanitized Asset
        val masterPath = targetDir.resolve("$partName.png")
        ImageIO.write(sanitized, "png", masterPath.toFile())

        // 3. Generate Master Masks
        preprocessImage(masterPath)

        // 4. Propagate to Variants if needed
        if (hasArms) {
            val defaultImg: BufferedImage
            val slimImg: BufferedImage

            if (isSlim) {
                slimImg = sanitized
                defaultImg = generateDefaultFromSlim(sanitized)
            } else {
                defaultImg = sanitized
                slimImg = generateSlimFromDefault(sanitized)
            }

            val defPath = targetDir.resolve("${partName}_Default.png")
            val slimPath = targetDir.resolve("${partName}_Slim.png")

            ImageIO.write(defaultImg, "png", defPath.toFile())
            ImageIO.write(slimImg, "png", slimPath.toFile())

            // Propagate Masks
            Files.list(targetDir).use { stream ->
                stream.filter { it.nameWithoutExtension.startsWith("${partName}_mask_") }.forEach {
                        masterMaskPath ->
                    val maskImg = ImageIO.read(masterMaskPath.toFile()) ?: return@forEach
                    val maskIdx = masterMaskPath.nameWithoutExtension.substringAfterLast("_mask_")

                    val (defMask, slimMask) =
                            if (isSlim) {
                                generateDefaultFromSlim(maskImg) to maskImg
                            } else {
                                maskImg to generateSlimFromDefault(maskImg)
                            }

                    ImageIO.write(
                            defMask,
                            "png",
                            targetDir.resolve("${partName}_Default_mask_$maskIdx.png").toFile()
                    )
                    ImageIO.write(
                            slimMask,
                            "png",
                            targetDir.resolve("${partName}_Slim_mask_$maskIdx.png").toFile()
                    )
                }
            }
        }

        writeMetadata(
                targetDir,
                partName,
                hasArms,
                isSlim,
                isDress,
                dressLength,
                blink.isBlink,
                blink.blinkStyle,
                blink.blinkHeight,
                blink.blinkEyeColumns,
                blink.blinkEyelidX,
                blink.blinkEyelidY,
                displayNameOverride
        )
    }

    private fun hasArmPixels(image: BufferedImage): Boolean {
        // Check all arm UV regions (Default and Slim)
        val regions =
                listOf(
                        SkinUv.Rect(40, 16, 16, 16), // Right Arm Base
                        SkinUv.Rect(40, 32, 16, 16), // Right Arm Overlay
                        SkinUv.Rect(32, 48, 16, 16), // Left Arm Base
                        SkinUv.Rect(48, 48, 16, 16) // Left Arm Overlay
                )
        for (r in regions) {
            for (x in r.x until (r.x + r.w)) {
                for (y in r.y until (r.y + r.h)) {
                    if (x < image.width && y < image.height) {
                        if ((image.getRGB(x, y) ushr 24) != 0) return true
                    }
                }
            }
        }
        return false
    }

    private data class ArmRegion(
            val startX: Int,
            val mainY: IntRange,
            val topY: IntRange,
            val isRight: Boolean
    ) {
        val faceY: Int
            get() = mainY.first
    }

    private val ARM_REGIONS =
            listOf(
                    ArmRegion(
                            startX = 40,
                            mainY = 20..31,
                            topY = 16..19,
                            isRight = true
                    ), // Right Arm Base
                    ArmRegion(
                            startX = 40,
                            mainY = 36..47,
                            topY = 32..35,
                            isRight = true
                    ), // Right Arm Overlay
                    ArmRegion(
                            startX = 32,
                            mainY = 52..63,
                            topY = 48..51,
                            isRight = false
                    ), // Left Arm Base
                    ArmRegion(
                            startX = 48,
                            mainY = 52..63,
                            topY = 48..51,
                            isRight = false
                    ) // Left Arm Overlay
            )

    private fun isSlimArmModel(image: BufferedImage): Boolean {
        // True "Default-only" offsets where Slim has gaps:
        // Alex has 3px width but 4px depth.
        // Faces: Depth(4) + Width(3) + Depth(4) + Width(3) = 14px total.
        // Steve: Depth(4) + Width(4) + Depth(4) + Width(4) = 16px total.
        // Therefore, only offsets 14 and 15 are guaranteed to be empty in Slim MainY region.
        val gapOffsets = listOf(14, 15)
        var gapPixels = 0
        for (arm in ARM_REGIONS) {
            for (off in gapOffsets) {
                val x = arm.startX + off
                for (y in arm.mainY) {
                    if (y < image.height && x < image.width) {
                        if ((image.getRGB(x, y) ushr 24) != 0) {
                            gapPixels++
                        }
                    }
                }
            }
        }
        // With corrected offsets, we can use a much lower threshold (e.g. 2 pixels)
        if (gapPixels > 2) {
            plugin.logger.info("Part identified as DEFAULT ($gapPixels pixels in true gaps)")
            return false
        }
        plugin.logger.info("Part identified as SLIM (gapPixels=$gapPixels)")
        return true
    }

    private fun generateDefaultFromSlim(slim: BufferedImage): BufferedImage {
        val out = copyImage(slim)
        for (arm in ARM_REGIONS) {
            val sx = arm.startX
            // Sequence of operations that preserve depth faces (Left/Right) while expanding width
            // faces (Front/Back/Top)

            // 1. Back face (+12)
            shiftRightOnly(out, sx + 11, 3, arm.mainY) // back-side faces
            expandMiddle(out, sx + 12, arm.mainY)

            // 2. Right face (+8): Depth face (4x12). Just shift from +7 to +8.
            shiftRightOnly(out, sx + 7, 4, arm.mainY)

            // 3. Front face (+4): Grow to +7
            expandMiddle(out, sx + 4, arm.mainY)

            // Top/Bottom (Caps are 3x4 in Alex, grow to 4x4 in Steve)
            shiftRightOnly(out, sx + 7, 3, arm.topY) // bottom cap: shift 3px width starting at sx+7
            expandMiddle(out, sx + 8, arm.topY) // expand bottom cap width using middle duplication
            expandMiddle(
                    out,
                    sx + 4,
                    arm.topY
            ) // top cap: expand width at sx+4 using middle duplication
        }
        return out
    }

    private fun generateSlimFromDefault(default: BufferedImage): BufferedImage {
        val out = copyImage(default)
        for (arm in ARM_REGIONS) {
            val sx = arm.startX
            // Correct Order: Move from Left to Right to avoid clearing already-shifted pixels.
            // Alex Target UVs: Front(4..6), Right(7..10), Back(11..13)

            // 1. Front face (+4): Just shrink to 3px. Perfect for sx+4.
            shrinkMiddle(out, sx + 4, arm.mainY, arm.isRight)

            // 2. Right face (+8): Depth face (4x12). Shift from 8..11 to 7..10.
            shiftLeftOnly(out, sx + 8, 4, arm.mainY)

            // 3. Back face (+12): Shrink to 3px, then shift from 12..14 to 11..13.
            shrinkMiddle(out, sx + 12, arm.mainY, !arm.isRight)
            shiftLeftOnly(out, sx + 12, 3, arm.mainY)

            // Caps: Top cap follows Front (+4), Bottom follows Right/Back loop (+8)
            shrinkMiddle(out, sx + 4, arm.topY, arm.isRight)

            shrinkMiddle(out, sx + 8, arm.topY, arm.isRight)
            shiftLeftOnly(out, sx + 8, 3, arm.topY)
        }
        return out
    }

    private fun expandMiddle(image: BufferedImage, x: Int, yr: IntRange) {
        for (y in yr) {
            if (y >= image.height) continue
            // C0, C1, C2 -> C0, C1, C1, C2
            val c1 = image.getRGB(x + 1, y)
            val c2 = image.getRGB(x + 2, y)
            image.setRGB(x + 3, y, c2)
            image.setRGB(x + 2, y, c1)
        }
    }

    private fun shrinkMiddle(image: BufferedImage, x: Int, yr: IntRange, dropLeft: Boolean) {
        for (y in yr) {
            if (y >= image.height) continue
            if (dropLeft) {
                // Erase col 1 (innermost for some faces)
                // C0, C1, C2, C3 -> C0, C2, C3
                image.setRGB(x + 1, y, image.getRGB(x + 2, y))
                image.setRGB(x + 2, y, image.getRGB(x + 3, y))
                image.setRGB(x + 3, y, 0)
            } else {
                // Erase col 2 (innermost for other faces)
                // C0, C1, C2, C3 -> C0, C1, C3
                image.setRGB(x + 2, y, image.getRGB(x + 3, y))
                image.setRGB(x + 3, y, 0)
            }
        }
    }

    // (Removed obsolete Edge expansion/shrinking helpers)

    // (Removed obsolete expansion/shrinking helpers)

    private fun copyImage(original: BufferedImage): BufferedImage {
        val b = BufferedImage(original.width, original.height, original.type)
        val g = b.createGraphics()
        g.drawImage(original, 0, 0, null)
        g.dispose()
        return b
    }

    // (Removed unused shiftRightAndExpand)

    private fun shiftRightOnly(image: BufferedImage, x: Int, w: Int, yr: IntRange) {
        for (y in yr) {
            if (y >= image.height) continue
            for (col in (x + w - 1) downTo x) {
                if (col + 1 < image.width) {
                    image.setRGB(col + 1, y, image.getRGB(col, y))
                    image.setRGB(col, y, 0)
                }
            }
        }
    }

    private fun shiftLeftOnly(image: BufferedImage, x: Int, w: Int, yr: IntRange) {
        for (y in yr) {
            if (y >= image.height) continue
            for (col in x until (x + w)) {
                if (col - 1 >= 0) {
                    image.setRGB(col - 1, y, image.getRGB(col, y))
                    image.setRGB(col, y, 0)
                }
            }
        }
    }

    // (Removed unused expandInPlace, shrinkInPlace)

    private fun maybePreprocess(path: Path) {
        if (!plugin.config.getBoolean("plugin.preprocessing.enabled", true)) return
        val fileName = path.nameWithoutExtension
        if (path.fileName.toString().lowercase().matches(Regex(".*_mask_\\d+\\.png"))) return
        val mask1 = path.parent.resolve("${fileName}_mask_1.png")
        if (Files.exists(mask1)) return

        preprocessImage(path)
    }

    private fun preprocessImage(
            sourcePath: Path,
            strategy: MaskStrategy = defaultStrategy(),
            distanceOrChannels: Any? = null
    ) {
        val image = ImageIO.read(sourcePath.toFile()) ?: return
        val sanitized = sanitizeUv(image)

        val clusters =
                clusterColors(
                        sanitized,
                        strategy,
                        distanceOrChannels,
                        params = currentRemaskParameters()
                )
        writeMasks(sourcePath, sanitized, clusters)
        reorderAllMaskChannelsInPart(sourcePath.parent)
        // overwrite source with sanitized (remove UV junk)
        ImageIO.write(sanitized, "png", sourcePath.toFile())
    }

    fun generatePreviewImage(
            sourcePath: Path,
            strategy: MaskStrategy,
            params: RemaskParameters,
            targetSlim: Boolean,
            distanceOrChannels: Any? = null
    ): BufferedImage? {
        val image =
                try {
                    ImageIO.read(sourcePath.toFile())
                } catch (_: Exception) {
                    return null
                }
        val sanitized = sanitizeUv(image)

        // Load metadata to check if the asset is natively Slim
        val dir = sourcePath.parent
        val metadata = loadMetadata(dir)
        val isAlexAsset = metadata["isAlex"] as? Boolean ?: false
        val hasArms = metadata["hasArms"] as? Boolean ?: false

        val clusters = clusterColors(sanitized, strategy, distanceOrChannels, params)

        val preview = BufferedImage(sanitized.width, sanitized.height, BufferedImage.TYPE_INT_ARGB)
        clusters.forEachIndexed { idx, cluster ->
            val color = previewVibrantColors[idx % previewVibrantColors.size]
            cluster.pixels.forEach { (x, y) -> preview.setRGB(x, y, color.rgb) }
        }

        // Apply shared conversion logic if mismatch found
        return if (hasArms && isAlexAsset != targetSlim) {
            if (isAlexAsset) generateDefaultFromSlim(preview) else generateSlimFromDefault(preview)
        } else {
            preview
        }
    }

    /**
     * Updates ETF-related settings for a specific part and persists them to metadata.json.
     * Use null for values that shouldn't be changed.
     */
    fun updateEtfSettings(
        layerId: String,
        partId: String,
        blinkHeight: Int? = null,
        blinkStyle: Int? = null,
        blinkEyeColumns: List<Int>? = null,
        blinkEyelidX: Int? = null,
        blinkEyelidY: Int? = null,
        dressLength: Int? = null,
        jacketStyle: Int? = null
    ): String {
        val (def, options) = loadedLayers[layerId] ?: return "Layer '$layerId' not found."
        val opt = options.find { it.id == partId } ?: return "Part '$partId' not found in '$layerId'."
        val dir = opt.directory ?: return "Part '$partId' has no directory (synthetic?)"

        val metaFile = dir.resolve("metadata.json")
        if (!metaFile.exists()) return "metadata.json not found for '$partId'."

        val content = Files.readString(metaFile)
        
        // Update values in JSON string (using regex for simplicity and consistency with loadMetadata)
        var newJson = content
        
        fun updateField(key: String, value: Int?) {
            if (value == null) return
            val regex = Regex("\"$key\":\\s*\\d+")
            if (regex.containsMatchIn(newJson)) {
                newJson = regex.replace(newJson, "\"$key\": $value")
            } else {
                // Insert before the last closing brace if it doesn't exist
                val lastBrace = newJson.lastIndexOf('}')
                if (lastBrace != -1) {
                    val leadingComma = if (newJson.trim().dropLast(1).trim().last() == ',') "" else ","
                    newJson = newJson.substring(0, lastBrace).trimEnd() + 
                              "$leadingComma\n    \"$key\": $value\n" + 
                              newJson.substring(lastBrace)
                }
            }
        }

        fun updateBooleanField(key: String, value: Boolean?) {
            if (value == null) return
            val regex = Regex("\"$key\":\\s*(true|false)")
            if (regex.containsMatchIn(newJson)) {
                newJson = regex.replace(newJson, "\"$key\": $value")
            } else {
                // Insert before the last closing brace
                val lastBrace = newJson.lastIndexOf('}')
                if (lastBrace != -1) {
                    val leadingComma = if (newJson.trim().dropLast(1).trim().last() == ',') "" else ","
                    newJson = newJson.substring(0, lastBrace).trimEnd() + 
                              "$leadingComma\n    \"$key\": $value\n" + 
                              newJson.substring(lastBrace)
                }
            }
        }

        fun updateIntArrayField(key: String, values: List<Int>?) {
            if (values == null) return
            val sorted = values.sorted().distinct()
            val rendered = sorted.joinToString(", ")
            val regex = Regex("\"$key\":\\s*\\[[^]]*\\]")
            if (regex.containsMatchIn(newJson)) {
                newJson = regex.replace(newJson, "\"$key\": [$rendered]")
            } else {
                val lastBrace = newJson.lastIndexOf('}')
                if (lastBrace != -1) {
                    val leadingComma = if (newJson.trim().dropLast(1).trim().last() == ',') "" else ","
                    newJson =
                            newJson.substring(0, lastBrace).trimEnd() +
                                    "$leadingComma\n    \"$key\": [$rendered]\n" +
                                    newJson.substring(lastBrace)
                }
            }
        }

        if (blinkHeight != null) {
            if (blinkHeight > 0) {
                updateField("blinkHeight", blinkHeight)
                updateField("blinkStyle", blinkStyle)
                updateBooleanField("isBlink", true)
                if (blinkEyeColumns != null) {
                    updateIntArrayField("blinkEyeColumns", blinkEyeColumns)
                }
                if (blinkEyelidX != null) {
                    updateField("blinkEyelidX", blinkEyelidX)
                }
                if (blinkEyelidY != null) {
                    updateField("blinkEyelidY", blinkEyelidY)
                }
            } else {
                updateBooleanField("isBlink", false)
            }
        } else {
            // If only style was provided (though unlikely from our current UI)
            updateField("blinkStyle", blinkStyle)
            if (blinkEyeColumns != null) {
                updateIntArrayField("blinkEyeColumns", blinkEyeColumns)
            }
            if (blinkEyelidX != null) {
                updateField("blinkEyelidX", blinkEyelidX)
            }
            if (blinkEyelidY != null) {
                updateField("blinkEyelidY", blinkEyelidY)
            }
        }

        if (dressLength != null) {
            if (dressLength > 0) {
                updateField("dressLength", dressLength)
                updateField("jacketStyle", jacketStyle)
                updateBooleanField("isDress", true)
            } else {
                updateBooleanField("isDress", false)
            }
        } else {
            updateField("jacketStyle", jacketStyle)
        }

        Files.writeString(metaFile, newJson)
        
        // Reload the layer to reflect changes
        reloadLayer(layerId)
        
        return "Updated ETF settings for '$partId' in '$layerId'."
    }

    fun commitRemask(
            layerId: String,
            partId: String,
            strategy: MaskStrategy,
            params: RemaskParameters,
            distanceOrChannels: Any? = null
    ): String {
        val option =
                findPartById(layerId, partId) ?: return "Part '$partId' not found in '$layerId'"
        val dir = option.directory ?: return "Part has no directory"

        // Resolve Master Path from metadata authority if possible
        val metadataMap = loadMetadata(dir)
        @Suppress("UNCHECKED_CAST")
        val mappings = metadataMap["mappings"] as? Map<String, Any> ?: emptyMap()
        val masterPathString = mappings["master"] as? String ?: "${dir.name}.png"
        val masterPath = dir.resolve(masterPathString)

        if (!masterPath.exists()) return "Master asset not found at $masterPath"

        // 1. Delete ALL masks in the directory
        Files.list(dir).use { stream ->
            stream.filter { it.name.lowercase().contains("_mask_") }.forEach {
                Files.deleteIfExists(it)
            }
        }

        // 2. Remask Master
        val masterImg = ImageIO.read(masterPath.toFile()) ?: return "Failed to read master asset"

        // Detect blink on raw image
        val blink = detectBlink(masterImg)

        val sanitized = sanitizeUv(masterImg)
        val clusters = clusterColors(sanitized, strategy, distanceOrChannels, params)
        writeMasks(masterPath, sanitized, clusters)
        ImageIO.write(sanitized, "png", masterPath.toFile())

        // 3. Propagate to Variants if this part has arms
        val hasArms = metadataMap["hasArms"] as? Boolean ?: false
        val isAlexAsset = metadataMap["isAlex"] as? Boolean ?: false

        if (hasArms) {
            // Re-read master masks just generated
            Files.list(dir).use { stream ->
                stream.filter { it.nameWithoutExtension.startsWith("${dir.name}_mask_") }.forEach {
                        masterMaskPath ->
                    val maskImg = ImageIO.read(masterMaskPath.toFile()) ?: return@forEach
                    val maskIdx = masterMaskPath.nameWithoutExtension.substringAfterLast("_mask_")

                    val (defMask, slimMask) =
                            if (isAlexMatch(isAlexAsset)) { // Helper for clarity
                                generateDefaultFromSlim(maskImg) to maskImg
                            } else {
                                maskImg to generateSlimFromDefault(maskImg)
                            }

                    // We use dir.name as partName consistent with preprocessPart
                    ImageIO.write(
                            defMask,
                            "png",
                            dir.resolve("${dir.name}_Default_mask_$maskIdx.png").toFile()
                    )
                    ImageIO.write(
                            slimMask,
                            "png",
                            dir.resolve("${dir.name}_Slim_mask_$maskIdx.png").toFile()
                    )
                }
            }
        }

        reorderAllMaskChannelsInPart(dir)

        val (isDress, dressLength) = detectDress(sanitized)
        writeMetadata(
                dir,
                dir.name,
                hasArms,
                isAlexAsset,
                isDress,
                dressLength,
                blink.isBlink,
                blink.blinkStyle,
                blink.blinkHeight,
                blink.blinkEyeColumns,
                blink.blinkEyelidX,
                blink.blinkEyelidY
        )
        refreshLoadedOptionFromMetadata(layerId, partId, dir)
        return "Remasked '$partId' in '$layerId' using ${strategy.name}: ${clusters.size} mask(s) generated and propagated"
    }

    data class MaskChannelRewrite(
            val targetOldIdx: Int?,
            val mappingOldToNew: Map<Int, Int>,
            val mergedOldIndices: Set<Int>,
            val deletedOldIndices: Set<Int>
    )

    data class MaskChannelRewriteResult(
            val master: MaskChannelRewrite,
            val default: MaskChannelRewrite,
            val slim: MaskChannelRewrite
    )

    private fun emptyRewrite(): MaskChannelRewrite =
            MaskChannelRewrite(targetOldIdx = null, mappingOldToNew = emptyMap(), mergedOldIndices = emptySet(), deletedOldIndices = emptySet())

    private fun loadMaskIndexMap(dir: Path, prefix: String): MutableMap<Int, Path> {
        val files = mutableMapOf<Int, Path>()
        Files.list(dir).use { stream ->
            stream.filter { it.name.lowercase().contains("_mask_") }.forEach { p ->
                val name = p.nameWithoutExtension
                if (prefix.isNotEmpty() && !name.contains(prefix)) return@forEach
                if (prefix.isEmpty() && (name.contains("_Default_") || name.contains("_Slim_"))) return@forEach
                val idx = name.substringAfterLast("_mask_").toIntOrNull() ?: return@forEach
                files[idx] = p
            }
        }
        return files
    }

    private fun baseMaskName(dir: Path, prefix: String): String =
            if (prefix.isEmpty()) dir.name
            else if (prefix == "_Default_") "${dir.name}_Default"
            else if (prefix == "_Slim_") "${dir.name}_Slim"
            else dir.name

    private fun resolvePartMasterPath(dir: Path): Path? {
        val metadataMap = loadMetadata(dir)
        @Suppress("UNCHECKED_CAST")
        val mappings = metadataMap["mappings"] as? Map<String, Any> ?: emptyMap()
        val masterPathString = mappings["master"] as? String ?: "${dir.fileName}.png"
        val masterPath = dir.resolve(masterPathString)
        if (Files.exists(masterPath)) return masterPath
        val fallback = dir.resolve("${dir.fileName}.png")
        return fallback.takeIf { Files.exists(it) }
    }

    private fun countOpaqueMaskPixels(image: java.awt.image.BufferedImage): Int {
        val w = image.width
        val h = image.height
        val data = IntArray(w * h)
        image.getRGB(0, 0, w, h, data, 0, w)
        return data.count { (it ushr 24) and 0xFF != 0 }
    }

    private fun maskTieBreak(image: java.awt.image.BufferedImage): Int {
        val w = image.width
        val h = image.height
        val data = IntArray(w * h)
        image.getRGB(0, 0, w, h, data, 0, w)
        for (i in data.indices) {
            if ((data[i] ushr 24) and 0xFF != 0) {
                return (i % w) * 10000 + (i / w)
            }
        }
        return 0
    }

    /**
     * True when most masked pixels on [master] are low-saturation / low-brightness (neutral channel).
     */
    private fun isPredominantlyNeutralMask(
            mask: java.awt.image.BufferedImage,
            master: java.awt.image.BufferedImage,
            neutralSat: Float,
            neutralBriLow: Float
    ): Boolean {
        val w = minOf(mask.width, master.width)
        val h = minOf(mask.height, master.height)
        var masked = 0
        var neutral = 0
        for (x in 0 until w) {
            for (y in 0 until h) {
                if ((mask.getRGB(x, y) ushr 24) and 0xFF == 0) continue
                masked++
                val rgb = master.getRGB(x, y)
                val hsb =
                        java.awt.Color.RGBtoHSB(
                                (rgb shr 16) and 0xFF,
                                (rgb shr 8) and 0xFF,
                                rgb and 0xFF,
                                null
                        )
                if (hsb[1] < neutralSat || hsb[2] < neutralBriLow) neutral++
            }
        }
        if (masked == 0) return false
        return neutral.toFloat() / masked >= 0.75f
    }

    /**
     * Renumber mask files so channel 1 has the most opaque pixels, then 2, etc. Neutral channels
     * (low-saturation pixels on the master) are always placed last, matching [clusterColors].
     */
    private fun reorderMaskChannelsByPixelCount(
            dir: Path,
            files: MutableMap<Int, Path>,
            prefix: String,
            masterPath: Path?
    ): Map<Int, Int> {
        val oldIndices = files.keys.toList()
        if (oldIndices.isEmpty()) return emptyMap()

        val params = currentRemaskParameters()
        val master =
                masterPath?.let { p ->
                    try {
                        ImageIO.read(p.toFile())
                    } catch (_: Exception) {
                        null
                    }
                }

        data class Entry(val oldIdx: Int, val pixels: Int, val tieBreak: Int, val isNeutral: Boolean)

        val entries =
                oldIndices.mapNotNull { old ->
                    val path = files[old] ?: return@mapNotNull null
                    val mask = loadMask(path) ?: return@mapNotNull null
                    val pixels = countOpaqueMaskPixels(mask)
                    val tieBreak = maskTieBreak(mask)
                    val isNeutral =
                            master != null &&
                                    isPredominantlyNeutralMask(
                                            mask,
                                            master,
                                            params.neutralSaturation,
                                            params.neutralBrightnessLow
                                    )
                    Entry(old, pixels, tieBreak, isNeutral)
                }

        if (entries.isEmpty()) return oldIndices.associateWith { it }

        val chromatic =
                entries
                        .filter { !it.isNeutral }
                        .sortedWith(
                                compareByDescending<Entry> { it.pixels }.thenBy { it.tieBreak }
                        )
        val neutral =
                entries
                        .filter { it.isNeutral }
                        .sortedWith(
                                compareByDescending<Entry> { it.pixels }.thenBy { it.tieBreak }
                        )
        val sorted = chromatic + neutral

        val mapping = sorted.withIndex().associate { (i, entry) -> entry.oldIdx to (i + 1) }
        if (mapping.all { it.key == it.value }) return mapping

        val temp = mutableMapOf<Int, Path>()
        for ((oldIdx, path) in files) {
            val newIdx = mapping[oldIdx] ?: continue
            if (newIdx == oldIdx) continue
            val tmp = dir.resolve("${path.nameWithoutExtension}_tmp_${UUID.randomUUID()}.png")
            Files.move(path, tmp)
            temp[oldIdx] = tmp
        }

        for ((oldIdx, tmpPath) in temp) {
            val newIdx = mapping.getValue(oldIdx)
            val final = dir.resolve("${baseMaskName(dir, prefix)}_mask_$newIdx.png")
            Files.move(tmpPath, final)
        }

        return mapping
    }

    /** Reorder master, default, and slim mask sets for a part directory. */
    private fun reorderAllMaskChannelsInPart(dir: Path) {
        val master = resolvePartMasterPath(dir)
        reorderMaskChannelsByPixelCount(dir, loadMaskIndexMap(dir, ""), "", master)
        reorderMaskChannelsByPixelCount(dir, loadMaskIndexMap(dir, "_Default_"), "_Default_", master)
        reorderMaskChannelsByPixelCount(dir, loadMaskIndexMap(dir, "_Slim_"), "_Slim_", master)
    }

    private fun loadMask(path: Path): BufferedImage? =
            try {
                ImageIO.read(path.toFile())
            } catch (_: Exception) {
                null
            }

    private fun rewriteMaskMappingsInMetadata(dir: Path) {
        val metaFile = dir.resolve("metadata.json")
        if (!metaFile.exists()) return

        val mappingsMaster = mutableMapOf<Int, String>()
        val mappingsDefault = mutableMapOf<Int, String>()
        val mappingsSlim = mutableMapOf<Int, String>()

        Files.list(dir).use { stream ->
            stream.forEach { path ->
                val name = path.nameWithoutExtension
                if (!name.contains("_mask_")) return@forEach
                val idx = name.substringAfterLast("_mask_").toIntOrNull() ?: return@forEach
                when {
                    name.contains("_Default_") -> mappingsDefault[idx] = path.name
                    name.contains("_Slim_") -> mappingsSlim[idx] = path.name
                    else -> mappingsMaster[idx] = path.name
                }
            }
        }

        fun mapToJson(m: Map<Int, String>) =
                m.entries.sortedBy { it.key }.joinToString(",") { "\"${it.key}\": \"${it.value}\"" }

        val content = try { Files.readString(metaFile) } catch (_: Exception) { return }

        fun replaceMap(key: String, jsonMap: String): String {
            val pattern = Regex("\"$key\"\\s*:\\s*\\{[\\s\\S]*?\\}")
            val repl = "\"$key\": { $jsonMap }"
            return if (pattern.containsMatchIn(content)) pattern.replace(content, repl) else content
        }

        var updated = content
        updated = Regex("\"masks\"\\s*:\\s*\\{[\\s\\S]*?\\}").replace(updated, "\"masks\": { ${mapToJson(mappingsMaster)} }")
        updated = Regex("\"masksDefault\"\\s*:\\s*\\{[\\s\\S]*?\\}").replace(updated, "\"masksDefault\": { ${mapToJson(mappingsDefault)} }")
        updated = Regex("\"masksSlim\"\\s*:\\s*\\{[\\s\\S]*?\\}").replace(updated, "\"masksSlim\": { ${mapToJson(mappingsSlim)} }")

        if (updated != content) {
            try {
                Files.writeString(metaFile, updated)
            } catch (_: Exception) {
                // ignore
            }
        }
    }

    fun mergeMaskChannels(layerId: String, partId: String, channels: List<Int>): Pair<String, MaskChannelRewriteResult> {
        val option =
                findPartById(layerId, partId)
                        ?: return "Part '$partId' not found in '$layerId'" to
                                MaskChannelRewriteResult(emptyRewrite(), emptyRewrite(), emptyRewrite())
        val dir =
                option.directory
                        ?: return "Part has no directory" to
                                MaskChannelRewriteResult(emptyRewrite(), emptyRewrite(), emptyRewrite())
        if (!dir.exists() || !dir.isDirectory())
            return "Part directory not found" to
                    MaskChannelRewriteResult(emptyRewrite(), emptyRewrite(), emptyRewrite())

        val unique = channels.distinct().sorted()
        if (unique.size < 2) return ("Provide 2+ channels to merge (e.g. 1 2 4)") to MaskChannelRewriteResult(emptyRewrite(), emptyRewrite(), emptyRewrite())
        val target = unique.first()
        val mergeSet = unique.toSet()
        val partMaster = resolvePartMasterPath(dir)

        fun mergeVariant(prefix: String): MaskChannelRewrite {
            val files = loadMaskIndexMap(dir, prefix)
            val existing = files.keys.sorted()
            if (target !in existing) {
                val mapping = reorderMaskChannelsByPixelCount(dir, files, prefix, partMaster)
                return MaskChannelRewrite(targetOldIdx = null, mappingOldToNew = mapping, mergedOldIndices = emptySet(), deletedOldIndices = emptySet())
            }

            val toMerge = existing.filter { it in mergeSet && it in files }
            if (toMerge.size < 2) {
                val mapping = reorderMaskChannelsByPixelCount(dir, files, prefix, partMaster)
                return MaskChannelRewrite(targetOldIdx = target, mappingOldToNew = mapping, mergedOldIndices = emptySet(), deletedOldIndices = emptySet())
            }

            val baseImg = loadMask(files.getValue(target)) ?: run {
                val mapping = reorderMaskChannelsByPixelCount(dir, files, prefix, partMaster)
                return MaskChannelRewrite(targetOldIdx = target, mappingOldToNew = mapping, mergedOldIndices = emptySet(), deletedOldIndices = emptySet())
            }

            val w = baseImg.width
            val h = baseImg.height
            val merged = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
            merged.graphics.drawImage(baseImg, 0, 0, null)

            for (ch in toMerge) {
                if (ch == target) continue
                val img = loadMask(files.getValue(ch)) ?: continue
                for (x in 0 until w) {
                    for (y in 0 until h) {
                        val a = merged.getRGB(x, y) ushr 24 and 0xFF
                        val b = img.getRGB(x, y) ushr 24 and 0xFF
                        val outA = maxOf(a, b)
                        if (outA != a) merged.setRGB(x, y, (outA shl 24) or 0x00FFFFFF)
                    }
                }
            }

            ImageIO.write(merged, "png", files.getValue(target).toFile())

            val deleted = mutableSetOf<Int>()
            for (ch in toMerge) {
                if (ch == target) continue
                Files.deleteIfExists(files.getValue(ch))
                files.remove(ch)
                deleted += ch
            }

            val mapping = reorderMaskChannelsByPixelCount(dir, files, prefix, partMaster)

            return MaskChannelRewrite(
                    targetOldIdx = target,
                    mappingOldToNew = mapping,
                    mergedOldIndices = toMerge.toSet(),
                    deletedOldIndices = deleted
            )
        }

        val rwMaster = mergeVariant("")
        val rwDef = mergeVariant("_Default_")
        val rwSlim = mergeVariant("_Slim_")

        // Keep metadata.json mappings in sync with on-disk masks.
        rewriteMaskMappingsInMetadata(dir)
        refreshLoadedOptionFromMetadata(layerId, partId, dir)
        val remainingMaster = rwMaster.mappingOldToNew.values.distinct().size
        val remainingDef = rwDef.mappingOldToNew.values.distinct().size
        val remainingSlim = rwSlim.mappingOldToNew.values.distinct().size
        val msg =
                "Merged channels ${unique.joinToString(",")} → $target for '$partId'. Remaining masks: master=$remainingMaster default=$remainingDef slim=$remainingSlim."
        return msg to MaskChannelRewriteResult(rwMaster, rwDef, rwSlim)
    }

    fun deleteMaskChannel(layerId: String, partId: String, channel: Int): Pair<String, MaskChannelRewriteResult> {
        val option = findPartById(layerId, partId) ?: return "Part '$partId' not found in '$layerId'" to MaskChannelRewriteResult(emptyRewrite(), emptyRewrite(), emptyRewrite())
        val dir = option.directory ?: return "Part has no directory" to MaskChannelRewriteResult(emptyRewrite(), emptyRewrite(), emptyRewrite())
        if (!dir.exists() || !dir.isDirectory()) return "Part directory not found" to MaskChannelRewriteResult(emptyRewrite(), emptyRewrite(), emptyRewrite())
        val ch = channel
        if (ch <= 0) return "Channel must be >= 1" to MaskChannelRewriteResult(emptyRewrite(), emptyRewrite(), emptyRewrite())

        val partMaster = resolvePartMasterPath(dir)

        fun deleteVariant(prefix: String): MaskChannelRewrite {
            val files = loadMaskIndexMap(dir, prefix)
            val existing = files.keys.sorted()
            if (ch !in existing) {
                val mapping = reorderMaskChannelsByPixelCount(dir, files, prefix, partMaster)
                return MaskChannelRewrite(targetOldIdx = null, mappingOldToNew = mapping, mergedOldIndices = emptySet(), deletedOldIndices = emptySet())
            }

            Files.deleteIfExists(files.getValue(ch))
            files.remove(ch)
            val mapping = reorderMaskChannelsByPixelCount(dir, files, prefix, partMaster)
            return MaskChannelRewrite(targetOldIdx = null, mappingOldToNew = mapping, mergedOldIndices = emptySet(), deletedOldIndices = setOf(ch))
        }

        val rwMaster = deleteVariant("")
        val rwDef = deleteVariant("_Default_")
        val rwSlim = deleteVariant("_Slim_")

        // Keep metadata.json mappings in sync with on-disk masks.
        rewriteMaskMappingsInMetadata(dir)
        refreshLoadedOptionFromMetadata(layerId, partId, dir)
        val remainingMaster = rwMaster.mappingOldToNew.values.distinct().size
        val remainingDef = rwDef.mappingOldToNew.values.distinct().size
        val remainingSlim = rwSlim.mappingOldToNew.values.distinct().size
        val msg =
                "Deleted mask channel $ch for '$partId'. Remaining masks: master=$remainingMaster default=$remainingDef slim=$remainingSlim."
        return msg to MaskChannelRewriteResult(rwMaster, rwDef, rwSlim)
    }

    private fun writeMetadata(
            dir: Path,
            partName: String,
            hasArms: Boolean,
            isAlex: Boolean,
            isDress: Boolean,
            dressLength: Int,
            isBlink: Boolean = false,
            blinkStyle: Int = 0,
            blinkHeight: Int = 0,
            blinkEyeColumns: List<Int> = emptyList(),
            blinkEyelidX: Int? = null,
            blinkEyelidY: Int? = null,
            displayNameOverride: String? = null
    ) {
        val mappingsMaster = mutableMapOf<Int, String>()
        val mappingsDefault = mutableMapOf<Int, String>()
        val mappingsSlim = mutableMapOf<Int, String>()

        var masterFile = "$partName.png"
        var defaultFile = if (hasArms) "${partName}_Default.png" else masterFile
        var slimFile = if (hasArms) "${partName}_Slim.png" else masterFile

        Files.list(dir).use { stream ->
            stream.forEach { path ->
                val name = path.nameWithoutExtension
                if (name.contains("_mask_")) {
                    val idx = name.substringAfterLast("_mask_").toIntOrNull() ?: return@forEach
                    if (name.contains("_Default_")) mappingsDefault[idx] = path.name
                    else if (name.contains("_Slim_")) mappingsSlim[idx] = path.name
                    else mappingsMaster[idx] = path.name
                }
            }
        }

        fun mapToJson(m: Map<Int, String>) =
                m.entries.sortedBy { it.key }.joinToString(",") { "\"${it.key}\": \"${it.value}\"" }

        val blinkExtras =
                if (isBlink) {
                    val cols = blinkEyeColumns.sorted().distinct().ifEmpty { listOf(3, 6) }
                    val ex = blinkEyelidX ?: 11
                    val ey = blinkEyelidY ?: (8 + blinkHeight - 1)
                    """,
                "blinkEyeColumns": [${cols.joinToString(", ")}],
                "blinkEyelidX": $ex,
                "blinkEyelidY": $ey"""
                } else ""

        val displayNameJson =
                JsonPrimitive(displayNameOverride ?: toDisplayName(partName)).toString()
        val internalKeyJson = JsonPrimitive(slugify(partName)).toString()

        val json =
                """
            {
                "displayName": $displayNameJson,
                "internalKey": $internalKeyJson,
                "hasArms": $hasArms,
                "isAlex": $isAlex,
                "isDress": $isDress,
                "dressLength": $dressLength,
                "isBlink": $isBlink,
                "blinkStyle": $blinkStyle,
                "blinkHeight": $blinkHeight$blinkExtras,
                "mappings": {
                    "master": "$masterFile",
                    "default": "$defaultFile",
                    "slim": "$slimFile",
                    "masks": { ${mapToJson(mappingsMaster)} },
                    "masksDefault": { ${mapToJson(mappingsDefault)} },
                    "masksSlim": { ${mapToJson(mappingsSlim)} }
                }
            }
        """.trimIndent()

        Files.writeString(dir.resolve("metadata.json"), json)
    }

    private fun isAlexMatch(isSlim: Boolean) = isSlim // Semantic helper

    /** Reload a single layer's options (re-reads files from disk). */
    private fun reloadLayer(layerId: String) {
        val (definition, _) = loadedLayers[layerId] ?: return
        val root = plugin.config.getConfigurationSection("layers") ?: return
        val definitions = root.getConfigurationSection("definitions") ?: return
        val optionConfig =
                definitions.getConfigurationSection(layerId)?.getConfigurationSection("options")
        val newOptions = loadOptions(definition, optionConfig)
        loadedLayers[layerId] = definition to newOptions
    }

    private fun sanitizeUv(image: java.awt.image.BufferedImage): java.awt.image.BufferedImage {
        val out =
                java.awt.image.BufferedImage(
                        image.width,
                        image.height,
                        java.awt.image.BufferedImage.TYPE_INT_ARGB
                )
        for (x in 0 until image.width) {
            for (y in 0 until image.height) {
                val argb = image.getRGB(x, y)
                if ((argb ushr 24) == 0) continue
                // Also preserve ETF Blink marker region (12..19, 16..19)
                val inBlinkRegion = x in 12..19 && y in 16..19
                if (SkinUv.isInAnyUv(x, y) || inBlinkRegion) {
                    out.setRGB(x, y, argb)
                }
            }
        }
        return out
    }

    // ── Slim → default arm-column fix ─────────────────────────────────────

    // (Redundant block removed)

    private fun fillGapColumn(
            image: java.awt.image.BufferedImage,
            gapX: Int,
            sourceX: Int,
            verifyX: Int,
            yRange: IntRange
    ) {
        if (gapX >= image.width || sourceX >= image.width) return
        val gapEmpty = yRange.all { y -> (image.getRGB(gapX, y) ushr 24) == 0 }
        if (!gapEmpty) return
        val verifyFilled = yRange.any { y -> (image.getRGB(verifyX, y) ushr 24) != 0 }
        if (!verifyFilled) return
        for (y in yRange) {
            image.setRGB(gapX, y, image.getRGB(sourceX, y))
        }
    }

    private fun isSlimAsset(path: Path): Boolean =
            path.nameWithoutExtension.endsWith("_slim", ignoreCase = true)

    // ── Masking strategies ────────────────────────────────────────────────

    enum class MaskStrategy {
        HSB,
        HUE,
        RGB,
        SATURATION_BANDS,
        BRIGHTNESS_BANDS,
        LAB,
        EDGE_AWARE
    }

    companion object {
        val STRATEGY_NAMES: List<String> = MaskStrategy.entries.map { it.name }
    }

    private data class Cluster(val pixels: MutableList<Pair<Int, Int>>)

    data class RemaskParameters(
            val chromaticDistance: Float,
            val neutralSaturation: Float,
            val neutralBrightnessLow: Float,
            val neutralThresholdPercent: Float
    )

    private val previewVibrantColors =
            listOf(
                    Color.RED,
                    Color.GREEN,
                    Color.BLUE,
                    Color.YELLOW,
                    Color.MAGENTA,
                    Color.CYAN,
                    Color.ORANGE,
                    Color.PINK,
                    Color.WHITE,
                    Color(128, 0, 128), // Purple
                    Color(0, 128, 128), // Teal
                    Color(128, 128, 0) // Olive
            )

    private data class ColorPixel(
            val h: Float,
            val s: Float,
            val b: Float,
            val r: Int,
            val g: Int,
            val bl: Int,
            val x: Int,
            val y: Int
    )

    enum class GroupingMode {
        DISTANCE,
        CHANNELS
    }

    fun findPartById(layerId: String, partId: String): LayerOption? {
        val (_, options) = loadedLayers[layerId] ?: return null
        return options.find { it.id.equals(partId, ignoreCase = true) }
    }

    fun defaultStrategy(): MaskStrategy {
        val name = plugin.config.getString("plugin.preprocessing.default-strategy", "RGB") ?: "RGB"
        return try {
            MaskStrategy.valueOf(name.uppercase())
        } catch (_: Exception) {
            MaskStrategy.RGB
        }
    }

    fun defaultGroupingMode(): GroupingMode {
        val name =
                plugin.config.getString("plugin.preprocessing.grouping-mode", "DISTANCE")
                        ?: "DISTANCE"
        return try {
            GroupingMode.valueOf(name.uppercase())
        } catch (_: Exception) {
            GroupingMode.DISTANCE
        }
    }

    fun defaultDistance(): Float {
        return plugin.config.getDouble("plugin.preprocessing.default-distance", 0.15).toFloat()
    }

    fun defaultChannels(): Int {
        return plugin.config.getInt("plugin.preprocessing.default-channels", 2).coerceIn(1, 8)
    }

    private fun neutralThresholdPercent(): Float {
        return plugin.config
                .getDouble("plugin.preprocessing.neutral-threshold-percent", 0.05)
                .toFloat()
    }

    fun currentRemaskParameters(): RemaskParameters {
        return RemaskParameters(
                chromaticDistance = defaultDistance(),
                neutralSaturation =
                        plugin.config
                                .getDouble("plugin.preprocessing.neutral-saturation", 0.15)
                                .toFloat(),
                neutralBrightnessLow =
                        plugin.config
                                .getDouble("plugin.preprocessing.neutral-brightness-low", 0.15)
                                .toFloat(),
                neutralThresholdPercent = neutralThresholdPercent()
        )
    }

    private fun colorDistance(rgb1: Int, rgb2: Int): Double {
        val r1 = (rgb1 shr 16) and 0xFF
        val g1 = (rgb1 shr 8) and 0xFF
        val b1 = rgb1 and 0xFF
        val r2 = (rgb2 shr 16) and 0xFF
        val g2 = (rgb2 shr 8) and 0xFF
        val b2 = rgb2 and 0xFF
        return Math.sqrt(((r1 - r2) * (r1 - r2) + (g1 - g2) * (g1 - g2) + (b1 - b2) * (b1 - b2)).toDouble())
    }

    private data class BlinkDetectResult(
            val isBlink: Boolean,
            val blinkStyle: Int = 0,
            val blinkHeight: Int = 0,
            val blinkEyeColumns: List<Int> = emptyList(),
            val blinkEyelidX: Int? = null,
            val blinkEyelidY: Int? = null
    )

    private fun detectBlinkEyeColumns(image: BufferedImage, faceY: Int, skinRef: Int): List<Int> {
        val neutralSat = plugin.config.getDouble("plugin.preprocessing.neutral-saturation", 0.15)
        return BlinkEyeGeometry.detectEyeColumns(image, faceY, skinRef, neutralSat)
    }

    private fun blinkWithEyeGeometry(image: BufferedImage, style: Int, height: Int): BlinkDetectResult {
        val h = height.coerceIn(1, 8)
        val faceY = 8 + h - 1
        val skinRef =
                BlinkEyeGeometry.dominantNoseSkinColor(image)
                        ?: run {
                            val bridge = image.getRGB(11, faceY)
                            if ((bridge ushr 24) != 0) bridge else null
                        }
                        ?: 0xFFE0C0.toInt()
        val cols = detectBlinkEyeColumns(image, faceY, skinRef)
        return BlinkDetectResult(true, style, h, cols, 11, faceY)
    }

    private fun detectBlink(image: BufferedImage): BlinkDetectResult {
        // 1. Check if Choice Box 0 (52, 16) already has a blink color
        if (image.width >= 64 && image.height >= 64) {
            val blinkChoiceArgb = image.getRGB(SkinUv.ETF_CHOICE_BLINK_STYLE_X, SkinUv.ETF_CHOICE_BLINK_STYLE_Y)
            if ((blinkChoiceArgb ushr 24) != 0) {
                val blinkChoice = getSkinPixelColourToNumber(blinkChoiceArgb)
                if (blinkChoice in 1..5) {
                    val heightChoiceArgb = image.getRGB(SkinUv.ETF_CHOICE_BLINK_HEIGHT_X, SkinUv.ETF_CHOICE_BLINK_HEIGHT_Y)
                    val height =
                            if ((heightChoiceArgb ushr 24) != 0) {
                                getSkinPixelColourToNumber(heightChoiceArgb).coerceIn(1, 8)
                            } else {
                                1
                            }
                    return blinkWithEyeGeometry(image, blinkChoice, height)
                }
            }

            // 2. Auto-detect from marker regions
            // Scan for the overall best horizontal offset and row
            for (rows in listOf(4, 2, 1)) {
                var bestX = -1
                var bestH = -1
                var bestScore = -1.0
                val style =
                        when (rows) {
                            1 -> 3
                            2 -> 4
                            4 -> 5
                            else -> 0
                        }

                // 1. Identify rows with potential eye markers at FIXED offset 12
                val xOffset = 12

                // First verify that this marker ACTUALLY has `rows` height by checking the bottom row of the expected marker
                var markerHeightValid = true
                for (r in 0 until rows) {
                    var opaqueInRow = 0
                    for (x in 0 until 8) {
                        if ((image.getRGB(xOffset + x, 16 + r) ushr 24) != 0) opaqueInRow++
                    }
                    if (opaqueInRow < 4) {
                        markerHeightValid = false
                        break
                    }
                }

                // Also verify that the row AFTER this marker is empty to avoid false positives (e.g. classifying a 4-row marker as 2-row)
                if (markerHeightValid && rows < 4) {
                    if (16 + rows <= 19) {
                        var opaqueInRow = 0
                        for (x in 0 until 8) {
                            if ((image.getRGB(xOffset + x, 16 + rows) ushr 24) != 0) opaqueInRow++
                        }
                        if (opaqueInRow >= 4) {
                            markerHeightValid = false
                        }
                    }
                }

                if (markerHeightValid) {
                    for (h in 1..8) {
                        val faceY = 8 + (h - 1)
                        var matches = 0
                        var opaqueInMarker = 0
                        for (x in 0 until 8) {
                            val facePixel = image.getRGB(8 + x, faceY)
                            // Compare with the top row of the marker (index 16)
                            val markerPixel = image.getRGB(xOffset + x, 16)

                            if ((markerPixel ushr 24) == 0) continue
                            opaqueInMarker++

                            if (colorDistance(facePixel, markerPixel) < 60.0) {
                                matches++
                            }
                        }

                        if (matches >= 4 && opaqueInMarker >= 4) {
                            val score = matches.toDouble() + (if (h in 6..7) 2.0 else 0.0)
                            if (score > bestScore) {
                                bestScore = score
                                bestX = xOffset
                                bestH = h
                            }
                        }
                    }
                }

                if (bestX != -1) {
                    return blinkWithEyeGeometry(image, style, bestH)
                }
            }

            // 3. Auto-detect from face pixels (fallback) — see [BlinkEyeGeometry.detectFaceFallbackBlink]
            val neutralSat = plugin.config.getDouble("plugin.preprocessing.neutral-saturation", 0.15)
            BlinkEyeGeometry.detectFaceFallbackBlink(image, neutralSat)?.let { fb ->
                return BlinkDetectResult(
                        true,
                        fb.blinkStyle,
                        fb.blinkHeight,
                        fb.blinkEyeColumns,
                        11,
                        fb.primaryFaceY
                )
            }
        }
        return BlinkDetectResult(false)
    }

    private fun hasPixelsIn(image: BufferedImage, rect: SkinUv.Rect): Boolean {
        for (x in rect.x until rect.x + rect.w) {
            for (y in rect.y until rect.y + rect.h) {
                if ((image.getRGB(x, y) ushr 24) != 0) return true
            }
        }
        return false
    }

    private fun getSkinPixelColourToNumber(argb: Int): Int {
        val color = java.awt.Color(argb, true)
        // Match against SkinUv.ETF_COLORS
        SkinUv.ETF_COLORS.forEachIndexed { index, etfColor ->
            if (etfColor.red == color.red && etfColor.green == color.green && etfColor.blue == color.blue) {
                return index + 1
            }
        }
        return 0
    }

    private fun collectPixels(
            image: java.awt.image.BufferedImage,
            params: RemaskParameters
    ): Pair<List<ColorPixel>, List<ColorPixel>> {
        val neutralSat = params.neutralSaturation
        val neutralBriLow = params.neutralBrightnessLow
        val chromatic = mutableListOf<ColorPixel>()
        val neutral = mutableListOf<ColorPixel>()
        for (x in 0 until image.width) {
            for (y in 0 until image.height) {
                val argb = image.getRGB(x, y)
                if ((argb ushr 24 and 0xFF) == 0) continue
                val r = argb ushr 16 and 0xFF
                val g = argb ushr 8 and 0xFF
                val bl = argb and 0xFF
                val hsb = java.awt.Color.RGBtoHSB(r, g, bl, null)
                val pixel = ColorPixel(hsb[0], hsb[1], hsb[2], r, g, bl, x, y)
                if (hsb[1] < neutralSat || hsb[2] < neutralBriLow) {
                    neutral += pixel
                } else {
                    chromatic += pixel
                }
            }
        }
        return chromatic to neutral
    }

    private fun clusterColors(
            image: java.awt.image.BufferedImage,
            strategy: MaskStrategy = defaultStrategy(),
            distanceOrChannels: Any? = null,
            params: RemaskParameters = currentRemaskParameters()
    ): List<Cluster> {
        val (chromatic, neutral) = collectPixels(image, params)
        val totalPixels = chromatic.size + neutral.size
        if (totalPixels == 0) return emptyList()

        if (chromatic.isEmpty()) {
            val neutralThreshold = params.neutralThresholdPercent
            val hasNeutralMask = neutral.size.toFloat() / totalPixels >= neutralThreshold
            val actualNeutralCluster =
                    if (hasNeutralMask) Cluster(neutral.map { it.x to it.y }.toMutableList())
                    else null
            return listOfNotNull(actualNeutralCluster)
        }

        val mode =
                if (distanceOrChannels is Float) GroupingMode.DISTANCE
                else if (distanceOrChannels is Int) GroupingMode.CHANNELS else defaultGroupingMode()
        val distance =
                if (distanceOrChannels is Float) distanceOrChannels else params.chromaticDistance
        val channels = if (distanceOrChannels is Int) distanceOrChannels else defaultChannels()

        val baseStrategy =
                when (strategy) {
                    MaskStrategy.EDGE_AWARE -> MaskStrategy.RGB
                    else -> strategy
                }

        val rawClusters =
                if (mode == GroupingMode.CHANNELS) {
                    val k = channels
                    if (chromatic.size == 1 || k <= 1) {
                        listOf(Cluster(chromatic.map { it.x to it.y }.toMutableList()))
                    } else {
                        val effectiveK = k.coerceAtMost(chromatic.size)
                        when (baseStrategy) {
                            MaskStrategy.HSB -> clusterKMeansHsb(chromatic, effectiveK)
                            MaskStrategy.HUE -> clusterHueGap(chromatic, effectiveK)
                            MaskStrategy.RGB -> clusterKMeansRgb(chromatic, effectiveK)
                            MaskStrategy.SATURATION_BANDS ->
                                    clusterQuantileBands(chromatic, effectiveK) { it.s }
                            MaskStrategy.BRIGHTNESS_BANDS ->
                                    clusterQuantileBands(chromatic, effectiveK) { it.b }
                            MaskStrategy.LAB -> clusterKMeansLab(chromatic, effectiveK)
                            MaskStrategy.EDGE_AWARE -> clusterKMeansRgb(chromatic, effectiveK)
                        }
                    }
                } else {
                    when (baseStrategy) {
                        MaskStrategy.HSB -> clusterAgglomerativeHsb(chromatic, distance)
                        MaskStrategy.HUE -> clusterAgglomerativeHue(chromatic, distance)
                        MaskStrategy.RGB -> clusterAgglomerativeRgb(chromatic, distance)
                        MaskStrategy.SATURATION_BANDS ->
                                clusterAgglomerative1D(chromatic, distance) { it.s }
                        MaskStrategy.BRIGHTNESS_BANDS ->
                                clusterAgglomerative1D(chromatic, distance) { it.b }
                        MaskStrategy.LAB -> clusterAgglomerativeLab(chromatic, distance * 100f)
                        MaskStrategy.EDGE_AWARE -> clusterAgglomerativeRgb(chromatic, distance)
                    }
                }

        val pixelMap = (chromatic + neutral).associateBy { it.x to it.y }

        class Centroid(
                val r: Float,
                val g: Float,
                val bl: Float,
                val h: Float,
                val s: Float,
                val b: Float,
                val labL: Float,
                val labA: Float,
                val labB: Float
        )

        val clusterCentroids =
                rawClusters.map { cluster ->
                    var rSum = 0f
                    var gSum = 0f
                    var blSum = 0f
                    var sSum = 0f
                    var bSum = 0f
                    var sinSum = 0.0
                    var cosSum = 0.0
                    var lSum = 0f
                    var aSum = 0f
                    var labBSum = 0f

                    val clusterPixels = cluster.pixels.mapNotNull { pixelMap[it] }
                    for (p in clusterPixels) {
                        rSum += p.r / 255f
                        gSum += p.g / 255f
                        blSum += p.bl / 255f
                        sSum += p.s
                        bSum += p.b
                        val angle = p.h.toDouble() * 2.0 * Math.PI
                        sinSum += kotlin.math.sin(angle)
                        cosSum += kotlin.math.cos(angle)
                        val lab = rgbToLab(p.r, p.g, p.bl)
                        lSum += lab.l
                        aSum += lab.a
                        labBSum += lab.b
                    }
                    val size = clusterPixels.size.toFloat().coerceAtLeast(1f)
                    val meanH =
                            (kotlin.math.atan2(sinSum, cosSum) / (2.0 * Math.PI)).toFloat().let {
                                if (it < 0) it + 1f else it
                            }

                    Centroid(
                            r = rSum / size,
                            g = gSum / size,
                            bl = blSum / size,
                            h = meanH,
                            s = sSum / size,
                            b = bSum / size,
                            labL = lSum / size,
                            labA = aSum / size,
                            labB = labBSum / size
                    )
                }

        val trueNeutral = mutableListOf<ColorPixel>()
        val thresholdSq =
                when (baseStrategy) {
                    MaskStrategy.LAB -> {
                        val t = distance * 100f
                        t * t
                    }
                    else -> distance * distance
                }

        for (np in neutral) {
            var bestDistSq = Float.MAX_VALUE
            var bestClusterIdx = -1

            for (i in rawClusters.indices) {
                val c = clusterCentroids[i]
                val dsq =
                        when (baseStrategy) {
                            MaskStrategy.RGB -> {
                                val dr = (np.r / 255f) - c.r
                                val dg = (np.g / 255f) - c.g
                                val dbl = (np.bl / 255f) - c.bl
                                dr * dr + dg * dg + dbl * dbl
                            }
                            MaskStrategy.HSB -> {
                                val hDiff = kotlin.math.abs(np.h - c.h)
                                val hDist = kotlin.math.min(hDiff, 1f - hDiff)
                                val ds = np.s - c.s
                                val db = np.b - c.b
                                hDist * hDist + ds * ds + db * db
                            }
                            MaskStrategy.SATURATION_BANDS -> {
                                val ds = np.s - c.s
                                ds * ds
                            }
                            MaskStrategy.BRIGHTNESS_BANDS -> {
                                val db = np.b - c.b
                                db * db
                            }
                            MaskStrategy.LAB -> {
                                val lab = rgbToLab(np.r, np.g, np.bl)
                                val dl = lab.l - c.labL
                                val da = lab.a - c.labA
                                val db = lab.b - c.labB
                                dl * dl + da * da + db * db
                            }
                            MaskStrategy.HUE -> {
                                val hDiff = kotlin.math.abs(np.h - c.h)
                                val hDist = kotlin.math.min(hDiff, 1f - hDiff)
                                hDist * hDist
                            }
                            MaskStrategy.EDGE_AWARE -> {
                                val dr = (np.r / 255f) - c.r
                                val dg = (np.g / 255f) - c.g
                                val dbl = (np.bl / 255f) - c.bl
                                dr * dr + dg * dg + dbl * dbl
                            }
                        }
                if (dsq < bestDistSq) {
                    bestDistSq = dsq
                    bestClusterIdx = i
                }
            }

            if (bestClusterIdx != -1 && bestDistSq <= thresholdSq) {
                rawClusters[bestClusterIdx].pixels.add(np.x to np.y)
            } else {
                trueNeutral.add(np)
            }
        }

        val neutralThreshold = params.neutralThresholdPercent
        val hasNeutralMask = trueNeutral.size.toFloat() / totalPixels >= neutralThreshold
        val actualNeutralCluster =
                if (hasNeutralMask) Cluster(trueNeutral.map { it.x to it.y }.toMutableList())
                else null

        val sortedChromatic =
                rawClusters.sortedWith(
                        compareByDescending<Cluster> { it.pixels.size }.thenBy { cluster ->
                            cluster.pixels.firstOrNull()?.let { (x, y) -> x * 10000 + y } ?: 0
                        }
                )

        val combined =
                if (actualNeutralCluster != null) {
                    sortedChromatic + actualNeutralCluster
                } else {
                    sortedChromatic
                }

        return if (strategy == MaskStrategy.EDGE_AWARE) {
            combined.flatMap { splitDisconnected(it) }
        } else {
            combined
        }
    }

    private fun clusterQuantileBands(
            chromatic: List<ColorPixel>,
            k: Int,
            value: (ColorPixel) -> Float
    ): List<Cluster> {
        val sorted = chromatic.sortedBy(value)
        if (sorted.isEmpty()) return emptyList()
        if (k <= 1) return listOf(Cluster(sorted.map { it.x to it.y }.toMutableList()))
        if (sorted.size <= k) return sorted.map { Cluster(mutableListOf(it.x to it.y)) }

        val clusters = mutableListOf<Cluster>()
        var start = 0
        for (i in 1 until k) {
            val endExclusive = (sorted.size * i) / k
            if (endExclusive <= start) continue
            val c = Cluster(mutableListOf())
            for (j in start until endExclusive) c.pixels += sorted[j].x to sorted[j].y
            clusters += c
            start = endExclusive
        }
        val last = Cluster(mutableListOf())
        for (j in start until sorted.size) last.pixels += sorted[j].x to sorted[j].y
        clusters += last
        return clusters.filter { it.pixels.isNotEmpty() }
    }

    private fun clusterAgglomerative1D(
            chromatic: List<ColorPixel>,
            distanceThreshold: Float,
            value: (ColorPixel) -> Float
    ): List<Cluster> {
        val sorted = chromatic.sortedBy(value)
        if (sorted.isEmpty()) return emptyList()
        if (sorted.size == 1) return listOf(Cluster(mutableListOf(sorted[0].x to sorted[0].y)))

        val clusters = mutableListOf<Cluster>()
        var current = Cluster(mutableListOf(sorted[0].x to sorted[0].y))
        var prev = value(sorted[0])
        for (i in 1 until sorted.size) {
            val v = value(sorted[i])
            if (kotlin.math.abs(v - prev) > distanceThreshold) {
                clusters += current
                current = Cluster(mutableListOf())
            }
            current.pixels += sorted[i].x to sorted[i].y
            prev = v
        }
        clusters += current
        return clusters.filter { it.pixels.isNotEmpty() }
    }

    private data class Lab(val l: Float, val a: Float, val b: Float)

    /** D65 sRGB -> CIELAB (L* 0..100). */
    private fun rgbToLab(r8: Int, g8: Int, b8: Int): Lab {
        fun srgbToLinear(c: Float): Float =
                if (c <= 0.04045f) c / 12.92f
                else ((c + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()
        val r = srgbToLinear(r8 / 255f)
        val g = srgbToLinear(g8 / 255f)
        val b = srgbToLinear(b8 / 255f)

        // sRGB -> XYZ (D65)
        val x = 0.4124564f * r + 0.3575761f * g + 0.1804375f * b
        val y = 0.2126729f * r + 0.7151522f * g + 0.0721750f * b
        val z = 0.0193339f * r + 0.1191920f * g + 0.9503041f * b

        // Reference white (D65)
        val xn = 0.95047f
        val yn = 1.00000f
        val zn = 1.08883f

        fun f(t: Float): Float {
            val d = 6f / 29f
            return if (t > d * d * d) kotlin.math.cbrt(t) else (t / (3f * d * d)) + (4f / 29f)
        }

        val fx = f(x / xn)
        val fy = f(y / yn)
        val fz = f(z / zn)

        val l = (116f * fy) - 16f
        val a = 500f * (fx - fy)
        val bb = 200f * (fy - fz)
        return Lab(l, a, bb)
    }

    private fun clusterAgglomerativeLab(
            chromatic: List<ColorPixel>,
            distanceThreshold: Float
    ): List<Cluster> {
        val colorGroups = chromatic.groupBy { (it.r shl 16) or (it.g shl 8) or it.bl }
        class Node(var l: Float, var a: Float, var b: Float, val pixels: MutableList<ColorPixel>)
        val nodes =
                colorGroups.values.map { pixels ->
                    val first = pixels.first()
                    val lab = rgbToLab(first.r, first.g, first.bl)
                    Node(lab.l, lab.a, lab.b, pixels.toMutableList())
                }.toMutableList()

        val thresholdSq = distanceThreshold * distanceThreshold
        while (nodes.size > 1) {
            var bestI = -1
            var bestJ = -1
            var minDistanceSq = Float.MAX_VALUE
            for (i in 0 until nodes.size) {
                for (j in i + 1 until nodes.size) {
                    val dl = nodes[i].l - nodes[j].l
                    val da = nodes[i].a - nodes[j].a
                    val db = nodes[i].b - nodes[j].b
                    val dsq = dl * dl + da * da + db * db
                    if (dsq < minDistanceSq) {
                        minDistanceSq = dsq
                        bestI = i
                        bestJ = j
                    }
                }
            }
            if (minDistanceSq > thresholdSq || bestI == -1) break
            val a = nodes[bestI]
            val b = nodes[bestJ]
            val total = a.pixels.size + b.pixels.size
            a.l = (a.l * a.pixels.size + b.l * b.pixels.size) / total
            a.a = (a.a * a.pixels.size + b.a * b.pixels.size) / total
            a.b = (a.b * a.pixels.size + b.b * b.pixels.size) / total
            a.pixels.addAll(b.pixels)
            nodes.removeAt(bestJ)
        }
        return nodes.map { n -> Cluster(n.pixels.map { it.x to it.y }.toMutableList()) }
    }

    private fun clusterKMeansLab(chromatic: List<ColorPixel>, k: Int): List<Cluster> {
        val labs = chromatic.map { rgbToLab(it.r, it.g, it.bl) }
        fun distSq(i: Int, cl: Float, ca: Float, cb: Float): Float {
            val p = labs[i]
            val dl = p.l - cl
            val da = p.a - ca
            val db = p.b - cb
            return dl * dl + da * da + db * db
        }

        val rng = java.util.Random(chromatic.hashCode().toLong() xor 0x51C0FFEE)
        val centroidIndices = mutableListOf(rng.nextInt(chromatic.size))
        while (centroidIndices.size < k) {
            val distances =
                    FloatArray(chromatic.size) { i ->
                        centroidIndices.minOf { ci ->
                            distSq(i, labs[ci].l, labs[ci].a, labs[ci].b)
                        }
                    }
            val totalDist = distances.sum()
            if (totalDist <= 0f) break
            var r = rng.nextFloat() * totalDist
            var chosen = 0
            for (i in distances.indices) {
                r -= distances[i]
                if (r <= 0f) {
                    chosen = i
                    break
                }
            }
            centroidIndices += chosen
        }

        val cL = FloatArray(k) { labs[centroidIndices.getOrElse(it) { 0 }].l }
        val cA = FloatArray(k) { labs[centroidIndices.getOrElse(it) { 0 }].a }
        val cB = FloatArray(k) { labs[centroidIndices.getOrElse(it) { 0 }].b }
        val assignments = IntArray(chromatic.size)

        for (iter in 0 until 30) {
            var changed = false
            for (i in chromatic.indices) {
                var bestC = 0
                var bestD = Float.MAX_VALUE
                for (c in 0 until k) {
                    val d = distSq(i, cL[c], cA[c], cB[c])
                    if (d < bestD) {
                        bestD = d
                        bestC = c
                    }
                }
                if (assignments[i] != bestC) {
                    changed = true
                    assignments[i] = bestC
                }
            }
            if (!changed && iter > 0) break
            for (c in 0 until k) {
                val members = chromatic.indices.filter { assignments[it] == c }
                if (members.isNotEmpty()) {
                    cL[c] = members.map { labs[it].l.toDouble() }.average().toFloat()
                    cA[c] = members.map { labs[it].a.toDouble() }.average().toFloat()
                    cB[c] = members.map { labs[it].b.toDouble() }.average().toFloat()
                }
            }
        }
        return buildClusters(chromatic, assignments, k)
    }

    private fun splitDisconnected(cluster: Cluster): List<Cluster> {
        if (cluster.pixels.size <= 1) return listOf(cluster)
        val remaining = cluster.pixels.toMutableSet()
        val out = mutableListOf<Cluster>()
        val dirs = arrayOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
        while (remaining.isNotEmpty()) {
            val start = remaining.first()
            val q: ArrayDeque<Pair<Int, Int>> = ArrayDeque()
            q.add(start)
            remaining.remove(start)
            val pixels = mutableListOf<Pair<Int, Int>>()
            while (q.isNotEmpty()) {
                val (x, y) = q.removeFirst()
                pixels += x to y
                for ((dx, dy) in dirs) {
                    val n = (x + dx) to (y + dy)
                    if (remaining.remove(n)) q.add(n)
                }
            }
            out += Cluster(pixels)
        }
        return out
    }

    private fun clusterAgglomerativeRgb(
            chromatic: List<ColorPixel>,
            distanceThreshold: Float
    ): List<Cluster> {
        val colorGroups = chromatic.groupBy { (it.r shl 16) or (it.g shl 8) or it.bl }

        class Node(var r: Float, var g: Float, var bl: Float, val pixels: MutableList<ColorPixel>)

        val nodes =
                colorGroups
                        .values
                        .map { pixels ->
                            val first = pixels.first()
                            Node(
                                    first.r / 255f,
                                    first.g / 255f,
                                    first.bl / 255f,
                                    pixels.toMutableList()
                            )
                        }
                        .toMutableList()

        val thresholdSq = distanceThreshold * distanceThreshold

        while (nodes.size > 1) {
            var bestI = -1
            var bestJ = -1
            var minDistanceSq = Float.MAX_VALUE

            for (i in 0 until nodes.size) {
                for (j in i + 1 until nodes.size) {
                    val dr = nodes[i].r - nodes[j].r
                    val dg = nodes[i].g - nodes[j].g
                    val dbl = nodes[i].bl - nodes[j].bl
                    val dsq = dr * dr + dg * dg + dbl * dbl
                    if (dsq < minDistanceSq) {
                        minDistanceSq = dsq
                        bestI = i
                        bestJ = j
                    }
                }
            }

            if (minDistanceSq > thresholdSq || bestI == -1) break

            val a = nodes[bestI]
            val b = nodes[bestJ]
            val total = a.pixels.size + b.pixels.size
            a.r = (a.r * a.pixels.size + b.r * b.pixels.size) / total
            a.g = (a.g * a.pixels.size + b.g * b.pixels.size) / total
            a.bl = (a.bl * a.pixels.size + b.bl * b.pixels.size) / total
            a.pixels.addAll(b.pixels)
            nodes.removeAt(bestJ)
        }

        return nodes.map { n -> Cluster(n.pixels.map { it.x to it.y }.toMutableList()) }
    }

    private fun clusterAgglomerativeHsb(
            chromatic: List<ColorPixel>,
            distanceThreshold: Float
    ): List<Cluster> {
        val colorGroups = chromatic.groupBy { (it.r shl 16) or (it.g shl 8) or it.bl }

        class Node(var h: Float, var s: Float, var b: Float, val pixels: MutableList<ColorPixel>)

        val nodes =
                colorGroups
                        .values
                        .map { pixels ->
                            val first = pixels.first()
                            Node(first.h, first.s, first.b, pixels.toMutableList())
                        }
                        .toMutableList()

        val thresholdSq = distanceThreshold * distanceThreshold

        while (nodes.size > 1) {
            var bestI = -1
            var bestJ = -1
            var minDistanceSq = Float.MAX_VALUE

            for (i in 0 until nodes.size) {
                for (j in i + 1 until nodes.size) {
                    val hDiff = kotlin.math.abs(nodes[i].h - nodes[j].h)
                    val hDist = kotlin.math.min(hDiff, 1f - hDiff)
                    val ds = nodes[i].s - nodes[j].s
                    val db = nodes[i].b - nodes[j].b
                    val dsq = hDist * hDist + ds * ds + db * db
                    if (dsq < minDistanceSq) {
                        minDistanceSq = dsq
                        bestI = i
                        bestJ = j
                    }
                }
            }

            if (minDistanceSq > thresholdSq || bestI == -1) break

            val a = nodes[bestI]
            val b = nodes[bestJ]
            a.pixels.addAll(b.pixels)

            var sinSum = 0.0
            var cosSum = 0.0
            for (p in a.pixels) {
                val angle = p.h.toDouble() * 2.0 * Math.PI
                sinSum += kotlin.math.sin(angle)
                cosSum += kotlin.math.cos(angle)
            }
            val meanH = kotlin.math.atan2(sinSum, cosSum) / (2.0 * Math.PI)
            a.h = (if (meanH < 0) meanH + 1.0 else meanH).toFloat()
            a.s = a.pixels.map { it.s.toDouble() }.average().toFloat()
            a.b = a.pixels.map { it.b.toDouble() }.average().toFloat()

            nodes.removeAt(bestJ)
        }

        return nodes.map { n -> Cluster(n.pixels.map { it.x to it.y }.toMutableList()) }
    }

    private fun clusterAgglomerativeHue(
            chromatic: List<ColorPixel>,
            distanceThreshold: Float
    ): List<Cluster> {
        val colorGroups = chromatic.groupBy { (it.r shl 16) or (it.g shl 8) or it.bl }

        class Node(var h: Float, val pixels: MutableList<ColorPixel>)

        val nodes =
                colorGroups
                        .values
                        .map { pixels ->
                            val first = pixels.first()
                            Node(first.h, pixels.toMutableList())
                        }
                        .toMutableList()

        val thresholdSq = distanceThreshold * distanceThreshold

        while (nodes.size > 1) {
            var bestI = -1
            var bestJ = -1
            var minDistanceSq = Float.MAX_VALUE

            for (i in 0 until nodes.size) {
                for (j in i + 1 until nodes.size) {
                    val hDiff = kotlin.math.abs(nodes[i].h - nodes[j].h)
                    val hDist = kotlin.math.min(hDiff, 1f - hDiff)
                    val dsq = hDist * hDist
                    if (dsq < minDistanceSq) {
                        minDistanceSq = dsq
                        bestI = i
                        bestJ = j
                    }
                }
            }

            if (minDistanceSq > thresholdSq || bestI == -1) break

            val a = nodes[bestI]
            val b = nodes[bestJ]
            a.pixels.addAll(b.pixels)

            var sinSum = 0.0
            var cosSum = 0.0
            for (p in a.pixels) {
                val angle = p.h.toDouble() * 2.0 * Math.PI
                sinSum += kotlin.math.sin(angle)
                cosSum += kotlin.math.cos(angle)
            }
            val meanH = kotlin.math.atan2(sinSum, cosSum) / (2.0 * Math.PI)
            a.h = (if (meanH < 0) meanH + 1.0 else meanH).toFloat()

            nodes.removeAt(bestJ)
        }

        return nodes.map { n -> Cluster(n.pixels.map { it.x to it.y }.toMutableList()) }
    }

    private fun clusterKMeansHsb(chromatic: List<ColorPixel>, k: Int): List<Cluster> {
        fun distSq(p: ColorPixel, ch: Float, cs: Float, cb: Float): Float {
            val hDiff = kotlin.math.abs(p.h - ch)
            val hDist = kotlin.math.min(hDiff, 1f - hDiff)
            val sDist = p.s - cs
            val bDist = p.b - cb
            return hDist * hDist + sDist * sDist + bDist * bDist
        }
        fun circularMeanHue(pixels: List<ColorPixel>): Float {
            var sinSum = 0.0
            var cosSum = 0.0
            for (p in pixels) {
                val angle = p.h.toDouble() * 2.0 * Math.PI
                sinSum += kotlin.math.sin(angle)
                cosSum += kotlin.math.cos(angle)
            }
            val mean = kotlin.math.atan2(sinSum, cosSum) / (2.0 * Math.PI)
            return (if (mean < 0) mean + 1.0 else mean).toFloat()
        }

        val rng = java.util.Random(chromatic.hashCode().toLong())
        val centroidIndices = mutableListOf(rng.nextInt(chromatic.size))
        while (centroidIndices.size < k) {
            val distances =
                    FloatArray(chromatic.size) { i ->
                        centroidIndices.minOf { ci ->
                            distSq(chromatic[i], chromatic[ci].h, chromatic[ci].s, chromatic[ci].b)
                        }
                    }
            val totalDist = distances.sum()
            if (totalDist <= 0f) break
            var r = rng.nextFloat() * totalDist
            var chosen = 0
            for (i in distances.indices) {
                r -= distances[i]
                if (r <= 0f) {
                    chosen = i
                    break
                }
            }
            centroidIndices += chosen
        }

        val cH = FloatArray(k) { chromatic[centroidIndices.getOrElse(it) { 0 }].h }
        val cS = FloatArray(k) { chromatic[centroidIndices.getOrElse(it) { 0 }].s }
        val cB = FloatArray(k) { chromatic[centroidIndices.getOrElse(it) { 0 }].b }
        val assignments = IntArray(chromatic.size)

        for (iter in 0 until 30) {
            var changed = false
            for (i in chromatic.indices) {
                var bestC = 0
                var bestD = Float.MAX_VALUE
                for (c in 0 until k) {
                    val d = distSq(chromatic[i], cH[c], cS[c], cB[c])
                    if (d < bestD) {
                        bestD = d
                        bestC = c
                    }
                }
                if (assignments[i] != bestC) {
                    changed = true
                    assignments[i] = bestC
                }
            }
            if (!changed && iter > 0) break
            for (c in 0 until k) {
                val members =
                        chromatic.indices.filter { assignments[it] == c }.map { chromatic[it] }
                if (members.isNotEmpty()) {
                    cH[c] = circularMeanHue(members)
                    cS[c] = members.map { it.s.toDouble() }.average().toFloat()
                    cB[c] = members.map { it.b.toDouble() }.average().toFloat()
                }
            }
        }
        return buildClusters(chromatic, assignments, k)
    }

    private fun clusterHueGap(chromatic: List<ColorPixel>, k: Int): List<Cluster> {
        val sorted = chromatic.sortedBy { it.h }
        val n = sorted.size
        if (n <= k) return sorted.map { Cluster(mutableListOf(it.x to it.y)) }

        data class Gap(val size: Float, val afterIndex: Int)
        val interiorGaps = (0 until n - 1).map { Gap(sorted[it + 1].h - sorted[it].h, it) }
        val largestInteriorGap = interiorGaps.maxOf { it.size }
        val wrapGap = 1f - sorted.last().h + sorted.first().h

        val splitPoints =
                if (wrapGap > largestInteriorGap) {
                    (1 until k).map { (n * it / k) - 1 }.sorted()
                } else {
                    interiorGaps
                            .sortedByDescending { it.size }
                            .take(k - 1)
                            .map { it.afterIndex }
                            .sorted()
                }

        val clusters = mutableListOf<Cluster>()
        var start = 0
        for (sp in splitPoints) {
            val cluster = Cluster(mutableListOf())
            for (i in start..sp) cluster.pixels += sorted[i].x to sorted[i].y
            clusters += cluster
            start = sp + 1
        }
        val last = Cluster(mutableListOf())
        for (i in start until n) last.pixels += sorted[i].x to sorted[i].y
        clusters += last

        return clusters.filter { it.pixels.isNotEmpty() }
    }

    private fun clusterKMeansRgb(chromatic: List<ColorPixel>, k: Int): List<Cluster> {
        fun distSq(p: ColorPixel, cr: Float, cg: Float, cb: Float): Float {
            val dr = p.r - cr
            val dg = p.g - cg
            val db = p.bl - cb
            return dr * dr + dg * dg + db * db
        }

        val rng = java.util.Random(chromatic.hashCode().toLong())
        val centroidIndices = mutableListOf(rng.nextInt(chromatic.size))
        while (centroidIndices.size < k) {
            val distances =
                    FloatArray(chromatic.size) { i ->
                        centroidIndices.minOf { ci ->
                            distSq(
                                    chromatic[i],
                                    chromatic[ci].r.toFloat(),
                                    chromatic[ci].g.toFloat(),
                                    chromatic[ci].bl.toFloat()
                            )
                        }
                    }
            val totalDist = distances.sum()
            if (totalDist <= 0f) break
            var r = rng.nextFloat() * totalDist
            var chosen = 0
            for (i in distances.indices) {
                r -= distances[i]
                if (r <= 0f) {
                    chosen = i
                    break
                }
            }
            centroidIndices += chosen
        }

        val cR = FloatArray(k) { chromatic[centroidIndices.getOrElse(it) { 0 }].r.toFloat() }
        val cG = FloatArray(k) { chromatic[centroidIndices.getOrElse(it) { 0 }].g.toFloat() }
        val cB = FloatArray(k) { chromatic[centroidIndices.getOrElse(it) { 0 }].bl.toFloat() }
        val assignments = IntArray(chromatic.size)

        for (iter in 0 until 30) {
            var changed = false
            for (i in chromatic.indices) {
                var bestC = 0
                var bestD = Float.MAX_VALUE
                for (c in 0 until k) {
                    val d = distSq(chromatic[i], cR[c], cG[c], cB[c])
                    if (d < bestD) {
                        bestD = d
                        bestC = c
                    }
                }
                if (assignments[i] != bestC) {
                    changed = true
                    assignments[i] = bestC
                }
            }
            if (!changed && iter > 0) break
            for (c in 0 until k) {
                val members =
                        chromatic.indices.filter { assignments[it] == c }.map { chromatic[it] }
                if (members.isNotEmpty()) {
                    cR[c] = members.map { it.r.toDouble() }.average().toFloat()
                    cG[c] = members.map { it.g.toDouble() }.average().toFloat()
                    cB[c] = members.map { it.bl.toDouble() }.average().toFloat()
                }
            }
        }
        return buildClusters(chromatic, assignments, k)
    }

    private fun buildClusters(
            chromatic: List<ColorPixel>,
            assignments: IntArray,
            k: Int
    ): List<Cluster> {
        val clusters = (0 until k).map { Cluster(mutableListOf()) }
        for (i in chromatic.indices) {
            val px = chromatic[i]
            clusters[assignments[i]].pixels += px.x to px.y
        }
        return clusters.filter { it.pixels.isNotEmpty() }
    }

    private fun writeMasks(
            sourcePath: Path,
            sanitized: java.awt.image.BufferedImage,
            clusters: List<Cluster>
    ) {
        clusters.forEachIndexed { idx, cluster ->
            val mask =
                    java.awt.image.BufferedImage(
                            sanitized.width,
                            sanitized.height,
                            java.awt.image.BufferedImage.TYPE_INT_ARGB
                    )
            cluster.pixels.forEach { (x, y) ->
                val alpha = sanitized.getRGB(x, y) ushr 24 and 0xFF
                val value = (alpha shl 24) or 0x00FFFFFF
                mask.setRGB(x, y, value)
            }
            val outPath =
                    sourcePath.parent.resolve(
                            "${sourcePath.nameWithoutExtension}_mask_${idx + 1}.png"
                    )
            ImageIO.write(mask, "png", outPath.toFile())
        }
    }

    private fun imageHasNonTransparentPixels(image: java.awt.image.BufferedImage): Boolean {
        val data = IntArray(image.width * image.height)
        image.getRGB(0, 0, image.width, image.height, data, 0, image.width)
        return data.any { (it ushr 24) != 0 }
    }

    // ── Palette spec parsing ──────────────────────────────────────────────

    /** Parse a [PaletteSpec] from the given config section. */
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

    // ── Texture spec parsing ─────────────────────────────────────────────

    /**
     * Parse a [TextureSpec] from the given config section. Looks for the key `textures`. Each entry
     * may be a plain string or a map with `texture` and optional `permission` keys.
     */
    private fun parseTextureSpec(section: ConfigurationSection): TextureSpec {
        if (!section.contains("textures")) return TextureSpec.INHERIT
        return TextureSpec(textures = parseTextureRefList(section, "textures"))
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseTextureRefList(section: ConfigurationSection, key: String): List<TextureRef> {
        val raw = section.getList(key) ?: return emptyList()
        return raw.mapNotNull { entry ->
            when (entry) {
                is String -> TextureRef(entry)
                is Map<*, *> -> {
                    val map = entry as Map<String, Any?>
                    val id = map["texture"]?.toString() ?: return@mapNotNull null
                    val perm = map["permission"]?.toString()
                    TextureRef(id, perm)
                }
                else -> null
            }
        }
    }

    // ── Palette resolution ──────────────────────────────────────────────

    fun resolvePalettes(layerId: String, optionId: String, player: Player?): List<String> {
        val (layerDef, options) = loadedLayers[layerId] ?: return emptyList()
        val option = options.firstOrNull { it.id == optionId } ?: return emptyList()
        return resolvePalettes(layerDef, option, player)
    }

    fun resolvePalettes(
            layerDef: LayerDefinition,
            option: LayerOption,
            player: Player?
    ): List<String> {
        val def = defaultPaletteSpec
        val lay = layerDef.paletteSpec
        val opt = option.paletteSpec

        val first = opt.first ?: lay.first ?: def.first ?: emptyList()
        val middle = opt.palettes ?: lay.palettes ?: def.palettes ?: emptyList()
        val last = opt.last ?: lay.last ?: def.last ?: emptyList()

        val seen = mutableSetOf<String>()
        return (first + middle + last)
                .filter { ref ->
                    val allowed =
                            ref.permission == null ||
                                    (player?.hasPermission(ref.permission) ?: true)
                    allowed && seen.add(ref.id)
                }
                .map { it.id }
    }

    // ── Texture resolution ──────────────────────────────────────────────

    /**
     * Resolve the final ordered, deduplicated list of texture IDs available to a specific option on
     * a specific layer for a given player.
     *
     * Resolution order: part-level → layer-level → default-level. The first non-null wins (empty
     * list **is** non-null). Entries whose permission the player lacks are silently removed.
     */
    fun resolveTextures(
            layerDef: LayerDefinition,
            option: LayerOption,
            player: Player?
    ): List<String> {
        val resolved =
                option.textureSpec.textures
                        ?: layerDef.textureSpec.textures ?: defaultTextureSpec.textures
                                ?: emptyList()

        val seen = mutableSetOf<String>()
        return resolved
                .filter { ref ->
                    val allowed =
                            ref.permission == null ||
                                    (player?.hasPermission(ref.permission) ?: true)
                    allowed &&
                            seen.add(ref.id) &&
                            (ref.id == "default" || textures.containsKey(ref.id))
                }
                .map { it.id }
    }

    fun resolveTextures(layerId: String, optionId: String, player: Player?): List<String> {
        val (layerDef, options) = loadedLayers[layerId] ?: return emptyList()
        val option = options.firstOrNull { it.id == optionId } ?: return emptyList()
        return resolveTextures(layerDef, option, player)
    }

    /** Resolve brightness-influence for a layer+option. Resolution: option → layer → default. */
    fun resolveBrightnessInfluence(layerDef: LayerDefinition, option: LayerOption): Float {
        return option.brightnessInfluence
                ?: layerDef.brightnessInfluence ?: defaultBrightnessInfluence
    }

    /** Resolve saturation-influence for a layer+option. Resolution: option → layer → default. */
    fun resolveSaturationInfluence(layerDef: LayerDefinition, option: LayerOption): Float {
        return option.saturationInfluence
                ?: layerDef.saturationInfluence ?: defaultSaturationInfluence
    }

    /**
     * Compose a 64×64 skin from [selection]. Single entry point for mannequin preview, session
     * finalization, and any other skin output — all pixel tinting uses the same influence and
     * texture resolution.
     */
    fun composeSkin(
            selection: SkinSelection,
            useSlimModel: Boolean,
            baseImage: BufferedImage? = null,
            showOverlay: Boolean = true,
            fullColorMaskInfluence: Boolean = false
    ): BufferedImage {
        val hueSuppressSatLow =
                plugin.config.getDouble("plugin.tinting.hue-suppress-saturation-low", 0.03).toFloat()
        val hueSuppressSatHigh =
                plugin.config.getDouble("plugin.tinting.hue-suppress-saturation-high", 0.10).toFloat()
        return SkinComposer.compose(
                layers = definitionsInOrder(),
                selection = selection,
                useSlimModel = useSlimModel,
                optionResolver = { layerId, optionId -> findPartById(layerId, optionId) },
                textureResolver = { layerId ->
                    selection.selections[layerId]?.selectedTexture?.let { texture(it) }
                },
                brightnessInfluenceResolver = { layerId, option ->
                    val def = loadedLayers[layerId]?.first
                    if (def != null) resolveBrightnessInfluence(def, option)
                    else defaultBrightnessInfluence
                },
                saturationInfluenceResolver = { layerId, option ->
                    val def = loadedLayers[layerId]?.first
                    if (def != null) resolveSaturationInfluence(def, option)
                    else defaultSaturationInfluence
                },
                baseImage = baseImage,
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
                showOverlay = showOverlay,
                hueSuppressSaturationLow = hueSuppressSatLow,
                hueSuppressSaturationHigh = hueSuppressSatHigh,
                fullColorMaskInfluence = fullColorMaskInfluence
        )
    }

    // ── Layer definition parsing ─────────────────────────────────────────

    private fun ConfigurationSection.toDefinition(dataFolder: Path): LayerDefinition {
        val id = this.name
        val displayName = getString("display-name", id) ?: id
        val allowMask = getBoolean("allow-color-mask", false)
        val allowEmpty = getBoolean("allow-empty", true)
        val directory =
                dataFolder.resolve(getString("directory", "layers/$id") ?: "layers/$id").normalize()

        val palSpec = parsePaletteSpec(this)
        val texSpec = parseTextureSpec(this)
        val briInf =
                if (contains("brightness-influence")) getDouble("brightness-influence").toFloat()
                else null
        val satInf =
                if (contains("saturation-influence")) getDouble("saturation-influence").toFloat()
                else null

        return LayerDefinition(
                id = id,
                displayName = displayName,
                directory = directory,
                allowColorMask = allowMask,
                allowEmpty = allowEmpty,
                paletteSpec = palSpec,
                textureSpec = texSpec,
                brightnessInfluence = briInf,
                saturationInfluence = satInf,
                isBase = getBoolean("base", false)
        )
    }

    private fun detectDress(image: BufferedImage): Pair<Boolean, Int> {
        val torsoX = 20..27
        val torsoYMiddle = 26 // Front torso middle row
        var torsoOccupied = false
        for (x in torsoX) {
            if ((image.getRGB(x, torsoYMiddle) ushr 24) > 0) {
                torsoOccupied = true
                break
            }
        }
        if (!torsoOccupied) return false to 0

        val rLegX = 4..7
        val rLegYTop = 20
        val lLegX = 20..23
        val lLegYTop = 52

        var highestLegOccupied = false
        for (x in rLegX) if ((image.getRGB(x, rLegYTop) ushr 24) > 0) {
            highestLegOccupied = true
            break
        }
        if (!highestLegOccupied) {
            for (x in lLegX) if ((image.getRGB(x, lLegYTop) ushr 24) > 0) {
                highestLegOccupied = true
                break
            }
        }
        if (!highestLegOccupied) return false to 0

        val rLegYBottom = 31
        val lLegYBottom = 63
        var lowestLegOccupied = false
        for (x in rLegX) if ((image.getRGB(x, rLegYBottom) ushr 24) > 0) {
            lowestLegOccupied = true
            break
        }
        if (!lowestLegOccupied) {
            for (x in lLegX) if ((image.getRGB(x, lLegYBottom) ushr 24) > 0) {
                lowestLegOccupied = true
                break
            }
        }
        if (lowestLegOccupied) return false to 0

        var maxLen = 0
        // Check both inner and outer leg regions to find the lowest pixel
        val legRects =
                listOf(
                        SkinUv.Rect(0, 16, 16, 16), // right leg base
                        SkinUv.Rect(0, 32, 16, 16), // right leg overlay
                        SkinUv.Rect(16, 48, 16, 16), // left leg base
                        SkinUv.Rect(0, 48, 16, 16) // left leg overlay
                )
        for (r in legRects) {
            val topY = if (r.y == 16 || r.y == 32) 20 else 52
            for (y in r.y until r.y + r.h) {
                if (y < topY || y >= topY + 12) continue
                for (x in r.x until r.x + r.w) {
                    if ((image.getRGB(x, y) ushr 24) > 0) {
                        val len = y - topY + 1
                        if (len > maxLen) maxLen = len
                    }
                }
            }
        }

        return true to maxLen.coerceAtMost(8)
    }
}
