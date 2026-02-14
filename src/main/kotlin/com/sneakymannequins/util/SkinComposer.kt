package com.sneakymannequins.util

import com.sneakymannequins.model.SkinSelection
import com.sneakymannequins.model.LayerDefinition
import java.awt.Color
import java.awt.image.BufferedImage

private const val SKIN_SIZE = 64

/**
 * Combines selected layers into a final 64x64 ARGB skin image.
 */
object SkinComposer {

    fun compose(layers: List<LayerDefinition>, selection: SkinSelection, useSlimModel: Boolean): BufferedImage {
        val output = BufferedImage(SKIN_SIZE, SKIN_SIZE, BufferedImage.TYPE_INT_ARGB)
        val graphics = output.createGraphics()

        layers.forEach { layer ->
            val sel = selection.selections[layer.id] ?: return@forEach
            val chosen = sel.option ?: return@forEach
            val sourceImage = when {
                useSlimModel && chosen.imageSlim != null -> chosen.imageSlim
                else -> chosen.imageDefault ?: chosen.imageSlim
            } ?: return@forEach

            // Apply each channel's color independently, then composite
            var source = sourceImage
            if (layer.allowColorMask && sel.channelColors.isNotEmpty()) {
                for ((channelIdx, color) in sel.channelColors) {
                    val maskImage = chosen.masks[channelIdx]?.let { javax.imageio.ImageIO.read(it.toFile()) }
                    source = applyColorMask(source, color, maskImage)
                }
            }
            graphics.drawImage(source, 0, 0, null)
        }

        graphics.dispose()
        return output
    }

    /**
     * Tint the image using relative HSB remapping.
     *
     * 1. First pass: collect all masked pixels and compute the average hue (circular mean)
     *    and average saturation of the original cluster.
     * 2. Second pass: for each masked pixel, apply the *offset* (target − average) to hue,
     *    and *scale* saturation by (target / average). Brightness is kept as-is.
     *
     * This preserves the original hue/saturation variance (e.g. teal-ish vs navy-ish blues)
     * instead of flattening everything to a single colour.
     *
     * Pixels outside the channel mask are kept unchanged from the original image.
     */
    private fun applyColorMask(image: BufferedImage, mask: Color, channelMask: BufferedImage?): BufferedImage {
        val tinted = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        val maskHsb = Color.RGBtoHSB(mask.red, mask.green, mask.blue, null)
        val targetHue = maskHsb[0]
        val targetSat = maskHsb[1]

        // --- First pass: compute average H and S of masked pixels ---
        var sinSum = 0.0
        var cosSum = 0.0
        var satSum = 0.0
        var count = 0

        for (x in 0 until image.width) {
            for (y in 0 until image.height) {
                val argb = image.getRGB(x, y)
                if ((argb ushr 24 and 0xFF) == 0) continue
                val inMask = if (channelMask != null) {
                    (channelMask.getRGB(x, y) ushr 24 and 0xFF) > 0
                } else true
                if (!inMask) continue

                val r = argb shr 16 and 0xFF
                val g = argb shr 8 and 0xFF
                val b = argb and 0xFF
                val hsb = Color.RGBtoHSB(r, g, b, null)
                // Circular mean for hue (hue wraps around at 1.0)
                val angle = hsb[0] * 2.0 * Math.PI
                sinSum += Math.sin(angle)
                cosSum += Math.cos(angle)
                satSum += hsb[1]
                count++
            }
        }

        if (count == 0) return image // nothing to tint

        val avgHue = ((Math.atan2(sinSum, cosSum) / (2.0 * Math.PI)).toFloat() + 1f) % 1f
        val avgSat = (satSum / count).toFloat()
        val hueDelta = targetHue - avgHue
        val satScale = if (avgSat > 0.001f) targetSat / avgSat else 0f

        // --- Second pass: apply relative offset to each masked pixel ---
        for (x in 0 until image.width) {
            for (y in 0 until image.height) {
                val argb = image.getRGB(x, y)
                val alpha = argb ushr 24 and 0xFF
                if (alpha == 0) {
                    tinted.setRGB(x, y, 0)
                    continue
                }

                val inMask = if (channelMask != null) {
                    (channelMask.getRGB(x, y) ushr 24 and 0xFF) > 0
                } else true

                if (!inMask) {
                    tinted.setRGB(x, y, argb)
                    continue
                }

                val r = argb shr 16 and 0xFF
                val g = argb shr 8 and 0xFF
                val b = argb and 0xFF
                val hsb = Color.RGBtoHSB(r, g, b, null)

                // Shift hue by the offset (wraps around), scale saturation proportionally
                val newHue = (hsb[0] + hueDelta + 1f) % 1f
                val newSat = (hsb[1] * satScale).coerceIn(0f, 1f)
                val newRgb = Color.HSBtoRGB(newHue, newSat, hsb[2])
                tinted.setRGB(x, y, (alpha shl 24) or (newRgb and 0x00FFFFFF))
            }
        }

        return tinted
    }
}

