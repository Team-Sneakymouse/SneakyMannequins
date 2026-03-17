package com.sneakymannequins.util

/**
 * Shared UV-region definitions for the vanilla 64x64 skin layout.
 *
 * Keep inner/outer UV geometry centralized here so renderers/composers do not replicate hardcoded
 * coordinate blocks in multiple places.
 */
object SkinUv {
    data class Rect(val x: Int, val y: Int, val w: Int, val h: Int)

    data class FaceUv(val u: Int, val v: Int)

    /** Represents the UV layout and dimensions (width, height, depth) of a body part. */
    data class PartUv(
            val w: Int,
            val h: Int,
            val d: Int,
            val top: FaceUv,
            val bottom: FaceUv,
            val front: FaceUv,
            val back: FaceUv,
            val left: FaceUv,
            val right: FaceUv
    )

    // Dimensions: (Head=8x8x8, Torso=8x12x4, Arms=4x12x4, SlimArms=3x12x4, Legs=4x12x4)
    val HEAD_BASE =
            PartUv(
                    8,
                    8,
                    8,
                    FaceUv(8, 0),
                    FaceUv(16, 0),
                    FaceUv(8, 8),
                    FaceUv(24, 8),
                    FaceUv(0, 8),
                    FaceUv(16, 8)
            )
    val HEAD_OVERLAY =
            PartUv(
                    8,
                    8,
                    8,
                    FaceUv(40, 0),
                    FaceUv(48, 0),
                    FaceUv(40, 8),
                    FaceUv(56, 8),
                    FaceUv(32, 8),
                    FaceUv(48, 8)
            )

    val TORSO_BASE =
            PartUv(
                    8,
                    12,
                    4,
                    FaceUv(20, 16),
                    FaceUv(28, 16),
                    FaceUv(20, 20),
                    FaceUv(32, 20),
                    FaceUv(16, 20),
                    FaceUv(28, 20)
            )
    val TORSO_OVERLAY =
            PartUv(
                    8,
                    12,
                    4,
                    FaceUv(20, 32),
                    FaceUv(28, 32),
                    FaceUv(20, 36),
                    FaceUv(32, 36),
                    FaceUv(16, 36),
                    FaceUv(28, 36)
            )

    // Steve (4x) Arms
    val STEVE_RIGHT_ARM_BASE =
            PartUv(
                    4,
                    12,
                    4,
                    FaceUv(44, 16),
                    FaceUv(48, 16),
                    FaceUv(44, 20),
                    FaceUv(52, 20),
                    FaceUv(40, 20),
                    FaceUv(48, 20)
            )
    val STEVE_RIGHT_ARM_OVERLAY =
            PartUv(
                    4,
                    12,
                    4,
                    FaceUv(44, 32),
                    FaceUv(48, 32),
                    FaceUv(44, 36),
                    FaceUv(52, 36),
                    FaceUv(40, 36),
                    FaceUv(48, 36)
            )
    val STEVE_LEFT_ARM_BASE =
            PartUv(
                    4,
                    12,
                    4,
                    FaceUv(36, 48),
                    FaceUv(40, 48),
                    FaceUv(36, 52),
                    FaceUv(44, 52),
                    FaceUv(32, 52),
                    FaceUv(40, 52)
            )
    val STEVE_LEFT_ARM_OVERLAY =
            PartUv(
                    4,
                    12,
                    4,
                    FaceUv(52, 48),
                    FaceUv(56, 48),
                    FaceUv(52, 52),
                    FaceUv(60, 52),
                    FaceUv(48, 52),
                    FaceUv(56, 52)
            )

    // Alex (3x) Arms
    val ALEX_RIGHT_ARM_BASE =
            PartUv(
                    3,
                    12,
                    4,
                    FaceUv(44, 16),
                    FaceUv(47, 16),
                    FaceUv(44, 20),
                    FaceUv(51, 20),
                    FaceUv(40, 20),
                    FaceUv(47, 20)
            )
    val ALEX_RIGHT_ARM_OVERLAY =
            PartUv(
                    3,
                    12,
                    4,
                    FaceUv(44, 32),
                    FaceUv(47, 32),
                    FaceUv(44, 36),
                    FaceUv(51, 36),
                    FaceUv(40, 36),
                    FaceUv(47, 36)
            )
    val ALEX_LEFT_ARM_BASE =
            PartUv(
                    3,
                    12,
                    4,
                    FaceUv(36, 48),
                    FaceUv(39, 48),
                    FaceUv(36, 52),
                    FaceUv(43, 52),
                    FaceUv(32, 52),
                    FaceUv(39, 52)
            )
    val ALEX_LEFT_ARM_OVERLAY =
            PartUv(
                    3,
                    12,
                    4,
                    FaceUv(52, 48),
                    FaceUv(55, 48),
                    FaceUv(52, 52),
                    FaceUv(59, 52),
                    FaceUv(48, 52),
                    FaceUv(55, 52)
            )

    val RIGHT_LEG_BASE =
            PartUv(
                    4,
                    12,
                    4,
                    FaceUv(4, 16),
                    FaceUv(8, 16),
                    FaceUv(4, 20),
                    FaceUv(12, 20),
                    FaceUv(0, 20),
                    FaceUv(8, 20)
            )
    val RIGHT_LEG_OVERLAY =
            PartUv(
                    4,
                    12,
                    4,
                    FaceUv(4, 32),
                    FaceUv(8, 32),
                    FaceUv(4, 36),
                    FaceUv(12, 36),
                    FaceUv(0, 36),
                    FaceUv(8, 36)
            )

    val LEFT_LEG_BASE =
            PartUv(
                    4,
                    12,
                    4,
                    FaceUv(20, 48),
                    FaceUv(24, 48),
                    FaceUv(20, 52),
                    FaceUv(28, 52),
                    FaceUv(16, 52),
                    FaceUv(24, 52)
            )
    val LEFT_LEG_OVERLAY =
            PartUv(
                    4,
                    12,
                    4,
                    FaceUv(4, 48),
                    FaceUv(8, 48),
                    FaceUv(4, 52),
                    FaceUv(12, 52),
                    FaceUv(0, 52),
                    FaceUv(8, 52)
            )

    /**
     * All valid UV regions in the vanilla 64x64 layout (base + overlay). Pixels outside these
     * rectangles should be treated as non-UV junk.
     */
    val ALL_UV_RECTS: List<Rect> =
            listOf(
                    Rect(0, 0, 32, 16), // head base
                    Rect(32, 0, 32, 16), // head overlay
                    Rect(16, 16, 24, 16), // torso base
                    Rect(16, 32, 40, 16), // torso overlay
                    Rect(40, 16, 16, 16), // right arm base
                    Rect(40, 32, 16, 16), // right arm overlay
                    Rect(32, 48, 16, 16), // left arm base
                    Rect(48, 48, 16, 16), // left arm overlay
                    Rect(0, 16, 16, 16), // right leg base
                    Rect(0, 32, 16, 16), // right leg overlay
                    Rect(16, 48, 16, 16), // left leg base
                    Rect(0, 48, 16, 16) // left leg overlay
            )

    /**
     * Inner/base layer regions (the non-overlay UVs). These are the only regions where vanilla
     * requires fully opaque pixels.
     */
    val INNER_BASE_RECTS: List<Rect> =
            listOf(
                    Rect(0, 0, 32, 16), // head base
                    Rect(0, 16, 56, 16), // right leg + torso + right arm base
                    Rect(16, 48, 32, 16) // left leg + left arm base
            )

    /** Overlay/outer layer regions. */
    val OUTER_OVERLAY_RECTS: List<Rect> =
            listOf(
                    Rect(32, 0, 32, 16), // head overlay
                    Rect(16, 32, 40, 16), // torso overlay
                    Rect(40, 32, 16, 16), // right arm overlay
                    Rect(48, 48, 16, 16), // left arm overlay
                    Rect(0, 32, 16, 16), // right leg overlay
                    Rect(0, 48, 16, 16) // left leg overlay
            )

    fun isInAnyUv(x: Int, y: Int): Boolean =
            ALL_UV_RECTS.any { x in it.x until (it.x + it.w) && y in it.y until (it.y + it.h) }

    fun isOuterLayer(x: Int, y: Int): Boolean =
            OUTER_OVERLAY_RECTS.any {
                x in it.x until (it.x + it.w) && y in it.y until (it.y + it.h)
            }

    fun isArmPixel(x: Int, y: Int): Boolean {
        // Right arm base (40, 16, 16, 16)
        if (x in 40..55 && y in 16..31) return true
        // Right arm overlay (40, 32, 16, 16)
        if (x in 40..55 && y in 32..47) return true
        // Left arm base (32, 48, 16, 16)
        if (x in 32..47 && y in 48..63) return true
        // Left arm overlay (48, 48, 16, 16)
        if (x in 48..63 && y in 48..63) return true
        return false
    }

    /**
     * Given an outer layer coordinate (x, y), returns the corresponding inner layer coordinate, or
     * null if it's not in an outer layer.
     */
    fun getInnerCorresponding(x: Int, y: Int): Pair<Int, Int>? {
        return when {
            // Head: (32..63, 0..15) -> (0..31, 0..15)
            x in 32..63 && y in 0..15 -> (x - 32) to y
            // Torso: (16..55, 32..47) -> (16..39, 16..31)
            x in 16..55 && y in 32..47 -> x to (y - 16)
            // Left Arm: (48..63, 48..63) -> (32..47, 48..63)
            x in 48..63 && y in 48..63 -> (x - 16) to y
            // Right Leg: (0..15, 32..47) -> (0..15, 16..31)
            x in 0..15 && y in 32..47 -> x to (y - 16)
            // Left Leg: (0..15, 48..63) -> (16..31, 48..63)
            x in 0..15 && y in 48..63 -> (x + 16) to y
            else -> null
        }
    }

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
