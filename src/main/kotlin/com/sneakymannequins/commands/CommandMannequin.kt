package com.sneakymannequins.commands

import com.sneakymannequins.SneakyMannequins
import com.sneakymannequins.managers.LayerManager
import com.sneakymannequins.managers.MannequinManager
import com.sneakymannequins.managers.SessionManager
import com.sneakymouse.sneakyholos.util.TextUtility
import io.papermc.paper.command.brigadier.CommandSourceStack
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.profile.PlayerTextures.SkinModel

class CommandMannequin(
        private val plugin: SneakyMannequins,
        private val mannequinManager: MannequinManager,
        private val layerManager: LayerManager,
        private val sessionManager: SessionManager
) : CommandBase("mannequin") {

        companion object {
                private const val HISTORY_PAGE_SIZE = 10
        }

        override fun handle(stack: CommandSourceStack, args: Array<out String>) {
                when (args.firstOrNull()?.lowercase()) {
                        "reload" -> {
                                handleReload(stack.sender)
                                return
                        }
                        "remask" -> {
                                handleRemask(stack.sender, args)
                                return
                        }
                        "history" -> {
                                handleHistory(stack, args)
                                return
                        }
                        "template" -> {
                                handleTemplate(stack, args)
                                return
                        }
                        "merge" -> {
                                handleMerge(stack, args)
                                return
                        }
                }

                val player =
                        stack.sender as? Player
                                ?: run {
                                        stack.sender.sendMessage(
                                                "You must be a player to use this command"
                                        )
                                        return
                                }
                when (args.firstOrNull()?.lowercase()) {
                        "remove" -> removeNearest(player)
                        else -> create(player)
                }
        }

        override fun suggest(
                stack: CommandSourceStack,
                args: Array<out String>
        ): MutableList<String> {
                return when (args.size) {
                        0 -> mutableListOf("remove", "reload", "remask", "history", "template")
                        1 ->
                                listOf("remove", "reload", "remask", "history", "template", "merge")
                                        .filter { it.startsWith(args[0], ignoreCase = true) }
                                        .toMutableList()
                        2 ->
                                when (args[0].lowercase()) {
                                        "remask" ->
                                                layerManager
                                                        .definitionsInOrder()
                                                        .map { it.id }
                                                        .filter {
                                                                it.startsWith(
                                                                        args[1],
                                                                        ignoreCase = true
                                                                )
                                                        }
                                                        .toMutableList()
                                        "template", "merge" ->
                                                sessionManager
                                                        .listSessionUids()
                                                        .filter {
                                                                it.startsWith(
                                                                        args[1],
                                                                        ignoreCase = true
                                                                )
                                                        }
                                                        .toMutableList()
                                        else -> mutableListOf()
                                }
                        3 ->
                                when (args[0].lowercase()) {
                                        "remask" -> {
                                                val layerId = args[1].lowercase()
                                                // The following lines seem to be from a different
                                                // context and introduce undefined variables.
                                                // Applying them literally would cause a compilation
                                                // error.
                                                // Keeping the original logic for remask suggestions
                                                // for args.size == 3.
                                                layerManager
                                                        .optionsFor(layerId)
                                                        .map { it.id }
                                                        .filter {
                                                                it.startsWith(
                                                                        args[2],
                                                                        ignoreCase = true
                                                                )
                                                        }
                                                        .toMutableList()
                                        }
                                        "template" ->
                                                sessionManager
                                                        .listTemplateNames()
                                                        .filter {
                                                                it.startsWith(
                                                                        args[2],
                                                                        ignoreCase = true
                                                                )
                                                        }
                                                        .toMutableList()
                                        else -> mutableListOf()
                                }
                        4 ->
                                when (args[0].lowercase()) {
                                        "remask" ->
                                                LayerManager.STRATEGY_NAMES
                                                        .filter {
                                                                it.startsWith(
                                                                        args[3],
                                                                        ignoreCase = true
                                                                )
                                                        }
                                                        .toMutableList()
                                        "template" ->
                                                (layerManager.definitionsInOrder().map { it.id } +
                                                                listOf("body_type"))
                                                        .filter {
                                                                it.startsWith(
                                                                        args[3],
                                                                        ignoreCase = true
                                                                )
                                                        }
                                                        .toMutableList()
                                        else -> mutableListOf()
                                }
                        5 ->
                                when (args[0].lowercase()) {
                                        "remask" ->
                                                (1..8)
                                                        .map { it.toString() }
                                                        .filter {
                                                                it.startsWith(
                                                                        args[4],
                                                                        ignoreCase = true
                                                                )
                                                        }
                                                        .toMutableList()
                                        "template" ->
                                                (layerManager.definitionsInOrder().map { it.id } +
                                                                listOf("body_type"))
                                                        .filter {
                                                                it.startsWith(
                                                                        args[4],
                                                                        ignoreCase = true
                                                                )
                                                        }
                                                        .toMutableList()
                                        else -> mutableListOf()
                                }
                        else ->
                                when (args[0].lowercase()) {
                                        "template" ->
                                                (layerManager.definitionsInOrder().map { it.id } +
                                                                listOf("body_type"))
                                                        .filter {
                                                                it.startsWith(
                                                                        args.last(),
                                                                        ignoreCase = true
                                                                )
                                                        }
                                                        .toMutableList()
                                        else -> mutableListOf()
                                }
                }
        }

        private fun create(player: Player) {
                try {
                        val location = player.location.clone()
                        val mannequin = mannequinManager.create(location)
                        player.sendMessage(
                                TextUtility.convertToComponent(
                                        "&aMannequin created at (${location.blockX}, ${location.blockY}, ${location.blockZ}) with id ${mannequin.id}"
                                )
                        )
                } catch (e: Exception) {
                        player.sendMessage(
                                TextUtility.convertToComponent(
                                        "&cFailed to create mannequin: ${e.message}"
                                )
                        )
                        e.printStackTrace()
                }
        }

        private fun removeNearest(player: Player) {
                val man =
                        mannequinManager.nearestMannequin(player.location)
                                ?: run {
                                        player.sendMessage(
                                                TextUtility.convertToComponent(
                                                        "&cNo mannequin nearby."
                                                )
                                        )
                                        return
                                }
                mannequinManager.remove(man.id, player.server.onlinePlayers)
                player.sendMessage(TextUtility.convertToComponent("&aRemoved mannequin ${man.id}"))
        }

        private fun handleReload(sender: CommandSender) {
                try {
                        plugin.reloadPlugin()
                        sender.sendMessage(
                                TextUtility.convertToComponent("&aSneakyMannequins reloaded.")
                        )
                } catch (e: Exception) {
                        sender.sendMessage(
                                TextUtility.convertToComponent("&cReload failed: ${e.message}")
                        )
                        plugin.logger.severe("Reload failed: ${e.message}")
                        e.printStackTrace()
                }
        }

        private fun handleRemask(sender: CommandSender, args: Array<out String>) {
                // /mannequin remask <layer> <part> [strategy] [channels]
                if (args.size < 3) {
                        sender.sendMessage(
                                TextUtility.convertToComponent(
                                        "&cUsage: /mannequin remask <layer> <part> [${LayerManager.STRATEGY_NAMES.joinToString("|")}] [channels]"
                                )
                        )
                        return
                }
                val layerId = args[1].lowercase()
                val partId = args[2].lowercase()
                val strategyName = (if (args.size >= 4) args[3] else null)?.uppercase()
                val strategy =
                        if (strategyName != null) {
                                try {
                                        LayerManager.MaskStrategy.valueOf(strategyName)
                                } catch (_: Exception) {
                                        sender.sendMessage(
                                                TextUtility.convertToComponent(
                                                        "&cUnknown strategy '$strategyName'. Available: ${LayerManager.STRATEGY_NAMES.joinToString(", ")}"
                                                )
                                        )
                                        return
                                }
                        } else null

                val channelsArg: Int? =
                        if (args.size >= 5) {
                                val n = args[4].toIntOrNull()
                                if (n == null || n < 1 || n > 8) {
                                        sender.sendMessage(
                                                TextUtility.convertToComponent(
                                                        "&cChannels must be a number between 1 and 8."
                                                )
                                        )
                                        return
                                }
                                n
                        } else null

                val label = strategy?.name ?: "default"
                val channelsLabel = channelsArg?.toString() ?: "default"
                sender.sendMessage(
                        TextUtility.convertToComponent(
                                "&7Remasking '$partId' in '$layerId' with $label strategy, $channelsLabel channels..."
                        )
                )
                try {
                        val result =
                                layerManager.remask(
                                        strategy = strategy,
                                        layerId = layerId,
                                        partId = partId,
                                        channels = channelsArg
                                )
                        sender.sendMessage(TextUtility.convertToComponent("&a$result"))
                } catch (e: Exception) {
                        sender.sendMessage(
                                TextUtility.convertToComponent("&cRemask failed: ${e.message}")
                        )
                        plugin.logger.severe("Remask failed: ${e.message}")
                        e.printStackTrace()
                }
        }

        private fun handleHistory(stack: CommandSourceStack, args: Array<out String>) {
                val player =
                        stack.sender as? Player
                                ?: run {
                                        stack.sender.sendMessage(
                                                "You must be a player to use this command"
                                        )
                                        return
                                }
                val page = if (args.size >= 2) (args[1].toIntOrNull() ?: 1) else 1
                val sessions = sessionManager.history(player.uniqueId)
                if (sessions.isEmpty()) {
                        player.sendMessage(
                                TextUtility.convertToComponent("&7No saved sessions found.")
                        )
                        return
                }

                val totalPages = (sessions.size + HISTORY_PAGE_SIZE - 1) / HISTORY_PAGE_SIZE
                val safePage = page.coerceIn(1, totalPages)
                val startIdx = (safePage - 1) * HISTORY_PAGE_SIZE
                val pageItems =
                        sessions.subList(
                                startIdx,
                                (startIdx + HISTORY_PAGE_SIZE).coerceAtMost(sessions.size)
                        )

                player.sendMessage(
                        TextUtility.convertToComponent(
                                "&6&lSession History &7(page $safePage/$totalPages)"
                        )
                )
                for (session in pageItems) {
                        val date = session.createdAt.substringBefore("T")
                        val layerCount = session.layers.size
                        val entry =
                                Component.text("  ")
                                        .append(
                                                Component.text(session.uid)
                                                        .color(NamedTextColor.YELLOW)
                                                        .clickEvent(
                                                                ClickEvent.copyToClipboard(
                                                                        session.uid
                                                                )
                                                        )
                                                        .hoverEvent(
                                                                HoverEvent.showText(
                                                                        Component.text(
                                                                                "Click to copy UID"
                                                                        )
                                                                )
                                                        )
                                        )
                                        .append(Component.text(" | ").color(NamedTextColor.GRAY))
                                        .append(Component.text(date).color(NamedTextColor.WHITE))
                                        .append(Component.text(" | ").color(NamedTextColor.GRAY))
                                        .append(
                                                Component.text("$layerCount layers")
                                                        .color(NamedTextColor.AQUA)
                                        )
                                        .let { base ->
                                                val charName = session.characterName
                                                if (!charName.isNullOrBlank()) {
                                                        base.append(
                                                                        Component.text(" | ")
                                                                                .color(
                                                                                        NamedTextColor
                                                                                                .GRAY
                                                                                )
                                                                )
                                                                .append(
                                                                        Component.text(charName)
                                                                                .color(
                                                                                        NamedTextColor
                                                                                                .LIGHT_PURPLE
                                                                                )
                                                                )
                                                } else {
                                                        base
                                                }
                                        }
                        player.sendMessage(entry)
                }
                if (totalPages > 1) {
                        player.sendMessage(
                                TextUtility.convertToComponent(
                                        "&7Use /mannequin history <page> to navigate."
                                )
                        )
                }
        }

        private fun handleTemplate(stack: CommandSourceStack, args: Array<out String>) {
                val player =
                        stack.sender as? Player
                                ?: run {
                                        stack.sender.sendMessage(
                                                "You must be a player to use this command"
                                        )
                                        return
                                }
                // /mannequin template <uid> <name> [layer1 layer2 ...]
                if (args.size < 3) {
                        player.sendMessage(
                                TextUtility.convertToComponent(
                                        "&cUsage: /mannequin template <uid> <name> [layer1 layer2 ...]"
                                )
                        )
                        return
                }
                val uid = args[1]
                val name = args[2]
                val layerIds =
                        if (args.size > 3) args.drop(3).map { it.lowercase() } else emptyList()

                val error = sessionManager.createTemplate(uid, name, layerIds, player)
                if (error != null) {
                        player.sendMessage(TextUtility.convertToComponent("&c$error"))
                } else {
                        val template = sessionManager.load(name)
                        val modelType =
                                when (template?.slimModel) {
                                        true -> "slim"
                                        false -> "classic"
                                        null -> "not specified"
                                }
                        val scopeMsg =
                                if (layerIds.isNotEmpty())
                                        " (${layerIds.filterNot { it.equals("body_type", ignoreCase = true) }.joinToString(", ")})"
                                else ""
                        player.sendMessage(
                                TextUtility.convertToComponent(
                                        "&aTemplate &7'${name.lowercase()}'&a created from &7$uid&a (Body type: &e$modelType&a)$scopeMsg"
                                )
                        )
                }
        }

        private fun handleMerge(stack: CommandSourceStack, args: Array<out String>) {
                val player =
                        stack.sender as? Player
                                ?: run {
                                        stack.sender.sendMessage(
                                                "You must be a player to use this command"
                                        )
                                        return
                                }
                if (args.size < 2) {
                        player.sendMessage(
                                TextUtility.convertToComponent(
                                        "&cUsage: /mannequin merge <uid/template>"
                                )
                        )
                        return
                }

                val man =
                        mannequinManager.nearestMannequin(player.location)
                                ?: run {
                                        player.sendMessage(
                                                TextUtility.convertToComponent(
                                                        "&cNo mannequin nearby."
                                                )
                                        )
                                        return
                                }

                val session1 =
                        sessionManager.load(args[1])
                                ?: run {
                                        player.sendMessage(
                                                TextUtility.convertToComponent(
                                                        "&cSession or template '${args[1]}' not found."
                                                )
                                        )
                                        return
                                }

                val session2 = sessionManager.sessionFromMannequin(man)
                val defaultSlim = player.playerProfile.textures.skinModel == SkinModel.SLIM

                val merged = sessionManager.merge(session1, session2, defaultSlim)
                mannequinManager.applySession(man.id, merged, player)

                player.sendMessage(
                        TextUtility.convertToComponent(
                                "&aMerged &7'${args[1]}'&a onto mannequin &7${man.id}&a."
                        )
                )
        }
}
