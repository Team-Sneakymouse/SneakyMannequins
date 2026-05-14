package com.sneakymannequins.listeners

import com.sneakymannequins.managers.MannequinManager
import com.sneakymannequins.managers.SessionManager
import com.sneakymouse.sneakyholos.util.TextUtility
import java.util.UUID
import org.bukkit.Bukkit
import org.bukkit.entity.Player

/**
 * Shared outfit-session apply path (resolve UID, same tick / cooldown rules, [MannequinManager.applyOutfitSession]).
 * Used by outfit item right-click and `/mannequin applysession`.
 */
class OutfitSessionApplyCoordinator(
        private val sessionManager: SessionManager,
        private val mannequinManager: MannequinManager
) {

    private val lastHandledTick = mutableMapOf<UUID, Int>()
    private val cooldownUntilEpochMs = mutableMapOf<UUID, Long>()

    fun clearPlayer(playerId: UUID) {
        lastHandledTick.remove(playerId)
        cooldownUntilEpochMs.remove(playerId)
    }

    fun tryApply(player: Player, uid: String, skinStateName: String) {
        val now = System.currentTimeMillis()
        cooldownUntilEpochMs[player.uniqueId]?.let { until ->
            if (now < until) {
                return
            }
        }

        val currentTick = Bukkit.getServer().currentTick
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
        mannequinManager.applyOutfitSession(player, session, skinStateName)
        cooldownUntilEpochMs[player.uniqueId] =
                System.currentTimeMillis() + OUTFIT_USE_COOLDOWN_MS
    }

    private companion object {
        const val OUTFIT_USE_COOLDOWN_MS = 5000L
    }
}
