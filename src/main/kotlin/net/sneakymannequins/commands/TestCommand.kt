package net.sneakymannequins.commands

import net.sneakymannequins.SneakyMannequins
import org.bukkit.command.CommandSender
import org.bukkit.command.defaults.BukkitCommand
import org.bukkit.entity.Player
import java.util.UUID

class TestCommand : BukkitCommand("testmannequin") {
    init {
        description = "Spawns a test mannequin"
        usageMessage = "/testmannequin"
        permission = "${SneakyMannequins.IDENTIFIER}.test"
    }

    override fun execute(sender: CommandSender, commandLabel: String, args: Array<String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage("This command can only be used by players")
            return true
        }

        // Create a test fake player at the sender's location
        val testName = "TestMannequin"
        
        SneakyMannequins.getVolatileCodeManager().spawnFakePlayer(
            sender,
            setOf(sender)
        )
        
        sender.sendMessage("Spawned test mannequin with name $testName")
        return true
    }
} 