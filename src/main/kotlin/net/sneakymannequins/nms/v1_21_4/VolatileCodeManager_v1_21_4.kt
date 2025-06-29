package net.sneakymannequins.nms.v1_21_4

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
import net.minecraft.world.entity.HumanoidArm
import net.minecraft.core.BlockPos
import net.sneakymannequins.nms.VolatileCodeManager
import org.bukkit.Location
import org.bukkit.craftbukkit.CraftWorld
import org.bukkit.craftbukkit.entity.CraftPlayer
import org.bukkit.entity.Player
import java.util.EnumSet
import java.util.UUID

class VolatileCodeManager_v1_21_4 : VolatileCodeManager() {
    override fun spawnFakePlayer(player: Player, viewers: Set<Player>): Int {
		val entityPlayer = (player as CraftPlayer).handle
		val uuid = UUID.randomUUID()
		val location = player.location
        val server = (location.world as CraftWorld).handle.server
        val level = (location.world as CraftWorld).handle
        val gameProfile = GameProfile(uuid, player.name)
        
        // Create the fake player with client information
        val fakePlayer = ServerPlayer(
            server,
            level,
            gameProfile,
            entityPlayer.clientInformation()  // Add the client information here
        )
        
        // Set the player's position
        fakePlayer.setPos(location.x, location.y, location.z)
        fakePlayer.setRot(location.yaw, location.pitch)
        
        // Send packets to viewers
        viewers.forEach { viewer ->
            val connection = (viewer as CraftPlayer).handle.connection
			fakePlayer.connection = connection
            
            // Send add player packet
            connection.send(ClientboundPlayerInfoUpdatePacket(
                ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER,
                fakePlayer
            ))
            
            // Send spawn packet
            connection.send(ClientboundAddEntityPacket(fakePlayer, 0, BlockPos(location.x.toInt(), location.y.toInt(), location.z.toInt())))
            
            // Send metadata if not empty
            val metadata = fakePlayer.entityData.nonDefaultValues
            if (metadata != null && !metadata.isEmpty()) {
                connection.send(ClientboundSetEntityDataPacket(fakePlayer.id, metadata))
            }
        }

		return fakePlayer.id
    }

    override fun removeFakePlayer(id: Int, viewers: Set<Player>) {
        viewers.forEach { viewer ->
            val connection = (viewer as? CraftPlayer)?.handle?.connection ?: return@forEach
            
            // Remove the player entity
            connection.connection.send(ClientboundRemoveEntitiesPacket(id))
        }
    }

    override fun updateFakePlayerLocation(id: Int, location: Location, viewers: Set<Player>) {
        // Update the position

        // Send update packet to viewers
        viewers.forEach { viewer ->
            val connection = (viewer as? CraftPlayer)?.handle?.connection ?: return@forEach
            
            // Create Vec3 objects for position and movement
            val position = Vec3(location.x, location.y, location.z)
            val deltaMovement = Vec3.ZERO
            
            // Create PositionMoveRotation object with correct types
            val positionMove = net.minecraft.world.entity.PositionMoveRotation(
                position,
                deltaMovement,
                location.yaw,
                location.pitch
            )
            
            // Create packet with new format
            connection.connection.send(ClientboundTeleportEntityPacket(
                id,
                positionMove,
                mutableSetOf(), // No relative movement flags
                false
            ))
        }
    }
} 