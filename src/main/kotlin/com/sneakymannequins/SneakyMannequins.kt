package com.sneakymannequins

import org.bukkit.plugin.java.JavaPlugin
import com.sneakymannequins.commands.*
import com.sneakymannequins.managers.*
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.EntityType
import org.bukkit.entity.Mannequin
import org.bukkit.NamespacedKey
import org.bukkit.profile.PlayerTextures

class SneakyMannequins : JavaPlugin() {

	companion object {
		const val IDENTIFIER = "sneakymannequins"
		lateinit var instance: SneakyMannequins

		public fun log(message: String) {
			instance.logger.info(message)
		}
	}

	/**
     * Initializes the plugin instance during server load.
     */
    override fun onLoad() {
        instance = this
    }
    
    override fun onEnable() {
        logger.info("SneakyMannequins plugin has been enabled!")

		// Register commands
        server.commandMap.register(IDENTIFIER, CommandMannequin())
        server.commandMap.register(IDENTIFIER, CommandTest())
        server.commandMap.register(IDENTIFIER, CommandTest2())
        
        // Save default config if it doesn't exist
        saveDefaultConfig()
    }
    
    override fun onDisable() {
        logger.info("SneakyMannequins plugin has been disabled!")
    }
    
}
