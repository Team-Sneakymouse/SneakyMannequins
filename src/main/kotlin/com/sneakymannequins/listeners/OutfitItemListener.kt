package com.sneakymannequins.listeners

import com.sneakymannequins.SneakyMannequins
import com.sneakymannequins.items.OutfitItem
import com.sneakymannequins.managers.MannequinManager
import com.sneakymannequins.managers.SessionManager
import com.sneakymouse.sneakyholos.util.TextUtility
import java.util.UUID
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
        private val mannequinManager: MannequinManager,
        private val sessionManager: SessionManager
) : Listener {

    private val lastHandledTick = mutableMapOf<UUID, Int>()
    /** Epoch millis until this player may use any outfit item again (after a successful apply). */
    private val cooldownUntilEpochMs = mutableMapOf<UUID, Long>()

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
        val id = event.player.uniqueId
        lastHandledTick.remove(id)
        cooldownUntilEpochMs.remove(id)
    }

    private fun tryApply(player: org.bukkit.entity.Player, stack: ItemStack, uid: String) {
        val now = System.currentTimeMillis()
        cooldownUntilEpochMs[player.uniqueId]?.let { until ->
            if (now < until) {
                return
            }
        }

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

        player.sendMessage(TextUtility.convertToComponent("&eApplying outfit..."))
        mannequinManager.applyOutfitSession(
                player,
                session,
                OutfitItem.skinStateNameFromOutfitStack(stack)
        )
        cooldownUntilEpochMs[player.uniqueId] =
                System.currentTimeMillis() + OUTFIT_USE_COOLDOWN_MS
    }

    private companion object {
        const val OUTFIT_USE_COOLDOWN_MS = 5000L
    }
}

