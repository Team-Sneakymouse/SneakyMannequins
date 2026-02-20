package com.sneakymannequins.util

import com.sneakymannequins.model.LayerOption
import com.sneakymannequins.model.SkinSelection
import com.sneakymannequins.model.LayerDefinition
import com.sneakymannequins.model.DetailMode
import com.sneakymannequins.model.TextureDefinition
import java.awt.Color
import java.awt.image.BufferedImage

private const val SKIN_SIZE = 64

/**
 * Combines selected layers into a final 64x64 ARGB skin image.
 */
object SkinComposer {

    /**
     * @param optionResolver  optional function that resolves the current (fresh)
     *        [LayerOption] for a given layer ID and selection option ID. If
     *        provided, mask paths are read from the resolved option instead of
     *        the potentially stale one stored in the selection.
     * @param textureResolver  optional function that resolves the selected
     *        [TextureDefinition] for a given layer ID.  Returns null when the
     *        layer uses "Default" (flat colour, no texture).
     */
    fun compose(
        layers: List<LayerDefinition>,
        selection: SkinSelection,
        useSlimModel: Boolean,
        optionResolver: ((layerId: String, optionId: String) -> LayerOption?)? = null,
        textureResolver: ((layerId: String) -> TextureDefinition?)? = null
    ): BufferedImage {
        val output = BufferedImage(SKIN_SIZE, SKIN_SIZE, BufferedImage.TYPE_INT_ARGB)
        val graphics = output.createGraphics()

        layers.forEach { layer ->
            val sel = selection.selections[layer.id] ?: return@forEach
            val selOption = sel.option ?: return@forEach
            // Resolve fresh option (with up-to-date masks) if a resolver is provided
            val chosen = optionResolver?.invoke(layer.id, selOption.id) ?: selOption
            val sourceImage = when {
                useSlimModel && chosen.imageSlim != null -> chosen.imageSlim
                else -> chosen.imageDefault ?: chosen.imageSlim
            } ?: return@forEach

            // Apply each channel's color independently, then composite.
            // Skip channels whose mask file is missing — tinting without a
            // mask would recolour every pixel instead of just the channel.
            var source = sourceImage
            if (layer.allowColorMask) {
                // Resolve the active texture for this layer (null = "Default" / flat)
                val texDef = textureResolver?.invoke(layer.id)

                // Collect all channels that need colouring (flat or textured)
                val flatChannels = sel.channelColors
                val texturedChannels = sel.texturedColors

                // Load all mask images upfront so we can identify unmasked pixels later
                val maskImages = chosen.masks.mapNotNull { (idx, path) ->
                    val img = try { javax.imageio.ImageIO.read(path.toFile()) } catch (_: Exception) { null }
                    if (img != null) idx to img else null
                }.toMap()

                val allChannels = (flatChannels.keys + texturedChannels.keys).sorted()
                for (channelIdx in allChannels) {
                    val maskImage = maskImages[channelIdx] ?: continue

                    // Determine rendering path based on the selected texture
                    val blendImage = texDef?.blendMapImage
                    val detailImage = texDef?.detailMapImage
                    val detailMode = texDef?.detailMode ?: DetailMode.ADD
                    val subColors = texturedChannels[channelIdx]

                    if (blendImage != null && subColors != null && subColors.isNotEmpty()) {
                        // Path 1: Blend map present → textured sub-channel mixing
                        source = applyTexturedColorMask(source, subColors, maskImage, blendImage, detailImage, detailMode)
                        continue
                    }

                    if (detailImage != null) {
                        // Path 2: Detail map only → flat hue + detail modulation
                        val flatColor = flatChannels[channelIdx] ?: continue
                        source = applyColorMaskWithDetail(source, flatColor, maskImage, detailImage, detailMode)
                        continue
                    }

                    // Path 3: No texture → flat-colour path (original behaviour)
                    val flatColor = flatChannels[channelIdx] ?: continue
                    source = applyColorMask(source, flatColor, maskImage)
                }

                // When a texture with a detail map is selected, apply it to ALL pixels
                // in the part — including those not covered by any mask channel.
                // Masked pixels were already detail-modulated above, so we only touch
                // pixels that are NOT in any mask.
                val detailImage = texDef?.detailMapImage
                if (detailImage != null) {
                    source = applyDetailToUnmasked(source, detailImage, texDef.detailMode, maskImages.values.toList())
                }
            }
            graphics.drawImage(source, 0, 0, null)
        }

        graphics.dispose()
        return output
    }

    /**
     * Tint the image using relative HSB remapping (flat colour, no texture).
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
                val angle = hsb[0] * 2.0 * Math.PI
                sinSum += Math.sin(angle)
                cosSum += Math.cos(angle)
                satSum += hsb[1]
                count++
            }
        }

        if (count == 0) return image

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

                val newHue = (hsb[0] + hueDelta + 1f) % 1f
                val newSat = (hsb[1] * satScale).coerceIn(0f, 1f)
                val newRgb = Color.HSBtoRGB(newHue, newSat, hsb[2])
                tinted.setRGB(x, y, (alpha shl 24) or (newRgb and 0x00FFFFFF))
            }
        }

        return tinted
    }

    // ── Detail-only colour masking ──────────────────────────────────────────────

    /**
     * Tint the image using relative HSB remapping with **detail map modulation**.
     * Same as [applyColorMask] but after computing the remapped S and B values,
     * the detail map is applied according to [detailMode]:
     *
     * - [DetailMode.ADD]: detail *modulates on top of* the remapped B/S (128 = neutral).
     * - [DetailMode.REPLACE]: detail *replaces* the original B/S entirely.
     *   The detail luminance becomes the new absolute saturation and brightness
     *   (128 = 1.0; 0 = 0.0; 255 ≈ 2.0 clamped to 1.0).
     *
     * @param detailMap  64×64 grayscale PNG
     */
    private fun applyColorMaskWithDetail(
        image: BufferedImage,
        mask: Color,
        channelMask: BufferedImage,
        detailMap: BufferedImage,
        detailMode: DetailMode = DetailMode.ADD
    ): BufferedImage {
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
                if ((channelMask.getRGB(x, y) ushr 24 and 0xFF) == 0) continue
                val r = argb shr 16 and 0xFF
                val g = argb shr 8 and 0xFF
                val b = argb and 0xFF
                val hsb = Color.RGBtoHSB(r, g, b, null)
                val angle = hsb[0] * 2.0 * Math.PI
                sinSum += Math.sin(angle)
                cosSum += Math.cos(angle)
                satSum += hsb[1]
                count++
            }
        }

        if (count == 0) return image

        val avgHue = ((Math.atan2(sinSum, cosSum) / (2.0 * Math.PI)).toFloat() + 1f) % 1f
        val avgSat = (satSum / count).toFloat()
        val hueDelta = targetHue - avgHue
        val satScale = if (avgSat > 0.001f) targetSat / avgSat else 0f

        // --- Second pass: apply relative offset + detail modulation ---
        for (x in 0 until image.width) {
            for (y in 0 until image.height) {
                val argb = image.getRGB(x, y)
                val alpha = argb ushr 24 and 0xFF
                if (alpha == 0) {
                    tinted.setRGB(x, y, 0)
                    continue
                }

                if ((channelMask.getRGB(x, y) ushr 24 and 0xFF) == 0) {
                    tinted.setRGB(x, y, argb)
                    continue
                }

                val r = argb shr 16 and 0xFF
                val g = argb shr 8 and 0xFF
                val b = argb and 0xFF
                val hsb = Color.RGBtoHSB(r, g, b, null)

                val newHue = (hsb[0] + hueDelta + 1f) % 1f
                var newSat = (hsb[1] * satScale).coerceIn(0f, 1f)
                var newBri = hsb[2]

                // Sample detail map luminance
                val detailArgb = detailMap.getRGB(
                    x.coerceIn(0, detailMap.width - 1),
                    y.coerceIn(0, detailMap.height - 1)
                )
                val dR = detailArgb shr 16 and 0xFF
                val dG = detailArgb shr 8 and 0xFF
                val dB = detailArgb and 0xFF
                val detailVal = (0.299f * dR + 0.587f * dG + 0.114f * dB) / 128f

                when (detailMode) {
                    DetailMode.ADD -> {
                        // Modulate on top of the remapped values (128 = neutral)
                        newSat = (newSat * detailVal).coerceIn(0f, 1f)
                        newBri = (newBri * detailVal).coerceIn(0f, 1f)
                    }
                    DetailMode.REPLACE -> {
                        // Replace: detail luminance becomes the absolute S and B
                        // (128 → 1.0, 0 → 0.0, 255 → ~2.0 clamped)
                        val absolute = (detailVal * 0.5f).coerceIn(0f, 1f)
                        newSat = (targetSat * absolute).coerceIn(0f, 1f)
                        newBri = absolute
                    }
                }

                val newRgb = Color.HSBtoRGB(newHue, newSat, newBri)
                tinted.setRGB(x, y, (alpha shl 24) or (newRgb and 0x00FFFFFF))
            }
        }

        return tinted
    }

    // ── Detail-only pass for unmasked pixels ──────────────────────────────────────

    /**
     * Apply the detail map to every non-transparent pixel that is NOT covered
     * by any of the [masks].  Masked pixels were already detail-modulated in
     * the per-channel rendering pass, so this handles the "rest" of the part.
     */
    private fun applyDetailToUnmasked(
        image: BufferedImage,
        detailMap: BufferedImage,
        detailMode: DetailMode,
        masks: List<BufferedImage>
    ): BufferedImage {
        val out = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        for (x in 0 until image.width) {
            for (y in 0 until image.height) {
                val argb = image.getRGB(x, y)
                val alpha = argb ushr 24 and 0xFF
                if (alpha == 0) { out.setRGB(x, y, 0); continue }

                // Check if this pixel is covered by any mask
                val inAnyMask = masks.any { m ->
                    (m.getRGB(x.coerceIn(0, m.width - 1), y.coerceIn(0, m.height - 1)) ushr 24 and 0xFF) > 0
                }
                if (inAnyMask) {
                    // Already processed in the per-channel pass
                    out.setRGB(x, y, argb)
                    continue
                }

                // Apply detail modulation to unmasked pixel
                val detailArgb = detailMap.getRGB(
                    x.coerceIn(0, detailMap.width - 1),
                    y.coerceIn(0, detailMap.height - 1)
                )
                val dR = detailArgb shr 16 and 0xFF
                val dG = detailArgb shr 8 and 0xFF
                val dB = detailArgb and 0xFF
                val detailVal = (0.299f * dR + 0.587f * dG + 0.114f * dB) / 128f

                val r = argb shr 16 and 0xFF
                val g = argb shr 8 and 0xFF
                val b = argb and 0xFF
                val hsb = Color.RGBtoHSB(r, g, b, null)

                var newSat = hsb[1]
                var newBri = hsb[2]
                when (detailMode) {
                    DetailMode.ADD -> {
                        newSat = (newSat * detailVal).coerceIn(0f, 1f)
                        newBri = (newBri * detailVal).coerceIn(0f, 1f)
                    }
                    DetailMode.REPLACE -> {
                        val absolute = (detailVal * 0.5f).coerceIn(0f, 1f)
                        newSat = (hsb[1] * absolute).coerceIn(0f, 1f) // scale original sat
                        newBri = absolute
                    }
                }

                val newRgb = Color.HSBtoRGB(hsb[0], newSat, newBri)
                out.setRGB(x, y, (alpha shl 24) or (newRgb and 0x00FFFFFF))
            }
        }
        return out
    }

    // ── Textured colour masking ──────────────────────────────────────────────────

    /**
     * Tint the image using a **blend map** for per-pixel sub-channel mixing
     * and an optional **detail map** for brightness/saturation modulation.
     *
     * @param detailMode controls how the detail map interacts with the
     *                   original art's B/S (see [DetailMode]).
     */
    private fun applyTexturedColorMask(
        image: BufferedImage,
        subColors: Map<Int, Color>,
        channelMask: BufferedImage,
        blendMap: BufferedImage,
        detailMap: BufferedImage?,
        detailMode: DetailMode = DetailMode.ADD
    ): BufferedImage {
        val tinted = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)

        data class SubHSB(val hue: Float, val sat: Float)
        val subHsb = subColors.mapValues { (_, c) ->
            val hsb = Color.RGBtoHSB(c.red, c.green, c.blue, null)
            SubHSB(hsb[0], hsb[1])
        }

        // ── First pass: compute average H and S of masked pixels in original art ──
        var sinSum = 0.0
        var cosSum = 0.0
        var satSum = 0.0
        var count = 0

        for (x in 0 until image.width) {
            for (y in 0 until image.height) {
                val argb = image.getRGB(x, y)
                if ((argb ushr 24 and 0xFF) == 0) continue
                if ((channelMask.getRGB(x, y) ushr 24 and 0xFF) == 0) continue
                val r = argb shr 16 and 0xFF
                val g = argb shr 8 and 0xFF
                val b = argb and 0xFF
                val hsb = Color.RGBtoHSB(r, g, b, null)
                val angle = hsb[0] * 2.0 * Math.PI
                sinSum += Math.sin(angle)
                cosSum += Math.cos(angle)
                satSum += hsb[1]
                count++
            }
        }

        if (count == 0) return image

        val avgHue = ((Math.atan2(sinSum, cosSum) / (2.0 * Math.PI)).toFloat() + 1f) % 1f
        val avgSat = (satSum / count).toFloat()

        // ── Second pass: per-pixel blend + detail ──
        for (x in 0 until image.width) {
            for (y in 0 until image.height) {
                val argb = image.getRGB(x, y)
                val alpha = argb ushr 24 and 0xFF
                if (alpha == 0) {
                    tinted.setRGB(x, y, 0)
                    continue
                }

                val inMask = (channelMask.getRGB(x, y) ushr 24 and 0xFF) > 0
                if (!inMask) {
                    tinted.setRGB(x, y, argb)
                    continue
                }

                // Read blend weights from blend map
                val blendArgb = blendMap.getRGB(
                    x.coerceIn(0, blendMap.width - 1),
                    y.coerceIn(0, blendMap.height - 1)
                )
                val wR = (blendArgb shr 16 and 0xFF).toFloat()
                val wG = (blendArgb shr 8 and 0xFF).toFloat()
                val wB = (blendArgb and 0xFF).toFloat()
                val totalW = wR + wG + wB

                // Compute per-pixel target hue (circular weighted mean) and saturation
                val perPixelHue: Float
                val perPixelSat: Float
                if (totalW < 1f) {
                    val fallback = subHsb.values.firstOrNull()
                    perPixelHue = fallback?.hue ?: avgHue
                    perPixelSat = fallback?.sat ?: avgSat
                } else {
                    val weights = mapOf(0 to wR / totalW, 1 to wG / totalW, 2 to wB / totalW)
                    var hSin = 0.0
                    var hCos = 0.0
                    var sMix = 0f
                    for ((subIdx, w) in weights) {
                        if (w <= 0f) continue
                        val sh = subHsb[subIdx] ?: continue
                        val a = sh.hue * 2.0 * Math.PI
                        hSin += Math.sin(a) * w
                        hCos += Math.cos(a) * w
                        sMix += sh.sat * w
                    }
                    perPixelHue = ((Math.atan2(hSin, hCos) / (2.0 * Math.PI)).toFloat() + 1f) % 1f
                    perPixelSat = sMix
                }

                val hueDelta = perPixelHue - avgHue
                val satScale = if (avgSat > 0.001f) perPixelSat / avgSat else 0f

                val oR = argb shr 16 and 0xFF
                val oG = argb shr 8 and 0xFF
                val oB = argb and 0xFF
                val hsb = Color.RGBtoHSB(oR, oG, oB, null)

                var newHue = (hsb[0] + hueDelta + 1f) % 1f
                var newSat = (hsb[1] * satScale).coerceIn(0f, 1f)
                var newBri = hsb[2]

                // Detail map application
                if (detailMap != null) {
                    val detailArgb = detailMap.getRGB(
                        x.coerceIn(0, detailMap.width - 1),
                        y.coerceIn(0, detailMap.height - 1)
                    )
                    val dR = detailArgb shr 16 and 0xFF
                    val dG = detailArgb shr 8 and 0xFF
                    val dB = detailArgb and 0xFF
                    val detailVal = (0.299f * dR + 0.587f * dG + 0.114f * dB) / 128f

                    when (detailMode) {
                        DetailMode.ADD -> {
                            // Modulate on top of remapped values (128 = neutral)
                            newSat = (newSat * detailVal).coerceIn(0f, 1f)
                            newBri = (newBri * detailVal).coerceIn(0f, 1f)
                        }
                        DetailMode.REPLACE -> {
                            // Replace: detail luminance becomes absolute S and B
                            val absolute = (detailVal * 0.5f).coerceIn(0f, 1f)
                            newSat = (perPixelSat * absolute).coerceIn(0f, 1f)
                            newBri = absolute
                        }
                    }
                }

                val newRgb = Color.HSBtoRGB(newHue, newSat, newBri)
                tinted.setRGB(x, y, (alpha shl 24) or (newRgb and 0x00FFFFFF))
            }
        }

        return tinted
    }
}
