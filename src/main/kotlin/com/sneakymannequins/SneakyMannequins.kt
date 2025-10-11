package com.sneakymannequins

import org.bukkit.plugin.java.JavaPlugin
import com.sneakymannequins.commands.CommandMannequin
import com.sneakymannequins.managers.MannequinManager
import com.sneakymannequins.managers.MannequinData
import com.sneakymannequins.managers.SkinData
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
        
        // Save default config if it doesn't exist
        saveDefaultConfig()
    }
    
    override fun onDisable() {
        logger.info("SneakyMannequins plugin has been disabled!")
    }
    
}
