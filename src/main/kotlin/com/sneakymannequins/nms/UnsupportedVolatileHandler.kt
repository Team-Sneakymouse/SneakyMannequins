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
    private var nextId = 2_000_000

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

    override fun allocateEntityId(): Int = nextId++

    override fun spawnHudTextDisplay(
        viewer: Player, entityId: Int,
        x: Double, y: Double, z: Double,
        textJson: String, bgColor: Int,
        tx: Float, ty: Float, tz: Float,
        yaw: Float, lineWidth: Int
    ) { /* no-op */ }

    override fun updateHudTextDisplay(
        viewer: Player, entityId: Int,
        textJson: String, bgColor: Int,
        tx: Float, ty: Float, tz: Float,
        yaw: Float, lineWidth: Int,
        interpolationTicks: Int
    ) { /* no-op */ }

    override fun sendHudBackground(viewer: Player, entityId: Int, bgColor: Int) { /* no-op */ }

    override fun spawnHudItemDisplay(
        viewer: Player, entityId: Int,
        x: Double, y: Double, z: Double,
        item: String, customModelData: Int, displayContext: String,
        tx: Float, ty: Float, tz: Float,
        sx: Float, sy: Float, sz: Float,
        yaw: Float
    ) { /* no-op */ }

    override fun updateHudItemDisplay(
        viewer: Player, entityId: Int,
        item: String, customModelData: Int, displayContext: String,
        tx: Float, ty: Float, tz: Float,
        sx: Float, sy: Float, sz: Float,
        yaw: Float,
        interpolationTicks: Int
    ) { /* no-op */ }

    override fun destroyEntities(viewer: Player, entityIds: IntArray) { /* no-op */ }
}
