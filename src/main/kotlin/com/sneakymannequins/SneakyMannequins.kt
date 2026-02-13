package com.sneakymannequins

import org.bukkit.plugin.java.JavaPlugin
import com.sneakymannequins.commands.*
import com.sneakymannequins.managers.*
import com.sneakymannequins.managers.LayerManager
import com.sneakymannequins.managers.MannequinManager
import com.sneakymannequins.nms.VolatileHandler
import com.sneakymannequins.nms.VolatileHandlerRegistry

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
    
    private lateinit var handler: VolatileHandler
    private lateinit var layerManager: LayerManager
    private lateinit var mannequinManager: MannequinManager
    
    override fun onEnable() {
        logger.info("SneakyMannequins plugin has been enabled!")

		saveDefaultConfig()
        handler = VolatileHandlerRegistry.resolve(this)
        layerManager = LayerManager(this).also { it.reload() }
        mannequinManager = MannequinManager(this, layerManager, handler)

        // Register commands
        registerCommand("mannequin", CommandMannequin(mannequinManager))
    }
    
    override fun onDisable() {
        if (this::mannequinManager.isInitialized) {
            mannequinManager.shutdown()
        }
        logger.info("SneakyMannequins plugin has been disabled!")
    }
    
}
