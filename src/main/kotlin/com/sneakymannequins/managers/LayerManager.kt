package com.sneakymannequins.managers

import com.sneakymannequins.SneakyMannequins
import com.sneakymannequins.model.ColorPalette
import com.sneakymannequins.model.LayerDefinition
import com.sneakymannequins.model.LayerOption
import com.sneakymannequins.model.NamedColor
import com.sneakymannequins.model.PaletteRef
import com.sneakymannequins.model.PaletteSpec
import com.sneakymannequins.model.TextureDefinition
import com.sneakymannequins.model.TextureRef
import com.sneakymannequins.model.TextureSpec
import com.sneakymannequins.util.SkinUv
import java.awt.Color
import java.awt.image.BufferedImage
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import javax.imageio.ImageIO
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension
import org.bukkit.configuration.ConfigurationSection
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
                root.getConfigurationSection("definitions")
                        ?: run {
                            plugin.logger.warning("No layer definitions found in config.")
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
            val options =
                    loadOptions(definition, definitionSection.getConfigurationSection("options"))
            loadedLayers[layerId] = definition to options
        }
    }

    fun definitionsInOrder(): List<LayerDefinition> =
            layerOrder.mapNotNull { loadedLayers[it]?.first }

    fun optionsFor(layerId: String, viewer: Player? = null): List<LayerOption> {
        val allOptions = loadedLayers[layerId]?.second.orEmpty()
        return if (viewer == null) {
            allOptions.filter { it.owner == null }
        } else {
            allOptions.filter { (it.owner == null || it.owner == viewer.uniqueId) && hasPermission(viewer, it) }
        }
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

        return loadLayerOptions(directory, definition, optionConfig)
    }

    private fun loadLayerOptions(
            directory: Path,
            definition: LayerDefinition,
            optionConfig: ConfigurationSection?
    ): List<LayerOption> {
        val entries = Files.list(directory).use { it.toList() }
        val grouped = mutableMapOf<String, OptionAggregate>()

        // 1. Identify preprocessed directories
        entries.filter { it.isDirectory() }.forEach { dir ->
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
                        if (dir.exists()) {
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
                                if (!Files.isDirectory(partDir)) return@partLoop
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
                permissions = optSection?.getStringList("permissions")
        )
    }

    private fun populateAggregate(agg: OptionAggregate, dir: Path) {
        val metadata = loadMetadata(dir)
        agg.hasArms = metadata["hasArms"] as? Boolean ?: false
        agg.isAlex = metadata["isAlex"] as? Boolean ?: false

        @Suppress("UNCHECKED_CAST")
        val mappings = metadata["mappings"] as? Map<String, Any> ?: emptyMap()

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

        // Fallback or additional scanning if mappings are incomplete
        Files.list(dir).use { stream ->
            stream.forEach { path ->
                val name = path.nameWithoutExtension.lowercase()
                val filename = path.name.lowercase()
                if (!filename.endsWith(".png")) return@forEach

                if (name.contains("_mask_")) {
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
            var isAlex: Boolean = false
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
        val entry = loadedLayers[layerId] ?: return "Base 1"
        val def = entry.first
        val targetDir = def.directory.resolve("uploads").resolve(player.uniqueId.toString())
        if (!Files.exists(targetDir)) return "Base 1"

        var maxIndex = 0
        val regex = Regex("^base_(\\d+)(?:\\.png)?$")
        Files.list(targetDir).use { stream ->
            stream.forEach { path ->
                val name = path.fileName.toString().lowercase()
                val match = regex.find(name)
                if (match != null) {
                    val idx = match.groupValues[1].toIntOrNull() ?: 0
                    if (idx > maxIndex) maxIndex = idx
                }
            }
        }
        return "Base ${maxIndex + 1}"
    }

    fun uploadPart(
            player: Player,
            layerId: String,
            url: URL,
            name: String? = null,
            sessionManager: SessionManager
    ): CompletableFuture<String> {
        val entry =
                loadedLayers[layerId]
                        ?: return CompletableFuture.failedFuture(
                                Exception("Unknown layer: $layerId")
                        )
        val def = entry.first
        val partId = name?.let { slugify(it) } ?: "upload_${System.currentTimeMillis()}"
        val targetDir = def.directory.resolve("uploads").resolve(player.uniqueId.toString())

        return sessionManager.downloadSkin(url).thenApplyAsync { image ->
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

    private fun loadMetadata(directory: Path): Map<String, Any> {
        val file = directory.resolve("metadata.json")
        if (!file.exists()) return emptyMap()
        return try {
            val content = Files.readString(file)
            val map = mutableMapOf<String, Any>()

            // Simple fields
            Regex("\"displayName\":\\s*\"([^\"]+)\"").find(content)?.let {
                map["displayName"] = it.groupValues[1]
            }
            Regex("\"internalKey\":\\s*\"([^\"]+)\"").find(content)?.let {
                map["internalKey"] = it.groupValues[1]
            }
            Regex("\"hasArms\":\\s*(true|false)").find(content)?.let {
                map["hasArms"] = it.groupValues[1].toBoolean()
            }
            Regex("\"isAlex\":\\s*(true|false)").find(content)?.let {
                map["isAlex"] = it.groupValues[1].toBoolean()
            }

            // Asset Mappings (Manual extraction for consistency)
            val mappings = mutableMapOf<String, Any>()
            Regex("\"(master|default|slim)\":\\s*\"([^\"]+)\"").findAll(content).forEach { m ->
                mappings[m.groupValues[1]] = m.groupValues[2]
            }

            fun extractMaskMap(key: String): Map<String, String> {
                val match = Regex("\"$key\":\\s*\\{([^}]+)\\}").find(content)
                val m = mutableMapOf<String, String>()
                match?.let {
                    Regex("\"(\\d+)\":\\s*\"([^\"]+)\"").findAll(it.groupValues[1]).forEach { res ->
                        m[res.groupValues[1]] = res.groupValues[2]
                    }
                }
                return m
            }

            mappings["masks"] = extractMaskMap("masks")
            mappings["masksDefault"] = extractMaskMap("masksDefault")
            mappings["masksSlim"] = extractMaskMap("masksSlim")

            if (mappings.isNotEmpty()) map["mappings"] = mappings

            map
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun preprocessPart(sourcePath: Path) {
        val partName = sourcePath.nameWithoutExtension
        val targetDir = sourcePath.parent.resolve(partName)
        if (!targetDir.exists()) Files.createDirectories(targetDir)

        val image = ImageIO.read(sourcePath.toFile()) ?: return
        val sanitized = sanitizeUv(image)

        val hasArms = hasArmPixels(sanitized)
        val isSlim = if (hasArms) isSlimArmModel(sanitized) else false

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

        writeMetadata(targetDir, partName, hasArms, isSlim)
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
        val isSlimAsset = metadata["isAlex"] as? Boolean ?: false
        val hasArms = metadata["hasArms"] as? Boolean ?: false

        val clusters = clusterColors(sanitized, strategy, distanceOrChannels, params)

        val preview = BufferedImage(sanitized.width, sanitized.height, BufferedImage.TYPE_INT_ARGB)
        clusters.forEachIndexed { idx, cluster ->
            val color = previewVibrantColors[idx % previewVibrantColors.size]
            cluster.pixels.forEach { (x, y) -> preview.setRGB(x, y, color.rgb) }
        }

        // Apply shared conversion logic if mismatch found
        return if (hasArms && isSlimAsset != targetSlim) {
            if (isSlimAsset) generateDefaultFromSlim(preview) else generateSlimFromDefault(preview)
        } else {
            preview
        }
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
        val sanitized = sanitizeUv(masterImg)
        val clusters = clusterColors(sanitized, strategy, distanceOrChannels, params)
        writeMasks(masterPath, sanitized, clusters)
        ImageIO.write(sanitized, "png", masterPath.toFile())

        // 3. Propagate to Variants if this part has arms
        val hasArms = metadataMap["hasArms"] as? Boolean ?: false
        val isSlim = metadataMap["isAlex"] as? Boolean ?: false

        if (hasArms) {
            // Re-read master masks just generated
            Files.list(dir).use { stream ->
                stream.filter { it.nameWithoutExtension.startsWith("${dir.name}_mask_") }.forEach {
                        masterMaskPath ->
                    val maskImg = ImageIO.read(masterMaskPath.toFile()) ?: return@forEach
                    val maskIdx = masterMaskPath.nameWithoutExtension.substringAfterLast("_mask_")

                    val (defMask, slimMask) =
                            if (isAlexMatch(isSlim)) { // Helper for clarity
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

        writeMetadata(dir, dir.name, hasArms, isSlim)
        reloadLayer(layerId)
        return "Remasked '$partId' in '$layerId' using ${strategy.name}: ${clusters.size} mask(s) generated and propagated"
    }

    private fun writeMetadata(dir: Path, partName: String, hasArms: Boolean, isAlex: Boolean) {
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

        val json =
                """
            {
                "displayName": "${toDisplayName(partName)}",
                "internalKey": "${slugify(partName)}",
                "hasArms": $hasArms,
                "isAlex": $isAlex,
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
                if (SkinUv.isInAnyUv(x, y)) {
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
        RGB
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

        val rawClusters =
                if (mode == GroupingMode.CHANNELS) {
                    val k = channels
                    if (chromatic.size == 1 || k <= 1) {
                        listOf(Cluster(chromatic.map { it.x to it.y }.toMutableList()))
                    } else {
                        val effectiveK = k.coerceAtMost(chromatic.size)
                        when (strategy) {
                            MaskStrategy.HSB -> clusterKMeansHsb(chromatic, effectiveK)
                            MaskStrategy.HUE -> clusterHueGap(chromatic, effectiveK)
                            MaskStrategy.RGB -> clusterKMeansRgb(chromatic, effectiveK)
                        }
                    }
                } else {
                    when (strategy) {
                        MaskStrategy.HSB -> clusterAgglomerativeHsb(chromatic, distance)
                        MaskStrategy.HUE -> clusterAgglomerativeHue(chromatic, distance)
                        MaskStrategy.RGB -> clusterAgglomerativeRgb(chromatic, distance)
                    }
                }

        val pixelMap = (chromatic + neutral).associateBy { it.x to it.y }

        class Centroid(
                val r: Float,
                val g: Float,
                val bl: Float,
                val h: Float,
                val s: Float,
                val b: Float
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
                            b = bSum / size
                    )
                }

        val trueNeutral = mutableListOf<ColorPixel>()
        val thresholdSq = distance * distance

        for (np in neutral) {
            var bestDistSq = Float.MAX_VALUE
            var bestClusterIdx = -1

            for (i in rawClusters.indices) {
                val c = clusterCentroids[i]
                val dsq =
                        when (strategy) {
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
                            MaskStrategy.HUE -> {
                                val hDiff = kotlin.math.abs(np.h - c.h)
                                val hDist = kotlin.math.min(hDiff, 1f - hDiff)
                                hDist * hDist
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

        return if (actualNeutralCluster != null) {
            sortedChromatic + actualNeutralCluster
        } else {
            sortedChromatic
        }
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

    // ── Layer definition parsing ─────────────────────────────────────────

    private fun ConfigurationSection.toDefinition(dataFolder: Path): LayerDefinition {
        val id = this.name
        val displayName = getString("display-name", id) ?: id
        val allowMask = getBoolean("allow-color-mask", false)
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
                paletteSpec = palSpec,
                textureSpec = texSpec,
                brightnessInfluence = briInf,
                saturationInfluence = satInf,
                isBase = getBoolean("base", false)
        )
    }
}
