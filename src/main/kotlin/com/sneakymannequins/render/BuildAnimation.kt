package com.sneakymannequins.render

import java.util.UUID
import kotlin.math.sqrt

/**
 * A transformation offset for a pixel that should "fly in" from a random distant position and
 * interpolate to its final location.
 *
 * The fly-in direction is derived from the pixel's model-space column so that pixels arrive from
 * the body-direction they belong to (e.g. front pixels fly in from the front, left-arm pixels from
 * the left).
 *
 * Random rotation angles are also included so the pixel appears to tumble / gyrate as it flies in.
 */
data class FlyInOffset(
        val offsetX: Float,
        val offsetY: Float,
        val offsetZ: Float,
        /** Random rotation around X axis in radians (for tumbling). */
        val rotX: Float,
        /** Random rotation around Y axis in radians (for tumbling). */
        val rotY: Float,
        /** Random rotation around Z axis in radians (for tumbling). */
        val rotZ: Float,
        /** Number of client ticks over which the entity interpolates to its target position. */
        val interpolationTicks: Int
)

/**
 * Result of a single [BuildAnimation.step].
 *
 * @property pixels All pixels that should be delivered this step.
 * @property flyInOffsets Subset of [pixels] (keyed by [ProjectedPixel.index]) that
 * ```
 *                          should spawn at a random offset and interpolate to their
 *                          target position over [FlyInOffset.interpolationTicks] ticks.
 * @property riseUpIndices
 * ```
 * Pixel indices that should use a small "rise-up" effect
 * ```
 *                          (translate from slightly below to their target position).
 * @property riseUpTicks
 * ```
 * Interpolation duration (client ticks) for the rise-up effect.
 */
data class StepResult(
        val pixels: List<ProjectedPixel>,
        val flyInOffsets: Map<Int, FlyInOffset>,
        val riseUpIndices: Set<Int> = emptySet(),
        val riseUpTicks: Int = 0
) {
    companion object {
        val EMPTY = StepResult(emptyList(), emptyMap(), emptySet(), 0)
    }
}

/**
 * Tracks the state of a bottom-to-top BUILD animation for a single mannequin viewed by a single
 * player.
 *
 * Pixels are grouped into vertical **columns** based on their model-space (X, Z) footprint. Within
 * each column, pixels are further grouped into **Y-bands** — all pixels at roughly the same height
 * (base + overlay) are sent together as a single step. Each build step, every column independently
 * decides whether to advance one Y-band or skip that tick, creating a staggered / chaotic build-up
 * effect.
 *
 * **Cascading rule** (applied per-step, per-band): A band may only be sent if its Y-level is at
 * most 1 band above the **wave front** — the highest Y-band that was fully sent in a *previous*
 * step. Bands at the global minimum Y are always exempt.
 *
 * Because we snapshot the wave front at the start of each step, the front can advance by at most 1
 * band per step. This creates a visible rising wave: feet first, then legs, body, arms, and finally
 * the head. The skip-chance adds organic stagger within each tier.
 *
 * **Fly-in effect**: each step, up to [flyInCount] randomly-chosen pixels are tagged with a
 * [FlyInOffset]. These pixels spawn at a distant position and interpolate to their target over
 * [tickInterval] ticks.
 */
class BuildAnimation
private constructor(
        val mannequinId: UUID,
        private val columns: Map<ColumnKey, List<YBand>>,
        private val progress: MutableMap<ColumnKey, Int>,
        private val cancelled: MutableSet<Int>,
        private val tickInterval: Int,
        private val skipChance: Double,
        private val flyInCount: Int,
        private val reversed: Boolean,
        /** The absolute lowest (or highest if reversed) Y-band key across all columns / bands. */
        private val globalEdgeBand: Int
) {
    private var tickCounter = 0
    private val random = java.util.Random()

    /**
     * The highest (or lowest if reversed) Y-band key sent by any column so far (updated live during
     * each step, then used as the snapshot for the *next* step).
     */
    private var highWaterBand: Int = if (reversed) Int.MAX_VALUE else Int.MIN_VALUE

    /** Quantised model-space column identifier. */
    data class ColumnKey(val x: Int, val z: Int)

    /** A group of pixels at the same Y-level within a column. */
    data class YBand(val yKey: Int, val pixels: List<ProjectedPixel>)

    // ── Public API ──────────────────────────────────────────────────────────────

    /**
     * Mark pixel indices as cancelled. These pixels will be silently skipped when their band is
     * reached — a later animation owns them.
     */
    fun cancelPixels(indices: Set<Int>) {
        cancelled.addAll(indices)
    }

    /** `true` when every column has been fully sent or cancelled. */
    fun isComplete(): Boolean {
        for ((key, bands) in columns) {
            var idx = progress[key] ?: 0
            while (idx < bands.size && bands[idx].pixels.all { it.index in cancelled }) idx++
            if (idx < bands.size) return false
        }
        return true
    }

    /**
     * Advance the animation by one server tick.
     *
     * @return a [StepResult] with the pixels to send and any fly-in offsets.
     * ```
     *         [StepResult.EMPTY] if nothing to do this tick.
     * ```
     */
    fun step(): StepResult {
        tickCounter++
        if (tickCounter < tickInterval) return StepResult.EMPTY
        tickCounter = 0

        // Snapshot the wave front from the *previous* step.
        // Columns may only send bands up to (waterSnapshot + 1), so the
        // front advances by at most 1 band per step.
        val waterSnapshot = highWaterBand

        val result = mutableListOf<ProjectedPixel>()
        for ((key, bands) in columns) {
            var idx = progress[key] ?: 0
            // advance past fully-cancelled bands
            while (idx < bands.size && bands[idx].pixels.all { it.index in cancelled }) idx++
            progress[key] = idx

            if (idx >= bands.size) continue

            // ── Cascading: block bands above/below the wave front ─────────────
            // Bands at the global edge are always exempt.
            val band = bands[idx]
            if (reversed) {
                if (band.yKey < globalEdgeBand && band.yKey < waterSnapshot - 1) continue
            } else {
                if (band.yKey > globalEdgeBand && band.yKey > waterSnapshot + 1) continue
            }

            if (random.nextDouble() < skipChance) continue

            // Send all non-cancelled pixels in this band
            for (pixel in band.pixels) {
                if (pixel.index !in cancelled) {
                    result.add(pixel)
                }
            }
            progress[key] = idx + 1

            // Update the live high-water mark (becomes the snapshot for the next step)
            if (reversed) {
                if (band.yKey < highWaterBand) {
                    highWaterBand = band.yKey
                }
            } else {
                if (band.yKey > highWaterBand) {
                    highWaterBand = band.yKey
                }
            }
        }

        if (result.isEmpty()) {
            // The wave front may be stuck in a gap where no bands exist
            // between the current water mark and the next actual band.
            // Advance the water mark to just below the lowest remaining
            // band so the next step can proceed.
            var edgeRemaining = if (reversed) Int.MIN_VALUE else Int.MAX_VALUE
            for ((key, bands) in columns) {
                val idx = progress[key] ?: 0
                if (idx < bands.size) {
                    val y = bands[idx].yKey
                    if (reversed) {
                        if (y > edgeRemaining) edgeRemaining = y
                    } else {
                        if (y < edgeRemaining) edgeRemaining = y
                    }
                }
            }
            if (reversed) {
                if (edgeRemaining != Int.MIN_VALUE && edgeRemaining < highWaterBand) {
                    highWaterBand = edgeRemaining + 1
                }
            } else {
                if (edgeRemaining != Int.MAX_VALUE && edgeRemaining > highWaterBand) {
                    highWaterBand = edgeRemaining - 1
                }
            }
            return StepResult.EMPTY
        }

        // ── Fly-in selection ────────────────────────────────────────────
        val flyInOffsets = mutableMapOf<Int, FlyInOffset>()
        val riseUpIndices = mutableSetOf<Int>()
        if (flyInCount > 0) {
            val chosen =
                    if (result.size <= flyInCount) {
                        result
                    } else {
                        result.shuffled(random).take(flyInCount)
                    }
            val chosenIndices = chosen.mapTo(HashSet()) { it.index }
            for (pixel in chosen) {
                flyInOffsets[pixel.index] = directionalFlyInOffset(pixel)
            }
            // Non-fly-in pixels get the rise-up effect
            for (pixel in result) {
                if (pixel.index !in chosenIndices) {
                    riseUpIndices.add(pixel.index)
                }
            }
        }

        return StepResult(result, flyInOffsets, riseUpIndices, riseUpTicks = tickInterval * 2)
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    /**
     * Generate a fly-in offset whose horizontal direction is based on the pixel's model-space
     * position. The pixel flies in from the outward direction of its body part (e.g. a front-center
     * pixel flies in from the front, a left-arm pixel flies in from the left).
     *
     * A random magnitude (distance) and upward Y offset are applied, as well as random rotation
     * angles for a tumbling / gyrating effect.
     */
    private fun directionalFlyInOffset(pixel: ProjectedPixel): FlyInOffset {
        val mx = pixel.modelX.toFloat()
        val mz = pixel.modelZ.toFloat()
        val len = sqrt(mx * mx + mz * mz)

        // Normalised outward direction (from center-of-body toward this pixel).
        // Fall back to a random direction if the pixel is at dead center.
        val dirX: Float
        val dirZ: Float
        if (len > 0.001f) {
            dirX = mx / len
            dirZ = mz / len
        } else {
            val angle = random.nextFloat() * (2f * Math.PI.toFloat())
            dirX = kotlin.math.cos(angle)
            dirZ = kotlin.math.sin(angle)
        }

        // Random distance along that direction: [1, 3] blocks
        val distance = 1f + random.nextFloat() * 2f
        // Small random perpendicular jitter (±0.5) so not every pixel in the
        // same column follows the exact same line.
        val perpX = -dirZ // perpendicular in 2D
        val perpZ = dirX
        val jitter = (random.nextFloat() - 0.5f) * 1.0f

        // Random rotation angles (±π) for tumbling
        val pi = Math.PI.toFloat()
        val rotX = (random.nextFloat() * 2f - 1f) * pi
        val rotY = (random.nextFloat() * 2f - 1f) * pi
        val rotZ = (random.nextFloat() * 2f - 1f) * pi

        return FlyInOffset(
                offsetX = dirX * distance + perpX * jitter,
                offsetY = 1f + random.nextFloat() * 2f,
                offsetZ = dirZ * distance + perpZ * jitter,
                rotX = rotX,
                rotY = rotY,
                rotZ = rotZ,
                interpolationTicks = tickInterval * 2
        )
    }

    // ── Factory ─────────────────────────────────────────────────────────────────

    companion object {
        /**
         * Column quantisation bin size — 2 × pixel scale (= 1/8 block). Coarse enough that
         * base-layer and overlay-layer pixels at the same logical model position end up in the same
         * column.
         */
        private const val BIN_SIZE = 1.0 / 8.0

        fun create(
                mannequinId: UUID,
                projected: List<ProjectedPixel>,
                tickInterval: Int,
                skipChance: Double,
                flyInCount: Int = 0,
                reversed: Boolean = false
        ): BuildAnimation {
            // Step 1: group pixels into (column, Y-band) buckets
            val grouped = mutableMapOf<ColumnKey, MutableMap<Int, MutableList<ProjectedPixel>>>()
            for (pixel in projected) {
                val colKey =
                        ColumnKey(
                                Math.round(pixel.modelX / BIN_SIZE).toInt(),
                                Math.round(pixel.modelZ / BIN_SIZE).toInt()
                        )
                val yBandKey = Math.round(pixel.y / BIN_SIZE).toInt()
                grouped
                        .getOrPut(colKey) { mutableMapOf() }
                        .getOrPut(yBandKey) { mutableListOf() }
                        .add(pixel)
            }

            // Step 2: convert to sorted list of YBands per column (ascending Y, or descending if
            // reversed)
            val columns =
                    grouped.mapValues { (_, bandMap) ->
                        val sorted = bandMap.entries.sortedBy { it.key }
                        if (reversed) {
                            sorted.reversed().map { YBand(it.key, it.value) }
                        } else {
                            sorted.map { YBand(it.key, it.value) }
                        }
                    }

            // Step 3: find the absolute lowest/highest Y-band across all pixels
            val globalEdgeBand =
                    columns.values.mapNotNull { bands -> bands.firstOrNull()?.yKey }.let {
                        if (reversed) it.maxOrNull() else it.minOrNull()
                    }
                            ?: 0

            val progress = columns.keys.associateWithTo(mutableMapOf()) { 0 }
            return BuildAnimation(
                    mannequinId = mannequinId,
                    columns = columns,
                    progress = progress,
                    cancelled = mutableSetOf(),
                    tickInterval = tickInterval,
                    skipChance = skipChance,
                    flyInCount = flyInCount,
                    reversed = reversed,
                    globalEdgeBand = globalEdgeBand
            )
        }
    }
}
