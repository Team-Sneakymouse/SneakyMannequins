package com.sneakymannequins.events

import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import java.util.UUID

/**
 * Base event for all SneakyMannequins events.
 * Third-party plugins can listen to this to receive every mannequin event,
 * or listen to a specific subclass for targeted handling.
 * All events are [Cancellable]; cancelling prevents the default config
 * command triggers from executing and (where applicable) aborts the action.
 */
abstract class MannequinEvent(
    val mannequinId: UUID,
    val mannequinLocation: Location,
    val player: Player
) : Event(), Cancellable {
    private var cancelled = false
    override fun isCancelled() = cancelled
    override fun setCancelled(cancel: Boolean) { cancelled = cancel }
    override fun getHandlers(): HandlerList = handlerList
    companion object {
        @JvmStatic val handlerList = HandlerList()
    }
}

// ── Informational events (no extra fields) ──────────────────────────────────────

class MannequinFirstSeenEvent(
    mannequinId: UUID, location: Location, player: Player
) : MannequinEvent(mannequinId, location, player) {
    override fun getHandlers(): HandlerList = handlerList
    companion object { @JvmStatic val handlerList = HandlerList() }
}

class MannequinControlOpenEvent(
    mannequinId: UUID, location: Location, player: Player
) : MannequinEvent(mannequinId, location, player) {
    override fun getHandlers(): HandlerList = handlerList
    companion object { @JvmStatic val handlerList = HandlerList() }
}

class MannequinControlClosedEvent(
    mannequinId: UUID, location: Location, player: Player
) : MannequinEvent(mannequinId, location, player) {
    override fun getHandlers(): HandlerList = handlerList
    companion object { @JvmStatic val handlerList = HandlerList() }
}

class MannequinSubmenuOpenEvent(
    mannequinId: UUID, location: Location, player: Player
) : MannequinEvent(mannequinId, location, player) {
    override fun getHandlers(): HandlerList = handlerList
    companion object { @JvmStatic val handlerList = HandlerList() }
}

class MannequinSubmenuCloseEvent(
    mannequinId: UUID, location: Location, player: Player
) : MannequinEvent(mannequinId, location, player) {
    override fun getHandlers(): HandlerList = handlerList
    companion object { @JvmStatic val handlerList = HandlerList() }
}

// ── Events with extra data ──────────────────────────────────────────────────────

class MannequinHoverEvent(
    mannequinId: UUID, location: Location, player: Player,
    val button: String
) : MannequinEvent(mannequinId, location, player) {
    override fun getHandlers(): HandlerList = handlerList
    companion object { @JvmStatic val handlerList = HandlerList() }
}

class MannequinClickEvent(
    mannequinId: UUID, location: Location, player: Player,
    val button: String,
    val backwards: Boolean = false
) : MannequinEvent(mannequinId, location, player) {
    override fun getHandlers(): HandlerList = handlerList
    companion object { @JvmStatic val handlerList = HandlerList() }
}

class MannequinPartChangeEvent(
    mannequinId: UUID, location: Location, player: Player,
    val layer: String,
    val part: String
) : MannequinEvent(mannequinId, location, player) {
    override fun getHandlers(): HandlerList = handlerList
    companion object { @JvmStatic val handlerList = HandlerList() }
}

class MannequinColorChangeEvent(
    mannequinId: UUID, location: Location, player: Player,
    val layer: String,
    val channel: String,
    val color: java.awt.Color?,
    val colorName: String
) : MannequinEvent(mannequinId, location, player) {
    override fun getHandlers(): HandlerList = handlerList
    companion object { @JvmStatic val handlerList = HandlerList() }
}

class MannequinSessionSaveEvent(
    mannequinId: UUID, location: Location, player: Player,
    val uid: String
) : MannequinEvent(mannequinId, location, player) {
    override fun getHandlers(): HandlerList = handlerList
    companion object { @JvmStatic val handlerList = HandlerList() }
}

class MannequinSessionLoadEvent(
    mannequinId: UUID, location: Location, player: Player,
    val uid: String
) : MannequinEvent(mannequinId, location, player) {
    override fun getHandlers(): HandlerList = handlerList
    companion object { @JvmStatic val handlerList = HandlerList() }
}
