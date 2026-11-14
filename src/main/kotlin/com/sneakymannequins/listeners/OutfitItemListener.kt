package com.sneakymannequins.listeners

import com.sneakymannequins.SneakyMannequins
import com.sneakymannequins.items.OutfitItem
import com.sneakyitems.api.SneakyItemsAPI
import com.sneakyitems.events.MagicItemClickEvent
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
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

    /**
     * Unstamped own-key copies until the updater stamps them as [OutfitItem.MAGIC_ITEM].
     * Air clicks are often cancelled before NORMAL; [ignoreCancelled] must be false.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    fun onInteractLegacy(event: PlayerInteractEvent) {
        val isRight =
                event.action == Action.RIGHT_CLICK_AIR || event.action == Action.RIGHT_CLICK_BLOCK
        if (!isRight) return

        val player = event.player
        val (stack, uid) = findLegacyOutfitStack(player, event.hand) ?: return

        event.isCancelled = true
        tryApply(player, stack, uid)
    }

    /**
     * Prefer the hand Paper attributes to the interaction; fall back to any hand holding an
     * unstamped outfit (RIGHT_CLICK_AIR sometimes omits or mis-attributes the item stack).
     */
    private fun findLegacyOutfitStack(
            player: org.bukkit.entity.Player,
            hand: EquipmentSlot?
    ): Pair<ItemStack, String>? {
        fun uidFor(stack: ItemStack): String? {
            if (stack.type.isAir) return null
            // Stamped copies are handled by [onMagicClick].
            if (SneakyItemsAPI.keyOf(stack) != null) return null
            val meta = stack.itemMeta ?: return null
            return OutfitItem.readUid(meta.persistentDataContainer, plugin)
        }

        when (hand) {
            EquipmentSlot.HAND -> {
                uidFor(player.inventory.itemInMainHand)?.let {
                    return player.inventory.itemInMainHand to it
                }
            }
            EquipmentSlot.OFF_HAND -> {
                uidFor(player.inventory.itemInOffHand)?.let {
                    return player.inventory.itemInOffHand to it
                }
            }
            null -> {}
            else -> {}
        }
        uidFor(player.inventory.itemInMainHand)?.let {
            return player.inventory.itemInMainHand to it
        }
        uidFor(player.inventory.itemInOffHand)?.let {
            return player.inventory.itemInOffHand to it
        }
        return null
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
