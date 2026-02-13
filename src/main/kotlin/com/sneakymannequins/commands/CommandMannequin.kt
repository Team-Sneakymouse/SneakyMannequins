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
        when (args.firstOrNull()?.lowercase()) {
            "remove" -> removeNearest(player)
            "controls" -> {
                if (args.getOrNull(1)?.lowercase() == "remove") {
                    removeNearestControl(player)
                } else {
                    addControls(player)
                }
            }
            else -> create(player)
        }
    }

    override fun suggest(stack: CommandSourceStack, args: Array<out String>): MutableList<String> {
        return when (args.size) {
            0 -> mutableListOf("remove", "controls")
            1 -> listOf("remove", "controls").filter { it.startsWith(args[0], ignoreCase = true) }.toMutableList()
            2 -> if (args[0].equals("controls", true)) {
                listOf("remove").filter { it.startsWith(args[1], ignoreCase = true) }.toMutableList()
            } else mutableListOf()
            else -> mutableListOf()
        }
    }

    private fun create(player: Player) {
        try {
            val location = player.location.clone()
            val mannequin = mannequinManager.create(location)
            val pixelCount = mannequinManager.render(mannequin, listOf(player))
            player.sendMessage(TextUtility.convertToComponent("&aMannequin created at (${location.blockX}, ${location.blockY}, ${location.blockZ}) with id ${mannequin.id} &7[$pixelCount pixels]"))
        } catch (e: Exception) {
            player.sendMessage(TextUtility.convertToComponent("&cFailed to create mannequin: ${e.message}"))
            e.printStackTrace()
        }
    }

    private fun removeNearest(player: Player) {
        val man = mannequinManager.nearestMannequin(player.location) ?: run {
            player.sendMessage(TextUtility.convertToComponent("&cNo mannequin nearby."))
            return
        }
        mannequinManager.remove(man.id, player.server.onlinePlayers)
        player.sendMessage(TextUtility.convertToComponent("&aRemoved mannequin ${man.id}"))
    }

    private fun addControls(player: Player) {
        val man = mannequinManager.nearestMannequin(player.location) ?: run {
            player.sendMessage(TextUtility.convertToComponent("&cNo mannequin nearby."))
            return
        }
        val controlLoc = player.location.clone()
        mannequinManager.addControls(man, controlLoc)
        player.sendMessage(TextUtility.convertToComponent("&aAdded controls for mannequin ${man.id}"))
    }

    private fun removeNearestControl(player: Player) {
        val removed = mannequinManager.removeNearestControl(player.location)
        if (removed) {
            player.sendMessage(TextUtility.convertToComponent("&aRemoved nearest control."))
        } else {
            player.sendMessage(TextUtility.convertToComponent("&cNo control nearby."))
        }
    }
}