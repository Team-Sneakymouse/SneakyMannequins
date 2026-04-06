package com.sneakymannequins.listeners

import com.sneakymannequins.SneakyMannequins
import com.sneakymannequins.items.OutfitItem
import com.sneakymannequins.managers.MannequinManager
import com.sneakymannequins.managers.SessionManager
import com.sneakymouse.sneakyholos.util.TextUtility
import java.util.UUID
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerAnimationEvent
import org.bukkit.event.player.PlayerInteractEvent

class OutfitItemListener(
        private val plugin: SneakyMannequins,
        private val mannequinManager: MannequinManager,
        private val sessionManager: SessionManager
) : Listener {

    private val lastHandledTick = mutableMapOf<UUID, Int>()

    @EventHandler(ignoreCancelled = true)
    fun onInteract(event: PlayerInteractEvent) {
        val isLeft =
                event.action == Action.LEFT_CLICK_AIR || event.action == Action.LEFT_CLICK_BLOCK
        if (!isLeft) return

        val player = event.player
        val stack = player.inventory.itemInMainHand
        if (stack.type.isAir) return

        val meta = stack.itemMeta ?: return
        val uid = OutfitItem.readUid(meta.persistentDataContainer, plugin) ?: return

        // PDC existence is the only contract; cancel vanilla behavior.
        event.isCancelled = true
        tryApply(event.player, uid)
    }

    @EventHandler(ignoreCancelled = true)
    fun onAnimation(event: PlayerAnimationEvent) {
        // Covers left-click air swings that don't fire PlayerInteractEvent.
        val player = event.player
        val stack = player.inventory.itemInMainHand
        if (stack.type.isAir) return
        val meta = stack.itemMeta ?: return
        val uid = OutfitItem.readUid(meta.persistentDataContainer, plugin) ?: return

        event.isCancelled = true
        tryApply(player, uid)
    }

    private fun tryApply(player: org.bukkit.entity.Player, uid: String) {
        val currentTick = plugin.server.currentTick
        val lastTick = lastHandledTick[player.uniqueId]
        if (lastTick == currentTick) return
        lastHandledTick[player.uniqueId] = currentTick

        val session =
                sessionManager.resolveSession(uid, player, mannequinManager)
                        ?: run {
                            player.sendMessage(
                                    TextUtility.convertToComponent(
                                            "&cOutfit session '&e$uid&c' not found."
                                    )
                            )
                            return
                        }

        val man =
                mannequinManager.nearestMannequin(player.location, 1000.0)
                        ?: run {
                            player.sendMessage(
                                    TextUtility.convertToComponent(
                                            "&cNo mannequin nearby for location context."
                                    )
                            )
                            return
                        }

        player.sendMessage(TextUtility.convertToComponent("&eApplying outfit..."))
        mannequinManager.finalizeAndApply(player, man, player, sessionOverride = session)
    }
}

