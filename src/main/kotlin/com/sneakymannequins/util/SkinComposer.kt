package com.sneakymannequins.util

import com.sneakymannequins.model.LayerSelection
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
            val chosen = selection.selections[layer.id]?.option ?: return@forEach
            val colorMask = selection.selections[layer.id]?.colorMask
            val maskIndex = selection.selections[layer.id]?.maskIndex
            val maskImage = maskIndex?.let { chosen.masks[it] }?.let { javax.imageio.ImageIO.read(it.toFile()) }
            val sourceImage = when {
                useSlimModel && chosen.imageSlim != null -> chosen.imageSlim
                else -> chosen.imageDefault ?: chosen.imageSlim
            } ?: return@forEach
            val source = if (colorMask != null && layer.allowColorMask) {
                applyColorMask(sourceImage, colorMask, maskImage)
            } else {
                sourceImage
            }
            graphics.drawImage(source, 0, 0, null)
        }

        graphics.dispose()
        return output
    }

    private fun applyColorMask(image: BufferedImage, mask: Color, channelMask: BufferedImage?): BufferedImage {
        val tinted = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        val maskRgb = mask.rgb and 0x00FFFFFF

        for (x in 0 until image.width) {
            for (y in 0 until image.height) {
                val argb = image.getRGB(x, y)
                val alpha = argb ushr 24 and 0xFF
                if (alpha == 0) {
                    tinted.setRGB(x, y, 0)
                    continue
                }
                if (channelMask != null) {
                    val maskAlpha = channelMask.getRGB(x, y) ushr 24 and 0xFF
                    if (maskAlpha == 0) {
                        tinted.setRGB(x, y, 0)
                        continue
                    }
                    val tintedArgb = (alpha shl 24) or maskRgb
                    tinted.setRGB(x, y, tintedArgb)
                } else {
                    val tintedArgb = (alpha shl 24) or maskRgb
                    tinted.setRGB(x, y, tintedArgb)
                }
            }
        }

        return tinted
    }
}

