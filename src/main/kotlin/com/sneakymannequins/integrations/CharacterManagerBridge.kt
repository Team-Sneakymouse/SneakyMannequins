package com.sneakymannequins.integrations

import com.sneakymannequins.SneakyMannequins
import org.bukkit.entity.Player

data class CharacterContext(
    val characterUuid: String,
    val characterName: String
)

interface CharacterManagerBridge {
    val active: Boolean
    fun currentCharacter(player: Player): CharacterContext?
}

class NoOpCharacterManagerBridge : CharacterManagerBridge {
    override val active: Boolean = false
    override fun currentCharacter(player: Player): CharacterContext? = null
}

class ActiveCharacterManagerBridge : CharacterManagerBridge {
    override val active: Boolean = true

    override fun currentCharacter(player: Player): CharacterContext? {
        val character = net.sneakycharactermanager.paper.handlers.character.Character.get(player) ?: return null
        return CharacterContext(
            characterUuid = character.characterUUID,
            characterName = character.nameUnformatted
        )
    }
}

object CharacterManagerBridgeFactory {
    private const val PLUGIN_NAME = "SneakyCharacterManager"
    private const val CONFIG_PATH = "integrations.character-manager.enabled"

    fun create(plugin: SneakyMannequins): CharacterManagerBridge {
        val enabledInConfig = plugin.config.getBoolean(CONFIG_PATH, true)
        if (!enabledInConfig) return NoOpCharacterManagerBridge()

        val scmPlugin = plugin.server.pluginManager.getPlugin(PLUGIN_NAME)
        if (scmPlugin == null || !scmPlugin.isEnabled) {
            plugin.logger.warning(
                "CharacterManager integration is enabled but '$PLUGIN_NAME' is not running. Continuing without integration."
            )
            return NoOpCharacterManagerBridge()
        }

        return try {
            ActiveCharacterManagerBridge()
        } catch (t: Throwable) {
            plugin.logger.warning("Failed to initialize CharacterManager integration: ${t.message}")
            NoOpCharacterManagerBridge()
        }
    }
}
