package com.sneakymannequins.nms.v1_21_4

import com.sneakymannequins.SneakyMannequins
import com.sneakymannequins.model.PixelChange
import com.sneakymannequins.nms.VolatileHandler
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
    private val pixelScaleMultiplier = 4f
    override fun pixelScaleMultiplier(): Float = pixelScaleMultiplier

    // viewerId -> mannequinId -> pixelIndex -> entityId
    private val viewerEntities = mutableMapOf<UUID, MutableMap<UUID, MutableMap<Int, Int>>>()

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
            scaleMultiplier = pixelScaleMultiplier
        )
        applyProjectedPixels(viewer, mannequinId, projected)
    }

    override fun applyProjectedPixels(
        viewer: Player,
        mannequinId: UUID,
        projected: Collection<ProjectedPixel>
    ) {
        if (projected.isEmpty()) return
        val handle = (viewer as CraftPlayer).handle as ServerPlayer
        val level = handle.serverLevel()
        val connection = handle.connection
        val perMannequin = viewerEntities.computeIfAbsent(viewer.uniqueId) { mutableMapOf() }
        val pixels = perMannequin.computeIfAbsent(mannequinId) { mutableMapOf() }

        if (plugin.config.getBoolean("plugin.debug", false)) {
            val visibleCount = projected.size
            plugin.logger.info("[NMS] applyPixelChanges viewer=${viewer.name} mannequin=$mannequinId projected=$visibleCount")
            projected.take(3).forEach {
                plugin.logger.info("[NMS]   proj idx=${it.index} pos=(${it.x},${it.y},${it.z}) yaw=${it.yaw} argb=${it.argb.toString(16)}")
            }
        }

        projected.forEach { proj ->
            val key = proj.index
            val existing = pixels[key]
            // If pixel is now invisible, remove and skip spawning
            if (!proj.visible) {
                existing?.let { connection.send(ClientboundRemoveEntitiesPacket(*intArrayOf(it))) }
                pixels.remove(key)
                return@forEach
            }

            // Remove old entity if present (simpler than updating metadata)
            existing?.let { connection.send(ClientboundRemoveEntitiesPacket(*intArrayOf(it))) }

            val display = TextDisplay(EntityType.TEXT_DISPLAY, level)
            display.setPos(proj.x, proj.y, proj.z)
            display.setYRot(proj.yaw)
            display.setXRot(proj.pitch)
            display.setBillboardConstraints(Display.BillboardConstraints.FIXED)
            display.setShadowRadius(0f)
            display.setShadowStrength(0f)
            display.setViewRange(32f)
            val scale = proj.scale
            display.setTransformation(
                com.mojang.math.Transformation(
                    Vector3f(0f, 0f, 0f),
                    Quaternionf(),
                    Vector3f(scale, scale, scale),
                    Quaternionf()
                )
            )

            val argb = proj.argb
            val argbOpaque = argb or (0xFF shl 24)
            val alpha = 255 // force visible for now
            val rgb = argbOpaque and 0x00FFFFFF
            val textComponent = Component.literal("\u2588").withStyle { style ->
                style.withColor(rgb)
            }
            display.setText(textComponent)
            display.setTextOpacity(alpha.toByte())
            display.setWidth(1f)
            display.setHeight(1f)
            display.entityData.set(TextDisplay.DATA_BACKGROUND_COLOR_ID, argbOpaque)

            val entityId = entityIdCounter.getAndIncrement()

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
            pixels[key] = entityId
            if (plugin.config.getBoolean("plugin.debug", false) && key == 0) {
                plugin.logger.info("[NMS] spawned first pixel entityId=$entityId pos=(${proj.x},${proj.y},${proj.z}) rgb=${rgb.toString(16)} alpha=$alpha")
            }
        }
    }

    override fun destroyMannequin(viewer: Player, mannequinId: UUID) {
        val handle = (viewer as CraftPlayer).handle as ServerPlayer
        val connection = handle.connection
        val perMannequin = viewerEntities[viewer.uniqueId] ?: return
        val pixels = perMannequin.remove(mannequinId) ?: return
        if (pixels.isNotEmpty()) {
            connection.send(ClientboundRemoveEntitiesPacket(*pixels.values.toIntArray()))
            if (plugin.config.getBoolean("plugin.debug", false)) {
                plugin.logger.info("[NMS] destroyMannequin viewer=${viewer.name} mannequin=$mannequinId removed=${pixels.size}")
            }
        }
    }
}

