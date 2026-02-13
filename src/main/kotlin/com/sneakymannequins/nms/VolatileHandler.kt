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
    val targetMinecraftVersion: String

    fun pixelScaleMultiplier(): Float = 1f

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
}

