package com.sneakymannequins.ui.outfit

import com.sneakymannequins.SneakyMannequins
import com.sneakymannequins.managers.LayerManager
import com.sneakymouse.sneakyholos.util.TextUtility
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent

/**
 * Reopens the outfit maker when the player tries to leave without finalising, and restores the GUI
 * after reconnect if a draft session is still stored.
 */
class OutfitGuiLifecycleListener(
        private val plugin: SneakyMannequins,
        private val layerManager: LayerManager
) : Listener {

    @EventHandler
    fun onInventoryClose(event: InventoryCloseEvent) {
        val player = event.player as? Player ?: return

        when (event.inventory.holder) {
            is OutfitMainGuiHolder -> {
                if (OutfitItemCreationService.get(player.uniqueId) == null) return
                if (OutfitGuiSessionGuard.consumeSkipMainReopen(player.uniqueId)) return
                Bukkit.getScheduler()
                        .runTask(
                                plugin,
                                Runnable {
                                    if (!player.isOnline) return@Runnable
                                    if (OutfitItemCreationService.get(player.uniqueId) == null) {
                                        return@Runnable
                                    }
                                    OutfitItemCreationUi.reopenMain(plugin, layerManager, player)
                                }
                        )
            }
            is OutfitIconPickerHolder -> {
                if (OutfitItemCreationService.get(player.uniqueId) == null) return
                if (OutfitGuiSessionGuard.consumeSkipIconPickerReopenMain(player.uniqueId)) return
                Bukkit.getScheduler()
                        .runTask(
                                plugin,
                                Runnable {
                                    if (!player.isOnline) return@Runnable
                                    if (OutfitItemCreationService.get(player.uniqueId) == null) {
                                        return@Runnable
                                    }
                                    OutfitItemCreationUi.reopenMain(plugin, layerManager, player)
                                }
                        )
            }
            else -> {}
        }
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        if (OutfitItemCreationService.get(player.uniqueId) == null) return
        Bukkit.getScheduler()
                .runTaskLater(
                        plugin,
                        Runnable {
                            if (!player.isOnline) return@Runnable
                            if (OutfitItemCreationService.get(player.uniqueId) == null) return@Runnable
                            OutfitItemCreationUi.reopenMain(plugin, layerManager, player)
                            player.sendMessage(
                                    TextUtility.convertToComponent(
                                            "&7Your outfit maker session was restored. Click the preview when ready to collect your item."
                                    )
                            )
                        },
                        5L
                )
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        OutfitGuiSessionGuard.clearPlayer(event.player.uniqueId)
    }
}
