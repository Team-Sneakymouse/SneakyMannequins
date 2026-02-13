package com.sneakymannequins.nms

import com.sneakymannequins.model.PixelChange
import org.bukkit.Location
import org.bukkit.entity.Player
import java.util.UUID

/**
 * Contract for version-specific NMS packet handling.
 */
interface VolatileHandler {
    val targetMinecraftVersion: String

    fun applyPixelChanges(
        viewer: Player,
        mannequinId: UUID,
        origin: Location,
        changes: Collection<PixelChange>
    )

    fun destroyMannequin(viewer: Player, mannequinId: UUID)
}

