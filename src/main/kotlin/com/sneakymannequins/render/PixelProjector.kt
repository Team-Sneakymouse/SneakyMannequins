package com.sneakymannequins.render

import com.sneakymannequins.model.PixelChange
import com.sneakymannequins.util.SkinUv
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import org.bukkit.Location

data class ProjectedPixel(
        val index: Int,
        val x: Double,
        val y: Double,
        val z: Double,
        val yaw: Float,
        val pitch: Float,
        val argb: Int,
        val scaleW: Float, // horizontal pixel size (world units)
        val scaleH: Float, // vertical pixel size (world units)
        val visible: Boolean,
        val modelX: Double = 0.0, // pre-rotation model-space X (for column grouping)
        val modelZ: Double = 0.0, // pre-rotation model-space Z (for column grouping)
        val originX: Double = 0.0, // mannequin origin (for per-viewer scale)
        val originY: Double = 0.0,
        val originZ: Double = 0.0
) {
    /**
     * Return a copy of this pixel with positions and sizes scaled relative to the mannequin origin
     * by [factor]. A factor of 1.0 returns this unchanged.
     */
    fun scaled(factor: Double): ProjectedPixel {
        if (factor == 1.0) return this
        val f = factor.toFloat()
        return copy(
                x = originX + (x - originX) * factor,
                y = originY + (y - originY) * factor,
                z = originZ + (z - originZ) * factor,
                scaleW = scaleW * f,
                scaleH = scaleH * f
        )
    }
}

/**
 * Projects 64x64 skin pixels onto a 3D player model with per-face orientation, applying world yaw
 * and scale.
 *
 * Overlay (second-layer) faces use vanilla Minecraft's scaling: each dimension grows by 1 pixel
 * total (+0.5 per side), giving per-axis scale factors that depend on the face's pixel count in
 * that direction.
 */
object PixelProjector {

    fun project(
            origin: Location,
            changes: Collection<PixelChange>,
            pixelScale: Double = 1.0 / 16.0,
            scaleMultiplier: Float = 1f,
            slimArms: Boolean = false,
            tPose: Boolean = false
    ): List<ProjectedPixel> {
        val yawRad = Math.toRadians(origin.yaw.toDouble())
        val sin = sin(yawRad)
        val cos = cos(yawRad)

        return changes.mapNotNull { change ->
            val rawPose =
                    mapSkinPixelToModel(change.x, change.y, pixelScale, slimArms, tPose)
                            ?: return@mapNotNull null

            // Apply alignment corrections
            var pose = rawPose
            // Use the part-specific nudge from PixelPose, falling back to 0.5px
            // for non-overlay faces if we want to maintain the "perfect" regular pose alignment.
            val nudge = if (pose.isOverlay) pose.nudge else 0.5 * pixelScale

            val yawRadF = Math.toRadians(pose.yaw.toDouble())
            val pitchRadF = Math.toRadians(pose.pitch.toDouble())
            val nx = -sin(yawRadF) * cos(pitchRadF)
            val ny = -sin(pitchRadF)
            val nz = cos(yawRadF) * cos(pitchRadF)

            if (pose.isOverlay) {
                // Nudge ALL overlay faces along their normal by their part-specific og
                pose =
                        pose.copy(
                                x = pose.x + nx * nudge,
                                y = pose.y + ny * nudge,
                                z = pose.z + nz * nudge
                        )
            }

            // Alignment shifts for all horizontal surfaces (Top/Bottom faces)
            // Combined with the normal-nudge, this aligns them with the
            // Front/Back (+/- 0.5px Z) and Top/Bottom (+/- 1.0px Y) boundaries.
            if (ny > 0.5) {
                // Points UP: extra Up (+Y) and Forward (+Z)
                pose = pose.copy(y = pose.y + nudge, z = pose.z + nudge)
            } else if (ny < -0.5) {
                // Points DOWN: extra Down (-Y) and Backward (-Z)
                // AND 1px lift for "Upside down" surfaces.
                pose = pose.copy(y = pose.y - nudge + pixelScale, z = pose.z - nudge)
            }

            if (tPose && pose.isArm && pose.isOverlay) {
                // "local right" nudge for T-posed arm overlays.
                // This shift depends on the face orientation:
                //   Top (ny=1)    -> +Z
                //   Front (nz=1)  -> -Y
                //   Bottom (ny=-1)-> +Z (Forward)
                //   Back (nz=-1)  -> -Y (Down)
                val magic = 0.125 * pixelScale
                pose = pose.copy(y = pose.y - abs(nz) * magic, z = pose.z + abs(ny) * magic)
            }

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
                    visible = isVisible,
                    modelX = pose.x,
                    modelZ = pose.z,
                    originX = origin.x,
                    originY = origin.y,
                    originZ = origin.z
            )
        }
    }

    private data class PixelPose(
            val x: Double,
            val y: Double,
            val z: Double,
            val yaw: Float,
            val pitch: Float,
            val scaleW: Double,
            val scaleH: Double,
            val isCap: Boolean = false,
            val isOverlay: Boolean = false,
            val isBottom: Boolean = false,
            val nudge: Double = 0.0,
            val isArm: Boolean = false,
            val isHand: Boolean = false
    )

    private fun mapSkinPixelToModel(
            x: Int,
            y: Int,
            s: Double,
            slimArms: Boolean,
            tPose: Boolean = false
    ): PixelPose? {
        // Skip any pixel outside the canonical UV map before evaluating face planes.
        if (!SkinUv.isInAnyUv(x, y)) return null

        // Overlay gap: vanilla adds 0.5 pixels on each side.
        // Adjacent outer layers overlap by ~1 pixel.  Tiny per-part increments
        // give the renderer a clear depth ordering and prevent Z-fighting.
        //   legs (innermost) → arms → torso (outermost, jacket covers pants/shoulders)
        // Head never overlaps other overlay parts, so it stays at base.
        val ogHead = 0.5 * s
        val ogBody = 0.5003 * s
        val ogRightArm = 0.5002 * s
        val ogLeftArm = 0.5002 * s
        val ogRightLeg = 0.5001 * s
        val ogLeftLeg = 0.5001 * s

        // --- Face helper functions ---
        // overlay = true → pixel spacing uses per-axis overlay scale, computed from face
        // dimensions:
        //   horizontal scale = (w + 1) / w * s
        //   vertical   scale = (h + 1) / h * s

        fun faceFront(
                x0: Int,
                y0: Int,
                w: Int,
                h: Int,
                cx: Double,
                by: Double,
                z: Double,
                overlay: Boolean = false,
                nudge: Double = 0.0
        ): PixelPose? {
            if (x !in x0 until x0 + w || y !in y0 until y0 + h) return null
            val psW = if (overlay) (w * s + 2.0 * nudge) / w else s
            val psH = if (overlay) (h * s + 2.0 * nudge) / h else s
            val lx = x - x0
            val ly = (y0 + h - 1) - y
            return PixelPose(
                    cx + (lx - (w - 1) / 2.0) * psW,
                    by + (ly + 0.5) * psH,
                    z,
                    0f,
                    0f,
                    psW,
                    psH,
                    isOverlay = overlay,
                    nudge = if (overlay) nudge else 0.0
            )
        }

        fun faceBack(
                x0: Int,
                y0: Int,
                w: Int,
                h: Int,
                cx: Double,
                by: Double,
                z: Double,
                overlay: Boolean = false,
                nudge: Double = 0.0
        ): PixelPose? {
            if (x !in x0 until x0 + w || y !in y0 until y0 + h) return null
            val psW = if (overlay) (w * s + 2.0 * nudge) / w else s
            val psH = if (overlay) (h * s + 2.0 * nudge) / h else s
            val lx = x - x0
            val ly = (y0 + h - 1) - y
            return PixelPose(
                    cx - (lx - (w - 1) / 2.0) * psW,
                    by + (ly + 0.5) * psH,
                    z,
                    180f,
                    0f,
                    psW,
                    psH,
                    isOverlay = overlay,
                    nudge = if (overlay) nudge else 0.0
            )
        }

        fun faceLeftFlipped(
                x0: Int,
                y0: Int,
                w: Int,
                h: Int,
                planeX: Double,
                by: Double,
                depth: Double,
                overlay: Boolean = false,
                nudge: Double = 0.0
        ): PixelPose? {
            if (x !in x0 until x0 + w || y !in y0 until y0 + h) return null
            val psW = if (overlay) (w * s + 2.0 * nudge) / w else s
            val psH = if (overlay) (h * s + 2.0 * nudge) / h else s
            val lz = (x0 + w - 1) - x
            val ly = (y0 + h - 1) - y
            return PixelPose(
                    planeX,
                    by + (ly + 0.5) * psH,
                    depth - (lz - (w - 1) / 2.0) * psW,
                    90f,
                    0f,
                    psW,
                    psH,
                    isOverlay = overlay,
                    nudge = if (overlay) nudge else 0.0
            )
        }

        fun faceRightFlipped(
                x0: Int,
                y0: Int,
                w: Int,
                h: Int,
                planeX: Double,
                by: Double,
                depth: Double,
                overlay: Boolean = false,
                nudge: Double = 0.0
        ): PixelPose? {
            if (x !in x0 until x0 + w || y !in y0 until y0 + h) return null
            val psW = if (overlay) (w * s + 2.0 * nudge) / w else s
            val psH = if (overlay) (h * s + 2.0 * nudge) / h else s
            val lz = (x0 + w - 1) - x
            val ly = (y0 + h - 1) - y
            return PixelPose(
                    planeX,
                    by + (ly + 0.5) * psH,
                    depth + (lz - (w - 1) / 2.0) * psW,
                    -90f,
                    0f,
                    psW,
                    psH,
                    isOverlay = overlay,
                    nudge = if (overlay) nudge else 0.0
            )
        }

        fun faceTop(
                x0: Int,
                y0: Int,
                w: Int,
                h: Int,
                cx: Double,
                topY: Double,
                depth: Double,
                overlay: Boolean = false,
                nudge: Double = 0.0
        ): PixelPose? {
            if (x !in x0 until x0 + w || y !in y0 until y0 + h) return null
            val psW = if (overlay) (w * s + 2.0 * nudge) / w else s // X axis
            val psH = if (overlay) (h * s + 2.0 * nudge) / h else s // Z axis
            val lx = x - x0
            val lz = (y0 + h - 1) - y
            return PixelPose(
                    cx + (lx - (w - 1) / 2.0) * psW,
                    topY,
                    depth - (lz - (h - 1) / 2.0) * psH,
                    0f,
                    -90f,
                    psW,
                    psH,
                    isCap = true,
                    isOverlay = overlay,
                    nudge = if (overlay) nudge else 0.0
            )
        }

        fun faceBottom(
                x0: Int,
                y0: Int,
                w: Int,
                h: Int,
                cx: Double,
                bottomY: Double,
                depth: Double,
                overlay: Boolean = false,
                nudge: Double = 0.0
        ): PixelPose? {
            if (x !in x0 until x0 + w || y !in y0 until y0 + h) return null
            val psW = if (overlay) (w * s + 2.0 * nudge) / w else s
            val psH = if (overlay) (h * s + 2.0 * nudge) / h else s
            val lx = x - x0
            val lz = y - y0
            return PixelPose(
                    cx + (lx - (w - 1) / 2.0) * psW,
                    bottomY,
                    depth + (lz - (h - 1) / 2.0) * psH,
                    0f,
                    90f,
                    psW,
                    psH,
                    isCap = true,
                    isOverlay = overlay,
                    isBottom = true,
                    isHand = true, // Added for T-pose end-cap alignment
                    nudge = if (overlay) nudge else 0.0
            )
        }

        val headY = 24.0 * s
        val bodyY = 12.0 * s
        val legY = 0.0

        // ── Head (8x8x8) ──
        faceFront(8, 8, 8, 8, 0.0, headY, 4.0 * s)?.let {
            return it
        }
        faceBack(24, 8, 8, 8, 0.0, headY, -4.0 * s)?.let {
            return it
        }
        faceLeftFlipped(0, 8, 8, 8, -4.0 * s, headY, 0.0)?.let {
            return it
        }
        faceRightFlipped(16, 8, 8, 8, 4.0 * s, headY, 0.0)?.let {
            return it
        }
        faceTop(8, 0, 8, 8, 0.0, headY + 8.0 * s, 0.0)?.let {
            return it
        }
        faceBottom(16, 0, 8, 8, 0.0, headY, 0.0)?.let {
            return it
        }
        // Hat overlay
        faceFront(40, 8, 8, 8, 0.0, headY, 4.0 * s, overlay = true, nudge = ogHead)?.let {
            return it
        }
        faceBack(56, 8, 8, 8, 0.0, headY, -4.0 * s, overlay = true, nudge = ogHead)?.let {
            return it
        }
        faceLeftFlipped(32, 8, 8, 8, -4.0 * s, headY, 0.0, overlay = true, nudge = ogHead)?.let {
            return it
        }
        faceRightFlipped(48, 8, 8, 8, 4.0 * s, headY, 0.0, overlay = true, nudge = ogHead)?.let {
            return it
        }
        faceTop(40, 0, 8, 8, 0.0, headY + 8.0 * s, 0.0, overlay = true, nudge = ogHead)?.let {
            return it
        }
        faceBottom(48, 0, 8, 8, 0.0, headY, 0.0, overlay = true, nudge = ogHead)?.let {
            return it
        }

        // ── Body (8w x 12h x 4d) ──
        faceFront(20, 20, 8, 12, 0.0, bodyY, 2.0 * s)?.let {
            return it
        }
        faceBack(32, 20, 8, 12, 0.0, bodyY, -2.0 * s)?.let {
            return it
        }
        faceLeftFlipped(16, 20, 4, 12, -4.0 * s, bodyY, 0.0)?.let {
            return it
        }
        faceRightFlipped(28, 20, 4, 12, 4.0 * s, bodyY, 0.0)?.let {
            return it
        }
        faceTop(20, 16, 8, 4, 0.0, bodyY + 12.0 * s, 0.0)?.let {
            return it
        }
        faceBottom(28, 16, 8, 4, 0.0, bodyY, 0.0)?.let {
            return it
        }
        // Jacket overlay
        faceFront(20, 36, 8, 12, 0.0, bodyY, 2.0 * s, overlay = true, nudge = ogBody)?.let {
            return it
        }
        faceBack(32, 36, 8, 12, 0.0, bodyY, -2.0 * s, overlay = true, nudge = ogBody)?.let {
            return it
        }
        faceLeftFlipped(16, 36, 4, 12, -4.0 * s, bodyY, 0.0, overlay = true, nudge = ogBody)?.let {
            return it
        }
        faceRightFlipped(28, 36, 4, 12, 4.0 * s, bodyY, 0.0, overlay = true, nudge = ogBody)?.let {
            return it
        }
        faceTop(20, 32, 8, 4, 0.0, bodyY + 12.0 * s, 0.0, overlay = true, nudge = ogBody)?.let {
            return it
        }
        faceBottom(28, 32, 8, 4, 0.0, bodyY, 0.0, overlay = true, nudge = ogBody)?.let {
            return it
        }

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

        val rightArm =
                if (slimArms) {
                    ArmSpec(3, 3, -5.5 * s, -7.0 * s, -4.0 * s, 52, 52)
                } else {
                    ArmSpec(4, 4, -6.0 * s, -8.0 * s, -4.0 * s, 52, 52)
                }
        val leftArm =
                if (slimArms) {
                    ArmSpec(3, 3, 5.5 * s, 7.0 * s, 4.0 * s, 44, 60)
                } else {
                    ArmSpec(4, 4, 6.0 * s, 8.0 * s, 4.0 * s, 44, 60)
                }

        fun renderRightArmInternal(spec: ArmSpec): PixelPose? {
            // Base layer
            return faceFront(44, 20, spec.frontWidth, 12, spec.centerX, bodyY, 2.0 * s)
                    ?: faceBack(
                            spec.backBaseX,
                            20,
                            spec.frontWidth,
                            12,
                            spec.centerX,
                            bodyY,
                            -2.0 * s
                    )
                            ?: faceLeftFlipped(40, 20, 4, 12, spec.outerX, bodyY, 0.0)
                            ?: faceRightFlipped(48, 20, 4, 12, spec.innerX, bodyY, 0.0)
                            ?: faceTop(
                            44,
                            16,
                            spec.topWidth,
                            4,
                            spec.centerX,
                            bodyY + 12.0 * s,
                            0.0
                    )
                            ?: faceBottom(48, 16, spec.topWidth, 4, spec.centerX, bodyY, 0.0)
                    // Overlay layer
                    ?: faceFront(
                            44,
                            36,
                            spec.frontWidth,
                            12,
                            spec.centerX,
                            bodyY,
                            2.0 * s,
                            overlay = true,
                            nudge = ogRightArm
                    )
                            ?: faceBack(
                            spec.backOverlayX,
                            36,
                            spec.frontWidth,
                            12,
                            spec.centerX,
                            bodyY,
                            -2.0 * s,
                            overlay = true,
                            nudge = ogRightArm
                    )
                            ?: faceLeftFlipped(
                            40,
                            36,
                            4,
                            12,
                            spec.outerX,
                            bodyY,
                            0.0,
                            overlay = true,
                            nudge = ogRightArm
                    )
                            ?: faceRightFlipped(
                            48,
                            36,
                            4,
                            12,
                            spec.innerX,
                            bodyY,
                            0.0,
                            overlay = true,
                            nudge = ogRightArm
                    )
                            ?: faceTop(
                            44,
                            32,
                            spec.topWidth,
                            4,
                            spec.centerX,
                            bodyY + 12.0 * s,
                            0.0,
                            overlay = true,
                            nudge = ogRightArm
                    )
                            ?: faceBottom(
                            48,
                            32,
                            spec.topWidth,
                            4,
                            spec.centerX,
                            bodyY,
                            0.0,
                            overlay = true,
                            nudge = ogRightArm
                    )
        }

        fun renderRightArm(spec: ArmSpec): PixelPose? {
            return renderRightArmInternal(spec)?.copy(isArm = true)
        }

        fun renderLeftArmInternal(spec: ArmSpec): PixelPose? {
            // Base layer
            return faceFront(36, 52, spec.frontWidth, 12, spec.centerX, bodyY, 2.0 * s)
                    ?: faceBack(
                            spec.backBaseX,
                            52,
                            spec.frontWidth,
                            12,
                            spec.centerX,
                            bodyY,
                            -2.0 * s
                    )
                            ?: faceLeftFlipped(32, 52, 4, 12, spec.innerX, bodyY, 0.0)
                            ?: faceRightFlipped(40, 52, 4, 12, spec.outerX, bodyY, 0.0)
                            ?: faceTop(
                            36,
                            48,
                            spec.topWidth,
                            4,
                            spec.centerX,
                            bodyY + 12.0 * s,
                            0.0
                    )
                            ?: faceBottom(40, 48, spec.topWidth, 4, spec.centerX, bodyY, 0.0)
                    // Overlay layer
                    ?: faceFront(
                            52,
                            52,
                            spec.frontWidth,
                            12,
                            spec.centerX,
                            bodyY,
                            2.0 * s,
                            overlay = true,
                            nudge = ogLeftArm
                    )
                            ?: faceBack(
                            spec.backOverlayX,
                            52,
                            spec.frontWidth,
                            12,
                            spec.centerX,
                            bodyY,
                            -2.0 * s,
                            overlay = true,
                            nudge = ogLeftArm
                    )
                            ?: faceLeftFlipped(
                            48,
                            52,
                            4,
                            12,
                            spec.innerX,
                            bodyY,
                            0.0,
                            overlay = true,
                            nudge = ogLeftArm
                    )
                            ?: faceRightFlipped(
                            56,
                            52,
                            4,
                            12,
                            spec.outerX,
                            bodyY,
                            0.0,
                            overlay = true,
                            nudge = ogLeftArm
                    )
                            ?: faceTop(
                            52,
                            48,
                            spec.topWidth,
                            4,
                            spec.centerX,
                            bodyY + 12.0 * s,
                            0.0,
                            overlay = true,
                            nudge = ogLeftArm
                    )
                            ?: faceBottom(
                            56,
                            48,
                            spec.topWidth,
                            4,
                            spec.centerX,
                            bodyY,
                            0.0,
                            overlay = true,
                            nudge = ogLeftArm
                    )
        }

        fun renderLeftArm(spec: ArmSpec): PixelPose? {
            return renderLeftArmInternal(spec)?.copy(isArm = true)
        }

        if (tPose) {
            // Vanilla arm pivot: 1 pixel into the arm from the body edge, 2 pixels below arm top
            val shoulderY = bodyY + 10.0 * s
            val rPivotX = rightArm.innerX - 1.0 * s // -5*s for default
            val lPivotX = leftArm.innerX + 1.0 * s //  5*s for default

            // Right arm: -90° CW in XY → extends outward to negative X
            renderRightArm(rightArm)?.let { pose ->
                return rotateArmPose(pose, rPivotX, shoulderY, clockwise = true)
            }

            // Left arm: +90° CCW in XY → extends outward to positive X
            renderLeftArm(leftArm)?.let { pose ->
                return rotateArmPose(pose, lPivotX, shoulderY, clockwise = false)
            }
        } else {
            renderRightArm(rightArm)?.let {
                return it
            }
            renderLeftArm(leftArm)?.let {
                return it
            }
        }

        // ── Right leg (4x12x4) ──
        faceFront(4, 20, 4, 12, -2.0 * s, legY, 2.0 * s)?.let {
            return it
        }
        faceBack(12, 20, 4, 12, -2.0 * s, legY, -2.0 * s)?.let {
            return it
        }
        faceLeftFlipped(0, 20, 4, 12, -4.0 * s, legY, 0.0)?.let {
            return it
        }
        faceRightFlipped(8, 20, 4, 12, 0.0, legY, 0.0)?.let {
            return it
        }
        faceTop(4, 16, 4, 4, -2.0 * s, legY + 12.0 * s, 0.0)?.let {
            return it
        }
        faceBottom(8, 16, 4, 4, -2.0 * s, legY, 0.0)?.let {
            return it
        }
        // Right leg overlay
        faceFront(4, 36, 4, 12, -2.0 * s, legY, 2.0 * s, overlay = true, nudge = ogRightLeg)?.let {
            return it
        }
        faceBack(12, 36, 4, 12, -2.0 * s, legY, -2.0 * s, overlay = true, nudge = ogRightLeg)?.let {
            return it
        }
        faceLeftFlipped(0, 36, 4, 12, -4.0 * s, legY, 0.0, overlay = true, nudge = ogRightLeg)
                ?.let {
                    return it
                }
        faceRightFlipped(8, 36, 4, 12, 0.0, legY, 0.0, overlay = true, nudge = ogRightLeg)?.let {
            return it
        }
        faceTop(4, 32, 4, 4, -2.0 * s, legY + 12.0 * s, 0.0, overlay = true, nudge = ogRightLeg)
                ?.let {
                    return it
                }
        faceBottom(8, 32, 4, 4, -2.0 * s, legY, 0.0, overlay = true, nudge = ogRightLeg)?.let {
            return it
        }

        // ── Left leg (4x12x4) ──
        faceFront(20, 52, 4, 12, 2.0 * s, legY, 2.0 * s)?.let {
            return it
        }
        faceBack(28, 52, 4, 12, 2.0 * s, legY, -2.0 * s)?.let {
            return it
        }
        faceLeftFlipped(16, 52, 4, 12, 0.0, legY, 0.0)?.let {
            return it
        }
        faceRightFlipped(24, 52, 4, 12, 4.0 * s, legY, 0.0)?.let {
            return it
        }
        faceTop(20, 48, 4, 4, 2.0 * s, legY + 12.0 * s, 0.0)?.let {
            return it
        }
        faceBottom(24, 48, 4, 4, 2.0 * s, legY, 0.0)?.let {
            return it
        }
        // Left leg overlay
        faceFront(4, 52, 4, 12, 2.0 * s, legY, 2.0 * s, overlay = true, nudge = ogLeftLeg)?.let {
            return it
        }
        faceBack(12, 52, 4, 12, 2.0 * s, legY, -2.0 * s, overlay = true, nudge = ogLeftLeg)?.let {
            return it
        }
        faceLeftFlipped(0, 52, 4, 12, 0.0, legY, 0.0, overlay = true, nudge = ogLeftLeg)?.let {
            return it
        }
        faceRightFlipped(8, 52, 4, 12, 4.0 * s, legY, 0.0, overlay = true, nudge = ogLeftLeg)?.let {
            return it
        }
        faceTop(4, 48, 4, 4, 2.0 * s, legY + 12.0 * s, 0.0, overlay = true, nudge = ogLeftLeg)
                ?.let {
                    return it
                }
        faceBottom(8, 48, 4, 4, 2.0 * s, legY, 0.0, overlay = true, nudge = ogLeftLeg)?.let {
            return it
        }

        return null
    }

    /**
     * Rotate an arm [PixelPose] 90° in the XY plane around a shoulder pivot.
     *
     * This properly transforms position, face normal (yaw/pitch), and scales:
     * - Position is rotated around (pivotX, pivotY); Z is unchanged.
     * - The face normal vector is rotated by the same angle so every face
     * ```
     *     (front, back, side, top, bottom) ends up pointing in the correct
     *     direction after the arm is T-posed.
     * ```
     * - scaleW / scaleH are swapped when a face flips between vertical and
     * ```
     *     horizontal orientation.
     *
     * @param clockwise
     * ```
     * true = −90° CW (right arm, extends to −X);
     * ```
     *                   false = +90° CCW (left arm, extends to +X).
     * ```
     */
    private fun rotateArmPose(
            pose: PixelPose,
            pivotX: Double,
            pivotY: Double,
            clockwise: Boolean
    ): PixelPose {
        val dx = pose.x - pivotX
        val dy = pose.y - pivotY

        // Rotate position
        val newX: Double
        val newY: Double
        if (clockwise) {
            // -90° CW: (dx, dy) → (dy, -dx)
            newX = pivotX + dy
            newY = pivotY - dx
        } else {
            // +90° CCW: (dx, dy) → (-dy, dx)
            newX = pivotX - dy
            newY = pivotY + dx
        }

        // Rotate face normal vector by the same angle
        val yawRad = Math.toRadians(pose.yaw.toDouble())
        val pitchRad = Math.toRadians(pose.pitch.toDouble())
        val nx = -sin(yawRad) * cos(pitchRad)
        val ny = -sin(pitchRad)
        val nz = cos(yawRad) * cos(pitchRad)

        val nx2: Double
        val ny2: Double
        if (clockwise) {
            // -90° CW: (nx, ny) → (ny, -nx)
            nx2 = ny
            ny2 = -nx
        } else {
            // +90° CCW: (nx, ny) → (-ny, nx)
            nx2 = -ny
            ny2 = nx
        }

        val newPitch = Math.toDegrees(asin((-ny2).coerceIn(-1.0, 1.0))).toFloat()
        val newYaw = Math.toDegrees(atan2(-nx2, nz)).toFloat()

        // Every arm face effectively rotates 90° in the XY plane;
        // swap local scales to match the new world-space orientation.
        return pose.copy(
                x = newX,
                y = newY,
                yaw = newYaw,
                pitch = newPitch,
                scaleW = pose.scaleH,
                scaleH = pose.scaleW
        )
    }
}
