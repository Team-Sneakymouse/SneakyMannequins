package com.sneakymannequins.managers

import com.sneakymannequins.SneakyMannequins
import com.sneakymannequins.managers.LayerManager.MaskStrategy
import com.sneakymannequins.managers.LayerManager.RemaskParameters
import com.sneakymouse.sneakyholos.util.TextUtility
import java.awt.image.BufferedImage
import java.util.*
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitRunnable

class RemaskManager(
        private val plugin: SneakyMannequins,
        private val mannequinManager: MannequinManager,
        private val layerManager: LayerManager
) {
    private val sessions = mutableMapOf<UUID, RemaskSession>()
    private var tickTask: BukkitRunnable? = null

    data class RemaskSession(
            val player: Player,
            val mannequinId: UUID,
            val layerId: String,
            val partId: String,
            val strategy: MaskStrategy,
            var params: RemaskParameters,
            var distanceOrChannels: Any? = null,
            var lastYaw: Float = player.location.yaw,
            var lastPitch: Float = player.location.pitch,
            var blinkState: Boolean = false,
            var tickCount: Int = 0,
            var previewImage: BufferedImage? = null,
            var originalImage: BufferedImage? = null
    )

    fun start() {
        tickTask =
                object : BukkitRunnable() {
                    override fun run() {
                        tick()
                    }
                }
        tickTask?.runTaskTimer(plugin, 1L, 1L)
    }

    fun stop() {
        tickTask?.cancel()
        tickTask = null
        sessions.clear()
    }

    fun startSession(
            player: Player,
            mannequinId: UUID,
            layerId: String,
            partId: String,
            strategy: MaskStrategy? = null,
            distanceOrChannels: Any? = null
    ) {
        val strat = strategy ?: layerManager.defaultStrategy()
        val session =
                RemaskSession(
                        player,
                        mannequinId,
                        layerId,
                        partId,
                        strat,
                        layerManager.currentRemaskParameters(),
                        distanceOrChannels
                )

        // Load original image for preview
        val opt = layerManager.findPartById(layerId, partId)
        val mannequin = mannequinManager.getMannequin(mannequinId)
        if (opt != null && mannequin != null) {
            session.originalImage =
                    if (mannequin.slimModel) opt.imageSlim ?: opt.imageMaster ?: opt.imageDefault
                    else opt.imageDefault ?: opt.imageMaster ?: opt.imageSlim
        }

        sessions[player.uniqueId] = session
        player.sendMessage(
                TextUtility.convertToComponent(
                        "&aEntering &eInteractive Remask Mode &afor &b$partId&a."
                )
        )
        player.sendMessage(
                TextUtility.convertToComponent(
                        "&7Sneak and move camera to tune. Run command again to save, or &c'/mannequin remask cancel'&7."
                )
        )
        player.playSound(player.location, Sound.BLOCK_BEACON_ACTIVATE, 1f, 1.5f)

        // Initial preview
        updatePreview(session)
    }

    fun hasSession(player: Player): Boolean = sessions.containsKey(player.uniqueId)

    fun getSession(player: Player): RemaskSession? = sessions[player.uniqueId]

    fun cancelSession(player: Player) {
        val session = sessions.remove(player.uniqueId) ?: return
        player.sendMessage(TextUtility.convertToComponent("&cRemask mode cancelled."))
        player.playSound(player.location, Sound.BLOCK_BEACON_DEACTIVATE, 1f, 0.8f)

        // Clean up visual override
        val mannequin = mannequinManager.getMannequin(session.mannequinId)
        if (mannequin != null) {
            mannequinManager.render(
                    mannequin,
                    mannequinManager.nearbyViewers(mannequin),
                    forceAll = true
            )
        }
    }

    fun confirmSession(player: Player) {
        val session = sessions.remove(player.uniqueId) ?: return
        val result =
                layerManager.commitRemask(
                        session.layerId,
                        session.partId,
                        session.strategy,
                        session.params,
                        session.distanceOrChannels
                )
        player.sendMessage(TextUtility.convertToComponent("&a$result"))
        player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f)

        val mannequin = mannequinManager.getMannequin(session.mannequinId)
        if (mannequin != null) {
            mannequinManager.render(
                    mannequin,
                    mannequinManager.nearbyViewers(mannequin),
                    forceAll = true
            )
        }
    }

    private fun tick() {
        val it = sessions.values.iterator()
        while (it.hasNext()) {
            val session = it.next()
            if (!session.player.isOnline) {
                it.remove()
                continue
            }

            // Only process input if sneaking
            if (session.player.isSneaking) {
                handleInput(session)
            } else {
                // Sync last look position to avoid jumps when starting to sneak
                session.lastYaw = session.player.location.yaw
                session.lastPitch = session.player.location.pitch
            }

            // Blinking logic (every 10 ticks)
            session.tickCount++
            if (session.tickCount % 10 == 0) {
                session.blinkState = !session.blinkState
                renderBlink(session)
            }
        }
    }

    private fun handleInput(session: RemaskSession) {
        val loc = session.player.location
        val yawDelta = loc.yaw - session.lastYaw
        val pitchDelta = loc.pitch - session.lastPitch

        // Normalize jumpy yaw deltas (crossing -180/180)
        val normalizedYawDelta =
                when {
                    yawDelta > 180 -> yawDelta - 360
                    yawDelta < -180 -> yawDelta + 360
                    else -> yawDelta
                }

        if (normalizedYawDelta == 0f && pitchDelta == 0f) return

        session.lastYaw = loc.yaw
        session.lastPitch = loc.pitch

        // Map Input to Parameters
        // Yaw -> Chromatic Distance / Channels
        // Pitch -> Neutral Thresholds

        var changed = false

        // Tuning coefficients (feel-based)
        val yawSens = 0.002f
        val pitchSens = 0.005f

        if (session.distanceOrChannels is Int) {
            // Tuning K-Channels with horizontal movement
            // Using a threshold to avoid jitter
            if (kotlin.math.abs(normalizedYawDelta) > 2f) {
                val currentK = session.distanceOrChannels as Int
                val newK = (currentK + (normalizedYawDelta / 10f).toInt()).coerceIn(1, 8)
                if (newK != currentK) {
                    session.distanceOrChannels = newK
                    changed = true
                }
            }
        } else {
            // Tuning distance
            val oldDist = session.params.chromaticDistance
            val newDist = (oldDist + normalizedYawDelta * yawSens).coerceIn(0.01f, 1.0f)
            if (newDist != oldDist) {
                session.params = session.params.copy(chromaticDistance = newDist)
                changed = true
            }
        }

        // Pitch -> Neutral Saturation
        val oldSat = session.params.neutralSaturation
        val newSat = (oldSat - pitchDelta * pitchSens).coerceIn(0.0f, 1.0f)
        if (newSat != oldSat) {
            session.params = session.params.copy(neutralSaturation = newSat)
            changed = true
        }

        if (changed) {
            updatePreview(session)
            val info =
                    if (session.distanceOrChannels is Int) "k=${session.distanceOrChannels}"
                    else "dist=${String.format("%.3f", session.params.chromaticDistance)}"
            val satInfo = "neut=${String.format("%.3f", session.params.neutralSaturation)}"
            session.player.sendActionBar(
                    TextUtility.convertToComponent("&eRemask: &b$info &8| &b$satInfo")
            )
        }
    }

    private fun updatePreview(session: RemaskSession) {
        val opt = layerManager.findPartById(session.layerId, session.partId) ?: return
        val sourcePath = opt.fileMaster ?: opt.fileDefault ?: opt.fileSlim ?: return
        val mannequin = mannequinManager.getMannequin(session.mannequinId) ?: return

        session.previewImage =
                layerManager.generatePreviewImage(
                        sourcePath,
                        session.strategy,
                        session.params,
                        mannequin.slimModel,
                        session.distanceOrChannels
                )

        // Show current state immediately and reset blink phase
        session.blinkState = true
        session.tickCount = 0
        renderBlink(session)
    }

    private fun renderBlink(session: RemaskSession) {
        val mannequin = mannequinManager.getMannequin(session.mannequinId) ?: return
        val viewers = mannequinManager.nearbyViewers(mannequin)

        if (session.blinkState && session.previewImage != null) {
            mannequinManager.renderOverride(
                    mannequin,
                    session.previewImage!!,
                    viewers,
                    force = true
            )
        } else if (session.originalImage != null) {
            mannequinManager.renderOverride(
                    mannequin,
                    session.originalImage!!,
                    viewers,
                    force = true
            )
        } else {
            // Fallback (shouldn't really happen if asset is valid)
            mannequinManager.render(mannequin, viewers, forceAll = true)
        }
    }
}
