package com.sneakymannequins.util

import java.awt.Color
import java.awt.image.BufferedImage
import kotlin.math.sqrt

/**
 * Face-only fallback blink detection (no ETF choice boxes). Pure AWT so it can be unit-tested
 * without Bukkit. [LayerManager.detectBlink] delegates path 3 here.
 *
 * Run tests: `./gradlew :SneakyMannequins-Plugin:test --tests com.sneakymannequins.util.BlinkEyeGeometryTest`
 *
 * Debug a PNG: `BLINK_TEST_IMAGE=/absolute/path/to/skin.png ./gradlew :SneakyMannequins-Plugin:test
 * --tests 'com.sneakymannequins.util.BlinkEyeGeometryTest.faceFallback_optionalEnvImage'`
 */
object BlinkEyeGeometry {

    data class FaceFallbackBlinkResult(
            val blinkStyle: Int,
            val blinkHeight: Int,
            val blinkEyeColumns: List<Int>,
            val primaryFaceY: Int
    )

    fun colorDistance(rgb1: Int, rgb2: Int): Double {
        val r1 = (rgb1 shr 16) and 0xFF
        val g1 = (rgb1 shr 8) and 0xFF
        val b1 = rgb1 and 0xFF
        val r2 = (rgb2 shr 16) and 0xFF
        val g2 = (rgb2 shr 8) and 0xFF
        val b2 = rgb2 and 0xFF
        return sqrt(
                ((r1 - r2) * (r1 - r2) + (g1 - g2) * (g1 - g2) + (b1 - b2) * (b1 - b2)).toDouble()
        )
    }

    fun rgbSaturation(rgb: Int): Float {
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        return Color.RGBtoHSB(r, g, b, null)[1]
    }

    /** Rec. 601 luma in 0–255; used to tell sclera from black/low-luma outline (saturation is 0 for both). */
    private fun rgbLuminance(rgb: Int): Float {
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        return 0.299f * r + 0.587f * g + 0.114f * b
    }

    /** Outer face columns 1,2,7,8: eye white is low-saturation and bright; dark pixels are not. */
    private val eyewhiteMinLuma = 175f

    fun dominantNoseSkinColor(image: BufferedImage): Int? {
        val colorCounts = mutableMapOf<Int, Int>()
        for (y in 12..15) {
            for (x in 11..12) {
                val c = image.getRGB(x, y)
                if ((c ushr 24) == 0) continue
                var found = false
                for (entry in colorCounts) {
                    if (colorDistance(c, entry.key) < 30.0) {
                        colorCounts[entry.key] = entry.value + 1
                        found = true
                        break
                    }
                }
                if (!found) {
                    colorCounts[c] = 1
                }
            }
        }
        return colorCounts.maxByOrNull { it.value }?.key
    }

    /**
     * Minimum chroma at inner eye columns (weaker fringe rows score lower than the main eye row).
     */
    fun innerEyeStrength(image: BufferedImage, y: Int, skinRef: Int): Double {
        val p3 = image.getRGB(10, y)
        val p6 = image.getRGB(13, y)
        if ((p3 ushr 24) == 0 || (p6 ushr 24) == 0) return 0.0
        return minOf(colorDistance(p3, skinRef), colorDistance(p6, skinRef))
    }

    /**
     * 1-based columns on the 8-wide front face whose pixels participate in blink replacement.
     * Columns 2,7 can be chromatic (iris) or eye white; 1,8 are only added when both read as **bright**
     * low-saturation sclera (not black hair/outline, which also has very low saturation).
     */
    fun detectEyeColumns(
            image: BufferedImage,
            faceY: Int,
            skinRef: Int,
            neutralSaturation: Double
    ): List<Int> {
        if (image.width < 16 || faceY < 0 || faceY >= image.height) return listOf(3, 6)
        val satThreshold =
                neutralSaturation.toFloat().coerceIn(0.05f, 0.5f)
        val chromaOuter = 38.0

        fun faceX(col1: Int) = 8 + col1 - 1

        fun isEyewhite(col: Int): Boolean {
            val c = image.getRGB(faceX(col), faceY)
            if ((c ushr 24) == 0) return false
            if (rgbSaturation(c) > satThreshold) return false
            if (rgbLuminance(c) < eyewhiteMinLuma) return false
            if (colorDistance(c, skinRef) < 28.0) return false
            return true
        }

        fun isOuterEyeColumn(col: Int): Boolean {
            val c = image.getRGB(faceX(col), faceY)
            if ((c ushr 24) == 0) return false
            if (isEyewhite(col)) return true
            return colorDistance(c, skinRef) >= chromaOuter
        }

        val cols = linkedSetOf(3, 6)
        if (isOuterEyeColumn(2) && isOuterEyeColumn(7)) {
            cols.add(2)
            cols.add(7)
        }
        if (2 in cols && 7 in cols && isEyewhite(1) && isEyewhite(8)) {
            cols.add(1)
            cols.add(8)
        }
        return cols.sorted()
    }

    /**
     * Contiguous eye band from chin upward. Face rows are 1–8 top→bottom (`y = 8 + row - 1`).
     * [blinkHeight] is 1-based row index: we take the **topmost** strong row in a sub-band. **Dark
     * eyebrows** often have *higher* inner-column chroma than the iris; we drop one row when the top
     * run row is much stronger than the row below, then apply the 88% floor within the rest of the
     * run. We also step down once when the row below is clearly stronger (brow weaker than eyes).
     */
    fun detectFaceFallbackBlink(
            image: BufferedImage,
            neutralSaturation: Double
    ): FaceFallbackBlinkResult? {
        if (image.width < 64 || image.height < 64) return null
        val bestSkinColor = dominantNoseSkinColor(image) ?: return null

        fun innerEyeBothChromatic(y: Int): Boolean {
            val p3 = image.getRGB(10, y)
            val p6 = image.getRGB(13, y)
            if ((p3 ushr 24) == 0 || (p6 ushr 24) == 0) return false
            return colorDistance(p3, bestSkinColor) > 42.0 &&
                    colorDistance(p6, bestSkinColor) > 42.0
        }

        val runRows = mutableListOf<Int>()
        for (y in 15 downTo 8) {
            var mismatchCount = 0
            for (x in listOf(9, 10, 13, 14)) {
                val c = image.getRGB(x, y)
                if ((c ushr 24) != 0 && colorDistance(c, bestSkinColor) > 60.0) {
                    mismatchCount++
                }
            }
            val rowIsEyes = mismatchCount >= 2 && innerEyeBothChromatic(y)
            if (rowIsEyes) {
                runRows.add(y)
            } else if (runRows.isNotEmpty()) {
                break
            }
        }

        if (runRows.isEmpty()) return null

        val strengths = runRows.associateWith { innerEyeStrength(image, it, bestSkinColor) }
        val maxStrengthAny = strengths.values.maxOrNull() ?: return null
        if (maxStrengthAny < 1.0) return null

        // Inner strength = chroma at columns 10 and 13 vs skin. **Eyebrows are often darker than
        // the iris**, so they beat the iris on this metric — then an 88%-of-max floor keeps only the
        // brow row in the candidate set. One step: if the top row of the run is much stronger than the
        // row directly below, treat it as brow and score the band from the lower row onward (base 5).
        var bandStartY = runRows.minOrNull()!!
        val rowBelowStart = bandStartY + 1
        if (rowBelowStart in runRows &&
                strengths.getValue(bandStartY) > strengths.getValue(rowBelowStart) * 1.10
        ) {
            bandStartY = rowBelowStart
        }

        val bandRows = runRows.filter { it >= bandStartY }
        val maxInBand = bandRows.maxOf { strengths.getValue(it) }
        if (maxInBand < 1.0) return null

        val strengthFloor = maxInBand * 0.88
        val candidates = bandRows.filter { strengths.getValue(it) >= strengthFloor }
        var primaryFaceY = candidates.minOrNull() ?: return null

        // Brow weaker than eyes but still within 88% of band max: prefer the row below when clearly
        // stronger (previous heuristic).
        val below = primaryFaceY + 1
        if (below in candidates &&
                strengths.getValue(below) > strengths.getValue(primaryFaceY) * 1.05
        ) {
            primaryFaceY = below
        }

        val eyePixelCount = runRows.size
        val fallbackStyle =
                when {
                    eyePixelCount <= 1 -> 3
                    eyePixelCount in 2..3 -> 4
                    else -> 5
                }
        val h = primaryFaceY - 8 + 1
        val cols = detectEyeColumns(image, primaryFaceY, bestSkinColor, neutralSaturation)
        return FaceFallbackBlinkResult(
                blinkStyle = fallbackStyle,
                blinkHeight = h,
                blinkEyeColumns = cols,
                primaryFaceY = primaryFaceY
        )
    }
}
