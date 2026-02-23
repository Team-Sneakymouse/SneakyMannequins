package com.sneakymannequins.managers

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.sneakymannequins.model.LayerSessionData
import com.sneakymannequins.model.Mannequin
import com.sneakymannequins.model.SessionData
import org.bukkit.entity.Player
import java.awt.image.BufferedImage
import java.io.File
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

    fun save(mannequin: Mannequin, player: Player, renderedImage: BufferedImage? = null): String {
        val uid = generateUid()
        val layers = mannequin.selection.selections.mapValues { (_, sel) ->
            LayerSessionData.fromSelection(sel)
        }
        val session = SessionData(
            uid = uid,
            creator = player.uniqueId.toString(),
            createdAt = Instant.now().toString(),
            slimModel = mannequin.slimModel,
            layers = layers
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
            layers = filteredLayers
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
}
