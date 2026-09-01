package com.sneakymannequins

import com.sneakymannequins.commands.CommandMannequin
import com.sneakymannequins.integrations.CharacterManagerBridge
import com.sneakymannequins.integrations.CharacterManagerBridgeFactory
import com.sneakymannequins.integrations.placeholderapi.SneakyMannequinsPlaceholderExpansion
import com.sneakymannequins.listeners.OutfitItemListener
import com.sneakymannequins.listeners.OutfitSessionApplyCoordinator
import com.sneakymannequins.listeners.TriggerListener
import com.sneakymannequins.ui.outfit.OutfitGuiLifecycleListener
import com.sneakymannequins.ui.outfit.OutfitIconPickerListener
import com.sneakymannequins.ui.outfit.OutfitItemCreationListener
import com.sneakymannequins.ui.outfit.OutfitItemGuiConfig
import com.sneakymannequins.ui.outfit.OutfitNameChatListener
import com.sneakymannequins.managers.EtfConfigManager
import com.sneakymannequins.managers.LayerManager
import com.sneakymannequins.managers.MannequinManager
import com.sneakymannequins.managers.MannequinPersistence
import com.sneakymannequins.managers.RemaskManager
import com.sneakymannequins.managers.SessionManager
import com.sneakymannequins.managers.StatsManager
import com.sneakymannequins.managers.StyleManager
import com.sneakymannequins.nms.VolatileHandler
import com.sneakymannequins.nms.VolatileHandlerRegistry
import com.sneakymouse.sneakyholos.HoloController
import com.sneakymouse.sneakyholos.v26_2.HoloHandler262
import io.papermc.paper.event.player.AsyncChatEvent
import java.io.File
import java.util.jar.JarFile
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin

class SneakyMannequins : JavaPlugin(), Listener {

    companion object {
        const val IDENTIFIER = "sneakymannequins"
        lateinit var instance: SneakyMannequins

        public fun log(message: String) {
            instance.logger.info(message)
        }
    }

    /** Initializes the plugin instance during server load. */
    override fun onLoad() {
        instance = this
    }

    private lateinit var handler: VolatileHandler
    lateinit var layerManager: LayerManager
        private set
    lateinit var outfitItemGuiConfig: OutfitItemGuiConfig
        private set
    private lateinit var styleManager: StyleManager
    private lateinit var mannequinManager: MannequinManager
    private lateinit var persistence: MannequinPersistence
    lateinit var sessionManager: SessionManager
        private set
    private var placeholderExpansion: SneakyMannequinsPlaceholderExpansion? = null
    lateinit var statsManager: StatsManager
        private set
    private lateinit var remaskManager: RemaskManager
    private lateinit var etfConfigManager: EtfConfigManager
    lateinit var characterManagerBridge: CharacterManagerBridge
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
        outfitItemGuiConfig = OutfitItemGuiConfig(this).also { it.reload() }
        styleManager = StyleManager(this).also { it.loadStyles() }
        persistence = MannequinPersistence(this)
        characterManagerBridge = CharacterManagerBridgeFactory.create(this)
        statsManager = StatsManager(this, dataFolder)
        val sm = SessionManager(this, dataFolder, layerManager, characterManagerBridge, statsManager)
        sessionManager = sm
        holoController = HoloController(this, HoloHandler262()).also { it.start() }
        mannequinManager =
                MannequinManager(
                                this,
                                layerManager,
                                styleManager,
                                handler,
                                persistence,
                                sm,
                                characterManagerBridge,
                                holoController
                        )
                        .also {
                            it.loadFromDisk()
                            it.startTickLoop()
                        }
        val outfitSessionApplyCoordinator =
                OutfitSessionApplyCoordinator(sessionManager, mannequinManager)
        remaskManager = RemaskManager(this, mannequinManager, layerManager).also { it.start() }
        etfConfigManager = EtfConfigManager(this, mannequinManager, layerManager)

        // Register commands
        registerCommand(
                "mannequin",
                CommandMannequin(
                        this,
                        mannequinManager,
                        layerManager,
                        styleManager,
                        sessionManager,
                        remaskManager,
                        etfConfigManager,
                        outfitSessionApplyCoordinator
                )
        )
        server.pluginManager.registerEvents(this, this)
        server.pluginManager.registerEvents(TriggerListener(this), this)
        server.pluginManager.registerEvents(
                OutfitItemListener(this, outfitSessionApplyCoordinator),
                this
        )
        server.pluginManager.registerEvents(etfConfigManager, this)
        val outfitNameChat = OutfitNameChatListener(this)
        server.pluginManager.registerEvents(outfitNameChat, this)
        server.pluginManager.registerEvents(
                OutfitItemCreationListener(this, layerManager, outfitNameChat),
                this
        )
        server.pluginManager.registerEvents(OutfitIconPickerListener(this, layerManager), this)
        server.pluginManager.registerEvents(OutfitGuiLifecycleListener(this, layerManager), this)
        if (characterManagerBridge.active) {
            logger.info("CharacterManager integration enabled.")
        }

        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            val expansion = SneakyMannequinsPlaceholderExpansion(this)
            if (expansion.register()) {
                placeholderExpansion = expansion
                logger.info(
                        "PlaceholderAPI expansion registered: %sneakymannequins_skin_session_uid%"
                )
            } else {
                logger.warning("PlaceholderAPI is present but SneakyMannequins expansion failed to register.")
            }
        }
    }

    override fun onDisable() {
        if (this::mannequinManager.isInitialized) {
            mannequinManager.shutdown()
        }
        if (this::statsManager.isInitialized) {
            statsManager.save()
        }
        if (this::remaskManager.isInitialized) {
            remaskManager.stop()
        }
        if (this::holoController.isInitialized) {
            holoController.shutdown()
        }
        if (this::sessionManager.isInitialized) {
            sessionManager.skinTextureSessionCache.clearOnShutdown()
        }
        placeholderExpansion?.unregister()
        placeholderExpansion = null
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
        server.scheduler.runTask(
                this,
                Runnable { mannequinManager.handleLoadChat(player, message) }
        )
    }

    fun reloadPlugin() {
        reloadConfig()
        styleManager.loadStyles()
        layerManager.reload()
        mannequinManager.reloadAll()
        outfitItemGuiConfig.reload()
    }

    private fun firstTimeSetup() {
        logger.info("First-time setup: copying default assets...")
        val jarFile = file // the plugin's JAR file
        val jar = JarFile(jarFile)
        jar.use {
            for (prefix in listOf("layers/", "textures/", "mannequin_presets/")) {
                val hasDir = jar.entries().asSequence().any { it.name.startsWith(prefix) }
                if (!hasDir) continue

                jar.entries()
                        .asSequence()
                        .filter { !it.isDirectory && it.name.startsWith(prefix) }
                        .forEach { entry ->
                            val target = File(dataFolder, entry.name)
                            if (target.exists()) return@forEach
                            target.parentFile.mkdirs()
                            jar.getInputStream(entry).use { input ->
                                target.outputStream().use { output -> input.copyTo(output) }
                            }
                        }
            }
        }
    }
}
