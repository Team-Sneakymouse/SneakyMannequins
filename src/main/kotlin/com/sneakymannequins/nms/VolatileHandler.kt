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

    /**
     * Send a per-player TextDisplay background-color override.
     * Only [viewer] sees the change; other players remain unaffected.
     */
    fun sendTextDisplayHighlight(viewer: Player, entityId: Int, backgroundColor: Int)
}

