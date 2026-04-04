package com.sneakymannequins.util

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BlinkEyeGeometryTest {

    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    /**
     * Fringe row above eyes fails inner-eye chroma (too close to skin); two strong eye rows below.
     * Expect topmost **strong** row = y=13 → blinkHeight 6.
     */
    @Test
    fun faceFallback_excludesFringeRowWithWeakInnerChroma() {
        val img = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB)
        val skin = argb(212, 165, 116)
        val fringeInner = argb(180, 140, 100) // closer to skin → innerEyeBothChromatic fails at >42
        val eye = argb(80, 140, 220)

        for (y in 8..15) {
            for (x in 8..15) {
                img.setRGB(x, y, skin)
            }
        }
        // y=12: only 2 probe mismatches but inner columns NOT both >42 from dominant skin
        for (x in listOf(9, 10, 13, 14)) {
            img.setRGB(x, 12, if (x == 9 || x == 14) skin else fringeInner)
        }
        img.setRGB(10, 12, fringeInner)
        img.setRGB(13, 12, fringeInner)

        for (x in listOf(9, 10, 13, 14)) {
            img.setRGB(x, 13, eye)
            img.setRGB(x, 14, eye)
        }

        val r = BlinkEyeGeometry.detectFaceFallbackBlink(img, 0.15)
        assertNotNull(r)
        val out = r!!
        assertEquals(6, out.blinkHeight, "blinkHeight should be face row 6 (y=13)")
        assertEquals(13, out.primaryFaceY)
        assertEquals(4, out.blinkStyle)
    }

    /**
     * Dark brows at inner columns beat the iris on chroma vs skin (e.g. base 5). Without trimming,
     * only the brow row passes an 88%-of-max floor. Top row >> row below → skip one row, then floor
     * from the iris band → [blinkHeight] 6 (y=13).
     */
    @Test
    fun faceFallback_skipsBrowWhenInnerChromaHigherThanIris() {
        val img = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB)
        val skin = argb(236, 200, 176)
        val browInner = argb(39, 39, 39)
        val iris = argb(73, 138, 142)
        val lowerEye = argb(230, 245, 246)
        val hair = argb(124, 78, 64)
        val black = argb(0, 0, 0)

        for (y in 8..15) {
            for (x in 8..15) {
                img.setRGB(x, y, skin)
            }
        }
        for (x in 8..15) {
            for (y in 8..10) {
                img.setRGB(x, y, hair)
            }
        }
        // y=11 skin bridge (inner chroma fails → breaks hair from eye run)
        for (x in listOf(9, 10, 13, 14)) {
            img.setRGB(x, 12, if (x == 10 || x == 13) browInner else black)
        }
        for (x in listOf(9, 10, 13, 14)) {
            img.setRGB(x, 13, when (x) {
                9, 14 -> argb(246, 230, 230)
                10, 13 -> iris
                else -> skin
            })
        }
        for (x in listOf(9, 10, 13, 14)) {
            img.setRGB(x, 14, when (x) {
                9, 14 -> argb(255, 255, 255)
                10, 13 -> lowerEye
                else -> skin
            })
        }

        val r = BlinkEyeGeometry.detectFaceFallbackBlink(img, 0.15)
        assertNotNull(r)
        val out = r!!
        assertEquals(6, out.blinkHeight)
        assertEquals(13, out.primaryFaceY)
        assertEquals(4, out.blinkStyle)
    }

    /**
     * Eyebrow row (y=12, face row 5) can pass probes + inner chroma and sit within 88% of max strength.
     * When the row below is clearly stronger (>5%), we use that row for [blinkHeight] (row 6, y=13).
     */
    @Test
    fun faceFallback_promotesPastEyebrowWhenRowBelowStronger() {
        val img = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB)
        val skin = argb(212, 165, 116)
        // Weaker inner-eye chroma than true eyes, but still >42 from skin at columns 10 and 13.
        val browInner = argb(130, 155, 205)
        val eye = argb(45, 110, 235)

        for (y in 8..15) {
            for (x in 8..15) {
                img.setRGB(x, y, skin)
            }
        }
        for (x in listOf(9, 10, 13, 14)) {
            img.setRGB(x, 12, if (x == 10 || x == 13) browInner else eye)
        }
        for (x in listOf(9, 10, 13, 14)) {
            img.setRGB(x, 13, eye)
            img.setRGB(x, 14, eye)
        }

        val r = BlinkEyeGeometry.detectFaceFallbackBlink(img, 0.15)
        assertNotNull(r)
        val out = r!!
        assertEquals(6, out.blinkHeight, "expect first true eye row, not eyebrow row 5")
        assertEquals(13, out.primaryFaceY)
        assertEquals(4, out.blinkStyle)
    }

    @Test
    fun faceFallback_singleRow_classicPupils() {
        val img = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB)
        val skin = argb(220, 180, 140)
        val dark = argb(40, 30, 25)
        for (y in 8..15) {
            for (x in 8..15) {
                img.setRGB(x, y, skin)
            }
        }
        val yEye = 12
        for (x in listOf(9, 10, 13, 14)) {
            img.setRGB(x, yEye, dark)
        }

        val r = BlinkEyeGeometry.detectFaceFallbackBlink(img, 0.15)
        assertNotNull(r)
        val out = r!!
        assertEquals(5, out.blinkHeight)
        assertEquals(12, out.primaryFaceY)
        assertEquals(3, out.blinkStyle)
        assertTrue(out.blinkEyeColumns.containsAll(listOf(3, 6)))
    }

    /**
     * Columns 1 and 8 are often black hair/outline: low saturation like sclera but not bright.
     * They must not count as eye white (base 5–style layout).
     */
    @Test
    fun detectEyeColumns_doesNotTreatBlackOutlineAsEyewhite() {
        val img = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB)
        val skin = argb(236, 200, 176)
        val sclera = argb(246, 230, 230)
        val iris = argb(73, 138, 142)
        val black = argb(0, 0, 0)
        for (y in 8..15) {
            for (x in 8..15) {
                img.setRGB(x, y, skin)
            }
        }
        val y = 13
        img.setRGB(8, y, black)
        img.setRGB(9, y, sclera)
        img.setRGB(10, y, iris)
        img.setRGB(11, y, skin)
        img.setRGB(12, y, skin)
        img.setRGB(13, y, iris)
        img.setRGB(14, y, sclera)
        img.setRGB(15, y, black)

        val cols = BlinkEyeGeometry.detectEyeColumns(img, y, skin, 0.15)
        assertEquals(listOf(2, 3, 6, 7), cols)
    }

    /**
     * Optional: set env `BLINK_TEST_IMAGE` to an absolute path of a 64×64 skin PNG to print detection.
     * `./gradlew :SneakyMannequins-Plugin:test --tests ...` with env var for local debugging.
     */
    @Test
    fun faceFallback_optionalEnvImage() {
        val path = System.getenv("BLINK_TEST_IMAGE") ?: return
        val f = File(path)
        if (!f.isFile) return
        val img = ImageIO.read(f) ?: return
        val r = BlinkEyeGeometry.detectFaceFallbackBlink(img, 0.15)
        assertNotNull(r, "No eye band detected for $path")
        val out = r!!
        println(
                "BLINK_TEST_IMAGE $path → height=${out.blinkHeight} style=${out.blinkStyle} cols=${out.blinkEyeColumns} faceY=${out.primaryFaceY}"
        )
    }
}
