package com.sneakymannequins.listeners

import com.sneakymannequins.SneakyMannequins
import com.sneakymannequins.items.OutfitItem
import com.sneakyitems.api.SneakyItemsAPI
import com.sneakyitems.events.MagicItemClickEvent
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.ItemStack

class OutfitItemListener(
        private val plugin: SneakyMannequins,
        private val outfitSessionApplyCoordinator: OutfitSessionApplyCoordinator
) : Listener {

    /** Right-click with a stamped outfit magic item. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    fun onMagicClick(event: MagicItemClickEvent) {
        if (!event.isRightClick) return
        if (event.trigger != MagicItemClickEvent.Trigger.AIR &&
                        event.trigger != MagicItemClickEvent.Trigger.BLOCK
        ) {
            return
        }
        if (!SneakyItemsAPI.isItem(event.item, OutfitItem.MAGIC_ITEM)) return
        if (event.isAlreadyDenied) return

        val uid = OutfitItem.readUid(event.item.itemMeta?.persistentDataContainer ?: return, plugin)
                ?: return

        event.isCancelled = true
        if (event.hasInteractResults()) {
            event.setUseItemInHand(Event.Result.DENY)
            event.setUseInteractedBlock(Event.Result.DENY)
        }
        tryApply(event.player, event.item, uid)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        outfitSessionApplyCoordinator.clearPlayer(event.player.uniqueId)
    }

    private fun tryApply(player: org.bukkit.entity.Player, stack: ItemStack, uid: String) {
        outfitSessionApplyCoordinator.tryApply(
                player,
                uid,
                OutfitItem.skinStateNameFromOutfitStack(stack)
        )
    }
}