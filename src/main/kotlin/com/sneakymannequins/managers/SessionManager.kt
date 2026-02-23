package com.sneakymannequins.managers

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.sneakymannequins.model.LayerSessionData
import com.sneakymannequins.model.Mannequin
import com.sneakymannequins.model.SessionData
import org.bukkit.entity.Player
import java.awt.image.BufferedImage
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom
import javax.imageio.ImageIO

class SessionManager(private val dataFolder: File) {

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
        val session = SessionData(
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
            return runCatching { gson.fromJson(sessionFile.readText(), SessionData::class.java) }.getOrNull()
        }
        val templateName = id.lowercase().trim()
        val templateFile = File(templatesDir, "$templateName.json")
        if (templateFile.exists()) {
            return runCatching { gson.fromJson(templateFile.readText(), SessionData::class.java) }.getOrNull()
        }
        return null
    }

    fun history(playerUuid: UUID): List<SessionData> {
        if (!sessionsDir.exists()) return emptyList()
        return sessionsDir.listFiles { f -> f.extension == "json" }
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
        fingerprint(session.slimModel, session.layers)

    /**
     * Create a named template from an existing UID session.
     * If [layerIds] is non-empty only those layers are included.
     * Returns a descriptive result string or null on failure.
     */
    fun createTemplate(uid: String, name: String, layerIds: List<String>, player: Player): String? {
        val source = load(uid) ?: return "Session '$uid' not found."
        val filteredLayers = if (layerIds.isNotEmpty()) {
            source.layers.filterKeys { it in layerIds }
        } else {
            source.layers
        }
        if (filteredLayers.isEmpty()) return "No matching layers found in session."

        val safeName = name.lowercase().trim().replace(Regex("[^a-z0-9_-]"), "_")
        val templateFile = File(templatesDir, "$safeName.json")
        if (templateFile.exists()) {
            val existing = runCatching {
                gson.fromJson(templateFile.readText(), SessionData::class.java)
            }.getOrNull()
            if (existing != null && existing.creator != player.uniqueId.toString()) {
                return "Template '$safeName' already exists and belongs to another player."
            }
        }

        val template = SessionData(
            uid = safeName,
            creator = player.uniqueId.toString(),
            createdAt = Instant.now().toString(),
            slimModel = source.slimModel,
            layers = filteredLayers,
            characterUuid = source.characterUuid,
            characterName = source.characterName
        )
        templateFile.writeText(gson.toJson(template))
        return null
    }

    fun listSessionUids(): List<String> {
        if (!sessionsDir.exists()) return emptyList()
        return sessionsDir.listFiles { f -> f.extension == "json" }
            ?.map { it.nameWithoutExtension }
            ?: emptyList()
    }

    fun listTemplateNames(): List<String> {
        if (!templatesDir.exists()) return emptyList()
        return templatesDir.listFiles { f -> f.extension == "json" }
            ?.map { it.nameWithoutExtension }
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

    private fun snapshotLayers(mannequin: Mannequin): Map<String, LayerSessionData> {
        return mannequin.selection.selections.mapValues { (_, sel) ->
            LayerSessionData.fromSelection(sel)
        }
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
