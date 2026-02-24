package com.sneakymannequins.util

/**
 * Shared UV-region definitions for the vanilla 64x64 skin layout.
 *
 * Keep inner/outer UV geometry centralized here so renderers/composers do not
 * duplicate hardcoded coordinate blocks in multiple places.
 */
object SkinUv {
    data class Rect(val x: Int, val y: Int, val w: Int, val h: Int)

    /**
     * All valid UV regions in the vanilla 64x64 layout (base + overlay).
     * Pixels outside these rectangles should be treated as non-UV junk.
     */
    val ALL_UV_RECTS: List<Rect> = listOf(
        Rect(0, 0, 32, 16),    // head base
        Rect(32, 0, 32, 16),   // head overlay
        Rect(16, 16, 24, 16),  // torso base
        Rect(16, 32, 40, 16),  // torso overlay
        Rect(40, 16, 16, 16),  // right arm base
        Rect(40, 32, 16, 16),  // right arm overlay
        Rect(32, 48, 16, 16),  // left arm base
        Rect(48, 48, 16, 16),  // left arm overlay
        Rect(0, 16, 16, 16),   // right leg base
        Rect(0, 32, 16, 16),   // right leg overlay
        Rect(16, 48, 16, 16),  // left leg base
        Rect(0, 48, 16, 16)    // left leg overlay
    )

    /**
     * Inner/base layer regions (the non-overlay UVs).
     * These are the only regions where vanilla requires fully opaque pixels.
     */
    val INNER_BASE_RECTS: List<Rect> = listOf(
        Rect(0, 0, 32, 16),    // head base
        Rect(0, 16, 56, 16),   // right leg + torso + right arm base
        Rect(16, 48, 32, 16)   // left leg + left arm base
    )

    fun isInAnyUv(x: Int, y: Int): Boolean =
        ALL_UV_RECTS.any { x in it.x until (it.x + it.w) && y in it.y until (it.y + it.h) }

    inline fun forEachInnerBasePixel(block: (x: Int, y: Int) -> Unit) {
        for (r in INNER_BASE_RECTS) {
            for (x in r.x until (r.x + r.w)) {
                for (y in r.y until (r.y + r.h)) {
                    block(x, y)
                }
            }
        }
    }
}
