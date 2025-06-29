package net.sneakymannequins.nms

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
     * @param player The player to spawn the fake player from
     * @param viewers The players who should see this fake player
	 * 
	 * @return The id of the fake player
     */
    abstract fun spawnFakePlayer(player: Player, viewers: Set<Player>): Int

    /**
     * Removes a fake player from the world
     * @param id The id of the fake player to remove
     * @param viewers The players who should no longer see this fake player
     */
    abstract fun removeFakePlayer(id: Int, viewers: Set<Player>)

    /**
     * Updates the location of a fake player
     * @param id The id of the fake player
     * @param location The new location
     * @param viewers The players who should see this update
     */
    abstract fun updateFakePlayerLocation(id: Int, location: Location, viewers: Set<Player>)
} 