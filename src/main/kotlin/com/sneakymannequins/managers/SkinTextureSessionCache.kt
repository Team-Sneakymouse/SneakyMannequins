package com.sneakymannequins.managers

import com.sneakymannequins.SneakyMannequins
import java.awt.image.BufferedImage
import java.net.URL
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import org.bukkit.entity.Player

/**
 * One download + decode pipeline per **skin texture URL** (Mojang URLs are immutable for that
 * asset). All features that read an encoded session UID from a player's skin share this cache
 * until plugin unload.
 */
class SkinTextureSessionCache(private val sessionManager: SessionManager) {

    data class DecodedSkin(val uid: String?, val skin64: BufferedImage, val fullImage: BufferedImage)

    private val futures = ConcurrentHashMap<String, CompletableFuture<DecodedSkin>>()

    fun clearOnShutdown() {
        futures.clear()
    }

    /**
     * Returns a shared [CompletableFuture] for [skinUrl]: at most one concurrent download per URL.
     * On decode failure after download, the entry is removed so a later call can retry.
     */
    fun getOrStartDecode(skinUrl: URL): CompletableFuture<DecodedSkin> {
        val key = skinUrl.toExternalForm()
        return futures.computeIfAbsent(key) {
            val created =
                    sessionManager.downloadSkin(skinUrl).thenApply { raw ->
                        decodeDownloadedSkin(raw)
                    }
            created.whenComplete { _, ex ->
                if (ex != null) {
                    futures.remove(key, created)
                }
            }
            created
        }
    }

    /** PlaceholderAPI: synchronous peek; returns decoded UID when ready, else [pendingText]. */
    fun peekSessionUidForPlaceholder(player: Player, pendingText: String): String {
        val skinUrl = player.playerProfile.textures.skin ?: return ""
        val f = getOrStartDecode(skinUrl)
        if (f.isDone && !f.isCompletedExceptionally()) {
            return try {
                f.get().uid ?: ""
            } catch (_: Exception) {
                pendingText
            }
        }
        return pendingText
    }

    private fun decodeDownloadedSkin(downloadedSkin: BufferedImage): DecodedSkin {
        val baseSkin =
                if (downloadedSkin.type != BufferedImage.TYPE_INT_ARGB) {
                    val converted =
                            BufferedImage(
                                    downloadedSkin.width,
                                    downloadedSkin.height,
                                    BufferedImage.TYPE_INT_ARGB
                            )
                    val g = converted.createGraphics()
                    g.composite = java.awt.AlphaComposite.Src
                    g.drawImage(downloadedSkin, 0, 0, null)
                    g.dispose()
                    converted
                } else {
                    downloadedSkin
                }
        val skin64 = SessionManager.skinTopLeft64Argb(baseSkin)
        val uid = SessionManager.decodeUidFromImage(skin64)
        return DecodedSkin(uid, skin64, baseSkin)
    }
}
