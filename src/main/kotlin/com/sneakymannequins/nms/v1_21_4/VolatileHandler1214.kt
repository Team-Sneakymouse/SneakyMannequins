package com.sneakymannequins.nms.v1_21_4

import com.sneakymannequins.SneakyMannequins
import com.sneakymannequins.model.PixelChange
import com.sneakymannequins.nms.VolatileHandler
import com.sneakymannequins.nms.PixelRenderManager
import com.sneakymannequins.render.PixelProjector
import com.sneakymannequins.render.ProjectedPixel
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Display
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.Display.TextDisplay
import net.minecraft.world.phys.Vec3
import org.joml.Quaternionf
import org.joml.Vector3f
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.craftbukkit.entity.CraftPlayer
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class VolatileHandler1214(
    private val plugin: SneakyMannequins
) : VolatileHandler {

    override val targetMinecraftVersion: String = "1.21.4"
    private val entityIdCounter = AtomicInteger(1_000_000)
    override fun pixelScaleMultiplier(): Float = VolatileHandler.DEFAULT_PIXEL_SCALE_MULTIPLIER
    private val renderManager = PixelRenderManager()

    override fun applyPixelChanges(
        viewer: Player,
        mannequinId: UUID,
        origin: Location,
        changes: Collection<PixelChange>
    ) {
        val projected = PixelProjector.project(
            origin = origin,
            changes = changes,
            pixelScale = 1.0 / 16.0,
            scaleMultiplier = pixelScaleMultiplier()
        )
        applyProjectedPixels(viewer, mannequinId, projected)
    }

    override fun applyProjectedPixels(
        viewer: Player,
        mannequinId: UUID,
        projected: Collection<ProjectedPixel>
    ) {
        val handle = (viewer as CraftPlayer).handle as ServerPlayer
        val level = handle.serverLevel()
        val connection = handle.connection

        if (plugin.config.getBoolean("plugin.debug", false)) {
            val visibleCount = projected.size
            plugin.logger.info("[NMS] applyPixelChanges viewer=${viewer.name} mannequin=$mannequinId projected=$visibleCount")
            projected.take(3).forEach {
                plugin.logger.info("[NMS]   proj idx=${it.index} pos=(${it.x},${it.y},${it.z}) yaw=${it.yaw} argb=${it.argb.toString(16)}")
            }
        }

        renderManager.handleProjectedPixels(
            viewer = viewer,
            mannequinId = mannequinId,
            projected = projected,
            spawn = { proj, entityId ->
                val display = TextDisplay(EntityType.TEXT_DISPLAY, level)
                display.setPos(proj.x, proj.y, proj.z)
                display.setYRot(proj.yaw)
                display.setXRot(proj.pitch)
                display.setBillboardConstraints(Display.BillboardConstraints.FIXED)
                display.setShadowRadius(0f)
                display.setShadowStrength(0f)
                display.setViewRange(32f)
                val sw = proj.scaleW   // horizontal pixel size
                val sh = proj.scaleH   // vertical pixel size
                display.setTransformation(
                    com.mojang.math.Transformation(
                        Vector3f(0f, 0f, 0f),
                        Quaternionf(),
                        Vector3f(sw * 2.1f, sh, sh), // widen X to match Y visual size
                        Quaternionf()
                    )
                )

                val argb = proj.argb
                display.setText(Component.literal(" "))
                display.setTextOpacity(0.toByte()) // hide glyph; rely on background color
                display.setWidth(1f)
                display.setHeight(1f)
                display.entityData.set(TextDisplay.DATA_BACKGROUND_COLOR_ID, argb)

                val spawnPacket = ClientboundAddEntityPacket(
                    entityId,
                    UUID.randomUUID(),
                    display.x,
                    display.y,
                    display.z,
                    display.xRot,
                    display.yRot,
                    EntityType.TEXT_DISPLAY,
                    0,
                    Vec3.ZERO,
                    0.0
                )
                connection.send(spawnPacket)
                connection.send(ClientboundSetEntityDataPacket(entityId, display.entityData.packAll()))
                if (plugin.config.getBoolean("plugin.debug", false) && proj.index == 0) {
                    plugin.logger.info("[NMS] spawned first pixel entityId=$entityId pos=(${proj.x},${proj.y},${proj.z}) argb=${argb.toString(16)}")
                }
            },
            remove = { entityId -> connection.send(ClientboundRemoveEntitiesPacket(*intArrayOf(entityId))) },
            allocateId = { entityIdCounter.getAndIncrement() }
        )
    }

    override fun destroyMannequin(viewer: Player, mannequinId: UUID) {
        val handle = (viewer as CraftPlayer).handle as ServerPlayer
        val connection = handle.connection
        renderManager.destroyMannequin(viewer, mannequinId) { ids ->
            connection.send(ClientboundRemoveEntitiesPacket(*ids))
            if (plugin.config.getBoolean("plugin.debug", false)) {
                plugin.logger.info("[NMS] destroyMannequin viewer=${viewer.name} mannequin=$mannequinId removed=${ids.size}")
            }
        }
    }
}

