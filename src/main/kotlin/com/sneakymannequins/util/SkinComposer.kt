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
            saturationInfluenceResolver: ((layerId: String, option: LayerOption) -> Float)? = null,
            baseImage: BufferedImage? = null,
            etfEnabled: Boolean = false,
            defaultJacketStyle: Int = 5,
            showOverlay: Boolean = true
    ): BufferedImage {
        val output =
                if (baseImage != null) {
                    val copy = BufferedImage(SKIN_SIZE, SKIN_SIZE, BufferedImage.TYPE_INT_ARGB)
                    val g = copy.createGraphics()
                    g.drawImage(baseImage, 0, 0, null)
                    g.dispose()
                    copy
                } else {
                    BufferedImage(SKIN_SIZE, SKIN_SIZE, BufferedImage.TYPE_INT_ARGB)
                }
        val graphics = output.createGraphics()
        var maxDressLength = 0
        var anyDress = false
        var maxBlinkStyle = 0
        var maxBlinkHeight = 0

        layers.forEach { layer ->
            val sel = selection.selections[layer.id] ?: return@forEach
            val selOption = sel.option ?: return@forEach
            // Resolve fresh option (with up-to-date masks) if a resolver is provided
            val chosen = optionResolver?.invoke(layer.id, selOption.id) ?: selOption
            var sourceImage = if (useSlimModel) chosen.imageSlim else chosen.imageDefault
            if (sourceImage == null) return@forEach

            // If this is a dress, we need to perform the ETF shift-and-swap:
            if (chosen.isDress) {
                anyDress = true
                if (chosen.dressLength > maxDressLength) maxDressLength = chosen.dressLength
                shiftOutputOuterToInner(output, chosen.dressLength)
                sourceImage = convertLegsToDress(sourceImage)
            }

            if (chosen.isBlink) {
                maxBlinkStyle = chosen.blinkStyle
                maxBlinkHeight = chosen.blinkHeight
            }

            // Apply each channel's color independently, then composite.
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

                // Load all mask images upfront.
                // We trust that variant masks are prepopulated or fallback to master appropriately.
                val maskMap =
                        if (useSlimModel) chosen.masksSlim.ifEmpty { chosen.masks }
                        else chosen.masksDefault.ifEmpty { chosen.masks }

                val maskImages =
                        maskMap
                                .mapNotNull { (idx, path) ->
                                    try {
                                        val img = javax.imageio.ImageIO.read(path.toFile())
                                        if (img != null) idx to img else null
                                    } catch (_: Exception) {
                                        null
                                    }
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
                                        source!!,
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
                                    source!!,
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
                    source = applyMaps(source!!, aoMap, roughnessMap, alphaMap)
                }
            }

            // Apply punch-through: if this layer has opaque inner skin pixels,
            // wipe the corresponding outer skin pixels in the composite so far.
            punchThroughOuter(source!!, output)

            graphics.drawImage(source, 0, 0, null)
        }

        graphics.dispose()
        forceInnerLayerOpaque(output)

        if ((anyDress || maxBlinkStyle != 0) && etfEnabled) {
            encodeEtf(output, if (anyDress) defaultJacketStyle else 0, maxDressLength.coerceIn(1, 8), maxBlinkStyle, maxBlinkHeight)
        }

        if (!showOverlay) {
            removeOverlay(output)
        }

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

    private fun punchThroughOuter(layerImage: BufferedImage, outputComposite: BufferedImage) {
        for (x in 0 until SKIN_SIZE) {
            for (y in 0 until SKIN_SIZE) {
                val argb = layerImage.getRGB(x, y)
                val alpha = (argb ushr 24) and 0xFF
                if (alpha > 0) {
                    // Check if this is an inner layer pixel
                    val outerCoord = SkinUv.getOuterCorresponding(x, y)
                    if (outerCoord != null) {
                        // This IS an inner layer pixel. Wipe the corresponding outer pixel in the
                        // composite.
                        outputComposite.setRGB(outerCoord.first, outerCoord.second, 0)
                    }
                }
            }
        }
    }

    private fun shiftOutputOuterToInner(output: BufferedImage, dressLength: Int) {
        // Outer leg regions: Right (0, 32), Left (0, 48)
        val legOuterRects = listOf(SkinUv.Rect(0, 32, 16, 16), SkinUv.Rect(0, 48, 16, 16))
        for (r in legOuterRects) {
            val faceTopY = if (r.y == 32) 36 else 52 // Front/Side/Back faces top Y
            for (y in r.y until r.y + r.h) {
                // Only shift if within the rows affected by dress length
                if (y < faceTopY || y >= faceTopY + dressLength) continue

                for (x in r.x until r.x + r.w) {
                    val outerArgb = output.getRGB(x, y)
                    if ((outerArgb ushr 24) == 0) continue

                    val inner = SkinUv.getInnerCorresponding(x, y) ?: continue
                    val innerArgb = output.getRGB(inner.first, inner.second)

                    // Shift outer to inner, blending it ON TOP of what's already there
                    val blended = blend(innerArgb, outerArgb)
                    output.setRGB(inner.first, inner.second, blended)

                    // Clear the outer pixel to make room for the dress
                    output.setRGB(x, y, 0)
                }
            }
        }
    }

    private fun blend(bottom: Int, top: Int): Int {
        val topA = (top ushr 24) and 0xFF
        if (topA == 0) return bottom
        if (topA == 255) return top

        val bottomA = (bottom ushr 24) and 0xFF
        if (bottomA == 0) return top

        // Simple ARGB blending
        val a1 = topA / 255.0
        val a2 = (bottomA / 255.0) * (1.0 - a1)
        val r = (((top shr 16) and 0xFF) * a1 + ((bottom shr 16) and 0xFF) * a2) / (a1 + a2)
        val g = (((top shr 8) and 0xFF) * a1 + ((bottom shr 8) and 0xFF) * a2) / (a1 + a2)
        val b = (((top) and 0xFF) * a1 + ((bottom) and 0xFF) * a2) / (a1 + a2)
        val aOut = (a1 + a2) * 255.0

        return (aOut.toInt() shl 24) or (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()
    }

    private fun convertLegsToDress(image: BufferedImage): BufferedImage {
        val out = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        g.drawImage(image, 0, 0, null)
        g.dispose()

        // Leg inner regions:
        // Right Leg Base: (0, 16, 16, 16)
        // Left Leg Base: (16, 48, 16, 16)
        val legInnerRects = listOf(SkinUv.Rect(0, 16, 16, 16), SkinUv.Rect(16, 48, 16, 16))
        for (r in legInnerRects) {
            for (x in r.x until r.x + r.w) {
                for (y in r.y until r.y + r.h) {
                    val innerArgb = out.getRGB(x, y)
                    if ((innerArgb ushr 24) == 0) continue

                    val outer = SkinUv.getOuterCorresponding(x, y) ?: continue
                    val outerArgb = out.getRGB(outer.first, outer.second)

                    // Put inner UNDER outer. If outer is transparent, inner takes over.
                    if ((outerArgb ushr 24) == 0) {
                        out.setRGB(outer.first, outer.second, innerArgb)
                    }
                    // Clear the inner pixel
                    out.setRGB(x, y, 0)
                }
            }
        }
        return out
    }

    private fun removeOverlay(image: BufferedImage) {
        val g = image.createGraphics()
        g.composite = java.awt.AlphaComposite.Clear
        for (r in SkinUv.OUTER_OVERLAY_RECTS) {
            g.fillRect(r.x, r.y, r.w, r.h)
        }
        g.dispose()
    }

    private fun encodeEtf(image: BufferedImage, style: Int, length: Int, blinkStyle: Int, blinkHeight: Int) {
        // 1. Check if assets already provided blink pixels (Column 12-19, Rows 16-19)
        // We do this BEFORE the Power Wash so we don't delete them.
        var assetsHadBlink = false
        val savedBlink = BufferedImage(8, 4, BufferedImage.TYPE_INT_ARGB)
        for (y in 16..19) {
            for (x in 12..19) {
                val rgb = image.getRGB(x, y)
                if (rgb != 0) assetsHadBlink = true
                savedBlink.setRGB(x - 12, y - 16, rgb)
            }
        }

        // 2. Power Wash (Clear ETF reserved zones of any junk)
        for (y in 16..19) {
            for (x in 0..3) image.setRGB(x, y, 0)   // Handshake
            for (x in 12..19) image.setRGB(x, y, 0)  // Blink
            for (x in 52..53) image.setRGB(x, y, 0)  // Choice Boxes
        }
        for (y in 48..51) {
            for (x in 60..63) image.setRGB(x, y, 0)  // Palette Area
        }

        // 3. Restore or Auto-Generate Blink Pixels
        if (assetsHadBlink) {
            val g = image.createGraphics()
            g.drawImage(savedBlink, 12, 16, null)
            g.dispose()
        } else if (blinkStyle > 0 && blinkHeight >= 0) {
            autoGenerateBlinkPixels(image, blinkStyle, blinkHeight)
        }

        // 4. Handshake Marker (Exact bit-match to ETFPlayerTexture.java)
        image.setRGB(0, 16, Color(127, 0, 0).rgb)     // Dark Red (-16777089)
        image.setRGB(1, 16, Color(255, 0, 0).rgb)     // Red (-16776961)
        image.setRGB(2, 16, Color(0, 255, 0).rgb)     // Green (-16711936)
        image.setRGB(3, 16, Color(0, 127, 0).rgb)     // Dark Green (-16744704)
        image.setRGB(0, 17, Color(255, 0, 0).rgb)     // Red (-16776961)
        image.setRGB(1, 17, Color(0, 0, 0).rgb)
        image.setRGB(2, 17, Color(0, 0, 0).rgb)
        image.setRGB(3, 17, Color(0, 255, 0).rgb)
        image.setRGB(0, 18, Color(0, 0, 255).rgb)     // Blue (-65536)
        image.setRGB(1, 18, Color(0, 0, 0).rgb)
        image.setRGB(2, 18, Color(0, 0, 0).rgb)
        image.setRGB(3, 18, Color(255, 255, 255).rgb) // White (-1)
        image.setRGB(0, 19, Color(0, 0, 127).rgb)     // Dark Blue (-8454144)
        image.setRGB(1, 19, Color(0, 0, 255).rgb)     // Blue (-65536)
        image.setRGB(2, 19, Color(255, 255, 255).rgb) // White (-1)
        image.setRGB(3, 19, Color(127, 127, 127).rgb) // Gray

        // 5. Choice Boxes (Column 52)
        // Default blink style to 4 (Green) if not specified, matching working reference.
        val finalBlinkStyle = if (blinkStyle > 0) blinkStyle else 4
        image.setRGB(SkinUv.ETF_CHOICE_BLINK_STYLE_X, SkinUv.ETF_CHOICE_BLINK_STYLE_Y, SkinUv.ETF_COLORS[finalBlinkStyle - 1].rgb)

        if (style > 0) {
            image.setRGB(SkinUv.ETF_CHOICE_STYLE_BOX_X, SkinUv.ETF_CHOICE_STYLE_BOX_Y, SkinUv.ETF_COLORS[style - 1].rgb)
        }
        if (length > 0) {
            image.setRGB(SkinUv.ETF_CHOICE_LENGTH_BOX_X, SkinUv.ETF_CHOICE_LENGTH_BOX_Y, SkinUv.ETF_COLORS[length - 1].rgb)
        }
        if (blinkHeight >= 0) {
            image.setRGB(SkinUv.ETF_CHOICE_BLINK_HEIGHT_X, SkinUv.ETF_CHOICE_BLINK_HEIGHT_Y, SkinUv.ETF_COLORS[blinkHeight - 1].rgb)
        }
    }

    private fun autoGenerateBlinkPixels(image: BufferedImage, style: Int, height: Int) {
        // Only generate if the target area is empty (all transparent)
        val checkY = 16
        var hasPixels = false
        for (x in 12..19) {
            if ((image.getRGB(x, checkY) ushr 24) != 0) {
                hasPixels = true
                break
            }
        }
        if (hasPixels) return

        // 1. Get the eye row from the Front face of the head (Y=8+height, X=8..15)
        val headEyeY = 8 + height
        if (headEyeY >= 16) return

        val eyeRow = IntArray(8)
        val colors = mutableListOf<Int>()
        for (i in 0..7) {
            val rgb = image.getRGB(8 + i, headEyeY)
            eyeRow[i] = rgb
            if ((rgb ushr 24) != 0) colors.add(rgb and 0xFFFFFF)
        }
        if (colors.isEmpty()) return

        // 2. Simple "close the eyes" heuristic: pick brightest pixel as skin color
        val skinRgb = colors.maxByOrNull {
            val r = (it shr 16) and 0xFF
            val g = (it shr 8) and 0xFF
            val b = it and 0xFF
            r + g + b 
        } ?: colors[0]

        // Create a "closed eye" row by replacing dark pixels with skin color
        val closedRow = IntArray(8)
        for (i in 0..7) {
            val rgb = eyeRow[i]
            val r = (rgb shr 16) and 0xFF
            val g = (rgb shr 8) and 0xFF
            val b = (rgb) and 0xFF
            val brightness = (r + g + b) / 765.0
            if (brightness < 0.4) {
                closedRow[i] = (0xFF shl 24) or skinRgb
            } else {
                closedRow[i] = rgb
            }
        }

        // 3. Write to 12..19, 16..19
        val numRows = if (style == 5) 4 else if (style == 4) 2 else 1
        for (y in 16 until 16 + numRows) {
            for (i in 0..7) {
                image.setRGB(12 + i, y, closedRow[i])
            }
        }
    }
}
