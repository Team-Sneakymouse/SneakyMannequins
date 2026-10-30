package com.sneakymannequins

import com.sneakymannequins.managers.LayerManager
import com.sneakymannequins.model.LayerDefinition
import com.sneakymannequins.model.LayerOption
import org.bukkit.entity.Player
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.*
import java.nio.file.Path
import java.nio.file.Files
import java.util.UUID
import kotlin.test.*

class PartManagementTest {
    @TempDir lateinit var root: Path
    private val plugin = mock(SneakyMannequins::class.java)
    private val player = mock(Player::class.java)
    private val owner = UUID.randomUUID()
    private lateinit var manager: LayerManager
    private lateinit var option: LayerOption

    private fun fixture() {
        `when`(player.uniqueId).thenReturn(owner)
        `when`(plugin.logger).thenReturn(java.util.logging.Logger.getAnonymousLogger())
        manager = LayerManager(plugin)
        val dir = Files.createDirectories(root.resolve("uploads/$owner/hat"))
        Files.writeString(dir.resolve("metadata.json"), "{\"displayName\":\"Old\",\"unknown\":{\"x\":3}}")
        option = LayerOption("$owner:hat", "Old", null, null, null, null, null, null,
            directory = dir, owner = owner, internalKey = "hat")
        @Suppress("UNCHECKED_CAST")
        val layers = LayerManager::class.java.getDeclaredField("loadedLayers").apply { isAccessible = true }
            .get(manager) as MutableMap<String, Pair<LayerDefinition, List<LayerOption>>>
        layers["hat"] = LayerDefinition("hat", "Hat", root, true) to listOf(option)
    }

    private fun rename(name: String): String {
        val method = manager.javaClass.methods.find { it.name == "renamePart" }
        assertNotNull(method, "renamePart behavior is not implemented")
        return method.invoke(manager, "hat", option.id, name) as String
    }

    @Test fun `selected and remembered options render renamed label by stable ID`() {
        fixture()
        val mannequins = com.sneakymannequins.managers.MannequinManager(plugin, manager,
            mock(com.sneakymannequins.managers.StyleManager::class.java),
            mock(com.sneakymannequins.nms.VolatileHandler::class.java),
            mock(com.sneakymannequins.managers.MannequinPersistence::class.java),
            mock(com.sneakymannequins.managers.SessionManager::class.java),
            mock(com.sneakymannequins.integrations.CharacterManagerBridge::class.java),
            mock(com.sneakymouse.sneakyholos.HoloController::class.java))
        val selected = com.sneakymannequins.model.LayerSelection("hat", option)
        val mannequin = com.sneakymannequins.model.Mannequin(
            location = org.bukkit.Location(null, 0.0, 0.0, 0.0),
            selection = com.sneakymannequins.model.SkinSelection(mapOf("hat" to selected)))
        @Suppress("UNCHECKED_CAST")
        val active = mannequins.javaClass.getDeclaredField("mannequins").apply { isAccessible = true }
            .get(mannequins) as MutableMap<UUID, com.sneakymannequins.model.Mannequin>
        active[mannequin.id] = mannequin
        @Suppress("UNCHECKED_CAST")
        val memory = mannequins.javaClass.getDeclaredField("partSelectionMemory").apply { isAccessible = true }
            .get(mannequins) as MutableMap<UUID, MutableMap<String, MutableMap<String, com.sneakymannequins.model.LayerSelection>>>
        memory[mannequin.id] = mutableMapOf("hat" to mutableMapOf(option.id to selected.copy()))
        @Suppress("UNCHECKED_CAST")
        val flashes = mannequins.javaClass.getDeclaredField("activeFlashRestoreSelection").apply { isAccessible = true }
            .get(mannequins) as MutableMap<Pair<UUID, String>, com.sneakymannequins.model.LayerSelection?>
        flashes[mannequin.id to "hat"] = selected.copy()
        assertTrue(rename("Fancy New Hat").startsWith("Successfully"))
        val renderer = mannequins.javaClass.declaredMethods.find { it.name == "partDisplayName" }
        assertNotNull(renderer, "HUD must resolve retained option labels through the current catalog")
        renderer.isAccessible = true
        for (selection in listOf(mannequin.selection.selections["hat"]!!,
            memory[mannequin.id]!!["hat"]!![option.id]!!, flashes[mannequin.id to "hat"]!!)) {
            assertEquals("Fancy New Hat", renderer.invoke(mannequins, "hat", selection.option))
            assertEquals(option.id, selection.option!!.id)
        }
    }

    @Test fun `rename changes only display name and preserves unknown metadata`() {
        fixture()
        assertTrue(rename("New Hat Name").startsWith("Successfully"))
        assertEquals(option.copy(displayName = "New Hat Name"), manager.allOptions("hat").single())
        val json = com.google.gson.JsonParser.parseString(Files.readString(option.directory!!.resolve("metadata.json"))).asJsonObject
        assertEquals(3, json.getAsJsonObject("unknown").get("x").asInt)
        assertEquals("New Hat Name", json.get("displayName").asString)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <K, V> fieldMap(target: Any, name: String): MutableMap<K, V> =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target) as MutableMap<K, V>

    private fun liveFixture(): Pair<com.sneakymannequins.managers.MannequinManager, com.sneakymannequins.model.Mannequin> {
        fixture()
        val server = mock(org.bukkit.Server::class.java, RETURNS_DEEP_STUBS)
        `when`(plugin.server).thenReturn(server)
        `when`(server.onlinePlayers).thenReturn(emptyList())
        val styles = mock(com.sneakymannequins.managers.StyleManager::class.java)
        val style = mock(com.sneakymannequins.model.MannequinStyle::class.java, RETURNS_DEEP_STUBS)
        val button = mock(com.sneakymannequins.model.HudButton::class.java)
        `when`(button.name).thenReturn("layer")
        `when`(button.type).thenReturn("layer")
        `when`(button.allowedLayers).thenReturn(null)
        `when`(style.hudButtons).thenReturn(listOf(button))
        `when`(style.availableLayers).thenReturn(listOf("base", "hat"))
        `when`(styles.getStyle(anyString())).thenReturn(style)
        val rendering = style.rendering
        doReturn(com.sneakymannequins.model.TextDisplayBrightnessSetting.Fixed(15, 15))
            .`when`(rendering).textDisplayBrightness
        val holos = mock(com.sneakymouse.sneakyholos.HoloController::class.java)
        `when`(holos.getHud(owner)).thenReturn(mock(com.sneakymouse.sneakyholos.HoloHUD::class.java))
        val persistence = mock(com.sneakymannequins.managers.MannequinPersistence::class.java)
        `when`(persistence.load()).thenReturn(emptyList())
        val mannequins = com.sneakymannequins.managers.MannequinManager(plugin, manager, styles,
            mock(com.sneakymannequins.nms.VolatileHandler::class.java), persistence,
            mock(com.sneakymannequins.managers.SessionManager::class.java),
            mock(com.sneakymannequins.integrations.CharacterManagerBridge::class.java), holos)
        val mannequin = com.sneakymannequins.model.Mannequin(location = org.bukkit.Location(null, 0.0, 0.0, 0.0),
            selection = com.sneakymannequins.model.SkinSelection(mapOf("hat" to com.sneakymannequins.model.LayerSelection("hat", option))))
        fieldMap<UUID, com.sneakymannequins.model.Mannequin>(mannequins, "mannequins")[mannequin.id] = mannequin
        val stateType = Class.forName("com.sneakymannequins.managers.ControlState")
        val state = stateType.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        fieldMap<UUID, Any>(mannequins, "controlState")[mannequin.id] = state
        fieldMap<String, Pair<LayerDefinition, List<LayerOption>>>(manager, "loadedLayers")["base"] =
            LayerDefinition("base", "Base", root, true) to emptyList()
        @Suppress("UNCHECKED_CAST")
        val order = manager.javaClass.getDeclaredField("layerOrder").apply { isAccessible = true }.get(manager) as MutableList<String>
        order.addAll(listOf("base", "hat"))
        `when`(plugin.config).thenReturn(org.bukkit.configuration.file.YamlConfiguration())
        return mannequins to mannequin
    }

    @Test fun `remove releases pending flash references before and after delayed callback`() {
        val (mannequins, mannequin) = liveFixture()
        var callback: Runnable? = null
        `when`(plugin.server.scheduler.runTaskLater(eq(plugin), any(Runnable::class.java), eq(10L))).thenAnswer {
            callback = it.getArgument(1)
            mock(org.bukkit.scheduler.BukkitTask::class.java).also { task -> `when`(task.taskId).thenReturn(71) }
        }
        mannequins.handleButtonClick("layer", mannequin.id, player, false)
        assertNotNull(callback, "Actual layer navigation must schedule the flash")
        assertTrue(mannequins.referencesPart("hat", option.id))
        mannequins.remove(mannequin.id, emptyList())
        assertFalse(mannequins.referencesPart("hat", option.id))
        assertTrue(fieldMap<Pair<UUID, String>, Int>(mannequins, "activeFlashRestoreTask").isEmpty())
        verify(plugin.server.scheduler).cancelTask(71)
        callback!!.run()
        assertFalse(mannequins.referencesPart("hat", option.id))
        `when`(player.hasPermission("sneakymannequins.command.part.globalize")).thenReturn(true)
        val stack = mock(io.papermc.paper.command.brigadier.CommandSourceStack::class.java)
        `when`(stack.sender).thenReturn(player)
        command(mannequins).execute(stack, arrayOf("part", "globalize", "hat", option.id))
        assertFalse(Files.exists(option.directory!!))
    }

    @Test fun `reload releases pending flash references before and after delayed callback`() {
        val (mannequins, mannequin) = liveFixture()
        var callback: Runnable? = null
        `when`(plugin.server.scheduler.runTaskLater(eq(plugin), any(Runnable::class.java), eq(10L))).thenAnswer {
            callback = it.getArgument(1)
            mock(org.bukkit.scheduler.BukkitTask::class.java).also { task -> `when`(task.taskId).thenReturn(71) }
        }
        mannequins.handleButtonClick("layer", mannequin.id, player, false)
        assertNotNull(callback, "Actual layer navigation must schedule the flash")
        assertTrue(mannequins.referencesPart("hat", option.id))
        mannequins.reloadAll()
        assertFalse(mannequins.referencesPart("hat", option.id))
        assertTrue(fieldMap<Pair<UUID, String>, Int>(mannequins, "activeFlashRestoreTask").isEmpty())
        verify(plugin.server.scheduler).cancelTask(71)
        callback!!.run()
        assertFalse(mannequins.referencesPart("hat", option.id))
        `when`(player.hasPermission("sneakymannequins.command.part.globalize")).thenReturn(true)
        val stack = mock(io.papermc.paper.command.brigadier.CommandSourceStack::class.java)
        `when`(stack.sender).thenReturn(player)
        command(mannequins).execute(stack, arrayOf("part", "globalize", "hat", option.id))
        assertFalse(Files.exists(option.directory!!))
    }

    @Test fun `rename immediately refreshes active status without changing selection`() {
        val (mannequins, mannequin) = liveFixture()
        val state = fieldMap<UUID, Any>(mannequins, "controlState")[mannequin.id]!!
        state.javaClass.getDeclaredField("layerIndex").apply { isAccessible = true }.setInt(state, 1)
        val update = mannequins.javaClass.getDeclaredMethod("updateStatus", UUID::class.java, String::class.java)
            .apply { isAccessible = true }
        update.invoke(mannequins, mannequin.id, "Hat: Old")
        val selection = mannequin.selection
        `when`(player.hasPermission("sneakymannequins.command.part.rename")).thenReturn(true)
        val stack = mock(io.papermc.paper.command.brigadier.CommandSourceStack::class.java)
        `when`(stack.sender).thenReturn(player)
        command(mannequins).execute(stack, arrayOf("part", "rename", "hat", option.id, "Fancy", "Hat"))
        assertEquals("Hat: Fancy Hat", fieldMap<UUID, String>(mannequins, "statusText")[mannequin.id])
        assertSame(selection, mannequin.selection)
        assertSame(option, mannequin.selection.selections["hat"]!!.option)
    }

    private fun globalize(): String {
        val method = manager.javaClass.methods.find { it.name == "globalizePart" }
        assertNotNull(method, "globalizePart behavior is not implemented")
        return method.invoke(manager, "hat", option.id) as String
    }

    @Test fun `globalize moves original directory and rebinds texture and mask paths`() {
        fixture()
        val old = option.directory!!
        Files.write(old.resolve("hat.png"), byteArrayOf(1, 2, 3))
        val metadata = Files.readString(old.resolve("metadata.json"))
        assertTrue(globalize().startsWith("Successfully"))
        assertFalse(Files.exists(old))
        assertContentEquals(byteArrayOf(1, 2, 3), Files.readAllBytes(root.resolve("hat/hat.png")))
        assertEquals(metadata, Files.readString(root.resolve("hat/metadata.json")))
        val updated = manager.allOptions("hat").single()
        assertEquals("hat", updated.id)
        assertNull(updated.owner)
        assertEquals(root.resolve("hat"), updated.directory)
    }

    @Test fun `asset mutations refuse a symlinked part directory`() {
        fixture()
        val directory = option.directory!!
        val outside = root.resolve("outside")
        Files.move(directory, outside)
        Files.createSymbolicLink(directory, outside)
        assertFalse(rename("Unsafe").startsWith("Successfully"))
        assertFalse(globalize().startsWith("Successfully"))
        assertFalse(manager.deletePart(player, "hat", option.id).startsWith("Successfully"))
        assertEquals("{\"displayName\":\"Old\",\"unknown\":{\"x\":3}}", Files.readString(outside.resolve("metadata.json")))
        assertEquals(listOf(option), manager.allOptions("hat"))
    }

    private fun command(mannequins: com.sneakymannequins.managers.MannequinManager =
        mock(com.sneakymannequins.managers.MannequinManager::class.java)): com.sneakymannequins.commands.CommandMannequin =
        com.sneakymannequins.commands.CommandMannequin(plugin,
            mannequins, manager,
            mock(com.sneakymannequins.managers.StyleManager::class.java),
            mock(com.sneakymannequins.managers.SessionManager::class.java),
            mock(com.sneakymannequins.managers.RemaskManager::class.java),
            mock(com.sneakymannequins.managers.EtfConfigManager::class.java),
            mock(com.sneakymannequins.listeners.OutfitSessionApplyCoordinator::class.java))

    @Test fun `rename permission alone admits root and dispatches multiword rename`() {
        fixture()
        `when`(player.hasPermission("sneakymannequins.command.part.rename")).thenReturn(true)
        val command = command()
        assertTrue(command.canUse(player))
        val stack = mock(io.papermc.paper.command.brigadier.CommandSourceStack::class.java)
        `when`(stack.sender).thenReturn(player)
        command.execute(stack, arrayOf("part", "rename", "hat", option.id, "Fancy", "Hat"))
        assertEquals("Fancy Hat", manager.allOptions("hat").single().displayName)
    }

    @Test fun `globalize refuses loader normalized standalone PNG collision`() {
        fixture()
        Files.write(root.resolve("HAT.png"), byteArrayOf(9))
        assertFalse(globalize().startsWith("Successfully"))
        assertTrue(Files.isDirectory(option.directory!!))
        assertEquals(listOf(option), manager.allOptions("hat"))
    }

    @Test fun `mixed case legacy deletion resolves canonical keys`() {
        fixture()
        `when`(player.hasPermission("sneakymannequins.command.me")).thenReturn(true)
        val stack = mock(io.papermc.paper.command.brigadier.CommandSourceStack::class.java)
        `when`(stack.sender).thenReturn(player)
        command().execute(stack, arrayOf("me", "delete", "HaT", owner.toString(), "HaT"))
        assertTrue(manager.allOptions("hat").isEmpty())
    }

    @Test fun `me delete shorthand infers owner without administrator permission`() {
        fixture()
        `when`(player.hasPermission("sneakymannequins.command.me")).thenReturn(true)
        val stack = mock(io.papermc.paper.command.brigadier.CommandSourceStack::class.java)
        `when`(stack.sender).thenReturn(player)
        val command = command()
        assertTrue(command.canUse(player))
        command.execute(stack, arrayOf("me", "delete", "HaT", "HaT"))
        assertTrue(manager.allOptions("hat").isEmpty())
        assertFalse(Files.exists(option.directory!!))
    }

    @Test fun `globalize refuses parts referenced by saved sessions rather than leaving stale IDs`() {
        fixture()
        val sessions = com.sneakymannequins.managers.SessionManager(plugin, root.toFile(), manager,
            mock(com.sneakymannequins.integrations.CharacterManagerBridge::class.java),
            mock(com.sneakymannequins.managers.StatsManager::class.java))
        `when`(plugin.sessionManager).thenReturn(sessions)
        @Suppress("UNCHECKED_CAST")
        val cache = sessions.javaClass.getDeclaredField("sessions").apply { isAccessible = true }
            .get(sessions) as MutableMap<String, com.sneakymannequins.model.SessionData>
        cache["saved"] = com.sneakymannequins.model.SessionData("saved", "now", false,
            mapOf("hat" to com.sneakymannequins.model.LayerSessionData(option.id)))
        assertFalse(globalize().startsWith("Successfully"))
        assertTrue(Files.isDirectory(option.directory!!))
    }

    @Test fun `globalize refuses active mannequin references including remembered selections`() {
        fixture()
        val mannequins = com.sneakymannequins.managers.MannequinManager(plugin, manager,
            mock(com.sneakymannequins.managers.StyleManager::class.java),
            mock(com.sneakymannequins.nms.VolatileHandler::class.java),
            mock(com.sneakymannequins.managers.MannequinPersistence::class.java),
            mock(com.sneakymannequins.managers.SessionManager::class.java),
            mock(com.sneakymannequins.integrations.CharacterManagerBridge::class.java),
            mock(com.sneakymouse.sneakyholos.HoloController::class.java))
        `when`(player.hasPermission("sneakymannequins.command.part.globalize")).thenReturn(true)
        @Suppress("UNCHECKED_CAST")
        val cache = mannequins.javaClass.getDeclaredField("mannequins").apply { isAccessible = true }
            .get(mannequins) as MutableMap<UUID, com.sneakymannequins.model.Mannequin>
        val mannequin = com.sneakymannequins.model.Mannequin(location = org.bukkit.Location(null, 0.0, 0.0, 0.0),
            selection = com.sneakymannequins.model.SkinSelection(mapOf("hat" to
                com.sneakymannequins.model.LayerSelection("hat", option))))
        cache[mannequin.id] = mannequin
        val stack = mock(io.papermc.paper.command.brigadier.CommandSourceStack::class.java)
        `when`(stack.sender).thenReturn(player)
        val command = command(mannequins)
        val args = arrayOf("part", "globalize", "hat", option.id)
        command.execute(stack, args)
        assertTrue(Files.isDirectory(option.directory!!))
        cache.clear()
        @Suppress("UNCHECKED_CAST")
        val remembered = mannequins.javaClass.getDeclaredField("partSelectionMemory").apply { isAccessible = true }
            .get(mannequins) as MutableMap<UUID, MutableMap<String, MutableMap<String, com.sneakymannequins.model.LayerSelection>>>
        remembered[mannequin.id] = mutableMapOf("hat" to mutableMapOf(option.id to
            com.sneakymannequins.model.LayerSelection("hat", option)))
        command.execute(stack, args)
        assertTrue(Files.isDirectory(option.directory!!))
        remembered.clear()
        @Suppress("UNCHECKED_CAST")
        val flashes = mannequins.javaClass.getDeclaredField("activeFlashRestoreSelection").apply { isAccessible = true }
            .get(mannequins) as MutableMap<Pair<UUID, String>, com.sneakymannequins.model.LayerSelection?>
        flashes[mannequin.id to "hat"] = com.sneakymannequins.model.LayerSelection("hat", option)
        command.execute(stack, args)
        assertTrue(Files.isDirectory(option.directory!!))
    }

    @Test fun `part completions filter actions by permission and expose stable IDs`() {
        fixture()
        `when`(player.hasPermission("sneakymannequins.command.part.rename")).thenReturn(true)
        val stack = mock(io.papermc.paper.command.brigadier.CommandSourceStack::class.java)
        `when`(stack.sender).thenReturn(player)
        val command = command()
        assertContains(command.suggest(stack, arrayOf("")), "part")
        assertEquals(listOf("rename"), command.suggest(stack, arrayOf("part", "")))
        assertEquals(listOf(option.id), command.suggest(stack, arrayOf("part", "rename", "hat", "")))
        assertTrue(command.suggest(stack, arrayOf("part", "globalize", "hat", "")).isEmpty())
    }

    @Test fun `me deletion completion offers own internal keys not foreign uploads`() {
        fixture()
        val other = UUID.randomUUID()
        manager.addOption("hat", option.copy(id = "$other:foreign", owner = other, internalKey = "foreign"))
        `when`(player.hasPermission("sneakymannequins.command.me")).thenReturn(true)
        val stack = mock(io.papermc.paper.command.brigadier.CommandSourceStack::class.java)
        `when`(stack.sender).thenReturn(player)
        val suggestions = command().suggest(stack, arrayOf("me", "delete", "hat", ""))
        assertContains(suggestions, "hat")
        assertFalse(suggestions.contains("foreign"))
        assertFalse(suggestions.contains(other.toString()))
    }

    @Test fun `globalize refuses texture paths outside original part`() {
        fixture()
        val outside = root.resolve("outside.png")
        Files.write(outside, byteArrayOf(8))
        @Suppress("UNCHECKED_CAST")
        val layers = LayerManager::class.java.getDeclaredField("loadedLayers").apply { isAccessible = true }
            .get(manager) as MutableMap<String, Pair<LayerDefinition, List<LayerOption>>>
        option = option.copy(fileMaster = outside)
        layers["hat"] = layers["hat"]!!.first to listOf(option)
        assertFalse(globalize().startsWith("Successfully"))
        assertTrue(Files.isDirectory(option.directory!!))
    }

    @Test fun `rename and globalize persist across real layer reload with masks and metadata`() {
        fixture()
        val directory = option.directory!!
        val image = java.awt.image.BufferedImage(64, 64, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, -1)
        javax.imageio.ImageIO.write(image, "png", directory.resolve("hat.png").toFile())
        javax.imageio.ImageIO.write(image, "png", directory.resolve("hat_mask_1.png").toFile())
        Files.writeString(directory.resolve("metadata.json"), """{"displayName":"Old","hasArms":true,"isDress":true,"dressLength":7,"unknown":42,"mappings":{"master":"hat.png","default":"hat.png","slim":"hat.png","masks":{"1":"hat_mask_1.png"}}}""")
        val config = org.bukkit.configuration.file.YamlConfiguration()
        config.set("layers.definitions.layers.hat.directory", ".")
        config.set("layers.definitions.layers.hat.allow-empty", false)
        `when`(plugin.config).thenReturn(config)
        `when`(plugin.dataFolder).thenReturn(root.toFile())
        manager.reload()
        option = manager.allOptions("hat").single()
        assertTrue(rename("Reloaded Hat").startsWith("Successfully"))
        manager.reload()
        option = manager.allOptions("hat").single()
        assertEquals("Reloaded Hat", option.displayName)
        assertTrue(globalize().startsWith("Successfully"))
        val moved = manager.allOptions("hat").single()
        assertEquals(root.resolve("hat/hat.png"), moved.fileMaster)
        assertEquals(root.resolve("hat/hat_mask_1.png"), moved.masks[1])
        manager.reload()
        val reloaded = manager.allOptions("hat").single()
        assertEquals("hat", reloaded.id)
        assertEquals("Reloaded Hat", reloaded.displayName)
        assertNull(reloaded.owner)
        assertEquals(7, reloaded.dressLength)
        assertTrue(reloaded.hasArms)
        assertTrue(reloaded.isDress)
        assertEquals(moved.fileMaster, reloaded.fileMaster)
        assertEquals(moved.masks, reloaded.masks)
        assertEquals(42, com.google.gson.JsonParser.parseString(Files.readString(reloaded.directory!!.resolve("metadata.json"))).asJsonObject.get("unknown").asInt)
        option = reloaded
        assertTrue(rename("Global Label").startsWith("Successfully"))
        manager.reload()
        assertEquals("Global Label", manager.allOptions("hat").single().displayName)
        assertEquals("hat", manager.allOptions("hat").single().id)
    }

    @Test fun `unauthorized action cannot use rename permission to globalize`() {
        fixture()
        `when`(player.hasPermission("sneakymannequins.command.part.rename")).thenReturn(true)
        val stack = mock(io.papermc.paper.command.brigadier.CommandSourceStack::class.java)
        `when`(stack.sender).thenReturn(player)
        command().execute(stack, arrayOf("part", "globalize", "hat", option.id))
        assertTrue(Files.isDirectory(option.directory!!))
        assertEquals(listOf(option), manager.allOptions("hat"))
        assertNull(command().permission())
    }

    @Test fun `legacy foreign deletion requires admin and shorthand never accepts foreign owner`() {
        fixture()
        val foreignPlayer = mock(Player::class.java)
        `when`(foreignPlayer.uniqueId).thenReturn(UUID.randomUUID())
        `when`(foreignPlayer.hasPermission("sneakymannequins.command.me")).thenReturn(true)
        val stack = mock(io.papermc.paper.command.brigadier.CommandSourceStack::class.java)
        `when`(stack.sender).thenReturn(foreignPlayer)
        command().execute(stack, arrayOf("me", "delete", "hat", owner.toString(), "hat"))
        assertTrue(Files.isDirectory(option.directory!!))
        `when`(foreignPlayer.hasPermission("sneakymannequins.admin")).thenReturn(true)
        command().execute(stack, arrayOf("me", "delete", "hat", option.id))
        assertTrue(Files.isDirectory(option.directory!!))
        command().execute(stack, arrayOf("me", "delete", "hat", owner.toString(), "hat"))
        assertFalse(Files.exists(option.directory!!))
        assertTrue(manager.allOptions("hat").isEmpty())
    }

    @Test fun `malformed metadata rename fails without changing cache or file`() {
        fixture()
        Files.writeString(option.directory!!.resolve("metadata.json"), "invalid json")
        assertFalse(rename("New").startsWith("Successfully"))
        assertEquals(listOf(option), manager.allOptions("hat"))
        assertEquals("invalid json", Files.readString(option.directory!!.resolve("metadata.json")))
    }

    @Test fun `partial cleanup quarantines deleted part without phantom cache or reload import`() {
        fixture()
        val directory = option.directory!!
        val nested = Files.createDirectory(directory.resolve("locked"))
        Files.writeString(nested.resolve("keep"), "value")
        Files.write(directory.resolve("hat.png"), byteArrayOf(1, 2, 3))
        Files.setPosixFilePermissions(nested, java.nio.file.attribute.PosixFilePermissions.fromString("r-x------"))
        try {
            val result = manager.deletePart(player, "hat", option.id)
            assertFalse(result.startsWith("Successfully"))
            assertTrue(manager.allOptions("hat").isEmpty(), "Part must not reference partially removed assets")
            assertFalse(Files.exists(directory))
            assertContains(result, "quarantined")
            val staged = Files.list(root.resolve("uploads/.part-deletions")).use { it.findFirst().orElseThrow() }
            // A genuine partial deletion: siblings were removed, but the protected child survived.
            assertFalse(Files.exists(staged.resolve("metadata.json")))
            assertFalse(Files.exists(staged.resolve("hat.png")))
            assertEquals("value", Files.readString(staged.resolve("locked/keep")))
            // Even an otherwise loadable remnant must remain invisible to the real loader.
            Files.writeString(staged.resolve("metadata.json"), """{"displayName":"Tombstone"}""")
            val image = java.awt.image.BufferedImage(64, 64, java.awt.image.BufferedImage.TYPE_INT_ARGB)
            image.setRGB(0, 0, -1)
            javax.imageio.ImageIO.write(image, "png", staged.resolve("hat.png").toFile())
            val config = org.bukkit.configuration.file.YamlConfiguration()
            config.set("layers.definitions.layers.hat.directory", ".")
            config.set("layers.definitions.layers.hat.allow-empty", false)
            `when`(plugin.config).thenReturn(config)
            `when`(plugin.dataFolder).thenReturn(root.toFile())
            manager.reload()
            assertTrue(manager.allOptions("hat").isEmpty(), "Quarantine must never be reimported")
        } finally {
            val quarantine = root.resolve("uploads/.part-deletions")
            val locked = if (Files.exists(nested)) nested else Files.list(quarantine).use {
                it.findFirst().orElseThrow().resolve("locked")
            }
            Files.setPosixFilePermissions(locked, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))
        }
    }

    @Test fun `failed rename and move preserve source files and cached identity`() {
        fixture()
        val directory = option.directory!!
        val metadata = Files.readString(directory.resolve("metadata.json"))
        Files.setPosixFilePermissions(directory, java.nio.file.attribute.PosixFilePermissions.fromString("r-x------"))
        try {
            assertFalse(rename("New").startsWith("Successfully"))
            assertEquals(metadata, Files.readString(directory.resolve("metadata.json")))
            assertEquals(listOf(option), manager.allOptions("hat"))
        } finally {
            Files.setPosixFilePermissions(directory, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))
        }
        Files.setPosixFilePermissions(root, java.nio.file.attribute.PosixFilePermissions.fromString("r-x------"))
        try {
            assertFalse(globalize().startsWith("Successfully"))
            assertEquals(metadata, Files.readString(directory.resolve("metadata.json")))
            assertEquals(listOf(option), manager.allOptions("hat"))
            assertFalse(Files.exists(root.resolve("hat")))
        } finally {
            Files.setPosixFilePermissions(root, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))
        }
    }

    @Test fun `missing directory does not evict cached part or claim deletion success`() {
        fixture()
        Files.delete(option.directory!!.resolve("metadata.json"))
        Files.delete(option.directory!!)
        assertFalse(manager.deletePart(player, "hat", option.id).startsWith("Successfully"))
        assertEquals(listOf(option), manager.allOptions("hat"))
    }
}
