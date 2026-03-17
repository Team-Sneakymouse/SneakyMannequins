package com.sneakymannequins.util

import java.awt.image.BufferedImage

/**
 * Utility for "Craig-ifying" and "Uncraig-ifying" Minecraft skins.
 * This transforms the head texture regions to support skins where 
 * the face is drawn on the top of the head (pitching up 90 deg).
 */
object SkinTransform {

    /**
     * Pitch the head UP by 90 degrees.
     * Front -> Top, Top -> Back, Back -> Bottom, Bottom -> Front.
     */
    fun craig(image: BufferedImage): BufferedImage {
        val out = copyImage(image)
        applyCraigToLayer(image, out, 0, 0)   // Inner layer
        applyCraigToLayer(image, out, 32, 0)  // Outer layer
        return out
    }

    /**
     * Pitch the head DOWN by 90 degrees.
     * The inverse of craig().
     */
    fun uncraig(image: BufferedImage): BufferedImage {
        val out = copyImage(image)
        applyUncraigToLayer(image, out, 0, 0)   // Inner layer
        applyUncraigToLayer(image, out, 32, 0)  // Outer layer
        return out
    }

    private fun applyCraigToLayer(src: BufferedImage, dst: BufferedImage, ox: Int, oy: Int) {
        // Source patches (Head relative)
        // Top: (8,0), Bottom: (16,0), Right: (0,8), Front: (8,8), Left: (16,8), Back: (24,8)
        
        // 5 (Right) -> rotate 90 deg CCW
        copyPatch(src, ox + 0, oy + 8, dst, ox + 0, oy + 8, rotate = -90)
        
        // 7 (Left) -> rotate 90 deg CW
        copyPatch(src, ox + 16, oy + 8, dst, ox + 16, oy + 8, rotate = 90)

        // 6 (Front) -> slide to 2 (Top)
        copyPatch(src, ox + 8, oy + 8, dst, ox + 8, oy + 0)

        // 2 (Top) -> slide to 8 (Back), and rotate 180 degrees
        copyPatch(src, ox + 8, oy + 0, dst, ox + 24, oy + 8, rotate = 180)

        // 8 (Back) -> slide to 3 (Bottom), and rotate 180 degrees
        copyPatch(src, ox + 24, oy + 8, dst, ox + 16, oy + 0, rotate = 180)

        // 3 (Bottom) -> slide to 6 (Front), and rotate 180 degrees
        copyPatch(src, ox + 16, oy + 0, dst, ox + 8, oy + 8, rotate = 180)
    }

    private fun applyUncraigToLayer(src: BufferedImage, dst: BufferedImage, ox: Int, oy: Int) {
        // Inverse of Craig
        
        // 5 (Right) -> rotate 90 deg CW (Inverse of CCW)
        copyPatch(src, ox + 0, oy + 8, dst, ox + 0, oy + 8, rotate = 90)
        
        // 7 (Left) -> rotate 90 deg CCW (Inverse of CW)
        copyPatch(src, ox + 16, oy + 8, dst, ox + 16, oy + 8, rotate = -90)

        // 2 (Top) -> slide to 6 (Front)
        copyPatch(src, ox + 8, oy + 0, dst, ox + 8, oy + 8)

        // 8 (Back) -> slide to 2 (Top), and rotate 180 degrees
        copyPatch(src, ox + 24, oy + 8, dst, ox + 8, oy + 0, rotate = 180)

        // 3 (Bottom) -> slide to 8 (Back), and rotate 180 degrees
        copyPatch(src, ox + 16, oy + 0, dst, ox + 24, oy + 8, rotate = 180)

        // 6 (Front) -> slide to 3 (Bottom), and rotate 180 degrees
        copyPatch(src, ox + 8, oy + 8, dst, ox + 16, oy + 0, rotate = 180)
    }

    private fun copyPatch(
        src: BufferedImage, sx: Int, sy: Int, 
        dst: BufferedImage, dx: Int, dy: Int,
        rotate: Int = 0 // 0, 90, 180, -90
    ) {
        val patch = BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB)
        for (px in 0 until 8) {
            for (py in 0 until 8) {
                // Determine source pixel in the 8x8 patch based on rotation
                val srcRGB = src.getRGB(sx + px, sy + py)
                
                val (tx, ty) = when (rotate) {
                    90 -> (7 - py) to px
                    180 -> (7 - px) to (7 - py)
                    -90, 270 -> py to (7 - px)
                    else -> px to py
                }
                patch.setRGB(tx, ty, srcRGB)
            }
        }
        
        // Draw the transformed patch into dst
        for (px in 0 until 8) {
            for (py in 0 until 8) {
                dst.setRGB(dx + px, dy + py, patch.getRGB(px, py))
            }
        }
    }

    private fun copyImage(original: BufferedImage): BufferedImage {
        val copy = BufferedImage(original.width, original.height, original.type)
        val g = copy.createGraphics()
        g.drawImage(original, 0, 0, null)
        g.dispose()
        return copy
    }
}
