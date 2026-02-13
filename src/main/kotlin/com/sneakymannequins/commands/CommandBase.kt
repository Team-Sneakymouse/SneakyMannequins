package com.sneakymannequins.commands

import com.sneakymannequins.SneakyMannequins
import io.papermc.paper.command.brigadier.BasicCommand
import io.papermc.paper.command.brigadier.CommandSourceStack

/**
 * Base class for Paper Brigadier-style commands registered via JavaPlugin#registerCommand.
 */
abstract class CommandBase(
    private val name: String
) : BasicCommand {

    private val permissionNode = "${SneakyMannequins.IDENTIFIER}.command.$name"

    override fun permission(): String = permissionNode

    override fun canUse(sender: org.bukkit.command.CommandSender): Boolean {
        return sender.hasPermission(permissionNode)
    }

    final override fun execute(stack: CommandSourceStack, args: Array<out String>) {
        handle(stack, args)
    }

    protected abstract fun handle(stack: CommandSourceStack, args: Array<out String>)
}