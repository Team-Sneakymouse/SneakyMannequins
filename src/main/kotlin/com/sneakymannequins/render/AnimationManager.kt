package com.sneakymannequins.render

import com.sneakymannequins.SneakyMannequins
import com.sneakymannequins.nms.VolatileHandler
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Pixel-delivery mode. */
enum class RenderMode { INSTANT, BUILD }

/** Per-context (first-seen / update) delivery settings. */
data class RenderSettings(
    val mode: RenderMode = RenderMode.INSTANT,
    val tickInterval: Int = 1,
    val skipChance: Double = 0.5,
    /** Max pixels per build step that fly in from a distance (0 = disabled). */
    val flyInCount: Int = 0
)

/**
 * Manages the delivery of projected pixels to viewers.
 *
 * - **INSTANT** mode sends all pixels in a single burst (original behaviour).
 * - **BUILD** mode queues pixels into [BuildAnimation]s that gradually reveal
 *   the mannequin from bottom to top, one column-step per tick interval.
 *
 * When a new edit arrives while a BUILD animation is still in progress, any
 * pending (unsent) pixels from the old animation that overlap with the new
 * edit are cancelled — the new animation takes ownership of those pixels.
 * The old animation continues for its remaining non-overlapping pixels.
 */
class AnimationManager(
    private val plugin: SneakyMannequins,
    private val handler: VolatileHandler
) {
    /** Per-player list of active build animations. */
    private val animations = ConcurrentHashMap<UUID, MutableList<BuildAnimation>>()
    private var taskId: Int = -1

    // ── Lifecycle ────────────────────────────────────────────────────────────────

    fun start() {
        if (taskId != -1) return
        taskId = plugin.server.scheduler.scheduleSyncRepeatingTask(plugin, Runnable { tick() }, 0L, 1L)
    }

    fun stop() {
        if (taskId != -1) {
            plugin.server.scheduler.cancelTask(taskId)
            taskId = -1
        }
        animations.clear()
    }

    // ── Delivery ─────────────────────────────────────────────────────────────────

    /**
     * Deliver projected pixels to [viewer].
     *
     * - [RenderMode.INSTANT]: sends immediately via the volatile handler.
     * - [RenderMode.BUILD]: queues a new [BuildAnimation], cancelling any
     *   overlapping pixels from earlier animations for the same mannequin.
     */
    fun deliver(
        viewer: Player,
        mannequinId: UUID,
        projected: List<ProjectedPixel>,
        settings: RenderSettings
    ) {
        if (projected.isEmpty()) return

        if (plugin.config.getBoolean("plugin.debug", false)) {
            plugin.logger.info("[DEBUG] AnimationManager deliver: mannequin=$mannequinId, viewer=${viewer.name}, pixels=${projected.size}, mode=${settings.mode}")
        }

        when (settings.mode) {
            RenderMode.INSTANT -> {
                // Scale at delivery time so each viewer sees the correct size.
                val scaled = scaleForViewer(projected, viewer)
                handler.applyProjectedPixels(viewer, mannequinId, scaled)
            }
            RenderMode.BUILD -> {
                // BUILD pixels are stored unscaled; scaling happens per-tick in
                // tick() so the viewer's current scale is always respected.
                queueBuild(viewer, mannequinId, projected, settings)
            }
        }
    }

    // ── Cleanup ──────────────────────────────────────────────────────────────────

    /** Remove all animations for a player (e.g. on disconnect). */
    fun cleanupPlayer(playerUUID: UUID) {
        animations.remove(playerUUID)
    }

    /** Cancel all animations targeting a specific mannequin (all players). */
    fun cancelMannequin(mannequinId: UUID) {
        for (anims in animations.values) {
            anims.removeIf { it.mannequinId == mannequinId }
        }
        animations.entries.removeIf { it.value.isEmpty() }
    }

    /** Cancel animations for a mannequin viewed by a specific player. */
    fun cancelMannequinForPlayer(playerUUID: UUID, mannequinId: UUID) {
        val anims = animations[playerUUID] ?: return
        anims.removeIf { it.mannequinId == mannequinId }
        if (anims.isEmpty()) animations.remove(playerUUID)
    }

    // ── Internal ─────────────────────────────────────────────────────────────────

    private fun queueBuild(
        viewer: Player,
        mannequinId: UUID,
        projected: List<ProjectedPixel>,
        settings: RenderSettings
    ) {
        val playerAnims = animations.computeIfAbsent(viewer.uniqueId) { mutableListOf() }

        // Cancel overlapping pixels in any existing animation for this mannequin
        val newIndices = projected.mapTo(HashSet()) { it.index }
        playerAnims
            .filter { it.mannequinId == mannequinId }
            .forEach { it.cancelPixels(newIndices) }
        playerAnims.removeIf { it.isComplete() }

        // Start the new animation
        val anim = BuildAnimation.create(
            mannequinId = mannequinId,
            projected = projected,
            tickInterval = settings.tickInterval,
            skipChance = settings.skipChance,
            flyInCount = settings.flyInCount
        )
        if (!anim.isComplete()) {
            playerAnims.add(anim)
        }
    }

    /**
     * Called every server tick.  Advances all active BUILD animations and
     * sends the resulting pixels to each viewer.
     */
    private fun tick() {
        val onlineUUIDs = plugin.server.onlinePlayers.mapTo(HashSet()) { it.uniqueId }
        val stale = mutableListOf<UUID>()

        for ((playerUUID, anims) in animations) {
            if (playerUUID !in onlineUUIDs) {
                stale.add(playerUUID)
                continue
            }
            val player = plugin.server.getPlayer(playerUUID)
            if (player == null) {
                stale.add(playerUUID)
                continue
            }

            val viewerScale = viewerScale(player)
            val iter = anims.iterator()
            while (iter.hasNext()) {
                val anim = iter.next()
                val result = anim.step()
                if (result.pixels.isNotEmpty()) {
                    val scaled = if (viewerScale == 1.0) result.pixels
                                 else result.pixels.map { it.scaled(viewerScale) }
                    
                    if (plugin.config.getBoolean("plugin.debug", false)) {
                        plugin.logger.info("[DEBUG] AnimationManager tick: sending ${scaled.size} pixels for mannequin ${anim.mannequinId} to ${player.name}")
                    }

                    if (result.flyInOffsets.isNotEmpty() || result.riseUpIndices.isNotEmpty()) {
                        handler.applyProjectedPixelsAnimated(
                            player, anim.mannequinId, scaled,
                            result.flyInOffsets, result.riseUpIndices, result.riseUpTicks
                        )
                    } else {
                        handler.applyProjectedPixels(player, anim.mannequinId, scaled)
                    }
                }
                if (anim.isComplete()) {
                    if (plugin.config.getBoolean("plugin.debug", false)) {
                        plugin.logger.info("[DEBUG] AnimationManager: animation complete for mannequin ${anim.mannequinId} to ${player.name}")
                    }
                    iter.remove()
                }
            }
        }

        stale.forEach { animations.remove(it) }
        animations.entries.removeIf { it.value.isEmpty() }
    }

    // ── Viewer scale ─────────────────────────────────────────────────────────────

    /** Read the observer's [Attribute.SCALE] value (defaults to 1.0). */
    private fun viewerScale(player: Player): Double =
        player.getAttribute(Attribute.SCALE)?.value ?: 1.0

    /**
     * Scale every projected pixel for the given viewer.  Returns the original
     * list unchanged when the viewer's scale is 1.0 (the common case).
     */
    private fun scaleForViewer(
        pixels: List<ProjectedPixel>,
        viewer: Player
    ): List<ProjectedPixel> {
        val scale = viewerScale(viewer)
        if (scale == 1.0) return pixels
        return pixels.map { it.scaled(scale) }
    }
}

