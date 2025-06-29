package net.sneakymannequin

import org.bukkit.plugin.java.JavaPlugin
import java.io.File

/**
 * Main plugin class for SneakyMannequin.
 * Handles plugin initialization, manager setup, and provides global access to core components.
 */
class SneakyMannequin : JavaPlugin() {

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
    }

    companion object {
        const val IDENTIFIER = "sneakymannequin"
        const val AUTHORS = "Team Sneakymouse"
        const val VERSION = "1.0.0"
        private lateinit var instance: SneakyMannequin

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
         * @return The SneakyMannequin plugin instance
         */
        fun getInstance(): SneakyMannequin {
            return instance
        }
    }
}