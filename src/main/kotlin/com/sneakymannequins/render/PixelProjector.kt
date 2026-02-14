package com.sneakymannequins.render

import com.sneakymannequins.model.PixelChange
import org.bukkit.Location
import kotlin.math.cos
import kotlin.math.sin

data class ProjectedPixel(
    val index: Int,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float,
    val pitch: Float,
    val argb: Int,
    val scaleW: Float,  // horizontal pixel size (world units)
    val scaleH: Float,  // vertical pixel size (world units)
    val visible: Boolean
)

/**
 * Projects 64x64 skin pixels onto a 3D player model with per-face orientation,
 * applying world yaw and scale.
 *
 * Overlay (second-layer) faces use vanilla Minecraft's scaling: each dimension
 * grows by 1 pixel total (+0.5 per side), giving per-axis scale factors that
 * depend on the face's pixel count in that direction.
 */
object PixelProjector {

    fun project(
        origin: Location,
        changes: Collection<PixelChange>,
        pixelScale: Double = 1.0 / 16.0,
        scaleMultiplier: Float = 1f,
        slimArms: Boolean = false
    ): List<ProjectedPixel> {
        val yawRad = Math.toRadians(origin.yaw.toDouble())
        val sin = sin(yawRad)
        val cos = cos(yawRad)

        return changes.mapNotNull { change ->
            val pose = mapSkinPixelToModel(change.x, change.y, pixelScale, slimArms) ?: return@mapNotNull null
            val isVisible = change.visible

            val rotX = pose.x * cos - pose.z * sin
            val rotZ = pose.x * sin + pose.z * cos
            ProjectedPixel(
                index = change.y * 64 + change.x,
                x = origin.x + rotX,
                y = origin.y + pose.y,
                z = origin.z + rotZ,
                yaw = pose.yaw + origin.yaw,
                pitch = pose.pitch,
                argb = change.argb ?: 0,
                scaleW = (pose.scaleW * scaleMultiplier).toFloat(),
                scaleH = (pose.scaleH * scaleMultiplier).toFloat(),
                visible = isVisible
            )
        }
    }

    private data class PixelPose(
        val x: Double, val y: Double, val z: Double,
        val yaw: Float, val pitch: Float,
        val scaleW: Double, val scaleH: Double
    )

    private fun mapSkinPixelToModel(x: Int, y: Int, s: Double, slimArms: Boolean): PixelPose? {
        // Overlay gap: vanilla adds 0.5 pixels on each side (uniform across all parts)
        val og = 0.5 * s

        // --- Face helper functions ---
        // overlay = true → pixel spacing uses per-axis overlay scale, computed from face dimensions:
        //   horizontal scale = (w + 1) / w * s
        //   vertical   scale = (h + 1) / h * s

        fun faceFront(x0: Int, y0: Int, w: Int, h: Int, cx: Double, by: Double, z: Double, overlay: Boolean = false): PixelPose? {
            if (x !in x0 until x0 + w || y !in y0 until y0 + h) return null
            val psW = if (overlay) (w + 1.0) / w * s else s
            val psH = if (overlay) (h + 1.0) / h * s else s
            val lx = x - x0
            val ly = (y0 + h - 1) - y
            return PixelPose(cx + (lx - (w - 1) / 2.0) * psW, by + ly * psH, z, 0f, 0f, psW, psH)
        }

        fun faceBack(x0: Int, y0: Int, w: Int, h: Int, cx: Double, by: Double, z: Double, overlay: Boolean = false): PixelPose? {
            if (x !in x0 until x0 + w || y !in y0 until y0 + h) return null
            val psW = if (overlay) (w + 1.0) / w * s else s
            val psH = if (overlay) (h + 1.0) / h * s else s
            val lx = x - x0
            val ly = (y0 + h - 1) - y
            return PixelPose(cx - (lx - (w - 1) / 2.0) * psW, by + ly * psH, z, 180f, 0f, psW, psH)
        }

        fun faceLeft(x0: Int, y0: Int, w: Int, h: Int, planeX: Double, by: Double, depth: Double, overlay: Boolean = false): PixelPose? {
            if (x !in x0 until x0 + w || y !in y0 until y0 + h) return null
            val psW = if (overlay) (w + 1.0) / w * s else s  // depth axis
            val psH = if (overlay) (h + 1.0) / h * s else s  // vertical axis
            val lz = x - x0
            val ly = (y0 + h - 1) - y
            return PixelPose(planeX, by + ly * psH, depth - (lz - (w - 1) / 2.0) * psW, 90f, 0f, psW, psH)
        }

        fun faceRight(x0: Int, y0: Int, w: Int, h: Int, planeX: Double, by: Double, depth: Double, overlay: Boolean = false): PixelPose? {
            if (x !in x0 until x0 + w || y !in y0 until y0 + h) return null
            val psW = if (overlay) (w + 1.0) / w * s else s
            val psH = if (overlay) (h + 1.0) / h * s else s
            val lz = x - x0
            val ly = (y0 + h - 1) - y
            return PixelPose(planeX, by + ly * psH, depth + (lz - (w - 1) / 2.0) * psW, -90f, 0f, psW, psH)
        }

        fun faceLeftFlipped(x0: Int, y0: Int, w: Int, h: Int, planeX: Double, by: Double, depth: Double, overlay: Boolean = false): PixelPose? {
            if (x !in x0 until x0 + w || y !in y0 until y0 + h) return null
            val psW = if (overlay) (w + 1.0) / w * s else s
            val psH = if (overlay) (h + 1.0) / h * s else s
            val lz = (x0 + w - 1) - x
            val ly = (y0 + h - 1) - y
            return PixelPose(planeX, by + ly * psH, depth - (lz - (w - 1) / 2.0) * psW, 90f, 0f, psW, psH)
        }

        fun faceRightFlipped(x0: Int, y0: Int, w: Int, h: Int, planeX: Double, by: Double, depth: Double, overlay: Boolean = false): PixelPose? {
            if (x !in x0 until x0 + w || y !in y0 until y0 + h) return null
            val psW = if (overlay) (w + 1.0) / w * s else s
            val psH = if (overlay) (h + 1.0) / h * s else s
            val lz = (x0 + w - 1) - x
            val ly = (y0 + h - 1) - y
            return PixelPose(planeX, by + ly * psH, depth + (lz - (w - 1) / 2.0) * psW, -90f, 0f, psW, psH)
        }

        fun faceTop(x0: Int, y0: Int, w: Int, h: Int, cx: Double, topY: Double, depth: Double, overlay: Boolean = false): PixelPose? {
            if (x !in x0 until x0 + w || y !in y0 until y0 + h) return null
            val psW = if (overlay) (w + 1.0) / w * s else s  // X axis
            val psH = if (overlay) (h + 1.0) / h * s else s  // Z axis
            val lx = x - x0
            val lz = (y0 + h - 1) - y
            val shift = psH * 0.5
            return PixelPose(cx + (lx - (w - 1) / 2.0) * psW, topY, depth - (lz - (h - 1) / 2.0) * psH + shift, 0f, -90f, psW, psH)
        }

        fun faceBottom(x0: Int, y0: Int, w: Int, h: Int, cx: Double, bottomY: Double, depth: Double, overlay: Boolean = false): PixelPose? {
            if (x !in x0 until x0 + w || y !in y0 until y0 + h) return null
            val psW = if (overlay) (w + 1.0) / w * s else s
            val psH = if (overlay) (h + 1.0) / h * s else s
            val lx = x - x0
            val lz = y - y0
            val shift = psH * 0.5
            return PixelPose(cx + (lx - (w - 1) / 2.0) * psW, bottomY, depth + (lz - (h - 1) / 2.0) * psH - shift, 0f, 90f, psW, psH)
        }

        val headY = 24.0 * s
        val bodyY = 12.0 * s
        val legY = 0.0

        // ── Head (8x8x8) ──
        faceFront(8, 8, 8, 8, 0.0, headY, 4.0 * s)?.let { return it }
        faceBack(24, 8, 8, 8, 0.0, headY, -4.0 * s)?.let { return it }
        faceLeftFlipped(0, 8, 8, 8, -4.0 * s, headY, 0.0)?.let { return it }
        faceRightFlipped(16, 8, 8, 8, 4.0 * s, headY, 0.0)?.let { return it }
        faceTop(8, 0, 8, 8, 0.0, headY + 8.0 * s, 0.0)?.let { return it }
        faceBottom(16, 0, 8, 8, 0.0, headY, 0.0)?.let { return it }
        // Hat overlay
        faceFront(40, 8, 8, 8, 0.0, headY - og, 4.0 * s + og, overlay = true)?.let { return it }
        faceBack(56, 8, 8, 8, 0.0, headY - og, -4.0 * s - og, overlay = true)?.let { return it }
        faceLeftFlipped(32, 8, 8, 8, -4.0 * s - og, headY - og, 0.0, overlay = true)?.let { return it }
        faceRightFlipped(48, 8, 8, 8, 4.0 * s + og, headY - og, 0.0, overlay = true)?.let { return it }
        faceTop(40, 0, 8, 8, 0.0, headY + 8.0 * s + og, 0.0, overlay = true)?.let { return it }
        faceBottom(48, 0, 8, 8, 0.0, headY - og, 0.0, overlay = true)?.let { return it }

        // ── Body (8w x 12h x 4d) ──
        faceFront(20, 20, 8, 12, 0.0, bodyY, 2.0 * s)?.let { return it }
        faceBack(32, 20, 8, 12, 0.0, bodyY, -2.0 * s)?.let { return it }
        faceLeft(16, 20, 4, 12, -4.0 * s, bodyY, 0.0)?.let { return it }
        faceRight(28, 20, 4, 12, 4.0 * s, bodyY, 0.0)?.let { return it }
        faceTop(20, 16, 8, 4, 0.0, bodyY + 12.0 * s, 0.0)?.let { return it }
        faceBottom(28, 16, 8, 4, 0.0, bodyY, 0.0)?.let { return it }
        // Jacket overlay
        faceFront(20, 36, 8, 12, 0.0, bodyY - og, 2.0 * s + og, overlay = true)?.let { return it }
        faceBack(32, 36, 8, 12, 0.0, bodyY - og, -2.0 * s - og, overlay = true)?.let { return it }
        faceLeft(16, 36, 4, 12, -4.0 * s - og, bodyY - og, 0.0, overlay = true)?.let { return it }
        faceRight(28, 36, 4, 12, 4.0 * s + og, bodyY - og, 0.0, overlay = true)?.let { return it }
        faceTop(20, 32, 8, 4, 0.0, bodyY + 12.0 * s + og, 0.0, overlay = true)?.let { return it }
        faceBottom(28, 32, 8, 4, 0.0, bodyY - og, 0.0, overlay = true)?.let { return it }

        // ── Arms ──

        data class ArmSpec(
            val frontWidth: Int,
            val topWidth: Int,
            val centerX: Double,
            val outerX: Double,
            val innerX: Double,
            val backBaseX: Int,
            val backOverlayX: Int
        )

        val rightArm = if (slimArms) {
            ArmSpec(3, 3, -5.5 * s, -7.0 * s, -4.0 * s, 51, 51)
        } else {
            ArmSpec(4, 4, -6.0 * s, -8.0 * s, -4.0 * s, 52, 52)
        }
        val leftArm = if (slimArms) {
            ArmSpec(3, 3, 5.5 * s, 7.0 * s, 4.0 * s, 43, 59)
        } else {
            ArmSpec(4, 4, 6.0 * s, 8.0 * s, 4.0 * s, 44, 60)
        }

        fun renderRightArm(spec: ArmSpec): PixelPose? {
            // Base
            return faceFront(44, 20, spec.frontWidth, 12, spec.centerX, bodyY, 2.0 * s)
                ?: faceBack(spec.backBaseX, 20, spec.frontWidth, 12, spec.centerX, bodyY, -2.0 * s)
                ?: faceLeft(40, 20, 4, 12, spec.outerX, bodyY, 0.0)
                ?: faceRight(48, 20, 4, 12, spec.innerX, bodyY, 0.0)
                ?: faceTop(44, 16, spec.topWidth, 4, spec.centerX, bodyY + 12.0 * s, 0.0)
                ?: faceBottom(48, 16, spec.topWidth, 4, spec.centerX, bodyY, 0.0)
                // Overlay
                ?: faceFront(44, 36, spec.frontWidth, 12, spec.centerX, bodyY - og, 2.0 * s + og, overlay = true)
                ?: faceBack(spec.backOverlayX, 36, spec.frontWidth, 12, spec.centerX, bodyY - og, -2.0 * s - og, overlay = true)
                ?: faceLeft(40, 36, 4, 12, spec.outerX - og, bodyY - og, 0.0, overlay = true)
                ?: faceRight(48, 36, 4, 12, spec.innerX + og, bodyY - og, 0.0, overlay = true)
                ?: faceTop(44, 32, spec.topWidth, 4, spec.centerX, bodyY + 12.0 * s + og, 0.0, overlay = true)
                ?: faceBottom(48, 32, spec.topWidth, 4, spec.centerX, bodyY - og, 0.0, overlay = true)
        }

        fun renderLeftArm(spec: ArmSpec): PixelPose? {
            val outerBaseUvX = if (spec.frontWidth == 3) 39 else 40
            val outerOverlayUvX = if (spec.frontWidth == 3) 55 else 56
            // Base
            return faceFront(36, 52, spec.frontWidth, 12, spec.centerX, bodyY, 2.0 * s)
                ?: faceBack(spec.backBaseX, 52, spec.frontWidth, 12, spec.centerX, bodyY, -2.0 * s)
                ?: faceLeft(32, 52, 4, 12, spec.innerX, bodyY, 0.0)
                ?: faceRight(outerBaseUvX, 52, 4, 12, spec.outerX, bodyY, 0.0)
                ?: faceTop(36, 48, spec.topWidth, 4, spec.centerX, bodyY + 12.0 * s, 0.0)
                ?: faceBottom(40, 48, spec.topWidth, 4, spec.centerX, bodyY, 0.0)
                // Overlay
                ?: faceFront(52, 52, spec.frontWidth, 12, spec.centerX, bodyY - og, 2.0 * s + og, overlay = true)
                ?: faceBack(spec.backOverlayX, 52, spec.frontWidth, 12, spec.centerX, bodyY - og, -2.0 * s - og, overlay = true)
                ?: faceLeft(48, 52, 4, 12, spec.innerX - og, bodyY - og, 0.0, overlay = true)
                ?: faceRight(outerOverlayUvX, 52, 4, 12, spec.outerX + og, bodyY - og, 0.0, overlay = true)
                ?: faceTop(52, 48, spec.topWidth, 4, spec.centerX, bodyY + 12.0 * s + og, 0.0, overlay = true)
                ?: faceBottom(56, 48, spec.topWidth, 4, spec.centerX, bodyY - og, 0.0, overlay = true)
        }

        renderRightArm(rightArm)?.let { return it }
        renderLeftArm(leftArm)?.let { return it }

        // ── Right leg (4x12x4) ──
        faceFront(4, 20, 4, 12, -2.0 * s, legY, 2.0 * s)?.let { return it }
        faceBack(12, 20, 4, 12, -2.0 * s, legY, -2.0 * s)?.let { return it }
        faceLeft(0, 20, 4, 12, -4.0 * s, legY, 0.0)?.let { return it }
        faceRight(8, 20, 4, 12, 0.0, legY, 0.0)?.let { return it }
        faceTop(4, 16, 4, 4, -2.0 * s, legY + 12.0 * s, 0.0)?.let { return it }
        faceBottom(8, 16, 4, 4, -2.0 * s, legY, 0.0)?.let { return it }
        // Right leg overlay
        faceFront(4, 36, 4, 12, -2.0 * s, legY - og, 2.0 * s + og, overlay = true)?.let { return it }
        faceBack(12, 36, 4, 12, -2.0 * s, legY - og, -2.0 * s - og, overlay = true)?.let { return it }
        faceLeft(0, 36, 4, 12, -4.0 * s - og, legY - og, 0.0, overlay = true)?.let { return it }
        faceRight(8, 36, 4, 12, 0.0 + og, legY - og, 0.0, overlay = true)?.let { return it }
        faceTop(4, 32, 4, 4, -2.0 * s, legY + 12.0 * s + og, 0.0, overlay = true)?.let { return it }
        faceBottom(8, 32, 4, 4, -2.0 * s, legY - og, 0.0, overlay = true)?.let { return it }

        // ── Left leg (4x12x4) ──
        faceFront(20, 52, 4, 12, 2.0 * s, legY, 2.0 * s)?.let { return it }
        faceBack(28, 52, 4, 12, 2.0 * s, legY, -2.0 * s)?.let { return it }
        faceLeft(16, 52, 4, 12, 0.0, legY, 0.0)?.let { return it }
        faceRight(24, 52, 4, 12, 4.0 * s, legY, 0.0)?.let { return it }
        faceTop(20, 48, 4, 4, 2.0 * s, legY + 12.0 * s, 0.0)?.let { return it }
        faceBottom(24, 48, 4, 4, 2.0 * s, legY, 0.0)?.let { return it }
        // Left leg overlay
        faceFront(4, 52, 4, 12, 2.0 * s, legY - og, 2.0 * s + og, overlay = true)?.let { return it }
        faceBack(12, 52, 4, 12, 2.0 * s, legY - og, -2.0 * s - og, overlay = true)?.let { return it }
        faceLeft(0, 52, 4, 12, 0.0 - og, legY - og, 0.0, overlay = true)?.let { return it }
        faceRight(8, 52, 4, 12, 4.0 * s + og, legY - og, 0.0, overlay = true)?.let { return it }
        faceTop(4, 48, 4, 4, 2.0 * s, legY + 12.0 * s + og, 0.0, overlay = true)?.let { return it }
        faceBottom(8, 48, 4, 4, 2.0 * s, legY - og, 0.0, overlay = true)?.let { return it }

        return null
    }
}
