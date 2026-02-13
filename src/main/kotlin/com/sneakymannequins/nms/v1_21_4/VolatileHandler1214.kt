package com.sneakymannequins.nms.v1_21_4

import com.sneakymannequins.SneakyMannequins
import com.sneakymannequins.model.PixelChange
import com.sneakymannequins.nms.VolatileHandler
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

    // viewerId -> mannequinId -> pixelIndex -> entityId
    private val viewerEntities = mutableMapOf<UUID, MutableMap<UUID, MutableMap<Int, Int>>>()

    override fun applyPixelChanges(
        viewer: Player,
        mannequinId: UUID,
        origin: Location,
        changes: Collection<PixelChange>
    ) {
        if (changes.isEmpty()) return
        val handle = (viewer as CraftPlayer).handle as ServerPlayer
        val level = handle.serverLevel()
        val connection = handle.connection
        val perMannequin = viewerEntities.computeIfAbsent(viewer.uniqueId) { mutableMapOf() }
        val pixels = perMannequin.computeIfAbsent(mannequinId) { mutableMapOf() }

        val pixelSize = 1.0 / 16.0 // 1 pixel = 1/16 block (vanilla pixel size)

        if (plugin.config.getBoolean("plugin.debug", false)) {
            val visibleCount = changes.count { it.visible }
            plugin.logger.info("[NMS] applyPixelChanges viewer=${viewer.name} mannequin=$mannequinId total=${changes.size} visible=$visibleCount")
            changes.take(3).forEach {
                plugin.logger.info("[NMS]   change x=${it.x} y=${it.y} visible=${it.visible} argb=${it.argb?.toString(16)}")
            }
        }

        changes.forEach { change ->
            val key = change.y * 64 + change.x
            val pose = mapSkinPixelToModel(change.x, change.y, pixelSize) ?: return@forEach
            val existing = pixels[key]
            if (!change.visible) {
                existing?.let {
                    connection.send(ClientboundRemoveEntitiesPacket(*intArrayOf(it)))
                    pixels.remove(key)
                }
                return@forEach
            }

            // Remove old entity if present (simpler than updating metadata)
            existing?.let { connection.send(ClientboundRemoveEntitiesPacket(*intArrayOf(it))) }

            val display = TextDisplay(EntityType.TEXT_DISPLAY, level)
            val xPos = origin.x + pose.x
            val yPos = origin.y + pose.y
            val zPos = origin.z + pose.z
            display.setPos(xPos, yPos, zPos)
            display.setYRot(pose.yaw)
            display.setXRot(pose.pitch)
            display.setBillboardConstraints(Display.BillboardConstraints.FIXED)
            display.setShadowRadius(0f)
            display.setShadowStrength(0f)
            display.setViewRange(32f)
            val scale = (pixelSize * pixelScaleMultiplier).toFloat()
            display.setTransformation(
                com.mojang.math.Transformation(
                    Vector3f(0f, 0f, 0f),
                    Quaternionf(),
                    Vector3f(scale, scale, scale),
                    Quaternionf()
                )
            )

            val argb = change.argb ?: 0
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
                plugin.logger.info("[NMS] spawned first pixel entityId=$entityId pos=($xPos,$yPos,$zPos) rgb=${rgb.toString(16)} alpha=$alpha")
            }
        }
    }

    /**
     * Maps a 64x64 skin pixel (x,y) to a mannequin model position (relative to feet origin).
     * Currently renders only the front faces of head/body/arms/legs for visibility.
     */
    private data class PixelPose(val x: Double, val y: Double, val z: Double, val yaw: Float, val pitch: Float)

    private fun mapSkinPixelToModel(x: Int, y: Int, s: Double): PixelPose? {
        // Helper to map a face of a cuboid
        fun faceFront(x0: Int, y0: Int, w: Int, h: Int, cx: Double, by: Double, z: Double) =
            if (x in x0 until x0 + w && y in y0 until y0 + h) {
                val lx = x - x0
                val ly = (y0 + h - 1) - y
                PixelPose(cx + (lx - (w - 1) / 2.0) * s, by + ly * s, z, 0f, 0f)
            } else null

        fun faceBack(x0: Int, y0: Int, w: Int, h: Int, cx: Double, by: Double, z: Double) =
            if (x in x0 until x0 + w && y in y0 until y0 + h) {
                val lx = x - x0
                val ly = (y0 + h - 1) - y
                PixelPose(cx - (lx - (w - 1) / 2.0) * s, by + ly * s, z, 180f, 0f)
            } else null

        fun faceLeft(x0: Int, y0: Int, w: Int, h: Int, planeX: Double, by: Double, depth: Double) =
            if (x in x0 until x0 + w && y in y0 until y0 + h) {
                val lz = x - x0
                val ly = (y0 + h - 1) - y
                PixelPose(planeX, by + ly * s, depth - (lz - (w - 1) / 2.0) * s, 90f, 0f)
            } else null

        fun faceRight(x0: Int, y0: Int, w: Int, h: Int, planeX: Double, by: Double, depth: Double) =
            if (x in x0 until x0 + w && y in y0 until y0 + h) {
                val lz = x - x0
                val ly = (y0 + h - 1) - y
                PixelPose(planeX, by + ly * s, depth + (lz - (w - 1) / 2.0) * s, -90f, 0f)
            } else null

        fun faceTop(x0: Int, y0: Int, w: Int, h: Int, cx: Double, topY: Double, depth: Double) =
            if (x in x0 until x0 + w && y in y0 until y0 + h) {
                val lx = x - x0
                val lz = (y0 + h - 1) - y
                val shift = s * 0.5
                PixelPose(cx + (lx - (w - 1) / 2.0) * s, topY, depth - (lz - (h - 1) / 2.0) * s + shift, 0f, -90f)
            } else null

        fun faceBottom(x0: Int, y0: Int, w: Int, h: Int, cx: Double, bottomY: Double, depth: Double) =
            if (x in x0 until x0 + w && y in y0 until y0 + h) {
                val lx = x - x0
                val lz = y - y0
                val shift = s * 0.5
                PixelPose(cx + (lx - (w - 1) / 2.0) * s, bottomY, depth + (lz - (h - 1) / 2.0) * s - shift, 0f, 90f)
            } else null

        // Model dimensions
        val headY = 24.0 * s
        val bodyY = 12.0 * s
        val legY = 0.0

        // Head (8x8x8)
        faceFront(8, 8, 8, 8, 0.0, headY, 4.0 * s)?.let { return it }
        faceBack(24, 8, 8, 8, 0.0, headY, -4.0 * s)?.let { return it }
        faceLeft(0, 8, 8, 8, -4.0 * s, headY, 0.0)?.let { return it }
        faceRight(16, 8, 8, 8, 4.0 * s, headY, 0.0)?.let { return it }
        faceTop(8, 0, 8, 8, 0.0, headY + 8.0 * s, 0.0)?.let { return it }
        faceBottom(16, 0, 8, 8, 0.0, headY, 0.0)?.let { return it }
        // Hat overlay
        faceFront(40, 8, 8, 8, 0.0, headY, 4.0 * s + 0.001)?.let { return it }
        faceBack(56, 8, 8, 8, 0.0, headY, -4.0 * s - 0.001)?.let { return it }
        faceLeft(32, 8, 8, 8, -4.0 * s - 0.001, headY, 0.0)?.let { return it }
        faceRight(48, 8, 8, 8, 4.0 * s + 0.001, headY, 0.0)?.let { return it }
        faceTop(40, 0, 8, 8, 0.0, headY + 8.0 * s + 0.001, 0.0)?.let { return it }
        faceBottom(48, 0, 8, 8, 0.0, headY - 0.001, 0.0)?.let { return it }

        // Body (8x12x4)
        faceFront(20, 20, 8, 12, 0.0, bodyY, 2.0 * s)?.let { return it }
        faceBack(32, 20, 8, 12, 0.0, bodyY, -2.0 * s)?.let { return it }
        faceLeft(16, 20, 4, 12, -4.0 * s, bodyY, 0.0)?.let { return it }
        faceRight(28, 20, 4, 12, 4.0 * s, bodyY, 0.0)?.let { return it }
        faceTop(20, 16, 8, 4, 0.0, bodyY + 12.0 * s, 0.0)?.let { return it }
        faceBottom(28, 16, 8, 4, 0.0, bodyY, 0.0)?.let { return it }
        // Jacket overlay
        faceFront(20, 36, 8, 12, 0.0, bodyY, 2.0 * s + 0.001)?.let { return it }
        faceBack(32, 36, 8, 12, 0.0, bodyY, -2.0 * s - 0.001)?.let { return it }
        faceLeft(16, 36, 4, 12, -4.0 * s - 0.001, bodyY, 0.0)?.let { return it }
        faceRight(28, 36, 4, 12, 4.0 * s + 0.001, bodyY, 0.0)?.let { return it }
        faceTop(20, 32, 8, 4, 0.0, bodyY + 12.0 * s + 0.001, 0.0)?.let { return it }
        faceBottom(28, 32, 8, 4, 0.0, bodyY - 0.001, 0.0)?.let { return it }

        // Right arm (4x12x4)
        faceFront(44, 20, 4, 12, -6.0 * s, bodyY, 2.0 * s)?.let { return it }
        faceBack(52, 20, 4, 12, -6.0 * s, bodyY, -2.0 * s)?.let { return it }
        faceLeft(40, 20, 4, 12, -8.0 * s, bodyY, 0.0)?.let { return it }
        faceRight(48, 20, 4, 12, -4.0 * s, bodyY, 0.0)?.let { return it }
        faceTop(44, 16, 4, 4, -6.0 * s, bodyY + 12.0 * s, 0.0)?.let { return it }
        faceBottom(48, 16, 4, 4, -6.0 * s, bodyY, 0.0)?.let { return it }
        // Right arm overlay
        faceFront(44, 36, 4, 12, -6.0 * s, bodyY, 2.0 * s + 0.001)?.let { return it }
        faceBack(52, 36, 4, 12, -6.0 * s, bodyY, -2.0 * s - 0.001)?.let { return it }
        faceLeft(40, 36, 4, 12, -8.0 * s - 0.001, bodyY, 0.0)?.let { return it }
        faceRight(48, 36, 4, 12, -4.0 * s + 0.001, bodyY, 0.0)?.let { return it }
        faceTop(44, 32, 4, 4, -6.0 * s, bodyY + 12.0 * s + 0.001, 0.0)?.let { return it }
        faceBottom(48, 32, 4, 4, -6.0 * s, bodyY - 0.001, 0.0)?.let { return it }

        // Left arm (4x12x4) using second layer areas
        faceFront(36, 52, 4, 12, 6.0 * s, bodyY, 2.0 * s)?.let { return it }
        faceBack(44, 52, 4, 12, 6.0 * s, bodyY, -2.0 * s)?.let { return it }
        faceLeft(32, 52, 4, 12, 4.0 * s, bodyY, 0.0)?.let { return it }
        faceRight(40, 52, 4, 12, 8.0 * s, bodyY, 0.0)?.let { return it }
        faceTop(36, 48, 4, 4, 6.0 * s, bodyY + 12.0 * s, 0.0)?.let { return it }
        faceBottom(40, 48, 4, 4, 6.0 * s, bodyY, 0.0)?.let { return it }
        // Left arm overlay
        faceFront(52, 52, 4, 12, 6.0 * s, bodyY, 2.0 * s + 0.001)?.let { return it }
        faceBack(60, 52, 4, 12, 6.0 * s, bodyY, -2.0 * s - 0.001)?.let { return it }
        faceLeft(48, 52, 4, 12, 4.0 * s - 0.001, bodyY, 0.0)?.let { return it }
        faceRight(56, 52, 4, 12, 8.0 * s + 0.001, bodyY, 0.0)?.let { return it }
        faceTop(52, 48, 4, 4, 6.0 * s, bodyY + 12.0 * s + 0.001, 0.0)?.let { return it }
        faceBottom(56, 48, 4, 4, 6.0 * s, bodyY - 0.001, 0.0)?.let { return it }

        // Right leg (4x12x4)
        faceFront(4, 20, 4, 12, -2.0 * s, legY, 2.0 * s)?.let { return it }
        faceBack(12, 20, 4, 12, -2.0 * s, legY, -2.0 * s)?.let { return it }
        faceLeft(0, 20, 4, 12, -4.0 * s, legY, 0.0)?.let { return it }
        faceRight(8, 20, 4, 12, 0.0, legY, 0.0)?.let { return it }
        faceTop(4, 16, 4, 4, -2.0 * s, legY + 12.0 * s, 0.0)?.let { return it }
        faceBottom(8, 16, 4, 4, -2.0 * s, legY, 0.0)?.let { return it }
        // Right leg overlay
        faceFront(4, 36, 4, 12, -2.0 * s, legY, 2.0 * s + 0.001)?.let { return it }
        faceBack(12, 36, 4, 12, -2.0 * s, legY, -2.0 * s - 0.001)?.let { return it }
        faceLeft(0, 36, 4, 12, -4.0 * s - 0.001, legY, 0.0)?.let { return it }
        faceRight(8, 36, 4, 12, 0.0 + 0.001, legY, 0.0)?.let { return it }
        faceTop(4, 32, 4, 4, -2.0 * s, legY + 12.0 * s + 0.001, 0.0)?.let { return it }
        faceBottom(8, 32, 4, 4, -2.0 * s, legY - 0.001, 0.0)?.let { return it }

        // Left leg (4x12x4) using second layer region
        faceFront(20, 52, 4, 12, 2.0 * s, legY, 2.0 * s)?.let { return it }
        faceBack(28, 52, 4, 12, 2.0 * s, legY, -2.0 * s)?.let { return it }
        faceLeft(16, 52, 4, 12, 0.0, legY, 0.0)?.let { return it }
        faceRight(24, 52, 4, 12, 4.0 * s, legY, 0.0)?.let { return it }
        faceTop(20, 48, 4, 4, 2.0 * s, legY + 12.0 * s, 0.0)?.let { return it }
        faceBottom(24, 48, 4, 4, 2.0 * s, legY, 0.0)?.let { return it }
        // Left leg overlay
        faceFront(4, 52, 4, 12, 2.0 * s, legY, 2.0 * s + 0.001)?.let { return it }
        faceBack(12, 52, 4, 12, 2.0 * s, legY, -2.0 * s - 0.001)?.let { return it }
        faceLeft(0, 52, 4, 12, 0.0 - 0.001, legY, 0.0)?.let { return it }
        faceRight(8, 52, 4, 12, 4.0 * s + 0.001, legY, 0.0)?.let { return it }
        faceTop(4, 48, 4, 4, 2.0 * s, legY + 12.0 * s + 0.001, 0.0)?.let { return it }
        faceBottom(8, 48, 4, 4, 2.0 * s, legY - 0.001, 0.0)?.let { return it }

        return null // skip unused regions
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

