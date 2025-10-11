package com.sneakymannequins.commands

import com.sneakymannequins.SneakyMannequins
import com.sneakymannequins.managers.MannequinData
import com.sneakymannequins.managers.SkinData
import com.sneakymannequins.util.TextUtility
import org.bukkit.command.CommandSender
import org.bukkit.command.Command
import org.bukkit.entity.Player
import org.bukkit.entity.EntityType
import org.bukkit.entity.Mannequin
import org.bukkit.profile.PlayerTextures

class CommandMannequin : CommandBase("mannequin") {
	
    override fun execute(sender: CommandSender, label: String, args: Array<String>): Boolean {
		val player = sender as? Player ?: run {
			sender.sendMessage("You must be a player to use this command")
			return true
		}
		
		try {
			// Get player's location
			val location = player.location.clone()
			
			// Create MannequinData first (with a temporary entity reference)
			val mannequinData = MannequinData(
				location = location
			)
			
			player.sendMessage(TextUtility.convertToComponent("&aMannequin created and skin applied!"))
			
		} catch (e: Exception) {
			player.sendMessage(TextUtility.convertToComponent("&cFailed to create mannequin: ${e.message}"))
			e.printStackTrace()
		}
		
        return true
    }

}