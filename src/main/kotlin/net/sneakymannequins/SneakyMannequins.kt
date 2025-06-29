package net.sneakymannequins

import net.sneakymannequins.commands.TestCommand
import net.sneakymannequins.nms.VolatileCodeManager
import net.sneakymannequins.nms.v1_21_4.VolatileCodeManager_v1_21_4
import org.bukkit.plugin.java.JavaPlugin
import java.io.File

/**
 * Main plugin class for SneakyMannequins.
 * Handles plugin initialization, manager setup, and provides global access to core components.
 */
class SneakyMannequins : JavaPlugin() {
    private lateinit var volatileCodeManager: VolatileCodeManager

    /**
     * Initializes the plugin instance during server load.
     */
    override fun onLoad() {
        instance = this
    }

    /**
     * Performs plugin setup on enable:
     * - Initializes managers
     * - Registers commands and listeners
     * - Sets up permissions
     * - Starts scheduled tasks
     */
    override fun onEnable() {
        saveDefaultConfig()
        
        // Initialize volatile code manager based on server version
        volatileCodeManager = when (server.minecraftVersion) {
            "1.21.4" -> VolatileCodeManager_v1_21_4()
            else -> throw UnsupportedOperationException("Unsupported Minecraft version: ${server.minecraftVersion}")
        }
        
        // Register commands
        setupCommands()
    }
    
    private fun setupCommands() {
        val commandMap = server.commandMap
        commandMap.register(IDENTIFIER, TestCommand())
    }

    companion object {
        const val IDENTIFIER = "sneakymannequins"
        const val AUTHORS = "Team Sneakymouse"
        const val VERSION = "1.0.0"
        private lateinit var instance: SneakyMannequins

        /**
         * Logs a message to the plugin's logger.
         * @param msg The message to log
         */
        fun log(msg: String) {
            instance.logger.info(msg)
        }

        /**
         * Gets the plugin's configuration file.
         * @return The config.yml file
         */
        fun getConfigFile(): File {
            return File(instance.dataFolder, "config.yml")
        }

        /**
         * Gets the plugin instance.
         * @return The SneakyMannequins plugin instance
         */
        fun getInstance(): SneakyMannequins {
            return instance
        }
        
        /**
         * Gets the volatile code manager instance.
         * @return The VolatileCodeManager instance
         */
        fun getVolatileCodeManager(): VolatileCodeManager {
            return instance.volatileCodeManager
        }
    }
}