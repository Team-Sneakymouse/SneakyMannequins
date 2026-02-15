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
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.player.PlayerInteractAtEntityEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot

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
        registerCommand("mannequin", CommandMannequin(this, mannequinManager, layerManager))
        server.pluginManager.registerEvents(this, this)
        
        // Start the per-tick hover detection task
        mannequinManager.startHoverTask()
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

    // Right-click the Interaction entity → backwards
    @EventHandler
    fun onInteractControl(event: PlayerInteractAtEntityEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        mannequinManager.handleInteract(event.rightClicked, event.player, backwards = true)
        event.isCancelled = true
    }

    @EventHandler
    fun onInteractControlGeneric(event: PlayerInteractEntityEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        if (event.isCancelled) return
        mannequinManager.handleInteract(event.rightClicked, event.player, backwards = true)
        event.isCancelled = true
    }

    // Left-click (punch) the Interaction entity → forwards
    @EventHandler
    fun onDamageControl(event: EntityDamageByEntityEvent) {
        val player = event.damager as? org.bukkit.entity.Player ?: return
        mannequinManager.handleInteract(event.entity, player, backwards = false)
        event.isCancelled = true
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        mannequinManager.forgetViewer(event.player.uniqueId)
    }

    fun reloadPlugin() {
        reloadConfig()
        layerManager.reload()
        mannequinManager.reloadAll()
    }
}
