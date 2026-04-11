package com.sneakymannequins.nms

import com.sneakymannequins.model.PixelChange
import com.sneakymannequins.render.FlyInOffset
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
            changes: Collection<PixelChange>,
            textLightBlock: Int = 15,
            textLightSky: Int = 15
    )

    fun applyProjectedPixels(
            viewer: Player,
            mannequinId: UUID,
            projected: Collection<ProjectedPixel>,
            textLightBlock: Int = 15,
            textLightSky: Int = 15
    )

    /**
     * Like [applyProjectedPixels], but with extra animation data:
     *
     * - Pixels in [flyInOffsets] are spawned at a distant offset position
     *   (with rotation) and interpolated to their correct location.
     * - Pixels in [riseUpIndices] get a small "rise-up from below" effect
     *   rather than popping into existence.
     *
     * The default implementation ignores animation data and falls through to
     * [applyProjectedPixels].
     */
    fun applyProjectedPixelsAnimated(
            viewer: Player,
            mannequinId: UUID,
            projected: Collection<ProjectedPixel>,
            flyInOffsets: Map<Int, FlyInOffset>,
            riseUpIndices: Set<Int> = emptySet(),
            riseUpTicks: Int = 6,
            textLightBlock: Int = 15,
            textLightSky: Int = 15
    ) {
        applyProjectedPixels(viewer, mannequinId, projected, textLightBlock, textLightSky)
    }

    fun destroyMannequin(viewer: Player, mannequinId: UUID)

    /** Allocate a unique NMS entity ID for a virtual entity. */
    fun allocateEntityId(): Int

    /** Remove one or more virtual entities for [viewer]. */
    fun destroyEntities(viewer: Player, entityIds: IntArray)
}
