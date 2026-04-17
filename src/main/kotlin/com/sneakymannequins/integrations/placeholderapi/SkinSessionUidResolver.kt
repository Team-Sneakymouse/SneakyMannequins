package com.sneakymannequins.integrations.placeholderapi

import com.sneakymannequins.SneakyMannequins
import com.sneakymannequins.managers.SessionManager
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.bukkit.entity.Player

/**
 * Decodes the session UID embedded in a player's skin (same idea as `/mannequin debug checkskin`).
 *
 * [SessionManager.downloadSkin] already runs asynchronously; this class adds caching because
 * PlaceholderAPI resolves placeholders synchronously on the calling thread.
 */
class SkinSessionUidResolver(private val plugin: SneakyMannequins, private val sessionManager: SessionManager) {

    private data class CacheEntry(val uid: String?, val noSkinUrl: Boolean, val timeMs: Long)

    private val cache = ConcurrentHashMap<UUID, CacheEntry>()
    private val inFlight = ConcurrentHashMap.newKeySet<UUID>()

    fun invalidate(playerId: UUID) {
        cache.remove(playerId)
        inFlight.remove(playerId)
    }

    fun invalidateAll() {
        cache.clear()
        inFlight.clear()
    }

    /** Value for `%sneakymannequins_skin_session_uid%` (and aliases). */
    fun resolveForPlaceholder(player: Player): String {
        val id = player.uniqueId
        val now = System.currentTimeMillis()
        val ttlMs = cacheTtlMs()
        cache[id]?.let { entry ->
            if (now - entry.timeMs < ttlMs) {
                return when {
                    entry.noSkinUrl -> ""
                    entry.uid == null -> ""
                    else -> entry.uid
                }
            }
        }
        if (!inFlight.add(id)) {
            return pendingLiteral()
        }
        val skinUrl = player.playerProfile.textures.skin
        if (skinUrl == null) {
            cache[id] = CacheEntry(uid = null, noSkinUrl = true, timeMs = now)
            inFlight.remove(id)
            return ""
        }
        sessionManager.downloadSkin(skinUrl).whenComplete { image, ex ->
            inFlight.remove(id)
            if (ex != null || image == null) {
                plugin.logger.fine(
                        "Skin session UID placeholder: download failed for $id: ${ex?.message}"
                )
                return@whenComplete
            }
            val uid = SessionManager.decodeUidFromImage(image)
            cache[id] = CacheEntry(uid = uid, noSkinUrl = false, timeMs = System.currentTimeMillis())
        }
        return pendingLiteral()
    }

    private fun cacheTtlMs(): Long {
        val sec = plugin.config.getLong("placeholders.skin-session-uid-cache-ttl-seconds", 300L)
        return sec.coerceAtLeast(10L) * 1000L
    }

    private fun pendingLiteral(): String {
        return plugin.config.getString("placeholders.skin-session-uid-pending") ?: ""
    }
}
