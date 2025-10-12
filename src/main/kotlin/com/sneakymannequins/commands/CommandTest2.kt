package com.sneakymannequins.commands

import org.bukkit.entity.Player
import org.bukkit.command.CommandSender
import org.bukkit.profile.PlayerTextures
import java.net.URL

public class CommandTest2 : CommandBase("test2") {

	override fun execute(sender: CommandSender, label: String, args: Array<String>): Boolean {
		val player = sender as? Player ?: run {
			sender.sendMessage("You must be a player to use this command")
			return true
		}
		val playerProfile = player.playerProfile
		playerProfile.textures.setSkin(URL("http://textures.minecraft.net/texture/dbb0fe08a42ebefe8e8f84cb231f4fe62bc9013ce958877b2738f252c721f393"), PlayerTextures.SkinModel.CLASSIC)
		player.playerProfile = playerProfile
		return true
	}

}