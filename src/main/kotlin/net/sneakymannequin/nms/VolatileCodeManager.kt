package net.sneakymannequin.nms

import org.bukkit.Location
import org.bukkit.entity.Player
import java.util.UUID

/**
 * Abstract base class for version-specific NMS code implementations.
 * This class provides the interface for spawning and managing fake players (mannequins)
 * across different Minecraft versions.
 */
abstract class VolatileCodeManager {
    /**
     * Spawns a fake player at the specified location
     * @param location The location to spawn the fake player
     * @param name The name of the fake player
     * @param uuid The UUID of the fake player
     * @param viewers The players who should see this fake player
     */
    abstract fun spawnFakePlayer(location: Location, name: String, uuid: UUID, viewers: Set<Player>)

    /**
     * Removes a fake player from the world
     * @param uuid The UUID of the fake player to remove
     * @param viewers The players who should no longer see this fake player
     */
    abstract fun removeFakePlayer(uuid: UUID, viewers: Set<Player>)

    /**
     * Updates the location of a fake player
     * @param uuid The UUID of the fake player
     * @param location The new location
     * @param viewers The players who should see this update
     */
    abstract fun updateFakePlayerLocation(uuid: UUID, location: Location, viewers: Set<Player>)

    /**
     * Updates the skin layers of a fake player
     * @param uuid The UUID of the fake player
     * @param viewers The players who should see this update
     * @param layers The skin layers to update (implementation specific)
     */
    abstract fun updateFakePlayerLayers(uuid: UUID, viewers: Set<Player>, layers: Map<String, Any>)
} 