package com.sneakymannequins.managers

import com.sneakymannequins.SneakyMannequins
import com.sneakymannequins.events.MannequinClickEvent
import com.sneakymannequins.events.MannequinHoverEvent
import com.sneakymannequins.model.LayerOption
import com.sneakymannequins.util.SkinUv
import com.sneakymouse.sneakyholos.util.TextUtility
import com.destroystokyo.paper.event.player.PlayerJumpEvent
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerToggleSneakEvent
import java.awt.Color
import java.awt.image.BufferedImage
import java.util.*

class EtfConfigManager(
    private val plugin: SneakyMannequins,
    private val mannequinManager: MannequinManager,
    private val layerManager: LayerManager
) : Listener {

    enum class Mode { BLINK, DRESS }

    data class EtfSession(
        val player: Player,
        val mannequinId: UUID,
        val layerId: String,
        val partId: String,
        val mode: Mode,
        var blinkHeight: Int,
        var blinkStyle: Int,
        var dressLength: Int,
        var jacketStyle: Int,
        var originalImage: BufferedImage? = null
    )

    private val sessions = mutableMapOf<UUID, EtfSession>()

    fun startSession(player: Player, mannequinId: UUID, layerId: String, partId: String, modeStr: String) {
        val mode = try {
            Mode.valueOf(modeStr.uppercase())
        } catch (e: Exception) {
            player.sendMessage(TextUtility.convertToComponent("&cInvalid ETF mode: $modeStr. Use 'blink' or 'dress'."))
            return
        }

        val mannequin = mannequinManager.getMannequin(mannequinId) ?: return
        val opt = layerManager.findPartById(layerId, partId) ?: return
        
        val session = EtfSession(
            player = player,
            mannequinId = mannequinId,
            layerId = layerId,
            partId = partId,
            mode = mode,
            blinkHeight = opt.blinkHeight,
            blinkStyle = opt.blinkStyle,
            dressLength = opt.dressLength,
            jacketStyle = opt.jacketStyle
        )

        // Capture original image for overlay
        session.originalImage = if (mannequin.slimModel) opt.imageSlim ?: opt.imageMaster ?: opt.imageDefault
                               else opt.imageDefault ?: opt.imageMaster ?: opt.imageSlim

        sessions[player.uniqueId] = session
        
        player.sendMessage(TextUtility.convertToComponent("&aEntering &eInteractive ETF Mode &afor &b$partId &a(&6${mode.name.lowercase()}&a)."))
        player.sendMessage(TextUtility.convertToComponent("&7Left/Right Click: +/- Height/Length | Jump/Sneak: Cycle Style | Re-run command to save."))
        player.playSound(player.location, Sound.BLOCK_BEACON_ACTIVATE, 1f, 1.5f)

        updateVisuals(session)
    }

    fun hasSession(player: Player): Boolean = sessions.containsKey(player.uniqueId)

    fun stopSession(player: Player, save: Boolean = false) {
        val session = sessions.remove(player.uniqueId) ?: return
        
        if (save) {
            val result = layerManager.updateEtfSettings(
                session.layerId,
                session.partId,
                blinkHeight = if (session.mode == Mode.BLINK) session.blinkHeight else null,
                blinkStyle = if (session.mode == Mode.BLINK) session.blinkStyle else null,
                dressLength = if (session.mode == Mode.DRESS) session.dressLength else null,
                jacketStyle = if (session.mode == Mode.DRESS) session.jacketStyle else null
            )
            player.sendMessage(TextUtility.convertToComponent("&a$result"))
            player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f)
        } else {
            player.sendMessage(TextUtility.convertToComponent("&cETF configuration mode cancelled."))
            player.playSound(player.location, Sound.BLOCK_BEACON_DEACTIVATE, 1f, 0.8f)
        }

        // Clean up visual override
        val mannequin = mannequinManager.getMannequin(session.mannequinId)
        if (mannequin != null) {
            mannequinManager.clearOverride(mannequin)
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onInteract(event: PlayerInteractEvent) {
        val session = sessions[event.player.uniqueId] ?: return
        
        // This is now primarily for blocking interactions with blocks/air.
        // The actual ETF logic moved to onMannequinClick for better reliability with entities.
        event.isCancelled = true
    }

    @EventHandler
    fun onJump(event: PlayerJumpEvent) {
        val session = sessions[event.player.uniqueId] ?: return
        
        if (session.mode == Mode.BLINK) {
            session.blinkStyle = if (session.blinkStyle >= 5) 3 else session.blinkStyle + 1
        } else {
            session.jacketStyle = if (session.jacketStyle >= 8) 5 else session.jacketStyle + 1
        }
        playerFeedback(session)
    }

    @EventHandler
    fun onSneak(event: PlayerToggleSneakEvent) {
        if (!event.isSneaking) return
        val session = sessions[event.player.uniqueId] ?: return
        
        if (session.mode == Mode.BLINK) {
            session.blinkStyle = if (session.blinkStyle <= 3) 5 else session.blinkStyle - 1
        } else {
            session.jacketStyle = if (session.jacketStyle <= 5) 8 else session.jacketStyle - 1
        }
        playerFeedback(session)
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onMannequinClick(event: MannequinClickEvent) {
        val session = sessions[event.player.uniqueId] ?: return
        event.isCancelled = true

        // backwards = true means Left Click (typically), backwards = false means Right Click
        if (event.backwards) { // Left Click -> Increment
            if (session.mode == Mode.BLINK) {
                session.blinkHeight = (session.blinkHeight + 1).coerceAtMost(8)
            } else {
                session.dressLength = (session.dressLength + 1).coerceAtMost(8)
            }
        } else { // Right Click -> Decrement
            if (session.mode == Mode.BLINK) {
                session.blinkHeight = (session.blinkHeight - 1).coerceAtLeast(0)
            } else {
                session.dressLength = (session.dressLength - 1).coerceAtLeast(0)
            }
        }
        playerFeedback(session)
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onMannequinHover(event: MannequinHoverEvent) {
        if (sessions.containsKey(event.player.uniqueId)) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        sessions.remove(event.player.uniqueId)
    }

    private fun playerFeedback(session: EtfSession) {
        val msg = if (session.mode == Mode.BLINK) {
            val h = if (session.blinkHeight == 0) "<red>[DISABLED]" else "<white>${session.blinkHeight}"
            "<gray>Blink Height: $h <gray>Style: <white>${styleLabel(session.blinkStyle)}"
        } else {
            val d = if (session.dressLength == 0) "<red>[DISABLED]" else "<white>${session.dressLength}"
            "<gray>Dress Length: $d <gray>Style: <white>${jacketLabel(session.jacketStyle)}"
        }
        session.player.sendActionBar(TextUtility.convertToComponent(msg))
        session.player.playSound(session.player.location, Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 1.2f)
        updateVisuals(session)
    }

    private fun styleLabel(style: Int) = when(style) {
        3 -> "3 (1 pixel blinking)"
        4 -> "4 (2 pixel blinking)"
        5 -> "5 (3-4 pixel blinking)"
        else -> style.toString()
    }

    private fun jacketLabel(style: Int) = when(style) {
        1 -> "1 (Copy to extension)"
        2 -> "2 (Move to extension)"
        3 -> "3 (Wide copy to extension)"
        4 -> "4 (Wide move to extension)"
        5 -> "5 (Copy to extension, ignore top)"
        6 -> "6 (Move to extension, ignore top)"
        7 -> "7 (Wide copy to extension, ignore top)"
        8 -> "8 (Wide move to extension, ignore top)"
        else -> style.toString()
    }

    private fun updateVisuals(session: EtfSession) {
        val img = generateOverlayImage(session)
        val mannequin = mannequinManager.getMannequin(session.mannequinId) ?: return
        mannequinManager.renderOverride(mannequin, img, mannequinManager.nearbyViewers(mannequin), force = true)
    }

    private fun generateOverlayImage(session: EtfSession): BufferedImage {
        val base = session.originalImage ?: return BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB)
        val img = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.drawImage(base, 0, 0, null)

        if (session.mode == Mode.BLINK) {
            if (session.blinkHeight > 0) {
                // blinkHeight 1-8 maps to headY 8-15
                val headY = 8 + (session.blinkHeight - 1)
                
                // Draw red band on chosen row (Front Face: 8..15)
                g.color = Color.RED
                g.fillRect(8, headY, 8, 1) // Base Front ONLY

                // Draw blue lines based on style
                g.color = Color.BLUE
                if (session.blinkStyle == 4) {
                    if (headY + 1 <= 15) {
                        g.fillRect(8, headY + 1, 8, 1)
                    }
                } else if (session.blinkStyle == 5) {
                    if (headY + 1 <= 15) {
                        g.fillRect(8, headY + 1, 8, 1)
                    }
                    if (headY + 2 <= 15) {
                        g.fillRect(8, headY + 2, 8, 1)
                    }
                }
            }
        } else {
            // Dress length: draw blue bands on affected leg rows
            g.color = Color.BLUE
            for (i in 0 until session.dressLength) {
                val legYBase = 20 + i
                val legYOverlay = 36 + i
                val legYLeftBase = 52 + i
                val legYLeftOverlay = 52 + i // Left leg overlay also starts at 52 (X=0..15)

                // Right Leg Front (X=4..7)
                g.fillRect(4, legYBase, 4, 1)
                if (legYOverlay < 48) g.fillRect(4, legYOverlay, 4, 1)

                // Left Leg Front (X=20..23)
                g.fillRect(20, legYLeftBase, 4, 1)
                // Left Leg Overlay (X=4..7, Y=52..63)
                g.fillRect(4, legYLeftOverlay, 4, 1)
            }
        }

        g.dispose()
        return img
    }
}
