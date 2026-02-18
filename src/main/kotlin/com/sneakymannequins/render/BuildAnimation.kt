package com.sneakymannequins.render

import java.util.UUID

/**
 * A transformation offset for a pixel that should "fly in" from a
 * random distant position and interpolate to its final location.
 */
data class FlyInOffset(
    val offsetX: Float,
    val offsetY: Float,
    val offsetZ: Float,
    /** Number of client ticks over which the entity interpolates to its target position. */
    val interpolationTicks: Int
)

/**
 * Result of a single [BuildAnimation.step].
 *
 * @property pixels         All pixels that should be delivered this step.
 * @property flyInOffsets   Subset of [pixels] (keyed by [ProjectedPixel.index]) that
 *                          should spawn at a random offset and interpolate to their
 *                          target position over [FlyInOffset.interpolationTicks] ticks.
 */
data class StepResult(
    val pixels: List<ProjectedPixel>,
    val flyInOffsets: Map<Int, FlyInOffset>
) {
    companion object {
        val EMPTY = StepResult(emptyList(), emptyMap())
    }
}

/**
 * Tracks the state of a bottom-to-top BUILD animation for a single
 * mannequin viewed by a single player.
 *
 * Pixels are grouped into vertical **columns** based on their model-space
 * (X, Z) footprint.  Within each column, pixels are further grouped into
 * **Y-bands** — all pixels at roughly the same height (base + overlay) are
 * sent together as a single step.  Each build step, every column
 * independently decides whether to advance one Y-band or skip that tick,
 * creating a staggered / chaotic build-up effect.
 *
 * **Cascading rule** (applied per-step, per-band):
 * A band may only be sent if its Y-level is at most 1 band above the
 * **wave front** — the highest Y-band that was fully sent in a *previous*
 * step.  Bands at the global minimum Y are always exempt.
 *
 * Because we snapshot the wave front at the start of each step, the front
 * can advance by at most 1 band per step.  This creates a visible rising
 * wave: feet first, then legs, body, arms, and finally the head.  The
 * skip-chance adds organic stagger within each tier.
 *
 * **Fly-in effect**: each step, up to [flyInCount] randomly-chosen pixels
 * are tagged with a [FlyInOffset].  These pixels spawn at a distant
 * position and interpolate to their target over [tickInterval] ticks.
 */
class BuildAnimation private constructor(
    val mannequinId: UUID,
    private val columns: Map<ColumnKey, List<YBand>>,
    private val progress: MutableMap<ColumnKey, Int>,
    private val cancelled: MutableSet<Int>,
    private val tickInterval: Int,
    private val skipChance: Double,
    private val flyInCount: Int,
    /** The absolute lowest Y-band key across all columns / bands. */
    private val globalMinBand: Int
) {
    private var tickCounter = 0
    private val random = java.util.Random()

    /**
     * The highest Y-band key sent by any column so far (updated live during
     * each step, then used as the snapshot for the *next* step).
     */
    private var highWaterBand: Int = Int.MIN_VALUE

    /** Quantised model-space column identifier. */
    data class ColumnKey(val x: Int, val z: Int)

    /** A group of pixels at the same Y-level within a column. */
    data class YBand(val yKey: Int, val pixels: List<ProjectedPixel>)

    // ── Public API ──────────────────────────────────────────────────────────────

    /**
     * Mark pixel indices as cancelled.  These pixels will be silently
     * skipped when their band is reached — a later animation owns them.
     */
    fun cancelPixels(indices: Set<Int>) {
        cancelled.addAll(indices)
    }

    /**
     * `true` when every column has been fully sent or cancelled.
     */
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
     *         [StepResult.EMPTY] if nothing to do this tick.
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
            // Advance past fully-cancelled bands
            while (idx < bands.size && bands[idx].pixels.all { it.index in cancelled }) idx++
            progress[key] = idx

            if (idx >= bands.size) continue

            // ── Cascading: block bands above the wave front ─────────────
            // Bands at the global minimum are always exempt.
            val band = bands[idx]
            if (band.yKey > globalMinBand && band.yKey > waterSnapshot + 1) continue

            if (random.nextDouble() < skipChance) continue

            // Send all non-cancelled pixels in this band
            for (pixel in band.pixels) {
                if (pixel.index !in cancelled) {
                    result.add(pixel)
                }
            }
            progress[key] = idx + 1

            // Update the live high-water mark (becomes the snapshot for the next step)
            if (band.yKey > highWaterBand) {
                highWaterBand = band.yKey
            }
        }

        if (result.isEmpty()) return StepResult.EMPTY

        // ── Fly-in selection ────────────────────────────────────────────
        val flyInOffsets = mutableMapOf<Int, FlyInOffset>()
        if (flyInCount > 0) {
            val chosen = if (result.size <= flyInCount) {
                result
            } else {
                result.shuffled(random).take(flyInCount)
            }
            for (pixel in chosen) {
                flyInOffsets[pixel.index] = randomFlyInOffset()
            }
        }

        return StepResult(result, flyInOffsets)
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    /**
     * Generate a random fly-in offset.
     * X / Z: ±[1, 3] (random sign), Y: [1, 3] (always up).
     */
    private fun randomFlyInOffset(): FlyInOffset {
        val xSign = if (random.nextBoolean()) 1f else -1f
        val zSign = if (random.nextBoolean()) 1f else -1f
        return FlyInOffset(
            offsetX = xSign * (1f + random.nextFloat() * 2f),
            offsetY = 1f + random.nextFloat() * 2f,
            offsetZ = zSign * (1f + random.nextFloat() * 2f),
            interpolationTicks = tickInterval * 2
        )
    }

    // ── Factory ─────────────────────────────────────────────────────────────────

    companion object {
        /**
         * Column quantisation bin size — 2 × pixel scale (= 1/8 block).
         * Coarse enough that base-layer and overlay-layer pixels at the same
         * logical model position end up in the same column.
         */
        private const val BIN_SIZE = 1.0 / 8.0

        fun create(
            mannequinId: UUID,
            projected: List<ProjectedPixel>,
            tickInterval: Int,
            skipChance: Double,
            flyInCount: Int = 0
        ): BuildAnimation {
            // Step 1: group pixels into (column, Y-band) buckets
            val grouped = mutableMapOf<ColumnKey, MutableMap<Int, MutableList<ProjectedPixel>>>()
            for (pixel in projected) {
                val colKey = ColumnKey(
                    Math.round(pixel.modelX / BIN_SIZE).toInt(),
                    Math.round(pixel.modelZ / BIN_SIZE).toInt()
                )
                val yBandKey = Math.round(pixel.y / BIN_SIZE).toInt()
                grouped.getOrPut(colKey) { mutableMapOf() }
                    .getOrPut(yBandKey) { mutableListOf() }
                    .add(pixel)
            }

            // Step 2: convert to sorted list of YBands per column (ascending Y)
            val columns = grouped.mapValues { (_, bandMap) ->
                bandMap.entries
                    .sortedBy { it.key }
                    .map { YBand(it.key, it.value) }
            }

            // Step 3: find the absolute lowest Y-band across all pixels
            val globalMinBand = columns.values
                .mapNotNull { bands -> bands.firstOrNull()?.yKey }
                .minOrNull() ?: 0

            val progress = columns.keys.associateWithTo(mutableMapOf()) { 0 }
            return BuildAnimation(
                mannequinId = mannequinId,
                columns = columns,
                progress = progress,
                cancelled = mutableSetOf(),
                tickInterval = tickInterval,
                skipChance = skipChance,
                flyInCount = flyInCount,
                globalMinBand = globalMinBand
            )
        }
    }
}
