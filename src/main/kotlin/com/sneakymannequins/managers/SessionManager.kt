package com.sneakymannequins.managers

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.sneakymannequins.model.LayerSessionData
import com.sneakymannequins.model.Mannequin
import com.sneakymannequins.model.SessionData
import com.sneakymannequins.util.SkinUv
import java.awt.image.BufferedImage
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.BitSet
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom
import javax.imageio.ImageIO
import org.bukkit.entity.Player

class SessionManager(private val dataFolder: File, private val layerManager: LayerManager) {

    private val sessionsDir = File(dataFolder, "sessions")
    private val templatesDir = File(dataFolder, "templates")
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    companion object {
        private const val UID_LENGTH = 8
        private const val UID_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
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
    ): String {
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
        File(sessionsDir, "$uid.json").writeText(gson.toJson(session))
        if (renderedImage != null) {
            runCatching { ImageIO.write(renderedImage, "PNG", File(sessionsDir, "$uid.png")) }
        }
        return uid
    }

    fun load(id: String): SessionData? {
        val normalized = id.uppercase().trim()
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
        templateFile.writeText(gson.toJson(template))
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
}
