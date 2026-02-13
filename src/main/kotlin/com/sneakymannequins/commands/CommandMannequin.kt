package com.sneakymannequins.commands

import com.sneakymannequins.managers.MannequinManager
import com.sneakymannequins.util.TextUtility
import io.papermc.paper.command.brigadier.CommandSourceStack
import org.bukkit.entity.Player

class CommandMannequin(
    private val mannequinManager: MannequinManager
) : CommandBase("mannequin") {

    override fun handle(stack: CommandSourceStack, args: Array<out String>) {
		val player = stack.sender as? Player ?: run {
			stack.sender.sendMessage("You must be a player to use this command")
			return
		}
		
		try {
			val location = player.location.clone()
            val mannequin = mannequinManager.create(location)
            val pixelCount = mannequinManager.render(mannequin, listOf(player))
            player.sendMessage(TextUtility.convertToComponent("&aMannequin created at (${location.blockX}, ${location.blockY}, ${location.blockZ}) with id ${mannequin.id} &7[$pixelCount pixels]"))
			
		} catch (e: Exception) {
			stack.sender.sendMessage(TextUtility.convertToComponent("&cFailed to create mannequin: ${e.message}"))
			e.printStackTrace()
		}
    }

}