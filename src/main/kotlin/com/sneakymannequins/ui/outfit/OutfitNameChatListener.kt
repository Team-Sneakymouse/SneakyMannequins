package com.sneakymannequins.ui.outfit

import com.sneakymannequins.SneakyMannequins
import com.sneakymouse.sneakyholos.util.TextUtility
import io.papermc.paper.event.player.AsyncChatEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent

/**
 * When a player is expecting an outfit item name, consumes chat on the async thread and delivers the
 * validated string on the main thread.
 */
class OutfitNameChatListener(private val plugin: SneakyMannequins) : Listener {

    private val nameCallbacks = ConcurrentHashMap<UUID, (String) -> Unit>()

    /** Registers a one-shot name capture; replaces any previous expectation for this player. */
    fun expectName(player: Player, onName: (String) -> Unit) {
        nameCallbacks[player.uniqueId] = onName
    }

    fun cancel(player: Player) {
        nameCallbacks.remove(player.uniqueId)
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    fun onAsyncChat(event: AsyncChatEvent) {
        val player = event.player
        val callback = nameCallbacks[player.uniqueId] ?: return
        event.isCancelled = true

        val raw =
                PlainTextComponentSerializer.plainText()
                        .serialize(event.message())
                        .trim()

        if (raw.isEmpty()) {
            player.sendMessage(
                    TextUtility.convertToComponent("&cName cannot be empty. Try again or reopen the menu.")
            )
            return
        }

        if (containsLegacyColorCodes(raw)) {
            player.sendMessage(
                    TextUtility.convertToComponent("&cColor codes are not allowed in the name.")
            )
            return
        }

        if (raw.length > 30) {
            player.sendMessage(
                    TextUtility.convertToComponent("&cName cannot be longer than 30 characters.")
            )
            return
        }

        nameCallbacks.remove(player.uniqueId)
        plugin.server.scheduler.runTask(plugin, Runnable { callback(raw) })
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        cancel(event.player)
    }

    private fun containsLegacyColorCodes(s: String): Boolean {
        if ('§' in s) return true
        var i = 0
        while (i < s.length - 1) {
            if (s[i] == '&') {
                val c = s[i + 1].lowercaseChar()
                if (c in "0123456789abcdefklmnorx") return true
            }
            i++
        }
        return false
    }
}
