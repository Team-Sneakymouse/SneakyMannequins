package com.sneakymannequins.listeners

import com.sneakymannequins.SneakyMannequins
import com.sneakymannequins.items.OutfitItem
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

    /**
     * Air clicks are often cancelled before NORMAL priority; [ignoreCancelled] must be false.
     * HIGH runs before typical protection plugins that cancel at NORMAL.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    fun onInteract(event: PlayerInteractEvent) {
        val isRight =
                event.action == Action.RIGHT_CLICK_AIR || event.action == Action.RIGHT_CLICK_BLOCK
        if (!isRight) return

        val player = event.player
        val (stack, uid) = findOutfitStack(player, event.hand) ?: return

        // PDC existence is the only contract; cancel vanilla behavior.
        event.isCancelled = true
        tryApply(player, stack, uid)
    }

    /**
     * Prefer the hand Paper attributes to the interaction; fall back to any hand holding an outfit
     * (RIGHT_CLICK_AIR sometimes omits or mis-attributes the item stack).
     */
    private fun findOutfitStack(player: org.bukkit.entity.Player, hand: EquipmentSlot?): Pair<ItemStack, String>? {
        fun uidFor(stack: ItemStack): String? {
            if (stack.type.isAir) return null
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

