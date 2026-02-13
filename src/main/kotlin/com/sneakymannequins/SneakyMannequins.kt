package com.sneakymannequins

import org.bukkit.plugin.java.JavaPlugin
import com.sneakymannequins.commands.CommandMannequin
import com.sneakymannequins.managers.LayerManager
import com.sneakymannequins.managers.MannequinManager
import com.sneakymannequins.managers.MannequinPersistence
import com.sneakymannequins.nms.VolatileHandler
import com.sneakymannequins.nms.VolatileHandlerRegistry
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerMoveEvent

class SneakyMannequins : JavaPlugin(), Listener {

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
    private lateinit var persistence: MannequinPersistence
    
    override fun onEnable() {
        logger.info("SneakyMannequins plugin has been enabled!")

		saveDefaultConfig()
        handler = VolatileHandlerRegistry.resolve(this)
        layerManager = LayerManager(this).also { it.reload() }
        persistence = MannequinPersistence(this)
        mannequinManager = MannequinManager(this, layerManager, handler, persistence).also { it.loadFromDisk() }

        // Register commands
        registerCommand("mannequin", CommandMannequin(mannequinManager))
        server.pluginManager.registerEvents(this, this)
    }
    
    override fun onDisable() {
        if (this::mannequinManager.isInitialized) {
            mannequinManager.shutdown()
        }
        logger.info("SneakyMannequins plugin has been disabled!")
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        mannequinManager.renderVisibleTo(event.player)
    }

    @EventHandler
    fun onMove(event: PlayerMoveEvent) {
        val to = event.to ?: return
        val from = event.from
        if (to.world != from.world) {
            mannequinManager.renderVisibleTo(event.player)
            return
        }
        if (to.distanceSquared(from) > 1.0) {
            mannequinManager.renderVisibleTo(event.player)
        }
    }
    
}
