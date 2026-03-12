package com.sneakymannequins.util

import com.sneakymannequins.model.LayerDefinition
import com.sneakymannequins.model.LayerOption
import com.sneakymannequins.model.SkinSelection
import com.sneakymannequins.model.TextureDefinition
import java.awt.Color
import java.awt.image.BufferedImage

private const val SKIN_SIZE = 64

/** Combines selected layers into a final 64x64 ARGB skin image. */
object SkinComposer {

    /**
     * @param optionResolver optional function that resolves the current (fresh)
     * ```
     *        [LayerOption] for a given layer ID and selection option ID. If
     *        provided, mask paths are read from the resolved option instead of
     *        the potentially stale one stored in the selection.
     * @param textureResolver
     * ```
     * optional function that resolves the selected
     * ```
     *        [TextureDefinition] for a given layer ID.  Returns null when the
     *        layer uses "Default" (flat colour, no texture).
     * ```
     */
    fun compose(
            layers: List<LayerDefinition>,
            selection: SkinSelection,
            useSlimModel: Boolean,
            optionResolver: ((layerId: String, optionId: String) -> LayerOption?)? = null,
            textureResolver: ((layerId: String) -> TextureDefinition?)? = null,
            brightnessInfluenceResolver: ((layerId: String, option: LayerOption) -> Float)? = null,
            saturationInfluenceResolver: ((layerId: String, option: LayerOption) -> Float)? = null
    ): BufferedImage {
        val output = BufferedImage(SKIN_SIZE, SKIN_SIZE, BufferedImage.TYPE_INT_ARGB)
        val graphics = output.createGraphics()

        layers.forEach { layer ->
            val sel = selection.selections[layer.id] ?: return@forEach
            val selOption = sel.option ?: return@forEach
            // Resolve fresh option (with up-to-date masks) if a resolver is provided
            val chosen = optionResolver?.invoke(layer.id, selOption.id) ?: selOption
            val sourceImage =
                    when {
                        useSlimModel && chosen.imageSlim != null -> chosen.imageSlim
                        else -> chosen.imageDefault ?: chosen.imageSlim
                    }
                            ?: return@forEach

            // Apply each channel's color independently, then composite.
            // Skip channels whose mask file is missing — tinting without a
            // mask would recolour every pixel instead of just the channel.
            var source = sourceImage
            if (layer.allowColorMask) {
                val brightnessInfluence =
                        brightnessInfluenceResolver?.invoke(layer.id, chosen) ?: 0.3f
                val saturationInfluence =
                        saturationInfluenceResolver?.invoke(layer.id, chosen) ?: 1.0f

                // Resolve the active texture for this layer (null = "Default" / flat)
                val texDef = textureResolver?.invoke(layer.id)

                // Collect all channels that need colouring (flat or textured)
                val flatChannels = sel.channelColors
                val texturedChannels = sel.texturedColors

                // Load all mask images upfront so we can identify unmasked pixels later
                val maskImages =
                        chosen.masks
                                .mapNotNull { (idx, path) ->
                                    val img =
                                            try {
                                                javax.imageio.ImageIO.read(path.toFile())
                                            } catch (_: Exception) {
                                                null
                                            }
                                    if (img != null) idx to img else null
                                }
                                .toMap()

                val allChannels = (flatChannels.keys + texturedChannels.keys).sorted()
                for (channelIdx in allChannels) {
                    val maskImage = maskImages[channelIdx] ?: continue

                    val blendImage = texDef?.blendMapImage
                    val subColors = texturedChannels[channelIdx]

                    if (blendImage != null && subColors != null && subColors.isNotEmpty()) {
                        source =
                                applyTexturedColorMask(
                                        source,
                                        subColors,
                                        maskImage,
                                        blendImage,
                                        brightnessInfluence,
                                        saturationInfluence
                                )
                        continue
                    }

                    val flatColor = flatChannels[channelIdx] ?: continue
                    source =
                            applyColorMask(
                                    source,
                                    flatColor,
                                    maskImage,
                                    brightnessInfluence,
                                    saturationInfluence
                            )
                }

                // AO/roughness/alpha maps apply to ALL pixels as a final pass (after tinting)
                val aoMap = texDef?.aoMapImage
                val roughnessMap = texDef?.roughnessMapImage
                val alphaMap = texDef?.alphaMapImage
                if (aoMap != null || roughnessMap != null || alphaMap != null) {
                    source = applyMaps(source, aoMap, roughnessMap, alphaMap)
                }
            }
            graphics.drawImage(source, 0, 0, null)
        }

        graphics.dispose()
        forceInnerLayerOpaque(output)
        return output
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private fun forceInnerLayerOpaque(image: BufferedImage) {
        SkinUv.forEachInnerBasePixel { x, y ->
            val argb = image.getRGB(x, y)
            val alpha = (argb ushr 24) and 0xFF
            if (alpha > 0) {
                image.setRGB(x, y, (0xFF shl 24) or (argb and 0x00FFFFFF))
            }
        }
    }

    /** Sample a grayscale map's luminance at (x, y), returning a multiplier centred at 1.0. */
    private fun sampleMultiplier(map: BufferedImage, x: Int, y: Int): Float {
        val argb = map.getRGB(x.coerceIn(0, map.width - 1), y.coerceIn(0, map.height - 1))
        val r = argb shr 16 and 0xFF
        val g = argb shr 8 and 0xFF
        val b = argb and 0xFF
        return (0.299f * r + 0.587f * g + 0.114f * b) / 128f
    }

    // ── Flat colour masking (no texture) ────────────────────────────────────────

    /** Tint the image using relative HSB remapping (flat colour, no texture). */
    private fun applyColorMask(
            image: BufferedImage,
            mask: Color,
            channelMask: BufferedImage?,
            brightnessInfluence: Float = 0.3f,
            saturationInfluence: Float = 1.0f
    ): BufferedImage {
        val tinted = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        val maskHsb = Color.RGBtoHSB(mask.red, mask.green, mask.blue, null)
        val targetHue = maskHsb[0]
        val targetSat = maskHsb[1]
        val targetBri = maskHsb[2]

        // --- First pass: compute average H, S and B of masked pixels ---
        var sinSum = 0.0
        var cosSum = 0.0
        var satSum = 0.0
        var briSum = 0.0
        var count = 0

        for (x in 0 until image.width) {
            for (y in 0 until image.height) {
                val argb = image.getRGB(x, y)
                if ((argb ushr 24 and 0xFF) == 0) continue
                val inMask =
                        if (channelMask != null) {
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
                briSum += hsb[2]
                count++
            }
        }

        if (count == 0) return image

        val avgHue = ((Math.atan2(sinSum, cosSum) / (2.0 * Math.PI)).toFloat() + 1f) % 1f
        val avgSat = (satSum / count).toFloat()
        val avgBri = (briSum / count).toFloat()
        val hueDelta = targetHue - avgHue

        // --- Second pass: apply relative offset to each masked pixel ---
        for (x in 0 until image.width) {
            for (y in 0 until image.height) {
                val argb = image.getRGB(x, y)
                val alpha = argb ushr 24 and 0xFF
                if (alpha == 0) {
                    tinted.setRGB(x, y, 0)
                    continue
                }

                val inMask =
                        if (channelMask != null) {
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
                val newSat =
                        if (avgSat > 0.0001f) {
                            val satScale = targetSat / avgSat
                            (hsb[1] * (1f + saturationInfluence * (satScale - 1f))).coerceIn(0f, 1f)
                        } else {
                            (hsb[1] + (targetSat - avgSat) * saturationInfluence).coerceIn(0f, 1f)
                        }
                val newBri =
                        if (avgBri > 0.0001f) {
                            val briScale = targetBri / avgBri
                            (hsb[2] * (1f + brightnessInfluence * (briScale - 1f))).coerceIn(0f, 1f)
                        } else {
                            (hsb[2] + (targetBri - avgBri) * brightnessInfluence).coerceIn(0f, 1f)
                        }
                val newRgb = Color.HSBtoRGB(newHue, newSat, newBri)
                tinted.setRGB(x, y, (alpha shl 24) or (newRgb and 0x00FFFFFF))
            }
        }

        return tinted
    }

    // ── AO/roughness pass (all pixels) ─────────────────────────────────────────

    /**
     * Apply AO, roughness, and alpha maps to every non-transparent pixel in the image. This is
     * called as a final pass AFTER per-channel colour tinting, so it modulates both tinted and
     * untinted pixels uniformly.
     */
    private fun applyMaps(
            image: BufferedImage,
            aoMap: BufferedImage?,
            roughnessMap: BufferedImage?,
            alphaMap: BufferedImage? = null
    ): BufferedImage {
        val out = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        for (x in 0 until image.width) {
            for (y in 0 until image.height) {
                val argb = image.getRGB(x, y)
                val alpha = argb ushr 24 and 0xFF
                if (alpha == 0) {
                    out.setRGB(x, y, 0)
                    continue
                }

                val r = argb shr 16 and 0xFF
                val g = argb shr 8 and 0xFF
                val b = argb and 0xFF
                val hsb = Color.RGBtoHSB(r, g, b, null)

                var newBri = hsb[2]
                var newSat = hsb[1]

                if (aoMap != null) {
                    newBri = (newBri * sampleMultiplier(aoMap, x, y)).coerceIn(0f, 1f)
                }
                if (roughnessMap != null) {
                    newSat = (newSat * sampleMultiplier(roughnessMap, x, y)).coerceIn(0f, 1f)
                }

                val newAlpha =
                        if (alphaMap != null) {
                            (alpha * sampleMultiplier(alphaMap, x, y)).toInt().coerceIn(0, 255)
                        } else alpha

                val newRgb = Color.HSBtoRGB(hsb[0], newSat, newBri)
                out.setRGB(x, y, (newAlpha shl 24) or (newRgb and 0x00FFFFFF))
            }
        }
        return out
    }

    // ── Textured colour masking ──────────────────────────────────────────────────

    /**
     * Tint the image using a **blend map** for per-pixel sub-channel mixing and optional **AO /
     * roughness maps** for brightness/saturation modulation.
     */
    private fun applyTexturedColorMask(
            image: BufferedImage,
            subColors: Map<Int, Color>,
            channelMask: BufferedImage,
            blendMap: BufferedImage,
            brightnessInfluence: Float = 0.3f,
            saturationInfluence: Float = 1.0f
    ): BufferedImage {
        val tinted = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)

        data class SubHSB(val hue: Float, val sat: Float, val bri: Float)
        val subHsb =
                subColors.mapValues { (_, c) ->
                    val hsb = Color.RGBtoHSB(c.red, c.green, c.blue, null)
                    SubHSB(hsb[0], hsb[1], hsb[2])
                }

        // ── First pass: compute average H, S and B of masked pixels in original art ──
        var sinSum = 0.0
        var cosSum = 0.0
        var satSum = 0.0
        var briSum = 0.0
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
                briSum += hsb[2]
                count++
            }
        }

        if (count == 0) return image

        val avgHue = ((Math.atan2(sinSum, cosSum) / (2.0 * Math.PI)).toFloat() + 1f) % 1f
        val avgSat = (satSum / count).toFloat()
        val avgBri = (briSum / count).toFloat()

        // ── Second pass: per-pixel blend ──
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
                val blendArgb =
                        blendMap.getRGB(
                                x.coerceIn(0, blendMap.width - 1),
                                y.coerceIn(0, blendMap.height - 1)
                        )
                val wR = (blendArgb shr 16 and 0xFF).toFloat()
                val wG = (blendArgb shr 8 and 0xFF).toFloat()
                val wB = (blendArgb and 0xFF).toFloat()
                val totalW = wR + wG + wB

                // Compute per-pixel target hue (circular weighted mean) and saturation.
                // Only sub-channels that have an assigned colour contribute; if none do,
                // leave the pixel as original art.
                val perPixelHue: Float
                val perPixelSat: Float
                val perPixelBri: Float
                if (totalW < 1f) {
                    val fallback = subHsb.values.firstOrNull()
                    if (fallback == null) {
                        tinted.setRGB(x, y, argb)
                        continue
                    }
                    perPixelHue = fallback.hue
                    perPixelSat = fallback.sat
                    perPixelBri = fallback.bri
                } else {
                    val weights = mapOf(0 to wR / totalW, 1 to wG / totalW, 2 to wB / totalW)
                    var hSin = 0.0
                    var hCos = 0.0
                    var sMix = 0f
                    var bMix = 0f
                    var effectiveW = 0f
                    for ((subIdx, w) in weights) {
                        if (w <= 0f) continue
                        val sh = subHsb[subIdx] ?: continue
                        effectiveW += w
                        val a = sh.hue * 2.0 * Math.PI
                        hSin += Math.sin(a) * w
                        hCos += Math.cos(a) * w
                        sMix += sh.sat * w
                        bMix += sh.bri * w
                    }
                    if (effectiveW <= 0f) {
                        tinted.setRGB(x, y, argb)
                        continue
                    }
                    perPixelHue = ((Math.atan2(hSin, hCos) / (2.0 * Math.PI)).toFloat() + 1f) % 1f
                    perPixelSat = sMix / effectiveW
                    perPixelBri = bMix / effectiveW
                }

                val hueDelta = perPixelHue - avgHue

                val oR = argb shr 16 and 0xFF
                val oG = argb shr 8 and 0xFF
                val oB = argb and 0xFF
                val hsb = Color.RGBtoHSB(oR, oG, oB, null)

                val newHue = (hsb[0] + hueDelta + 1f) % 1f
                val newSat =
                        if (avgSat > 0.0001f) {
                            val satScale = perPixelSat / avgSat
                            (hsb[1] * (1f + saturationInfluence * (satScale - 1f))).coerceIn(0f, 1f)
                        } else {
                            (hsb[1] + (perPixelSat - avgSat) * saturationInfluence).coerceIn(0f, 1f)
                        }
                val newBri =
                        if (avgBri > 0.0001f) {
                            val briScale = perPixelBri / avgBri
                            (hsb[2] * (1f + brightnessInfluence * (briScale - 1f))).coerceIn(0f, 1f)
                        } else {
                            (hsb[2] + (perPixelBri - avgBri) * brightnessInfluence).coerceIn(0f, 1f)
                        }

                val newRgb = Color.HSBtoRGB(newHue, newSat, newBri)
                tinted.setRGB(x, y, (alpha shl 24) or (newRgb and 0x00FFFFFF))
            }
        }

        return tinted
    }
}
