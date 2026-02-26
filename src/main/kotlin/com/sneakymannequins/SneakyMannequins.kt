package com.sneakymannequins

import org.bukkit.plugin.java.JavaPlugin
import com.sneakymannequins.commands.CommandMannequin
import com.sneakymannequins.integrations.CharacterManagerBridge
import com.sneakymannequins.integrations.CharacterManagerBridgeFactory
import com.sneakymannequins.managers.LayerManager
import com.sneakymannequins.managers.MannequinManager
import com.sneakymannequins.managers.MannequinPersistence
import com.sneakymannequins.managers.SessionManager
import com.sneakymannequins.managers.AppliedSessionRegistry
import com.sneakymannequins.listeners.CharacterManagerListener
import com.sneakymannequins.listeners.TriggerListener
import com.sneakymannequins.nms.VolatileHandler
import com.sneakymannequins.nms.VolatileHandlerRegistry
import com.sneakymouse.holoui.HoloController
import com.sneakymouse.holoui.v1_21_4.HoloHandler1214
import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.player.PlayerInteractAtEntityEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import java.io.File
import java.util.jar.JarFile

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
    private lateinit var sessionManager: SessionManager
    private lateinit var characterManagerBridge: CharacterManagerBridge
    private lateinit var appliedSessionRegistry: AppliedSessionRegistry
    lateinit var holoController: HoloController
        private set
    
    override fun onEnable() {
        logger.info("SneakyMannequins plugin has been enabled!")

		if (!File(dataFolder, "config.yml").exists()) {
			firstTimeSetup()
		}
		saveDefaultConfig()
        handler = VolatileHandlerRegistry.resolve(this)
        layerManager = LayerManager(this).also { it.reload() }
        persistence = MannequinPersistence(this)
        sessionManager = SessionManager(dataFolder)
        characterManagerBridge = CharacterManagerBridgeFactory.create(this)
        appliedSessionRegistry = AppliedSessionRegistry(
            dataFolder = dataFolder,
            logger = logger,
            characterScopedMode = { characterManagerBridge.active }
        )
        holoController = HoloController(this, HoloHandler1214()).also { it.start() }
        mannequinManager = MannequinManager(
            this,
            layerManager,
            handler,
            persistence,
            sessionManager,
            characterManagerBridge,
            appliedSessionRegistry,
            holoController
        ).also { 
            it.loadFromDisk()
            it.startTickLoop()
        }

		// Register commands
        registerCommand("mannequin", CommandMannequin(this, mannequinManager, layerManager, sessionManager))
        server.pluginManager.registerEvents(this, this)
        server.pluginManager.registerEvents(TriggerListener(this), this)
        if (characterManagerBridge.active) {
            server.pluginManager.registerEvents(CharacterManagerListener(appliedSessionRegistry), this)
            logger.info("CharacterManager integration enabled.")
        }
        
    }
    
    override fun onDisable() {
        if (this::mannequinManager.isInitialized) {
            mannequinManager.shutdown()
        }
        if (this::holoController.isInitialized) {
            holoController.shutdown()
        }
        logger.info("SneakyMannequins plugin has been disabled!")
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

    // Moving interaction handling to HoloController library-side listener

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        mannequinManager.forgetViewer(event.player.uniqueId)
    }

    @EventHandler
    fun onChat(event: AsyncChatEvent) {
        val player = event.player
        if (!mannequinManager.isPlayerInLoadMode(player.uniqueId)) return
        event.isCancelled = true
        val message = PlainTextComponentSerializer.plainText().serialize(event.message())
        server.scheduler.runTask(this, Runnable {
            mannequinManager.handleLoadChat(player, message)
        })
    }

    fun reloadPlugin() {
        reloadConfig()
        layerManager.reload()
        mannequinManager.reloadAll()
    }

    private fun firstTimeSetup() {
        logger.info("First-time setup: copying default assets...")
        val jarFile = file // the plugin's JAR file
        val jar = JarFile(jarFile)
        jar.use {
            for (prefix in listOf("layers/", "textures/")) {
                val hasDir = jar.entries().asSequence().any { it.name.startsWith(prefix) }
                if (!hasDir) continue

                jar.entries().asSequence()
                    .filter { !it.isDirectory && it.name.startsWith(prefix) }
                    .forEach { entry ->
                        val target = File(dataFolder, entry.name)
                        if (target.exists()) return@forEach
                        target.parentFile.mkdirs()
                        jar.getInputStream(entry).use { input ->
                            target.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
            }
        }
    }
}
