package com.sneakymannequins.model

import java.awt.image.BufferedImage

private const val SKIN_SIZE = 64
private const val TOTAL_PIXELS = SKIN_SIZE * SKIN_SIZE

data class PixelChange(val x: Int, val y: Int, val argb: Int?, val visible: Boolean)

/** Represents a 64x64 ARGB pixel grid with diff utilities. */
class PixelFrame(private val pixels: IntArray) {

    fun get(x: Int, y: Int): Int {
        require(x in 0 until SKIN_SIZE && y in 0 until SKIN_SIZE) { "Pixel out of bounds ($x,$y)" }
        return pixels[index(x, y)]
    }

    fun diff(next: PixelFrame): List<PixelChange> = diff(next, null)

    fun diff(next: PixelFrame, forcePredicate: ((Int, Int) -> Boolean)?): List<PixelChange> {
        val changes = mutableListOf<PixelChange>()
        for (i in 0 until TOTAL_PIXELS) {
            val current = pixels[i]
            val target = next.pixels[i]
            val x = i % SKIN_SIZE
            val y = i / SKIN_SIZE
            val forced = forcePredicate?.invoke(x, y) ?: false
            if (!forced && current == target) continue
            val visible = (target ushr 24) != 0
            changes += PixelChange(x, y, argb = if (visible) target else null, visible = visible)
        }
        return changes
    }

    companion object {
        fun blank(): PixelFrame = PixelFrame(IntArray(TOTAL_PIXELS))

        fun fromImage(image: BufferedImage): PixelFrame {
            require(image.width == SKIN_SIZE && image.height == SKIN_SIZE) {
                "Expected 64x64 image, got ${image.width}x${image.height}"
            }
            val buffer = IntArray(TOTAL_PIXELS)
            image.getRGB(0, 0, SKIN_SIZE, SKIN_SIZE, buffer, 0, SKIN_SIZE)
            return PixelFrame(buffer)
        }
    }

    private fun index(x: Int, y: Int): Int = y * SKIN_SIZE + x
}
