package com.sneakymannequins.integrations.placeholderapi

import com.sneakymannequins.SneakyMannequins
import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.entity.Player

/**
 * PlaceholderAPI bridge. Primary placeholder:
 * `%sneakymannequins_skin_session_uid%` — session id read from the player's current skin texture
 * (same pipeline as `/mannequin debug checkskin`).
 *
 * Aliases for [params]: `skin_session_uid`, `skin-session-uid`, or empty (same identifier only).
 */
class SneakyMannequinsPlaceholderExpansion(private val plugin: SneakyMannequins) : PlaceholderExpansion() {

    override fun getIdentifier(): String = SneakyMannequins.IDENTIFIER

    override fun getAuthor(): String = "Team Sneakymouse"

    override fun getVersion(): String = plugin.pluginMeta.version

    override fun persist(): Boolean = true

    override fun onPlaceholderRequest(player: Player?, params: String): String? {
        if (player == null) {
            return ""
        }
        val p = params.lowercase().trim()
        if (p.isEmpty() || p == "skin_session_uid" || p == "skin-session-uid") {
            return plugin.skinSessionUidResolver.resolveForPlaceholder(player)
        }
        return null
    }
}
