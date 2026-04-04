package com.sneakymannequins.managers

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.sneakymannequins.SneakyMannequins
import com.sneakymannequins.integrations.CharacterManagerBridge
import com.sneakymannequins.model.LayerSelection
import com.sneakymannequins.model.LayerSessionData
import com.sneakymannequins.model.Mannequin
import com.sneakymannequins.model.SessionData
import com.sneakymannequins.model.SkinSelection
import com.sneakymannequins.util.SkinComposer
import com.sneakymannequins.util.SkinUv
import com.sneakymouse.sneakyholos.util.TextUtility
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.time.Instant
import java.util.BitSet
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ThreadLocalRandom
import javax.imageio.ImageIO
import org.bukkit.entity.Player
import org.bukkit.profile.PlayerTextures.SkinModel

data class FinalizedResult(val file: File, val slim: Boolean)

class SessionManager(
        private val plugin: SneakyMannequins,
        private val dataFolder: File,
        private val layerManager: LayerManager,
        private val characterManagerBridge: CharacterManagerBridge
) {
    private fun hexToColor(hex: String?): Color? {
        if (hex == null) return null
        return try {
            val normalized = if (hex.startsWith("#")) hex.substring(1) else hex
            val argb = normalized.toLong(16).toInt()
            Color(argb, normalized.length > 6)
        } catch (e: Exception) {
            null
        }
    }

    private val sessionsDir = File(dataFolder, "sessions")
    private val templatesDir = File(dataFolder, "templates")
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    companion object {
        private const val UID_LENGTH = 8
        private const val UID_CHARS =
                "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

        fun encodeUidToImage(image: BufferedImage, uid: String) {
            val paddedUid = uid.padEnd(UID_LENGTH, '0').take(UID_LENGTH)
            val bytes = paddedUid.toByteArray(Charsets.US_ASCII)

            val p1 =
                    ((bytes[0].toInt() and 0xFF) shl 24) or
                            ((bytes[1].toInt() and 0xFF) shl 16) or
                            ((bytes[2].toInt() and 0xFF) shl 8) or
                            (bytes[3].toInt() and 0xFF)
            image.setRGB(3, 48, p1)

            val p2 =
                    ((bytes[4].toInt() and 0xFF) shl 24) or
                            ((bytes[5].toInt() and 0xFF) shl 16) or
                            ((bytes[6].toInt() and 0xFF) shl 8) or
                            (bytes[7].toInt() and 0xFF)
            image.setRGB(3, 49, p2)
        }

        fun decodeUidFromImage(image: BufferedImage): String? {
            if (image.width < 64 || image.height < 64) return null
            val p1 = image.getRGB(3, 48)
            val p2 = image.getRGB(3, 49)

            val bytes = ByteArray(8)
            bytes[0] = ((p1 ushr 24) and 0xFF).toByte()
            bytes[1] = ((p1 ushr 16) and 0xFF).toByte()
            bytes[2] = ((p1 ushr 8) and 0xFF).toByte()
            bytes[3] = (p1 and 0xFF).toByte()

            bytes[4] = ((p2 ushr 24) and 0xFF).toByte()
            bytes[5] = ((p2 ushr 16) and 0xFF).toByte()
            bytes[6] = ((p2 ushr 8) and 0xFF).toByte()
            bytes[7] = (p2 and 0xFF).toByte()

            val str = String(bytes, Charsets.US_ASCII)
            if (str.length == 8 && str.all { it in UID_CHARS }) {
                return str
            }
            return null
        }
    }

    init {
        sessionsDir.mkdirs()
        templatesDir.mkdirs()
    }

    fun save(
            mannequin: Mannequin,
            player: Player,
            renderedImage: BufferedImage? = null,
            characterUuid: String? = null,
            characterName: String? = null
    ): SessionData {
        val uid = generateUid()
        val layers = snapshotLayers(mannequin)
        val session =
                SessionData(
                        uid = uid,
                        creator = player.uniqueId.toString(),
                        createdAt = Instant.now().toString(),
                        slimModel = mannequin.slimModel,
                        layers = layers,
                        characterUuid = characterUuid,
                        characterName = characterName
                )
        val jsonString = gson.toJson(session)
        CompletableFuture.runAsync {
            File(sessionsDir, "$uid.json").writeText(jsonString)
            if (renderedImage != null) {
                runCatching { ImageIO.write(renderedImage, "PNG", File(sessionsDir, "$uid.png")) }
            }
        }
        return session
    }

    fun load(id: String): SessionData? {
        val normalized = id.trim()
        val sessionFile = File(sessionsDir, "$normalized.json")
        if (sessionFile.exists()) {
            return runCatching { gson.fromJson(sessionFile.readText(), SessionData::class.java) }
                    .getOrNull()
        }
        val templateName = id.lowercase().trim()
        val templateFile = File(templatesDir, "$templateName.json")
        if (templateFile.exists()) {
            return runCatching { gson.fromJson(templateFile.readText(), SessionData::class.java) }
                    .getOrNull()
        }
        return null
    }

    fun history(playerUuid: UUID): List<SessionData> {
        if (!sessionsDir.exists()) return emptyList()
        return sessionsDir
                .listFiles { f -> f.extension == "json" }
                ?.mapNotNull { f ->
                    runCatching { gson.fromJson(f.readText(), SessionData::class.java) }.getOrNull()
                }
                ?.filter { it.creator == playerUuid.toString() }
                ?.sortedByDescending { it.createdAt }
                ?: emptyList()
    }

    fun latest(playerUuid: UUID): SessionData? = history(playerUuid).firstOrNull()

    fun fingerprint(mannequin: Mannequin): String {
        val layers = snapshotLayers(mannequin)
        return fingerprint(mannequin.slimModel, layers)
    }

    fun fingerprint(session: SessionData): String =
            fingerprint(session.slimModel ?: false, session.layers)

    /**
     * Create a named template from an existing UID session. If [layerIds] is non-empty only those
     * layers are included. Returns a descriptive result string or null on failure.
     */
    fun createTemplate(uid: String, name: String, layerIds: List<String>, player: Player): String? {
        val source = load(uid) ?: return "Session '$uid' not found."

        val inheritBodyType = layerIds.any { it.equals("body_type", ignoreCase = true) }
        val filteredLayerIds = layerIds.filterNot { it.equals("body_type", ignoreCase = true) }

        val filteredLayers =
                if (filteredLayerIds.isNotEmpty()) {
                    source.layers.filterKeys { it in filteredLayerIds }
                } else {
                    source.layers
                }
        if (filteredLayers.isEmpty() && filteredLayerIds.isNotEmpty())
                return "No matching layers found in session."

        val safeName = name.lowercase().trim().replace(Regex("[^a-z0-9_-]"), "_")
        val templateFile = File(templatesDir, "$safeName.json")
        if (templateFile.exists()) {
            val existing =
                    runCatching { gson.fromJson(templateFile.readText(), SessionData::class.java) }
                            .getOrNull()
            if (existing != null && existing.creator != player.uniqueId.toString()) {
                return "Template '$safeName' already exists and belongs to another player."
            }
        }

        val template =
                SessionData(
                        uid = safeName,
                        creator = player.uniqueId.toString(),
                        createdAt = Instant.now().toString(),
                        slimModel = if (inheritBodyType) source.slimModel else null,
                        layers = filteredLayers,
                        characterUuid = source.characterUuid,
                        characterName = source.characterName
                )
        val jsonString = gson.toJson(template)
        CompletableFuture.runAsync { templateFile.writeText(jsonString) }
        return null
    }

    fun merge(s1: SessionData, s2: SessionData, defaultSlim: Boolean): SessionData {
        val mergedLayers = s2.layers.toMutableMap()
        mergedLayers.putAll(s1.layers)

        val mergedSlim = s1.slimModel ?: s2.slimModel ?: defaultSlim

        return SessionData(
                uid = "merged",
                creator = s2.creator,
                createdAt = Instant.now().toString(),
                slimModel = mergedSlim,
                layers = mergedLayers,
                characterUuid = s1.characterUuid ?: s2.characterUuid,
                characterName = s1.characterName ?: s2.characterName
        )
    }

    fun isValid(session: SessionData, slim: Boolean): Boolean {
        for ((layerId, layerData) in session.layers) {
            val optionId = layerData.option ?: continue
            val options = layerManager.optionsFor(layerId)
            val option = options.find { it.id == optionId } ?: return false

            if (slim) {
                if (option.imageSlim == null) return false
            } else {
                if (option.imageDefault == null) return false
            }

            if (layerData.selectedTexture != null) {
                if (layerManager.texture(layerData.selectedTexture) == null) return false
            }
        }
        return true
    }

    fun isComplete(session: SessionData, slim: Boolean): Boolean {
        val covered = BitSet(64 * 64)

        for ((layerId, layerData) in session.layers) {
            val optionId = layerData.option ?: continue
            val options = layerManager.optionsFor(layerId)
            val option = options.find { it.id == optionId } ?: continue

            val image = if (slim) option.imageSlim else option.imageDefault
            if (image == null) continue

            for (x in 0 until 64) {
                for (y in 0 until 64) {
                    if ((image.getRGB(x, y) ushr 24) != 0) {
                        covered.set(y * 64 + x)
                    }
                }
            }
        }

        var allCovered = true
        SkinUv.forEachInnerBasePixel { x, y ->
            if (!covered.get(y * 64 + x)) {
                allCovered = false
            }
        }
        return allCovered
    }

    fun finalizeSession(
            requester: Player,
            man: Mannequin,
            sessionOverride: SessionData? = null,
            contextPlayer: Player = requester
    ): CompletableFuture<FinalizedResult> {
        val playerSkinModel = contextPlayer.playerProfile.textures.skinModel
        val playerSkinUrl = contextPlayer.playerProfile.textures.skin
        val charUuid = characterManagerBridge.currentCharacter(contextPlayer)?.characterUuid
        val contextPlayerUniqueId = contextPlayer.uniqueId

        return CompletableFuture.supplyAsync {
            val targetDir = ConfigManager.instance.getImageStoragePath().toFile()
            val mannequinSession = sessionOverride ?: sessionFromMannequin(man)

            val skinUrl =
                    playerSkinUrl ?: throw IllegalStateException("Context player has no skin URL")

            val downloadedSkin =
                    downloadSkin(skinUrl).join()
                            ?: throw IllegalStateException("Failed to download skin")

            val baseSkin =
                    if (downloadedSkin.type != BufferedImage.TYPE_INT_ARGB) {
                        val converted =
                                BufferedImage(
                                        downloadedSkin.width,
                                        downloadedSkin.height,
                                        BufferedImage.TYPE_INT_ARGB
                                )
                        val g = converted.createGraphics()
                        g.drawImage(downloadedSkin, 0, 0, null)
                        g.dispose()
                        converted
                    } else downloadedSkin

            val lastAppliedUid =
                    if (characterManagerBridge.active) decodeUidFromImage(baseSkin) else null
            val baseSession = lastAppliedUid?.let { load(it) }
            val merged =
                    if (baseSession != null) {
                        merge(mannequinSession, baseSession, playerSkinModel == SkinModel.SLIM)
                    } else {
                        val defaultSlim = playerSkinModel == SkinModel.SLIM
                        mannequinSession.copy(slimModel = mannequinSession.slimModel ?: defaultSlim)
                    }

            val slim = merged.slimModel ?: false
            if (!isValid(merged, slim)) {
                throw IllegalStateException("Merged session is invalid for finalization")
            }

            val selection = sessionToSelection(merged)
            val layersDef = layerManager.definitionsInOrder()
            val sessionImage =
                    SkinComposer.compose(
                            layers = layersDef,
                            selection = selection,
                            useSlimModel = slim,
                            optionResolver = { l, o -> layerManager.optionsFor(l).find { it.id == o } },
                            textureResolver = { layerManager.texture(it) },
                            baseImage = baseSkin,
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
                                    )
                    )

            targetDir.mkdirs()
            encodeUidToImage(sessionImage, merged.uid)
            val f = File(targetDir, "finalized_${merged.uid}.png")
            ImageIO.write(sessionImage, "PNG", f)
            FinalizedResult(f, slim)
        }
    }

    private fun sessionToSelection(session: SessionData): SkinSelection {
        val selections =
                session.layers.mapValues { (layerId, data) ->
                    val option = layerManager.optionsFor(layerId).find { it.id == data.option }
                    LayerSelection(
                            layerId = layerId,
                            option = option,
                            channelColors =
                                    data.channelColors
                                            .mapNotNull { (k, v) ->
                                                val idx = k.toIntOrNull() ?: return@mapNotNull null
                                                val color = hexToColor(v) ?: return@mapNotNull null
                                                idx to color
                                            }
                                            .toMap(),
                            texturedColors =
                                    data.texturedColors
                                            .mapNotNull outer@{ (k, v) ->
                                                val idx = k.toIntOrNull() ?: return@outer null
                                                val subMap =
                                                        v
                                                                .mapNotNull inner@{ (sk, sv) ->
                                                                    val sIdx =
                                                                            sk.toIntOrNull()
                                                                                    ?: return@inner null
                                                                    val color =
                                                                            hexToColor(sv)
                                                                                    ?: return@inner null
                                                                    sIdx to color
                                                                }
                                                                .toMap()
                                                idx to subMap
                                            }
                                            .toMap(),
                            selectedTexture = data.selectedTexture
                    )
                }
        return SkinSelection(selections)
    }

    fun downloadSkin(url: URL): CompletableFuture<BufferedImage> {
        return CompletableFuture.supplyAsync {
            try {
                ImageIO.read(url)
            } catch (e: Exception) {
                throw RuntimeException("Failed to download skin: ${e.message}", e)
            }
        }
    }


    private fun saveImage(image: BufferedImage, dir: File, name: String): CompletableFuture<File> {
        return CompletableFuture.supplyAsync {
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "$name.png")
            try {
                ImageIO.write(image, "png", file)
                file
            } catch (e: Exception) {
                throw RuntimeException("Failed to save image: ${e.message}", e)
            }
        }
    }

    fun listSessionUids(): List<String> {
        if (!sessionsDir.exists()) return emptyList()
        return sessionsDir.listFiles { f -> f.extension == "json" }?.map { it.nameWithoutExtension }
                ?: emptyList()
    }

    fun listTemplateNames(): List<String> {
        if (!templatesDir.exists()) return emptyList()
        return templatesDir.listFiles { f -> f.extension == "json" }?.map {
            it.nameWithoutExtension
        }
                ?: emptyList()
    }

    private fun generateUid(): String {
        val rng = ThreadLocalRandom.current()
        var uid: String
        do {
            uid = (1..UID_LENGTH).map { UID_CHARS[rng.nextInt(UID_CHARS.length)] }.joinToString("")
        } while (File(sessionsDir, "$uid.json").exists())
        return uid
    }

    fun snapshotLayers(mannequin: Mannequin): Map<String, LayerSessionData> {
        return mannequin.selection.selections.mapValues { (_, sel) ->
            LayerSessionData.fromSelection(sel)
        }
    }

    fun sessionFromMannequin(mannequin: Mannequin): SessionData {
        return SessionData(
                uid = "mannequin_${mannequin.id}",
                creator = "system",
                createdAt = Instant.now().toString(),
                slimModel = mannequin.slimModel,
                layers = snapshotLayers(mannequin)
        )
    }

    private fun fingerprint(slimModel: Boolean, layers: Map<String, LayerSessionData>): String {
        val sb = StringBuilder()
        sb.append("slim=").append(if (slimModel) "1" else "0").append('|')

        for (layerId in layers.keys.sorted()) {
            val layer = layers[layerId] ?: continue
            sb.append(layerId).append(':')
            sb.append(layer.option ?: "").append(':')
            sb.append(layer.selectedTexture ?: "").append('|')

            for (ch in layer.channelColors.keys.sortedWith(channelKeyComparator())) {
                sb.append("c").append(ch).append('=').append(layer.channelColors[ch]).append(';')
            }
            sb.append('|')

            for (ch in layer.texturedColors.keys.sortedWith(channelKeyComparator())) {
                sb.append("t").append(ch).append('=')
                val sub = layer.texturedColors[ch] ?: emptyMap()
                for (subKey in sub.keys.sortedWith(channelKeyComparator())) {
                    sb.append(subKey).append(':').append(sub[subKey]).append(',')
                }
                sb.append(';')
            }
            sb.append('|')
        }

        return sha256(sb.toString())
    }

    private fun channelKeyComparator(): Comparator<String> =
            compareBy<String> { it.toIntOrNull() ?: Int.MAX_VALUE }.thenBy { it }

    private fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun resolveSession(
            input: String,
            player: Player,
            mannequinManager: MannequinManager
    ): SessionData? {
        val lower = input.lowercase()
        if (lower == "nearest") {
            val man = mannequinManager.nearestMannequin(player.location, 5.0) ?: return null
            return sessionFromMannequin(man)
        }
        if (lower == "null") {
            return SessionData(
                    uid = "null",
                    creator = player.uniqueId.toString(),
                    createdAt = Instant.now().toString(),
                    slimModel = null,
                    layers = emptyMap()
            )
        }

        val normalized = input.trim()
        val isSession = File(sessionsDir, "$normalized.json").exists()
        val res = load(input) ?: return null

        if (isSession) {
            for ((layerId, data) in res.layers) {
                val optionId = data.option ?: continue
                val opt = layerManager.findPartById(layerId, optionId)
                if (opt != null && opt.owner != null && opt.owner != player.uniqueId) {
                    player.sendMessage(
                            TextUtility.convertToComponent(
                                    "<red>You do not own part '${opt.displayName}' in layer '$layerId'."
                            )
                    )
                    return null
                }
            }
        }

        return res
    }
}
