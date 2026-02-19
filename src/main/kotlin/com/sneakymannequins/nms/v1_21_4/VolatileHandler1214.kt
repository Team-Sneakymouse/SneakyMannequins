package com.sneakymannequins.nms.v1_21_4

import com.sneakymannequins.SneakyMannequins
import com.sneakymannequins.model.PixelChange
import com.sneakymannequins.nms.VolatileHandler
import com.sneakymannequins.nms.PixelRenderManager
import com.sneakymannequins.render.FlyInOffset
import com.sneakymannequins.render.PixelProjector
import com.sneakymannequins.render.ProjectedPixel
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Display
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.Display.ItemDisplay
import net.minecraft.world.entity.Display.TextDisplay
import net.minecraft.world.item.ItemDisplayContext
import org.bukkit.Material
import org.bukkit.craftbukkit.inventory.CraftItemStack
import org.bukkit.inventory.ItemStack as BukkitItemStack
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
        applyProjectedPixelsAnimated(viewer, mannequinId, projected, emptyMap(), emptySet())
    }

    override fun applyProjectedPixelsAnimated(
        viewer: Player,
        mannequinId: UUID,
        projected: Collection<ProjectedPixel>,
        flyInOffsets: Map<Int, FlyInOffset>,
        riseUpIndices: Set<Int>,
        riseUpTicks: Int
    ) {
        val handle = (viewer as CraftPlayer).handle as ServerPlayer
        val level = handle.serverLevel()
        val connection = handle.connection

        if (plugin.config.getBoolean("plugin.debug", false)) {
            val visibleCount = projected.size
            plugin.logger.info("[NMS] applyPixelChanges viewer=${viewer.name} mannequin=$mannequinId projected=$visibleCount flyIns=${flyInOffsets.size}")
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
                // Entity rotation stays at 0; face orientation lives in the
                // transformation's left-rotation quaternion so the client
                // renderer handles all visual positioning via the transform.
                display.setBillboardConstraints(Display.BillboardConstraints.FIXED)
                display.setShadowRadius(0f)
                display.setShadowStrength(0f)
                display.setViewRange(32f)
                val sw = proj.scaleW   // horizontal pixel size
                val sh = proj.scaleH   // vertical pixel size
                val yawRad = Math.toRadians(-proj.yaw.toDouble()).toFloat()
                val pitchRad = Math.toRadians(proj.pitch.toDouble()).toFloat()
                val rotation = Quaternionf().rotateY(yawRad).rotateX(pitchRad)

                // The TextDisplay background is rendered slightly to the right of where
                // we expect it. Compensate with a small leftward nudge in local-X.
                // The value 0.025 was determined empirically.
                val localXDir = Vector3f(1f, 0f, 0f)
                rotation.transform(localXDir)
                val nudge = -0.025f * sw

                val baseTranslation = Vector3f(
                    nudge * localXDir.x,
                    nudge * localXDir.y,
                    nudge * localXDir.z
                )
                val scale = Vector3f(sw * 2f, sh, sh) // widen X to match Y visual size

                val flyIn = flyInOffsets[proj.index]
                val isRiseUp = proj.index in riseUpIndices

                if (flyIn != null) {
                    // ── Fly-in spawn: start at a distant offset with tumble rotation ──

                    // 1) Spawn with offset translation + random rotation (instant — duration 0)
                    val offsetTranslation = Vector3f(baseTranslation).add(
                        flyIn.offsetX, flyIn.offsetY, flyIn.offsetZ
                    )
                    // Compose the pixel's base rotation with random tumble angles
                    val tumbledRotation = Quaternionf(rotation)
                        .rotateX(flyIn.rotX)
                        .rotateY(flyIn.rotY)
                        .rotateZ(flyIn.rotZ)
                    display.setTransformation(
                        com.mojang.math.Transformation(offsetTranslation, tumbledRotation, scale, Quaternionf())
                    )
                    display.setTransformationInterpolationDelay(0)
                    display.setTransformationInterpolationDuration(0)
                } else if (isRiseUp) {
                    // ── Rise-up spawn: start slightly below final position ──
                    val riseOffset = Vector3f(baseTranslation).add(0f, -sh * 1.5f, 0f)
                    display.setTransformation(
                        com.mojang.math.Transformation(riseOffset, rotation, scale, Quaternionf())
                    )
                    display.setTransformationInterpolationDelay(0)
                    display.setTransformationInterpolationDuration(0)
                } else {
                    // ── Normal spawn ──
                    display.setTransformation(
                        com.mojang.math.Transformation(baseTranslation, rotation, scale, Quaternionf())
                    )
                }

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
                    0f,   // no entity pitch — orientation is in the transformation
                    0f,   // no entity yaw
                    EntityType.TEXT_DISPLAY,
                    0,
                    Vec3.ZERO,
                    0.0
                )
                connection.send(spawnPacket)
                connection.send(ClientboundSetEntityDataPacket(entityId, display.entityData.packAll()))

                if (flyIn != null || isRiseUp) {
                    // 2) Schedule the target transformation for the next tick so the
                    //    client has a frame to register the offset as the "old" position
                    //    before interpolation begins.  We reuse the original `display`
                    //    object (captured by the lambda) so packAll() re-sends all
                    //    properties with the corrected transformation.
                    val interpTicks = flyIn?.interpolationTicks ?: riseUpTicks
                    plugin.server.scheduler.scheduleSyncDelayedTask(plugin, Runnable {
                        if (!viewer.isOnline) return@Runnable
                        display.setTransformation(
                            com.mojang.math.Transformation(baseTranslation, rotation, scale, Quaternionf())
                        )
                        display.setTransformationInterpolationDelay(0)
                        display.setTransformationInterpolationDuration(interpTicks)
                        val conn = (viewer as CraftPlayer).handle.connection
                        conn.send(ClientboundSetEntityDataPacket(entityId, display.entityData.packAll()))
                    }, 1L)
                }

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

    // ── Virtual HUD TextDisplay ─────────────────────────────────────────────────

    override fun allocateEntityId(): Int = entityIdCounter.getAndIncrement()

    override fun spawnHudTextDisplay(
        viewer: Player, entityId: Int,
        x: Double, y: Double, z: Double,
        textJson: String, bgColor: Int,
        tx: Float, ty: Float, tz: Float,
        yaw: Float, lineWidth: Int
    ) {
        val handle = (viewer as CraftPlayer).handle as ServerPlayer
        val level = handle.serverLevel()
        val connection = handle.connection

        val display = buildHudDisplay(level, textJson, bgColor, tx, ty, tz, yaw, lineWidth)
        display.setPos(x, y, z)

        val spawnPacket = ClientboundAddEntityPacket(
            entityId,
            UUID.randomUUID(),
            x, y, z,
            0f, 0f,
            EntityType.TEXT_DISPLAY,
            0,
            Vec3.ZERO,
            0.0
        )
        connection.send(spawnPacket)
        connection.send(ClientboundSetEntityDataPacket(entityId, display.entityData.packAll()))
    }

    override fun updateHudTextDisplay(
        viewer: Player, entityId: Int,
        textJson: String, bgColor: Int,
        tx: Float, ty: Float, tz: Float,
        yaw: Float, lineWidth: Int,
        interpolationTicks: Int
    ) {
        val handle = (viewer as CraftPlayer).handle as ServerPlayer
        val level = handle.serverLevel()
        val connection = handle.connection

        val display = buildHudDisplay(level, textJson, bgColor, tx, ty, tz, yaw, lineWidth)
        // Interpolation: start immediately, smooth over the given duration
        display.setTransformationInterpolationDelay(0)
        display.setTransformationInterpolationDuration(interpolationTicks)

        connection.send(ClientboundSetEntityDataPacket(entityId, display.entityData.packAll()))
    }

    override fun sendHudBackground(viewer: Player, entityId: Int, bgColor: Int) {
        val handle = (viewer as CraftPlayer).handle as ServerPlayer
        // Send ONLY the background colour – no transformation, no interpolation reset
        val dataItems = listOf(
            net.minecraft.network.syncher.SynchedEntityData.DataValue.create(
                TextDisplay.DATA_BACKGROUND_COLOR_ID, bgColor
            )
        )
        handle.connection.send(ClientboundSetEntityDataPacket(entityId, dataItems))
    }

    override fun destroyEntities(viewer: Player, entityIds: IntArray) {
        if (entityIds.isEmpty()) return
        val handle = (viewer as CraftPlayer).handle as ServerPlayer
        handle.connection.send(ClientboundRemoveEntitiesPacket(*entityIds))
    }

    // ── Virtual HUD ItemDisplay (backdrop frame) ──────────────────────────────────

    override fun spawnHudItemDisplay(
        viewer: Player, entityId: Int,
        x: Double, y: Double, z: Double,
        item: String, customModelData: Int, displayContext: String,
        tx: Float, ty: Float, tz: Float,
        sx: Float, sy: Float, sz: Float,
        yaw: Float
    ) {
        val handle = (viewer as CraftPlayer).handle as ServerPlayer
        val level = handle.serverLevel()
        val connection = handle.connection

        val display = buildHudItemDisplay(level, item, customModelData, displayContext, tx, ty, tz, sx, sy, sz, yaw)
        display.setPos(x, y, z)

        val spawnPacket = ClientboundAddEntityPacket(
            entityId,
            UUID.randomUUID(),
            x, y, z,
            0f, 0f,
            EntityType.ITEM_DISPLAY,
            0,
            Vec3.ZERO,
            0.0
        )
        connection.send(spawnPacket)
        connection.send(ClientboundSetEntityDataPacket(entityId, display.entityData.packAll()))
    }

    override fun updateHudItemDisplay(
        viewer: Player, entityId: Int,
        item: String, customModelData: Int, displayContext: String,
        tx: Float, ty: Float, tz: Float,
        sx: Float, sy: Float, sz: Float,
        yaw: Float,
        interpolationTicks: Int
    ) {
        val handle = (viewer as CraftPlayer).handle as ServerPlayer
        val level = handle.serverLevel()
        val connection = handle.connection

        val display = buildHudItemDisplay(level, item, customModelData, displayContext, tx, ty, tz, sx, sy, sz, yaw)
        display.setTransformationInterpolationDelay(0)
        display.setTransformationInterpolationDuration(interpolationTicks)

        connection.send(ClientboundSetEntityDataPacket(entityId, display.entityData.packAll()))
    }

    private fun buildHudItemDisplay(
        level: net.minecraft.server.level.ServerLevel,
        item: String, customModelData: Int, displayContext: String,
        tx: Float, ty: Float, tz: Float,
        sx: Float, sy: Float, sz: Float,
        yaw: Float
    ): ItemDisplay {
        val display = ItemDisplay(EntityType.ITEM_DISPLAY, level)
        display.setBillboardConstraints(Display.BillboardConstraints.FIXED)
        display.setShadowRadius(0f)
        display.setShadowStrength(0f)
        display.setViewRange(32f)

        // Resolve item via Bukkit Material, apply custom model data, convert to NMS
        val material = Material.matchMaterial(item) ?: Material.GLASS_PANE
        val bukkitStack = BukkitItemStack(material)
        if (customModelData > 0) {
            val meta = bukkitStack.itemMeta
            meta?.setCustomModelData(customModelData)
            bukkitStack.itemMeta = meta
        }
        display.setItemStack(CraftItemStack.asNMSCopy(bukkitStack))

        // Set display context
        val context = try {
            ItemDisplayContext.valueOf(displayContext.uppercase())
        } catch (_: Exception) {
            ItemDisplayContext.FIXED
        }
        display.setItemTransform(context)

        // Rotate translation by yaw, same as TextDisplay HUD
        val rotQ = Quaternionf().rotationY(yaw)
        val rotatedTranslation = Vector3f(tx, ty, tz).also { rotQ.transform(it) }

        display.setTransformation(
            com.mojang.math.Transformation(
                rotatedTranslation,
                rotQ,
                Vector3f(sx, sy, sz),
                Quaternionf()
            )
        )
        display.setTransformationInterpolationDelay(0)
        display.setTransformationInterpolationDuration(0)
        return display
    }

    // ── Helper ──────────────────────────────────────────────────────────────────

    /**
     * Build a throw-away NMS TextDisplay with all HUD properties set.
     * Used for both spawning and full-state metadata updates.
     *
     * @param textJson  JSON-serialised Adventure Component (rich text)
     */
    private fun buildHudDisplay(
        level: net.minecraft.server.level.ServerLevel,
        textJson: String, bgColor: Int,
        tx: Float, ty: Float, tz: Float,
        yaw: Float, lineWidth: Int
    ): TextDisplay {
        val display = TextDisplay(EntityType.TEXT_DISPLAY, level)
        display.setBillboardConstraints(Display.BillboardConstraints.FIXED)
        display.setShadowRadius(0f)
        display.setShadowStrength(0f)
        display.setViewRange(32f)

        val nmsText = try {
            Component.Serializer.fromJson(textJson, level.registryAccess())
        } catch (_: Exception) { null } ?: Component.literal("?")
        display.setText(nmsText)
        display.setTextOpacity((-1).toByte()) // 0xFF = fully opaque
        display.entityData.set(TextDisplay.DATA_LINE_WIDTH_ID, lineWidth)
        display.entityData.set(TextDisplay.DATA_BACKGROUND_COLOR_ID, bgColor)

        // Rotate the translation offset by the yaw so buttons orbit the mannequin,
        // then also rotate the text content so it faces the viewer.
        val rotQ = Quaternionf().rotationY(yaw)
        val rotatedTranslation = Vector3f(tx, ty, tz).also { rotQ.transform(it) }

        display.setTransformation(
            com.mojang.math.Transformation(
                rotatedTranslation,
                rotQ,
                Vector3f(1f, 1f, 1f),
                Quaternionf()
            )
        )
        display.setTransformationInterpolationDelay(0)
        display.setTransformationInterpolationDuration(0)
        return display
    }
}
