package com.sneakymannequins.commands

import com.sneakymannequins.SneakyMannequins
import com.sneakymannequins.managers.LayerManager
import com.sneakymannequins.managers.LayerManager.MaskStrategy
import com.sneakymannequins.managers.MannequinManager
import com.sneakymannequins.managers.RemaskManager
import com.sneakymannequins.managers.SessionManager
import com.sneakymannequins.managers.StyleManager
import com.sneakymouse.sneakyholos.util.TextUtility
import io.papermc.paper.command.brigadier.CommandSourceStack
import java.io.File
import java.net.URL
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.profile.PlayerTextures.SkinModel

class CommandMannequin(
        private val plugin: SneakyMannequins,
        private val mannequinManager: MannequinManager,
        private val layerManager: LayerManager,
        private val styleManager: StyleManager,
        private val sessionManager: SessionManager,
        private val remaskManager: RemaskManager
) : CommandBase("mannequin") {

        companion object {
                private const val HISTORY_PAGE_SIZE = 10
        }

        override fun handle(stack: CommandSourceStack, args: Array<out String>) {
                val player = stack.sender as? Player
                val cmd = args.firstOrNull()?.lowercase()

                if (cmd == null) {
                        sendHelp(stack.sender)
                        return
                }

                if (!hasPermission(stack.sender, cmd)) {
                        stack.sender.sendMessage(
                                TextUtility.convertToComponent(
                                        "&cYou do not have permission to use this command."
                                )
                        )
                        return
                }

                when (cmd) {
                        "reload" -> handleReload(stack.sender)
                        "history" -> handleHistory(stack, args)
                        "remove" -> player?.let { removeNearest(it) }
                                        ?: stack.sender.sendMessage(
                                                "You must be a player to use this command"
                                        )
                        "template" -> handleTemplate(stack, args)
                        "remask" -> player?.let { handleRemask(it, args) }
                                        ?: stack.sender.sendMessage(
                                                "You must be a player to use this command"
                                        )
                        "me" -> player?.let { handleMe(it, args) }
                                        ?: stack.sender.sendMessage(
                                                "You must be a player to use this command"
                                        )
                        "add" -> player?.let { create(it, args) }
                                        ?: stack.sender.sendMessage(
                                                "You must be a player to use this command"
                                        )
                        "debug" -> handleDebug(stack, args)
                        else -> sendHelp(stack.sender)
                }
        }

        private fun hasPermission(sender: CommandSender, subcommand: String): Boolean {
                return sender.hasPermission("sneakymannequins.command.$subcommand")
        }

        private fun hasDebugPermission(sender: CommandSender, subsubcommand: String): Boolean {
                return sender.hasPermission("sneakymannequins.command.debug.$subsubcommand")
        }

        private fun sendHelp(sender: CommandSender) {
                sender.sendMessage(TextUtility.convertToComponent("&6&lSneakyMannequins Help"))
                val commands =
                        linkedMapOf(
                                "add <style>" to "Create a new mannequin",
                                "remove" to "Remove nearest mannequin",
                                "reload" to "Reload plugin configuration",
                                "history" to "View your session history",
                                "template" to "Manage session templates",
                                "remask" to "Remask a specific layer part",
                                "me" to "Manage user-uploaded custom skin parts",
                                "debug" to "Access developer/debug tools"
                        )

                commands.forEach { (cmd, desc) ->
                        val color = if (hasPermission(sender, cmd)) "&a" else "&c"
                        sender.sendMessage(
                                TextUtility.convertToComponent("$color/mannequin $cmd &7- $desc")
                        )
                }
        }

        override fun suggest(
                stack: CommandSourceStack,
                args: Array<out String>
        ): MutableList<String> {
                return when (args.size) {
                        0, 1 ->
                                listOf(
                                                "add",
                                                "remove",
                                                "reload",
                                                "remask",
                                                "me",
                                                "history",
                                                "template",
                                                "debug"
                                        )
                                        .filter { hasPermission(stack.sender, it) }
                                        .filter {
                                                it.startsWith(
                                                        args.getOrNull(0) ?: "",
                                                        ignoreCase = true
                                                )
                                        }
                                        .toMutableList()
                        2 ->
                                when (args[0].lowercase()) {
                                        "remask" -> {
                                                val p = stack.sender as? Player
                                                if (p != null && remaskManager.hasSession(p)) {
                                                        listOf("confirm", "cancel")
                                                                .filter {
                                                                        it.startsWith(
                                                                                args[1],
                                                                                ignoreCase = true
                                                                        )
                                                                }
                                                                .toMutableList()
                                                } else {
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
                                                }
                                        }
                                        "add" -> styleManager.listStyleIds().toMutableList()
                                        "debug" ->
                                                listOf(
                                                                "merge",
                                                                "finalize",
                                                                "save",
                                                                "apply",
                                                                "info",
                                                                "delete",
                                                                "checkskin",
                                                                "copyme",
                                                                "debug",
                                                                "overlay"
                                                        )
                                                        .filter {
                                                                hasDebugPermission(stack.sender, it)
                                                        }
                                                        .filter {
                                                                it.startsWith(
                                                                        args[1],
                                                                        ignoreCase = true
                                                                )
                                                        }
                                                        .toMutableList()
                                        "me" ->
                                                listOf("upload", "delete")
                                                        .filter {
                                                                it.startsWith(
                                                                        args[1],
                                                                        ignoreCase = true
                                                                )
                                                        }
                                                        .toMutableList()
                                        "template" ->
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
                                                        "apply", "delete" ->
                                                                (sessionManager
                                                                                .listTemplateNames() +
                                                                                sessionManager
                                                                                        .listSessionUids())
                                                                        .filter {
                                                                                it.startsWith(
                                                                                        args[2],
                                                                                        ignoreCase =
                                                                                                true
                                                                                )
                                                                        }
                                                                        .toMutableList()
                                                        "checkskin" ->
                                                                plugin.server
                                                                        .onlinePlayers
                                                                        .map { it.name }
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
                                        "me" ->
                                                when (args[1].lowercase()) {
                                                        "upload", "remask", "delete" ->
                                                                layerManager
                                                                        .definitionsInOrder()
                                                                        .map { it.id }
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
                                        "template" -> mutableListOf("<template_name>")
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
                                                        "merge", "finalize", "apply" ->
                                                                plugin.server
                                                                        .onlinePlayers
                                                                        .map { it.name }
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
                                        "me" ->
                                                when (args[1].lowercase()) {
                                                        "upload" -> mutableListOf("<url>")
                                                        "remask", "delete" -> {
                                                                val layerId = args[2].lowercase()
                                                                val isAdmin =
                                                                        stack.sender.hasPermission(
                                                                                "${SneakyMannequins.IDENTIFIER}.admin"
                                                                        )
                                                                val playerUuid =
                                                                        (stack.sender as? Player)
                                                                                ?.uniqueId
                                                                                ?.toString()
                                                                layerManager
                                                                        .allOptions(layerId)
                                                                        .mapNotNull {
                                                                                it.owner?.toString()
                                                                        }
                                                                        .distinct()
                                                                        .filter {
                                                                                isAdmin ||
                                                                                        it ==
                                                                                                playerUuid
                                                                        }
                                                                        .filter {
                                                                                it.startsWith(
                                                                                        args[3],
                                                                                        ignoreCase =
                                                                                                true
                                                                                )
                                                                        }
                                                                        .toMutableList()
                                                        }
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
                                        "me" ->
                                                when (args[1].lowercase()) {
                                                        "upload" -> mutableListOf("<name>")
                                                        "remask", "delete" -> {
                                                                val layerId = args[2].lowercase()
                                                                val uuidStr = args[3]
                                                                val isAdmin =
                                                                        stack.sender.hasPermission(
                                                                                "${SneakyMannequins.IDENTIFIER}.admin"
                                                                        )
                                                                val playerUuid =
                                                                        (stack.sender as? Player)
                                                                                ?.uniqueId
                                                                                ?.toString()

                                                                if (!isAdmin &&
                                                                                uuidStr !=
                                                                                        playerUuid
                                                                ) {
                                                                        return mutableListOf()
                                                                }

                                                                layerManager
                                                                        .allOptions(layerId)
                                                                        .filter {
                                                                                it.owner
                                                                                        ?.toString() ==
                                                                                        uuidStr
                                                                        }
                                                                        .mapNotNull {
                                                                                it.internalKey
                                                                        }
                                                                        .filter {
                                                                                it.startsWith(
                                                                                        args[4],
                                                                                        ignoreCase =
                                                                                                true
                                                                                )
                                                                        }
                                                                        .toMutableList()
                                                        }
                                                        else -> mutableListOf()
                                                }
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
                        6 ->
                                when (args[0].lowercase()) {
                                        "me" ->
                                                when (args[1].lowercase()) {
                                                        "remask" ->
                                                                LayerManager.STRATEGY_NAMES
                                                                        .filter {
                                                                                it.startsWith(
                                                                                        args[5],
                                                                                        ignoreCase =
                                                                                                true
                                                                                )
                                                                        }
                                                                        .toMutableList()
                                                        else -> mutableListOf()
                                                }
                                        else -> mutableListOf()
                                }
                        7 ->
                                when (args[0].lowercase()) {
                                        "me" ->
                                                when (args[1].lowercase()) {
                                                        "remask" ->
                                                                (1..8)
                                                                        .map { it.toString() }
                                                                        .filter {
                                                                                it.startsWith(
                                                                                        args[6],
                                                                                        ignoreCase =
                                                                                                true
                                                                                )
                                                                        }
                                                                        .toMutableList()
                                                        else -> mutableListOf()
                                                }
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

        private fun create(player: Player, args: Array<out String> = emptyArray()) {
                try {
                        if (args.size < 2) {
                                player.sendMessage(
                                        TextUtility.convertToComponent(
                                                "&cUsage: /mannequin add <style> [world,x,y,z,yaw]"
                                        )
                                )
                                return
                        }

                        val styleId = args[1]
                        if (styleManager.getStyle(styleId) == null) {
                                player.sendMessage(
                                        TextUtility.convertToComponent(
                                                "&cStyle '$styleId' not found."
                                        )
                                )
                                return
                        }

                        var location = player.location.clone()

                        if (args.size > 2) {
                                val input = args[2]
                                val parts = input.split(",")
                                if (parts.size >= 4) {
                                        val worldName = parts[0]
                                        val world =
                                                player.server.getWorld(worldName)
                                                        ?: throw IllegalArgumentException(
                                                                "World '$worldName' not found."
                                                        )
                                        val x =
                                                parts[1].toDoubleOrNull()
                                                        ?: throw IllegalArgumentException(
                                                                "Invalid X coordinate."
                                                        )
                                        val y =
                                                parts[2].toDoubleOrNull()
                                                        ?: throw IllegalArgumentException(
                                                                "Invalid Y coordinate."
                                                        )
                                        val z =
                                                parts[3].toDoubleOrNull()
                                                        ?: throw IllegalArgumentException(
                                                                "Invalid Z coordinate."
                                                        )
                                        val yaw =
                                                if (parts.size >= 5) parts[4].toFloatOrNull() ?: 0f
                                                else 0f

                                        location = org.bukkit.Location(world, x, y, z, yaw, 0f)
                                } else {
                                        player.sendMessage(
                                                TextUtility.convertToComponent(
                                                        "&cInvalid location format. Use: world,x,y,z,yaw"
                                                )
                                        )
                                        return
                                }
                        }

                        val mannequin = mannequinManager.create(location, styleId)
                        player.sendMessage(
                                TextUtility.convertToComponent(
                                        "&aMannequin created with style '$styleId' at (${location.world.name}, ${location.x.toInt()}, ${location.y.toInt()}, ${location.z.toInt()}) with id ${mannequin.id}"
                                )
                        )
                } catch (e: Exception) {
                        player.sendMessage(
                                TextUtility.convertToComponent(
                                        "&cFailed to create mannequin: ${e.message}"
                                )
                        )
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
                if (args.size < 2) {
                        sendDebugHelp(stack.sender)
                        return true
                }

                when (args[1].lowercase()) {
                        "checkskin" -> {
                                handleCheckSkin(stack, args)
                                return true
                        }
                        "debug" -> {
                                handleDebugToggle(stack, args)
                                return true
                        }
                }

                val player =
                        stack.sender as? Player
                                ?: run {
                                        stack.sender.sendMessage(
                                                "You must be a player to use debug commands"
                                        )
                                        return true
                                }

                val subCmd = args[1].lowercase()
                if (!hasDebugPermission(stack.sender, subCmd)) {
                        stack.sender.sendMessage(
                                TextUtility.convertToComponent(
                                        "&cYou do not have permission to use this debug command."
                                )
                        )
                        return true
                }

                return when (subCmd) {
                        "merge" -> {
                                handleMerge(stack, args)
                                true
                        }
                        "finalize" -> {
                                handleFinalize(player, args)
                                true
                        }
                        "save" -> {
                                handleSave(player)
                                true
                        }
                        "apply" -> {
                                handleApply(player, args)
                                true
                        }
                        "info" -> {
                                handleInfo(player)
                                true
                        }
                        "overlay" -> {
                                handleOverlay(player)
                                true
                        }
                        "copyme" -> {
                                handleDebugCopyMe(player)
                                true
                        }
                        "delete" -> {
                                handleDelete(player, args.drop(1).toTypedArray())
                                true
                        }
                        else -> {
                                sendDebugHelp(stack.sender)
                                true
                        }
                }
        }

        private fun sendDebugHelp(sender: CommandSender) {
                sender.sendMessage(
                        TextUtility.convertToComponent("&6&lSneakyMannequins Debug Help")
                )
                val debugCommands =
                        linkedMapOf(
                                "merge" to "Merge two sessions",
                                "finalize" to "Finalize and export a session",
                                "save" to "Save nearest mannequin session",
                                "apply" to "Apply a session/template",
                                "copyme" to "Trigger 'Copy Me' for nearest mannequin",
                                "overlay" to "Toggle nearest mannequin overlay",
                                "info" to "Show nearest mannequin info",
                                "delete" to "Delete a session UID"
                        )

                debugCommands.forEach { (cmd, desc) ->
                        val color = if (hasDebugPermission(sender, cmd)) "&a" else "&c"
                        sender.sendMessage(
                                TextUtility.convertToComponent(
                                        "$color/mannequin debug $cmd &7- $desc"
                                )
                        )
                }
        }

        private fun handleApply(requester: Player, args: Array<out String>) {
                if (args.size < 3) {
                        requester.sendMessage(
                                TextUtility.convertToComponent(
                                        "&cUsage: /mannequin debug apply <uid/template/nearest> [player]"
                                )
                        )
                        return
                }

                val sessionInput = args[2]
                val targetPlayerName = if (args.size >= 4) args[3] else requester.name
                val targetPlayer =
                        plugin.server.getPlayer(targetPlayerName)
                                ?: run {
                                        requester.sendMessage(
                                                TextUtility.convertToComponent(
                                                        "&cPlayer '$targetPlayerName' not found."
                                                )
                                        )
                                        return
                                }

                val session =
                        sessionManager.resolveSession(sessionInput, requester, mannequinManager)
                                ?: run {
                                        requester.sendMessage(
                                                TextUtility.convertToComponent(
                                                        "&cSession or template '$sessionInput' not found."
                                                )
                                        )
                                        return
                                }

                val man =
                        mannequinManager.nearestMannequin(requester.location, 5.0)
                                ?: run {
                                        requester.sendMessage(
                                                TextUtility.convertToComponent(
                                                        "&cNo mannequin nearby for location context."
                                                )
                                        )
                                        return
                                }

                requester.sendMessage(
                        TextUtility.convertToComponent(
                                "&eApplying finalized skin to ${targetPlayer.name}..."
                        )
                )
                mannequinManager.finalizeAndApply(
                        requester,
                        man,
                        targetPlayer,
                        sessionOverride = session
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

        private fun handleOverlay(player: Player) {
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
                mannequinManager.toggleOverlay(man.id, player)
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

                if (!mannequinManager.hasUnsavedChanges(man)) {
                        val uid = man.savedUid!!
                        player.sendMessage(
                                TextUtility.convertToComponent(
                                        "&aSession unchanged. UID: ${TextUtility.clickableCopy(uid)}"
                                )
                        )
                        return
                }

                val session = mannequinManager.saveMannequinState(man, player)
                val uid = session.uid
                player.sendMessage(
                        TextUtility.convertToComponent(
                                "&aMannequin state saved with UID: ${TextUtility.clickableCopy(uid)}"
                        )
                )
        }

        private fun handleRemask(sender: Player, args: Array<out String>) {
                if (remaskManager.hasSession(sender)) {
                        val sub = args.getOrNull(1)?.lowercase()
                        if (sub == "cancel") {
                                remaskManager.cancelSession(sender)
                        } else if (sub == "confirm" || sub == null) {
                                remaskManager.confirmSession(sender)
                        } else {
                                sender.sendMessage(
                                        TextUtility.convertToComponent(
                                                "&cUsage: /mannequin remask [confirm|cancel]"
                                        )
                                )
                        }
                        return
                }

                if (args.size < 2) {
                        sender.sendMessage(
                                TextUtility.convertToComponent(
                                        "&cUsage: /mannequin remask <layer> [strategy]"
                                )
                        )
                        return
                }

                val layerId = args[1]
                val strategyName = args.getOrNull(2)
                val strategy =
                        strategyName?.let {
                                try {
                                        MaskStrategy.valueOf(it.uppercase())
                                } catch (_: Exception) {
                                        null
                                }
                        }

                val mannequin = mannequinManager.nearestMannequin(sender.location)
                if (mannequin == null) {
                        sender.sendMessage(TextUtility.convertToComponent("&cNo mannequin nearby."))
                        return
                }

                val partId = mannequinManager.currentPartId(mannequin.id, layerId)
                if (partId == null) {
                        sender.sendMessage(
                                TextUtility.convertToComponent(
                                        "&cNo part selected in layer '&b$layerId&c' for this mannequin."
                                )
                        )
                        return
                }

                // Permission check
                val opt = layerManager.findPartById(layerId, partId)
                if (opt == null) {
                        sender.sendMessage(
                                TextUtility.convertToComponent("&cCould not find part definition.")
                        )
                        return
                }

                val isOwner = opt.owner == sender.uniqueId
                val isAdmin = sender.hasPermission("sneakymannequins.admin")
                if (!isOwner && !isAdmin) {
                        sender.sendMessage(
                                TextUtility.convertToComponent(
                                        "&cYou do not have permission to remask this part (not the owner)."
                                )
                        )
                        return
                }

                remaskManager.startSession(sender, mannequin.id, layerId, partId, strategy)
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

                val contextPlayerName = if (args.size >= 4) args[3] else player.name
                val contextPlayer =
                        plugin.server.getPlayer(contextPlayerName)
                                ?: run {
                                        player.sendMessage(
                                                TextUtility.convertToComponent(
                                                        "&cPlayer '$contextPlayerName' not found."
                                                )
                                        )
                                        return
                                }

                player.sendMessage(TextUtility.convertToComponent("&eFinalizing session..."))

                sessionManager
                        .finalizeSession(player, man, session, contextPlayer = contextPlayer)
                        .thenAccept { result ->
                                player.sendMessage(
                                        TextUtility.convertToComponent(
                                                "&aSession finalized and exported to &7${result.file.name}&a using context of &d${contextPlayer.name}&a."
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

        private fun handleDebugToggle(stack: CommandSourceStack, args: Array<out String>) {
                val current = plugin.config.getBoolean("plugin.debug", false)
                val newDebug = !current
                plugin.config.set("plugin.debug", newDebug)
                stack.sender.sendMessage(
                        TextUtility.convertToComponent(
                                "&aPlugin debug mode is now &e$newDebug&a (in-memory)."
                        )
                )
        }

        private fun handleCheckSkin(stack: CommandSourceStack, args: Array<out String>) {
                val targetName = if (args.size >= 3) args[2] else stack.sender.name
                val target =
                        plugin.server.getPlayer(targetName)
                                ?: run {
                                        stack.sender.sendMessage(
                                                TextUtility.convertToComponent(
                                                        "&cPlayer '$targetName' not found or offline."
                                                )
                                        )
                                        return
                                }
                val skinUrl = target.playerProfile.textures.skin
                if (skinUrl == null) {
                        stack.sender.sendMessage(
                                TextUtility.convertToComponent(
                                        "&cPlayer '${target.name}' has no skin URL."
                                )
                        )
                        return
                }
                stack.sender.sendMessage(
                        TextUtility.convertToComponent("&eDownloading skin for ${target.name}...")
                )
                sessionManager
                        .downloadSkin(skinUrl)
                        .thenAccept { skin ->
                                val uid = SessionManager.decodeUidFromImage(skin)
                                if (uid == null) {
                                        stack.sender.sendMessage(
                                                TextUtility.convertToComponent(
                                                        "&cNo valid encoded session ID (UID) found in ${target.name}'s skin pixels."
                                                )
                                        )
                                        return@thenAccept
                                }
                                val session = sessionManager.load(uid)
                                if (session == null) {
                                        stack.sender.sendMessage(
                                                TextUtility.convertToComponent(
                                                        "&eFound valid encoded UID '&b$uid&e' in skin pixels, but no local session data matches it."
                                                )
                                        )
                                } else {
                                        stack.sender.sendMessage(
                                                TextUtility.convertToComponent(
                                                        "&aFound VALID encoded UID '&b$uid&a' in skin pixels! Session contains &e${session.layers.size}&a layers."
                                                )
                                        )
                                }
                        }
                        .exceptionally { ex ->
                                stack.sender.sendMessage(
                                        TextUtility.convertToComponent(
                                                "&cFailed to download or process skin: ${ex.message}"
                                        )
                                )
                                null
                        }
        }

        private fun handleMe(player: Player, args: Array<out String>) {
                if (args.size < 2) {
                        player.sendMessage(
                                TextUtility.convertToComponent(
                                        "&cUsage: /mannequin me <upload|delete> ..."
                                )
                        )
                        return
                }
                when (args[1].lowercase()) {
                        "upload" -> handleMeUpload(player, args)
                        "delete" -> handleMeDelete(player, args)
                        else ->
                                player.sendMessage(
                                        TextUtility.convertToComponent(
                                                "&cUnknown me subcommand. Use upload or delete."
                                        )
                                )
                }
        }

        private fun handleMeDelete(player: Player, args: Array<out String>) {
                // /mannequin me delete <layer> <uuid> <part>
                if (args.size < 5) {
                        player.sendMessage(
                                TextUtility.convertToComponent(
                                        "&cUsage: /mannequin me delete <layer> <uuid> <part>"
                                )
                        )
                        return
                }
                val layerId = args[2].lowercase()
                val uuidStr = args[3]
                val internalKey = args[4].lowercase()
                val partId = "$uuidStr:$internalKey"

                val msg = layerManager.deletePart(player, layerId, partId)
                player.sendMessage(TextUtility.convertToComponent("&e$msg"))
        }

        private fun handleMeRemask(sender: Player, args: Array<out String>) {
                // /mannequin me remask <layer> <uuid> <part> [strategy] [channels]
                if (args.size < 5) {
                        sender.sendMessage(
                                TextUtility.convertToComponent(
                                        "&cUsage: /mannequin me remask <layer> <uuid> <part> [${LayerManager.STRATEGY_NAMES.joinToString("|")}] [channels]"
                                )
                        )
                        return
                }
                val layerId = args[2].lowercase()
                val uuidStr = args[3]
                val internalKey = args[4].lowercase()

                val partId = "$uuidStr:$internalKey"

                val optOpt = layerManager.allOptions(layerId).find { it.id == partId }
                if (optOpt == null) {
                        sender.sendMessage(TextUtility.convertToComponent("&cPart not found."))
                        return
                }

                if (optOpt.owner == null) {
                        sender.sendMessage(
                                TextUtility.convertToComponent("&cCannot remask builtin parts.")
                        )
                        return
                }

                if (optOpt.owner != sender.uniqueId &&
                                !sender.hasPermission("${SneakyMannequins.IDENTIFIER}.admin")
                ) {
                        sender.sendMessage(
                                TextUtility.convertToComponent("&cYou do not own this part.")
                        )
                        return
                }

                val strategyName = (if (args.size >= 6) args[5] else null)?.uppercase()
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

                val distanceOrChannelsArg: Any? =
                        if (args.size >= 7) {
                                val floatVal = args[6].toFloatOrNull()
                                val intVal = args[6].toIntOrNull()
                                if (intVal != null &&
                                                floatVal != null &&
                                                floatVal == intVal.toFloat() &&
                                                !args[6].contains('.')
                                ) {
                                        if (intVal < 1 || intVal > 8) {
                                                sender.sendMessage(
                                                        TextUtility.convertToComponent(
                                                                "&cChannels must be between 1 and 8."
                                                        )
                                                )
                                                return
                                        }
                                        intVal
                                } else if (floatVal != null) {
                                        if (floatVal < 0f || floatVal > 1f) {
                                                sender.sendMessage(
                                                        TextUtility.convertToComponent(
                                                                "&cDistance must be between 0.0 and 1.0."
                                                        )
                                                )
                                                return
                                        }
                                        floatVal
                                } else {
                                        sender.sendMessage(
                                                TextUtility.convertToComponent(
                                                        "&cArgument must be an integer (channels) or float (distance)."
                                                )
                                        )
                                        return
                                }
                        } else null

                val label = strategy?.name ?: "default"
                val configLabel =
                        distanceOrChannelsArg?.let {
                                if (it is Float) "dist=$it" else "channels=$it"
                        }
                                ?: "default config"
                sender.sendMessage(
                        TextUtility.convertToComponent(
                                "&7Remasking ME '$internalKey' (owner $uuidStr) in '$layerId' with $label strategy, $configLabel..."
                        )
                )
                try {
                        val result =
                                layerManager.commitRemask(
                                        strategy = strategy ?: layerManager.defaultStrategy(),
                                        layerId = layerId,
                                        partId = partId,
                                        params = layerManager.currentRemaskParameters(),
                                        distanceOrChannels = distanceOrChannelsArg
                                )
                        sender.sendMessage(TextUtility.convertToComponent("&a$result"))
                } catch (e: Exception) {
                        sender.sendMessage(
                                TextUtility.convertToComponent("&cRemask ME failed: ${e.message}")
                        )
                        plugin.logger.severe("Remask ME failed: ${e.message}")
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
                                        "&cUsage: /mannequin debug merge <source> <target/null> [player]"
                                )
                        )
                        return
                }

                val contextPlayerName = if (args.size >= 5) args[4] else player.name
                val contextPlayer =
                        plugin.server.getPlayer(contextPlayerName)
                                ?: run {
                                        player.sendMessage(
                                                TextUtility.convertToComponent(
                                                        "&cPlayer '$contextPlayerName' not found."
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

                val defaultSlim = contextPlayer.playerProfile.textures.skinModel == SkinModel.SLIM
                val merged = sessionManager.merge(sourceSession, targetSession, defaultSlim)

                // Save for debug purposes
                val savedSession =
                        sessionManager.save(
                                mannequin = mannequinManager.nearestMannequin(player.location)
                                                ?: return,
                                player = player
                        )
                val uid = savedSession.uid
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

        private fun handleMeUpload(player: Player, args: Array<out String>) {
                if (args.size < 4) {
                        player.sendMessage(
                                TextUtility.convertToComponent(
                                        "&cUsage: /mannequin me upload <layer> <url> [name]"
                                )
                        )
                        return
                }
                val layerId = args[2].lowercase()
                val urlStr = args[3]
                val name = if (args.size > 4) args[4] else null

                val url =
                        try {
                                URL(urlStr)
                        } catch (e: Exception) {
                                player.sendMessage(
                                        TextUtility.convertToComponent(
                                                "&cInvalid URL: ${e.message}"
                                        )
                                )
                                return
                        }

                player.sendMessage(
                        TextUtility.convertToComponent("&eUploading part to layer '$layerId'...")
                )
                layerManager
                        .uploadPart(player, layerId, url, name, sessionManager)
                        .thenAccept { msg: String ->
                                player.sendMessage(TextUtility.convertToComponent("&a$msg"))
                        }
                        .exceptionally { e: Throwable ->
                                player.sendMessage(
                                        TextUtility.convertToComponent(
                                                "&cUpload failed: ${e.cause?.message ?: e.message}"
                                        )
                                )
                                null
                        }
        }

        private fun handleDebugCopyMe(player: Player) {
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
                player.sendMessage(
                        TextUtility.convertToComponent("&eTriggering Copy Me for ${player.name}...")
                )
                mannequinManager.handleButtonClick("copyMe", man.id, player, false)
        }
}
