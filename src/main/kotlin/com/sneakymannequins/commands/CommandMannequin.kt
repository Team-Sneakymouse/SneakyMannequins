package com.sneakymannequins.commands

import com.sneakymannequins.SneakyMannequins
import com.sneakymannequins.managers.ConfigManager
import com.sneakymannequins.managers.LayerManager
import com.sneakymannequins.managers.MannequinManager
import com.sneakymannequins.managers.SessionManager
import com.sneakymouse.sneakyholos.util.TextUtility
import io.papermc.paper.command.brigadier.CommandSourceStack
import java.io.File
import net.kyori.adventure.text.Component
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
                        "history" -> {
                                handleHistory(stack, args)
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
                        "template" -> handleTemplate(stack, args)
                        "apply" -> handleApply(player, args)
                        "info" -> handleInfo(player)
                        "debug" -> handleDebug(stack, args)
                        "remask" -> handleRemask(player, args)
                        "save" -> handleSave(player)
                        "delete" -> handleDelete(player, args)
                        else -> create(player)
                }
        }

        override fun suggest(
                stack: CommandSourceStack,
                args: Array<out String>
        ): MutableList<String> {
                return when (args.size) {
                        0, 1 ->
                                listOf(
                                                "remove",
                                                "reload",
                                                "remask",
                                                "history",
                                                "template",
                                                "apply",
                                                "info",
                                                "debug",
                                                "save",
                                                "delete"
                                        )
                                        .filter {
                                                it.startsWith(
                                                        args.getOrNull(0) ?: "",
                                                        ignoreCase = true
                                                )
                                        }
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
                                        "template", "apply", "save", "delete" ->
                                                (sessionManager.listTemplateNames() +
                                                                sessionManager.listSessionUids())
                                                        .filter {
                                                                it.startsWith(
                                                                        args[1],
                                                                        ignoreCase = true
                                                                )
                                                        }
                                                        .toMutableList()
                                        "debug" ->
                                                listOf("merge", "finalize")
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
                                        "debug" ->
                                                when (args[1].lowercase()) {
                                                        "merge" ->
                                                                (sessionManager
                                                                                .listTemplateNames() +
                                                                                sessionManager
                                                                                        .listSessionUids() +
                                                                                listOf(
                                                                                        "nearest",
                                                                                        "null"
                                                                                ))
                                                                        .filter {
                                                                                it.startsWith(
                                                                                        args[2],
                                                                                        ignoreCase =
                                                                                                true
                                                                                )
                                                                        }
                                                                        .toMutableList()
                                                        "finalize" ->
                                                                (sessionManager
                                                                                .listTemplateNames() +
                                                                                sessionManager
                                                                                        .listSessionUids() +
                                                                                listOf("nearest"))
                                                                        .filter {
                                                                                it.startsWith(
                                                                                        args[2],
                                                                                        ignoreCase =
                                                                                                true
                                                                                )
                                                                        }
                                                                        .toMutableList()
                                                        else -> mutableListOf()
                                                }
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
                                        "debug" ->
                                                when (args[1].lowercase()) {
                                                        "merge" ->
                                                                (sessionManager
                                                                                .listTemplateNames() +
                                                                                sessionManager
                                                                                        .listSessionUids() +
                                                                                listOf(
                                                                                        "nearest",
                                                                                        "null"
                                                                                ))
                                                                        .filter {
                                                                                it.startsWith(
                                                                                        args[3],
                                                                                        ignoreCase =
                                                                                                true
                                                                                )
                                                                        }
                                                                        .toMutableList()
                                                        else -> mutableListOf()
                                                }
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

        private fun handleDebug(stack: CommandSourceStack, args: Array<out String>): Boolean {
                if (args.size < 2) return false
                val player = stack.sender as? Player ?: return false
                return when (args[1].lowercase()) {
                        "merge" -> {
                                handleMerge(stack, args)
                                true
                        }
                        "finalize" -> {
                                handleFinalize(player, args)
                                true
                        }
                        else -> false
                }
        }

        private fun handleApply(player: Player, args: Array<out String>) {
                if (args.size < 2) {
                        player.sendMessage(
                                TextUtility.convertToComponent(
                                        "&cUsage: /mannequin apply <uid/template>"
                                )
                        )
                        return
                }
                val target = args[1]
                val session =
                        sessionManager.load(target)
                                ?: run {
                                        player.sendMessage(
                                                TextUtility.convertToComponent(
                                                        "&cSession or template '$target' not found."
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
                mannequinManager.applySession(man.id, session, player)
                player.sendMessage(
                        TextUtility.convertToComponent(
                                "&aApplied '${TextUtility.clickableCopy(target)}'&a to mannequin ${man.id}"
                        )
                )
        }

        private fun handleInfo(player: Player) {
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
                player.sendMessage(
                        TextUtility.convertToComponent("&6&lMannequin Info &7(${man.id})")
                )
                player.sendMessage(
                        TextUtility.convertToComponent(
                                "&eBody Type: &f${if (man.slimModel) "Slim" else "Classic"}"
                        )
                )
                player.sendMessage(
                        TextUtility.convertToComponent(
                                "&eLayers: &f${man.selection.selections.size}"
                        )
                )
        }

        private fun handleSave(player: Player) {
                val man =
                        mannequinManager.nearestMannequin(player.location, 5.0)
                                ?: run {
                                        player.sendMessage(
                                                TextUtility.convertToComponent(
                                                        "&cNo mannequin nearby."
                                                )
                                        )
                                        return
                                }

                val charContext = plugin.characterManagerBridge.currentCharacter(player)
                val uid =
                        sessionManager.save(
                                man,
                                player,
                                characterUuid = charContext?.characterUuid,
                                characterName = charContext?.characterName
                        )
                player.sendMessage(
                        TextUtility.convertToComponent(
                                "&aMannequin state saved with UID: ${TextUtility.clickableCopy(uid)}"
                        )
                )
        }

        private fun handleDelete(player: Player, args: Array<out String>) {
                if (args.size < 2) {
                        player.sendMessage(
                                TextUtility.convertToComponent("&cUsage: /mannequin delete <uid>")
                        )
                        return
                }
                val uid = args[1]
                val file = File(File(plugin.dataFolder, "sessions"), "$uid.json")
                if (file.exists()) {
                        file.delete()
                        player.sendMessage(
                                TextUtility.convertToComponent(
                                        "&aSession '${TextUtility.clickableCopy(uid)}'&a deleted."
                                )
                        )
                } else {
                        player.sendMessage(
                                TextUtility.convertToComponent("&cSession '$uid' not found.")
                        )
                }
        }

        private fun handleFinalize(player: Player, args: Array<out String>) {
                val sessionInput = if (args.size >= 3) args[2] else "nearest"
                val session =
                        sessionManager.resolveSession(sessionInput, player, mannequinManager)
                                ?: run {
                                        player.sendMessage(
                                                TextUtility.convertToComponent(
                                                        "&cCould not resolve session from '$sessionInput'."
                                                )
                                        )
                                        return
                                }

                val man =
                        mannequinManager.nearestMannequin(player.location, 5.0)
                                ?: run {
                                        player.sendMessage(
                                                TextUtility.convertToComponent(
                                                        "&cNo mannequin nearby for location context."
                                                )
                                        )
                                        return
                                }

                val targetDir = ConfigManager.instance.getImageStoragePath().toFile()
                player.sendMessage(TextUtility.convertToComponent("&eFinalizing session..."))

                sessionManager
                        .finalizeSession(player, man, targetDir, session)
                        .thenAccept { file ->
                                player.sendMessage(
                                        TextUtility.convertToComponent(
                                                "&aSession finalized and exported to &7${file.name}&a."
                                        )
                                )
                        }
                        .exceptionally { ex ->
                                player.sendMessage(
                                        TextUtility.convertToComponent(
                                                "&cFinalization failed: ${ex.message}"
                                        )
                                )
                                null
                        }
        }

        private fun handleRemask(sender: Player, args: Array<out String>) {
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
                                                TextUtility.convertToComponent(
                                                        TextUtility.clickableCopy(
                                                                session.uid,
                                                                "&e",
                                                                "Click to copy UID"
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
                val player = stack.sender as? Player ?: return
                // args: [debug, merge, source, target]
                if (args.size < 4) {
                        player.sendMessage(
                                TextUtility.convertToComponent(
                                        "&cUsage: /mannequin debug merge <source> <target/null>"
                                )
                        )
                        return
                }

                val sourceSession =
                        sessionManager.resolveSession(args[2], player, mannequinManager)
                                ?: run {
                                        player.sendMessage(
                                                TextUtility.convertToComponent(
                                                        "&cSource session '${args[2]}' not found."
                                                )
                                        )
                                        return
                                }

                val targetSession =
                        sessionManager.resolveSession(args[3], player, mannequinManager)
                                ?: run {
                                        player.sendMessage(
                                                TextUtility.convertToComponent(
                                                        "&cTarget session '${args[3]}' not found."
                                                )
                                        )
                                        return
                                }

                val defaultSlim = player.playerProfile.textures.skinModel == SkinModel.SLIM
                val merged = sessionManager.merge(sourceSession, targetSession, defaultSlim)

                // Save for debug purposes
                val uid =
                        sessionManager.save(
                                mannequin = mannequinManager.nearestMannequin(player.location)
                                                ?: return,
                                player = player
                        )
                // Overwrite the saved JSON with our merged session but keep the UID
                val savedFile = File(File(plugin.dataFolder, "sessions"), "$uid.json")
                val finalMerged = merged.copy(uid = uid)
                savedFile.writeText(
                        com.google.gson.GsonBuilder()
                                .setPrettyPrinting()
                                .create()
                                .toJson(finalMerged)
                )

                player.sendMessage(
                        TextUtility.convertToComponent(
                                "&aMerged &7'${TextUtility.clickableCopy(args[2], "&7")}'&a onto &7'${TextUtility.clickableCopy(args[3], "&7")}'&a. Result saved as: ${TextUtility.clickableCopy(uid)}"
                        )
                )
        }
}
