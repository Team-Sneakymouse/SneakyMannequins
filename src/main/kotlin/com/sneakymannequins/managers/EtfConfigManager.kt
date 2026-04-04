package com.sneakymannequins.managers

import com.sneakymannequins.SneakyMannequins
import com.sneakymannequins.events.MannequinClickEvent
import com.sneakymannequins.events.MannequinHoverEvent
import com.sneakymouse.sneakyholos.util.TextUtility
import com.destroystokyo.paper.event.player.PlayerJumpEvent
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
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

    companion object {
        /** ETF configure-mode presets: 1-based front-face columns (symmetrical). */
        private val BLINK_WIDTH_PRESETS =
                listOf(listOf(3, 6), listOf(2, 3, 6, 7), listOf(1, 2, 3, 6, 7, 8))

        private fun nextBlinkWidthPreset(columns: List<Int>): List<Int> {
            val sorted = columns.sorted()
            val idx = BLINK_WIDTH_PRESETS.indexOfFirst { it == sorted }
            val i = if (idx >= 0) (idx + 1) % BLINK_WIDTH_PRESETS.size else 0
            return BLINK_WIDTH_PRESETS[i]
        }

        private fun prevBlinkWidthPreset(columns: List<Int>): List<Int> {
            val sorted = columns.sorted()
            val idx = BLINK_WIDTH_PRESETS.indexOfFirst { it == sorted }
            val i =
                    if (idx >= 0) (idx + BLINK_WIDTH_PRESETS.size - 1) % BLINK_WIDTH_PRESETS.size
                    else BLINK_WIDTH_PRESETS.size - 1
            return BLINK_WIDTH_PRESETS[i]
        }
    }

    data class EtfSession(
            val player: Player,
            val mannequinId: UUID,
            val layerId: String,
            val partId: String,
            val mode: Mode,
            var blinkHeight: Int,
            var blinkStyle: Int,
            var blinkEyeColumns: List<Int>,
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

        val initialEyeCols =
                opt.blinkEyeColumns.sorted().distinct().ifEmpty { listOf(3, 6) }

        val session =
                EtfSession(
                        player = player,
                        mannequinId = mannequinId,
                        layerId = layerId,
                        partId = partId,
                        mode = mode,
                        blinkHeight = opt.blinkHeight,
                        blinkStyle = opt.blinkStyle,
                        blinkEyeColumns = initialEyeCols,
                        dressLength = opt.dressLength,
                        jacketStyle = opt.jacketStyle
                )

        // Capture original image for overlay
        session.originalImage = if (mannequin.slimModel) opt.imageSlim ?: opt.imageMaster ?: opt.imageDefault
                               else opt.imageDefault ?: opt.imageMaster ?: opt.imageSlim

        sessions[player.uniqueId] = session
        
        player.sendMessage(TextUtility.convertToComponent("&aEntering &eInteractive ETF Mode &afor &b$partId &a(&6${mode.name.lowercase()}&a)."))
        if (mode == Mode.BLINK) {
            player.sendMessage(
                    TextUtility.convertToComponent(
                            "&7Left/Right Click: +/- eye row | Jump/Sneak: cycle blink style | Swap hand / Drop: cycle eye width | Re-run command to save."
                    )
            )
        } else {
            player.sendMessage(
                    TextUtility.convertToComponent(
                            "&7Left/Right Click: +/- Length | Jump/Sneak: Cycle Style | Re-run command to save."
                    )
            )
        }
        player.playSound(player.location, Sound.BLOCK_BEACON_ACTIVATE, 1f, 1.5f)

        updateVisuals(session)
    }

    fun hasSession(player: Player): Boolean = sessions.containsKey(player.uniqueId)

    fun stopSession(player: Player, save: Boolean = false) {
        val session = sessions.remove(player.uniqueId) ?: return
        
        if (save) {
            val result =
                    layerManager.updateEtfSettings(
                            session.layerId,
                            session.partId,
                            blinkHeight = if (session.mode == Mode.BLINK) session.blinkHeight else null,
                            blinkStyle = if (session.mode == Mode.BLINK) session.blinkStyle else null,
                            blinkEyeColumns =
                                    if (session.mode == Mode.BLINK && session.blinkHeight > 0) {
                                        session.blinkEyeColumns.sorted().distinct()
                                    } else {
                                        null
                                    },
                            blinkEyelidX =
                                    if (session.mode == Mode.BLINK && session.blinkHeight > 0) {
                                        11
                                    } else {
                                        null
                                    },
                            blinkEyelidY =
                                    if (session.mode == Mode.BLINK && session.blinkHeight > 0) {
                                        8 + session.blinkHeight - 1
                                    } else {
                                        null
                                    },
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
        if (sessions[event.player.uniqueId] == null) return
        // Block interactions with blocks/air; mannequin clicks use MannequinClickEvent.
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

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onSwapHand(event: PlayerSwapHandItemsEvent) {
        val session = sessions[event.player.uniqueId] ?: return
        if (session.mode != Mode.BLINK) return
        event.isCancelled = true
        session.blinkEyeColumns = nextBlinkWidthPreset(session.blinkEyeColumns)
        playerFeedback(session)
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onDropItem(event: PlayerDropItemEvent) {
        val session = sessions[event.player.uniqueId] ?: return
        if (session.mode != Mode.BLINK) return
        event.isCancelled = true
        session.blinkEyeColumns = prevBlinkWidthPreset(session.blinkEyeColumns)
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
            val w = eyeWidthLabel(session.blinkEyeColumns)
            "<gray>Row: $h <gray>Style: <white>${styleLabel(session.blinkStyle)} <gray>| Eyes: <white>$w"
        } else {
            val d = if (session.dressLength == 0) "<red>[DISABLED]" else "<white>${session.dressLength}"
            "<gray>Dress Length: $d <gray>Style: <white>${jacketLabel(session.jacketStyle)}"
        }
        session.player.sendActionBar(TextUtility.convertToComponent(msg))
        session.player.playSound(session.player.location, Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 1.2f)
        updateVisuals(session)
    }

    private fun styleLabel(style: Int) =
            when (style) {
                3 -> "3 (1 pixel blinking)"
                4 -> "4 (2 pixel blinking)"
                5 -> "5 (3-4 pixel blinking)"
                else -> style.toString()
            }

    private fun eyeWidthLabel(columns: List<Int>): String {
        val sorted = columns.sorted()
        return when (sorted) {
            BLINK_WIDTH_PRESETS[0] -> "narrow [3,6]"
            BLINK_WIDTH_PRESETS[1] -> "medium [2,3,6,7]"
            BLINK_WIDTH_PRESETS[2] -> "wide [1,2,3,6,7,8]"
            else -> sorted.joinToString(",", prefix = "[", postfix = "]")
        }
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

                fun fillFaceColumnPixels(y: Int, color: Color) {
                    if (y !in 8..15) return
                    g.color = color
                    for (col in session.blinkEyeColumns) {
                        if (col in 1..8) {
                            g.fillRect(8 + col - 1, y, 1, 1)
                        }
                    }
                }

                fillFaceColumnPixels(headY, Color.RED)

                // Blue: extra eye rows implied by blink style — same columns as red, not full 8-wide.
                when (session.blinkStyle) {
                    4 -> fillFaceColumnPixels(headY + 1, Color.BLUE)
                    5 -> {
                        fillFaceColumnPixels(headY + 1, Color.BLUE)
                        fillFaceColumnPixels(headY + 2, Color.BLUE)
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
