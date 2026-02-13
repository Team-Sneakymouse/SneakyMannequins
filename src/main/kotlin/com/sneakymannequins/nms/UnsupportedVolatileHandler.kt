package com.sneakymannequins.nms

import com.sneakymannequins.SneakyMannequins
import com.sneakymannequins.model.PixelChange
import com.sneakymannequins.render.ProjectedPixel
import org.bukkit.Location
import org.bukkit.entity.Player
import java.util.UUID

class UnsupportedVolatileHandler(
    override val targetMinecraftVersion: String,
    private val plugin: SneakyMannequins
) : VolatileHandler {

    override fun pixelScaleMultiplier(): Float = 1f

    override fun applyPixelChanges(
        viewer: Player,
        mannequinId: UUID,
        origin: Location,
        changes: Collection<PixelChange>
    ) {
        if (plugin.config.getBoolean("plugin.debug", false)) {
            plugin.logger.warning("No NMS handler for $targetMinecraftVersion; skipping pixel updates.")
        }
    }

    override fun destroyMannequin(viewer: Player, mannequinId: UUID) {
        if (plugin.config.getBoolean("plugin.debug", false)) {
            plugin.logger.warning("No NMS handler for $targetMinecraftVersion; skipping mannequin removal.")
        }
    }

    override fun applyProjectedPixels(
        viewer: Player,
        mannequinId: UUID,
        projected: Collection<ProjectedPixel>
    ) {
        applyPixelChanges(viewer, mannequinId, viewer.location, emptyList())
    }
}

