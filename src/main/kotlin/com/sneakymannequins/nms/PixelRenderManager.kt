package com.sneakymannequins.nms

import com.sneakymannequins.render.ProjectedPixel
import java.util.UUID
import org.bukkit.entity.Player

/**
 * Version-agnostic bookkeeping for per-viewer mannequin pixel entities. The caller supplies
 * version-specific spawn/remove lambdas.
 */
class PixelRenderManager {

    // viewerId -> mannequinId -> pixelIndex -> entityId
    private val viewerEntities = mutableMapOf<UUID, MutableMap<UUID, MutableMap<Int, Int>>>()

    fun handleProjectedPixels(
            viewer: Player,
            mannequinId: UUID,
            projected: Collection<ProjectedPixel>,
            spawn: (ProjectedPixel, Int) -> Unit,
            remove: (ProjectedPixel, Int) -> Unit,
            allocateId: () -> Int
    ) {
        if (projected.isEmpty()) return
        val perMannequin = viewerEntities.computeIfAbsent(viewer.uniqueId) { mutableMapOf() }
        val pixels = perMannequin.computeIfAbsent(mannequinId) { mutableMapOf() }

        projected.forEach { proj ->
            val key = proj.index
            val existing = pixels[key]
            if (!proj.visible) {
                existing?.let { remove(proj, it) }
                pixels.remove(key)
                return@forEach
            }

            // Replace existing entity to keep logic simple
            existing?.let { remove(proj, it) }

            val entityId = allocateId()
            spawn(proj, entityId)
            pixels[key] = entityId
        }
    }

    fun destroyMannequin(viewer: Player, mannequinId: UUID, remove: (IntArray) -> Unit) {
        val perMannequin = viewerEntities[viewer.uniqueId] ?: return
        val pixels = perMannequin.remove(mannequinId) ?: return
        if (pixels.isNotEmpty()) {
            remove(pixels.values.toIntArray())
        }
    }
}
