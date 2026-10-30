package com.sneakymannequins

import com.sneakymannequins.managers.*
import com.sneakymannequins.integrations.CharacterManagerBridge
import com.sneakymannequins.model.Mannequin
import com.sneakymannequins.model.SkinSelection
import com.sneakymannequins.nms.VolatileHandler
import com.sneakymouse.sneakyholos.HoloController
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Item
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.player.PlayerDropItemEvent
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import java.util.UUID
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import org.bukkit.event.EventPriority

class MannequinDropGuardTest {
    private val config = YamlConfiguration()
    private val plugin = mock(SneakyMannequins::class.java, CALLS_REAL_METHODS)
    private val world = mock(World::class.java)
    private val player = mock(Player::class.java)
    private val item = mock(Item::class.java)
    private val manager: MannequinManager
    private val mannequins: MutableMap<UUID, Mannequin>

    init {
        doReturn(config).`when`(plugin).config
        manager = MannequinManager(plugin, mock(LayerManager::class.java), mock(StyleManager::class.java),
            mock(VolatileHandler::class.java), mock(MannequinPersistence::class.java),
            mock(SessionManager::class.java), mock(CharacterManagerBridge::class.java), mock(HoloController::class.java))
        SneakyMannequins::class.java.getDeclaredField("mannequinManager").apply { isAccessible = true }.set(plugin, manager)
        @Suppress("UNCHECKED_CAST")
        mannequins = MannequinManager::class.java.getDeclaredField("mannequins").apply { isAccessible = true }
            .get(manager) as MutableMap<UUID, Mannequin>
        val mannequin = Mannequin(location = Location(world, 0.0, 0.0, 0.0), selection = mock(SkinSelection::class.java))
        mannequins[mannequin.id] = mannequin
    }

    // Discover the actual annotated plugin entrypoint, so absence is a behavioral RED, not a compile error.
    private fun drop(location: Location): PlayerDropItemEvent {
        `when`(player.location).thenReturn(location)
        val event = PlayerDropItemEvent(player, item)
        plugin.javaClass.methods.filter {
            it.getAnnotation(EventHandler::class.java) != null &&
                it.parameterTypes.contentEquals(arrayOf(PlayerDropItemEvent::class.java))
        }.forEach { it.invoke(plugin, event) }
        return event
    }

    @Test
    fun `nearby player without active controls cannot drop items`() {
        assertTrue(drop(Location(world, 6.0, 0.0, 0.0)).isCancelled)
    }

    @Test
    fun `boundary is inclusive in three dimensions`() {
        assertTrue(drop(Location(world, 2.0, 3.0, 6.0)).isCancelled)
        assertTrue(drop(Location(world, 0.0, -7.0, 0.0)).isCancelled)
    }

    @Test
    fun `outside sphere remains droppable even inside bounding cube`() {
        assertFalse(drop(Location(world, 7.0001, 0.0, 0.0)).isCancelled)
        assertFalse(drop(Location(world, 5.0, 5.0, 0.0)).isCancelled)
    }

    @Test
    fun `another world is not protected`() {
        assertFalse(drop(Location(mock(World::class.java), 0.0, 0.0, 0.0)).isCancelled)
    }

    @Test
    fun `no tracked mannequins means no protection`() {
        mannequins.clear()
        assertFalse(drop(Location(world, 0.0, 0.0, 0.0)).isCancelled)
    }

    @Test
    fun `radius changes are read from current config and use interaction minimum`() {
        config.set("controls.interact-radius", 8.0)
        assertTrue(drop(Location(world, 10.0, 0.0, 0.0)).isCancelled)
        config.set("controls.interact-radius", -1.0)
        assertFalse(drop(Location(world, 2.5001, 0.0, 0.0)).isCancelled)
        assertTrue(drop(Location(world, 2.5, 0.0, 0.0)).isCancelled)
    }

    @Test
    fun `preexisting cancellation is never cleared`() {
        val event = PlayerDropItemEvent(player, item).apply { isCancelled = true }
        `when`(player.location).thenReturn(Location(world, 100.0, 0.0, 0.0))
        plugin.onDropItem(event)
        assertTrue(event.isCancelled)
    }

    @Test
    fun `listener only cancels without inventory or dropped entity mutation`() {
        clearInvocations(player, item, world)
        assertTrue(drop(Location(world, 0.0, 0.0, 0.0)).isCancelled)
        verify(player).location
        verifyNoMoreInteractions(player)
        verifyNoInteractions(item, world)
    }

    @Test
    fun `guard runs after existing Q controls and ignores their cancellation`() {
        val guard = SneakyMannequins::class.java.getMethod("onDropItem", PlayerDropItemEvent::class.java)
            .getAnnotation(EventHandler::class.java)
        assertEquals(EventPriority.HIGHEST, guard.priority)
        assertTrue(guard.ignoreCancelled)
        for (type in listOf(RemaskManager::class.java, EtfConfigManager::class.java)) {
            val controls = type.getMethod("onDropItem", PlayerDropItemEvent::class.java)
                .getAnnotation(EventHandler::class.java)
            assertEquals(EventPriority.HIGH, controls.priority)
            assertTrue(controls.ignoreCancelled)
        }
    }
}
