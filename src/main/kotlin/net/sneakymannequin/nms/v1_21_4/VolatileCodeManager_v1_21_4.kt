package net.sneakymannequin.nms.v1_21_4

import com.mojang.authlib.GameProfile
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.network.ServerGamePacketListenerImpl
import net.minecraft.world.entity.EntityType
import net.minecraft.world.level.GameType
import net.minecraft.world.phys.Vec3
import net.sneakymannequin.nms.VolatileCodeManager
import org.bukkit.Location
import org.bukkit.craftbukkit.CraftWorld
import org.bukkit.craftbukkit.entity.CraftPlayer
import org.bukkit.entity.Player
import java.util.EnumSet
import java.util.UUID

class VolatileCodeManager_v1_21_4 : VolatileCodeManager() {
    private val fakePlayerMap = mutableMapOf<UUID, ServerPlayer>()

    override fun spawnFakePlayer(location: Location, name: String, uuid: UUID, viewers: Set<Player>) {
        val world = (location.world as? CraftWorld)?.handle ?: run {
            throw IllegalArgumentException("Location world cannot be null and must be a CraftWorld")
        }
        val server = MinecraftServer.getServer()
        
        // Create game profile for the fake player
        val gameProfile = GameProfile(uuid, name)
        
        // Create the fake player entity
        val fakePlayer = ServerPlayer(
            server,
            world as ServerLevel,
            gameProfile,
            null // No client connection needed for fake players
        ).apply {
            // Set the player's position
            setPos(location.x, location.y, location.z)
            setYRot(location.yaw)
            setXRot(location.pitch)
            setGameMode(GameType.CREATIVE)
        }

        // Store the fake player for later use
        fakePlayerMap[uuid] = fakePlayer

        // Send packets to all viewers
        viewers.forEach { viewer ->
            val connection = (viewer as? CraftPlayer)?.handle?.connection ?: return@forEach
            
            // Add player info
            val actions = EnumSet.of(
                ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER,
                ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED,
                ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LATENCY,
                ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE,
                ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME
            )
            
            connection.connection.send(ClientboundPlayerInfoUpdatePacket(actions, listOf(fakePlayer)))

            // Spawn the player entity
            connection.connection.send(ClientboundAddEntityPacket(
                fakePlayer.id,
                fakePlayer.uuid,
                fakePlayer.x,
                fakePlayer.y,
                fakePlayer.z,
                fakePlayer.xRot,
                fakePlayer.yRot,
                EntityType.PLAYER,
                0,
                Vec3.ZERO,
                fakePlayer.yRot.toDouble()
            ))

            // Send metadata
            val metadata = fakePlayer.entityData.nonDefaultValues ?: emptyList()
            if (metadata.isNotEmpty()) {
                connection.connection.send(ClientboundSetEntityDataPacket(fakePlayer.id, metadata))
            }
        }
    }

    override fun removeFakePlayer(uuid: UUID, viewers: Set<Player>) {
        val fakePlayer = fakePlayerMap[uuid] ?: return
        
        viewers.forEach { viewer ->
            val connection = (viewer as? CraftPlayer)?.handle?.connection ?: return@forEach
            
            // Remove the player entity
            connection.connection.send(ClientboundRemoveEntitiesPacket(fakePlayer.id))
            
            // Remove player info
            connection.connection.send(ClientboundPlayerInfoRemovePacket(listOf(fakePlayer.uuid)))
        }

        fakePlayerMap.remove(uuid)
    }

    override fun updateFakePlayerLocation(uuid: UUID, location: Location, viewers: Set<Player>) {
        val fakePlayer = fakePlayerMap[uuid] ?: return
        
        // Update the position
        fakePlayer.apply {
            setPos(location.x, location.y, location.z)
            setYRot(location.yaw)
            setXRot(location.pitch)
        }

        // Send update packet to viewers
        viewers.forEach { viewer ->
            val connection = (viewer as? CraftPlayer)?.handle?.connection ?: return@forEach
            
            // Create Vec3 objects for position and movement
            val position = Vec3(fakePlayer.x, fakePlayer.y, fakePlayer.z)
            val deltaMovement = Vec3.ZERO
            
            // Create PositionMoveRotation object with correct types
            val positionMove = net.minecraft.world.entity.PositionMoveRotation(
                position,
                deltaMovement,
                fakePlayer.yRot,
                fakePlayer.xRot
            )
            
            // Create packet with new format
            connection.connection.send(ClientboundTeleportEntityPacket(
                fakePlayer.id,
                positionMove,
                emptySet(), // No relative movement flags
                fakePlayer.onGround
            ))
        }
    }

    override fun updateFakePlayerLayers(uuid: UUID, viewers: Set<Player>, layers: Map<String, Any>) {
        val fakePlayer = fakePlayerMap[uuid] ?: return
        
        // TODO: Implement layer updates using PlayerDataContainer when we implement the layer system
        // For now this is a placeholder that will be implemented when we add the layer system
        
        viewers.forEach { viewer ->
            val connection = (viewer as? CraftPlayer)?.handle?.connection ?: return@forEach
            val metadata = fakePlayer.entityData.nonDefaultValues ?: emptyList()
            if (metadata.isNotEmpty()) {
                connection.connection.send(ClientboundSetEntityDataPacket(fakePlayer.id, metadata))
            }
        }
    }
} 