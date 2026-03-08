package com.sneakymannequins.managers

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import java.io.File
import java.util.UUID
import java.util.logging.Logger

class AppliedSessionRegistry(
        dataFolder: File,
        private val logger: Logger,
        private val characterScopedMode: () -> Boolean
) {
    private val file = File(dataFolder, "applied-sessions.json")
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    @Synchronized
    fun setLastApplied(playerUuid: UUID, sessionUid: String, characterUuid: String?) {
        val root = loadRoot()
        val playerKey = playerUuid.toString()

        if (characterScopedMode()) {
            val charKey = characterUuid?.trim().orEmpty()
            if (charKey.isEmpty()) {
                logger.warning(
                        "Character-scoped apply registry is active but no character UUID was available for $playerKey."
                )
                return
            }
            val existing = root.get(playerKey)
            val charMap =
                    if (existing != null && existing.isJsonObject) existing.asJsonObject
                    else JsonObject()
            charMap.addProperty(charKey, sessionUid)
            root.add(playerKey, charMap)
        } else {
            root.addProperty(playerKey, sessionUid)
        }

        saveRoot(root)
    }

    @Synchronized
    fun getLastApplied(playerUuid: UUID, characterUuid: String?): String? {
        val root = loadRoot()
        val playerKey = playerUuid.toString()
        val entry = root.get(playerKey) ?: return null

        return if (characterScopedMode()) {
            if (!entry.isJsonObject) return null
            val charKey = characterUuid?.trim().orEmpty()
            if (charKey.isEmpty()) null else entry.asJsonObject.get(charKey)?.asString
        } else {
            if (!entry.isJsonPrimitive) return null
            entry.asString
        }
    }

    @Synchronized
    fun clearCharacter(playerUuid: UUID, characterUuid: String) {
        if (!characterScopedMode()) return
        val root = loadRoot()
        val playerKey = playerUuid.toString()
        val existing = root.get(playerKey) ?: return
        if (!existing.isJsonObject) return

        val charMap = existing.asJsonObject
        charMap.remove(characterUuid)
        if (charMap.size() == 0) {
            root.remove(playerKey)
        } else {
            root.add(playerKey, charMap)
        }
        saveRoot(root)
    }

    private fun loadRoot(): JsonObject {
        if (!file.exists()) return JsonObject()
        return runCatching {
            gson.fromJson(file.readText(), JsonObject::class.java) ?: JsonObject()
        }
                .getOrElse {
                    logger.warning("Failed to read applied session registry: ${it.message}")
                    JsonObject()
                }
    }

    private fun saveRoot(root: JsonObject) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(gson.toJson(root))
        }
                .onFailure {
                    logger.warning("Failed to save applied session registry: ${it.message}")
                }
    }
}
