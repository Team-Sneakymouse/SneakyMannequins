package com.sneakymannequins.nms

import com.sneakymannequins.model.PixelChange
import com.sneakymannequins.render.ProjectedPixel
import org.bukkit.Location
import org.bukkit.entity.Player
import java.util.UUID

/**
 * Contract for version-specific NMS packet handling.
 */
interface VolatileHandler {
    companion object {
        const val DEFAULT_PIXEL_SCALE_MULTIPLIER = 4f
    }

    val targetMinecraftVersion: String

    fun pixelScaleMultiplier(): Float = DEFAULT_PIXEL_SCALE_MULTIPLIER

    fun applyPixelChanges(
        viewer: Player,
        mannequinId: UUID,
        origin: Location,
        changes: Collection<PixelChange>
    )

    fun applyProjectedPixels(
        viewer: Player,
        mannequinId: UUID,
        projected: Collection<ProjectedPixel>
    )

    fun destroyMannequin(viewer: Player, mannequinId: UUID)

    // ── Virtual HUD TextDisplay methods ─────────────────────────────────────────

    /** Allocate a unique NMS entity ID for a virtual entity. */
    fun allocateEntityId(): Int

    /**
     * Spawn a virtual (packet-only) TextDisplay for [viewer].
     * The entity uses Billboard.FIXED; rotation is controlled via the
     * Transformation's left-rotation quaternion (Y-axis).
     *
     * @param textJson  JSON-serialised Adventure Component (rich text)
     * @param bgColor   ARGB colour for background
     * @param yaw       Y-rotation in radians
     */
    fun spawnHudTextDisplay(
        viewer: Player, entityId: Int,
        x: Double, y: Double, z: Double,
        textJson: String, bgColor: Int,
        tx: Float, ty: Float, tz: Float,
        yaw: Float, lineWidth: Int
    )

    /**
     * Update an existing virtual HUD TextDisplay.  Sends a metadata-only packet
     * (no spawn) replacing all entity data including an interpolated transformation.
     *
     * @param textJson            JSON-serialised Adventure Component (rich text)
     * @param interpolationTicks  client-side interpolation duration for the
     *                            transformation change (0 = instant)
     */
    fun updateHudTextDisplay(
        viewer: Player, entityId: Int,
        textJson: String, bgColor: Int,
        tx: Float, ty: Float, tz: Float,
        yaw: Float, lineWidth: Int,
        interpolationTicks: Int
    )

    /**
     * Send ONLY a background-colour change for a virtual HUD TextDisplay.
     * This must NOT resend the transformation, so it won't interrupt an
     * in-progress rotation interpolation on the client.
     */
    fun sendHudBackground(viewer: Player, entityId: Int, bgColor: Int)

    // ── Virtual HUD ItemDisplay (backdrop frame) ──────────────────────────────────

    /**
     * Spawn a virtual (packet-only) ItemDisplay for [viewer] that acts as a
     * backdrop / frame behind the HUD buttons.  Billboard FIXED; rotation via yaw.
     *
     * @param item            Minecraft item key, e.g. "minecraft:glass_pane"
     * @param displayContext  item model transform context (e.g. "FIXED", "HEAD")
     * @param yaw             Y-rotation in radians
     */
    fun spawnHudItemDisplay(
        viewer: Player, entityId: Int,
        x: Double, y: Double, z: Double,
        item: String, customModelData: Int, displayContext: String,
        tx: Float, ty: Float, tz: Float,
        sx: Float, sy: Float, sz: Float,
        yaw: Float
    )

    /**
     * Update an existing virtual HUD ItemDisplay (transformation + item).
     */
    fun updateHudItemDisplay(
        viewer: Player, entityId: Int,
        item: String, customModelData: Int, displayContext: String,
        tx: Float, ty: Float, tz: Float,
        sx: Float, sy: Float, sz: Float,
        yaw: Float,
        interpolationTicks: Int
    )

    /** Remove one or more virtual entities for [viewer]. */
    fun destroyEntities(viewer: Player, entityIds: IntArray)
}
