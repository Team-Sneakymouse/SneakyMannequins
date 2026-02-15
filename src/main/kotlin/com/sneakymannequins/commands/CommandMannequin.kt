package com.sneakymannequins.commands

import com.sneakymannequins.SneakyMannequins
import com.sneakymannequins.managers.LayerManager
import com.sneakymannequins.managers.MannequinManager
import com.sneakymannequins.util.TextUtility
import io.papermc.paper.command.brigadier.CommandSourceStack
import org.bukkit.entity.Player
import org.bukkit.command.CommandSender

class CommandMannequin(
    private val plugin: SneakyMannequins,
    private val mannequinManager: MannequinManager,
    private val layerManager: LayerManager
) : CommandBase("mannequin") {

    override fun handle(stack: CommandSourceStack, args: Array<out String>) {
        // Allow console for reload and remask; other actions require a player
        when (args.firstOrNull()?.lowercase()) {
            "reload" -> { handleReload(stack.sender); return }
            "remask" -> { handleRemask(stack.sender, args); return }
        }

        val player = stack.sender as? Player ?: run {
            stack.sender.sendMessage("You must be a player to use this command")
            return
        }
        when (args.firstOrNull()?.lowercase()) {
            "remove" -> removeNearest(player)
            else -> create(player)
        }
    }

    override fun suggest(stack: CommandSourceStack, args: Array<out String>): MutableList<String> {
        return when (args.size) {
            0 -> mutableListOf("remove", "reload", "remask")
            1 -> listOf("remove", "reload", "remask")
                .filter { it.startsWith(args[0], ignoreCase = true) }.toMutableList()
            2 -> when (args[0].lowercase()) {
                "remask" -> LayerManager.STRATEGY_NAMES
                    .filter { it.startsWith(args[1], ignoreCase = true) }.toMutableList()
                else -> mutableListOf()
            }
            3 -> if (args[0].equals("remask", true)) {
                layerManager.definitionsInOrder().map { it.id }
                    .filter { it.startsWith(args[2], ignoreCase = true) }.toMutableList()
            } else mutableListOf()
            4 -> if (args[0].equals("remask", true)) {
                val layerId = args[2].lowercase()
                layerManager.optionsFor(layerId).map { it.id }
                    .filter { it.startsWith(args[3], ignoreCase = true) }.toMutableList()
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

    private fun handleReload(sender: CommandSender) {
        try {
            plugin.reloadPlugin()
            sender.sendMessage(TextUtility.convertToComponent("&aSneakyMannequins reloaded."))
        } catch (e: Exception) {
            sender.sendMessage(TextUtility.convertToComponent("&cReload failed: ${e.message}"))
            plugin.logger.severe("Reload failed: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun handleRemask(sender: CommandSender, args: Array<out String>) {
        // /mannequin remask <strategy> <layer> <part>
        if (args.size < 4) {
            sender.sendMessage(TextUtility.convertToComponent(
                "&cUsage: /mannequin remask <${LayerManager.STRATEGY_NAMES.joinToString("|")}> <layer> <part>"
            ))
            return
        }
        val strategyName = args[1].uppercase()
        val strategy = try {
            LayerManager.MaskStrategy.valueOf(strategyName)
        } catch (_: Exception) {
            sender.sendMessage(TextUtility.convertToComponent(
                "&cUnknown strategy '$strategyName'. Available: ${LayerManager.STRATEGY_NAMES.joinToString(", ")}"
            ))
            return
        }
        val layerId = args[2].lowercase()
        val partId = args[3].lowercase()

        sender.sendMessage(TextUtility.convertToComponent("&7Remasking '$partId' in '$layerId' with ${strategy.name}..."))
        try {
            val result = layerManager.remask(strategy, layerId, partId)
            sender.sendMessage(TextUtility.convertToComponent("&a$result"))
        } catch (e: Exception) {
            sender.sendMessage(TextUtility.convertToComponent("&cRemask failed: ${e.message}"))
            plugin.logger.severe("Remask failed: ${e.message}")
            e.printStackTrace()
        }
    }
}
