package com.sneakymannequins.listeners

import com.sneakymannequins.SneakyMannequins
import com.sneakymannequins.events.*
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener

/**
 * Internal listener that runs at [EventPriority.MONITOR] with `ignoreCancelled = true`.
 * It reads the matching config commands for each event and dispatches them with
 * placeholder substitution. Because MONITOR runs last, any third-party listener
 * that cancels the event will prevent command dispatch.
 */
class TriggerListener(private val plugin: SneakyMannequins) : Listener {

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onFirstSeen(event: MannequinFirstSeenEvent) {
        dispatch("first-seen", basePlaceholders(event))
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onControlOpen(event: MannequinControlOpenEvent) {
        dispatch("control-open", basePlaceholders(event))
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onControlClosed(event: MannequinControlClosedEvent) {
        dispatch("control-closed", basePlaceholders(event))
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onSubmenuOpen(event: MannequinSubmenuOpenEvent) {
        dispatch("submenu-open", basePlaceholders(event))
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onSubmenuClose(event: MannequinSubmenuCloseEvent) {
        dispatch("submenu-close", basePlaceholders(event))
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onHover(event: MannequinHoverEvent) {
        val ph = basePlaceholders(event).apply { put("button", event.button) }
        dispatch("hover", ph)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onClick(event: MannequinClickEvent) {
        val ph = basePlaceholders(event).apply { put("button", event.button) }
        dispatch("click", ph)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPartChange(event: MannequinPartChangeEvent) {
        val ph = basePlaceholders(event).apply {
            put("layer", event.layer)
            put("part", event.part)
        }
        val perLayer = plugin.config.getStringList("triggers.part-change.${event.layer}")
        val commands = perLayer.ifEmpty { plugin.config.getStringList("triggers.part-change.default") }
        dispatchCommands(commands, ph)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onColorChange(event: MannequinColorChangeEvent) {
        val ph = basePlaceholders(event).apply {
            put("layer", event.layer)
            put("channel", event.channel)
            put("color", event.colorName)
            put("color_code", event.color?.let { String.format("#%02X%02X%02X", it.red, it.green, it.blue) } ?: "")
            put("color_r", event.color?.let { String.format("%.3f", it.red / 255.0) } ?: "1.000")
            put("color_g", event.color?.let { String.format("%.3f", it.green / 255.0) } ?: "1.000")
            put("color_b", event.color?.let { String.format("%.3f", it.blue / 255.0) } ?: "1.000")
        }
        dispatch("color-change", ph)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onSessionSave(event: MannequinSessionSaveEvent) {
        val ph = basePlaceholders(event).apply { put("uid", event.uid) }
        dispatch("session-save", ph)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onSessionLoad(event: MannequinSessionLoadEvent) {
        val ph = basePlaceholders(event).apply { put("uid", event.uid) }
        dispatch("session-load", ph)
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private fun basePlaceholders(event: MannequinEvent): MutableMap<String, String> {
        val loc = event.mannequinLocation
        return mutableMapOf(
            "player" to event.player.name,
            "x" to String.format("%.2f", loc.x),
            "y" to String.format("%.2f", loc.y),
            "z" to String.format("%.2f", loc.z)
        )
    }

    private fun dispatch(configPath: String, placeholders: Map<String, String>) {
        val commands = plugin.config.getStringList("triggers.$configPath")
        dispatchCommands(commands, placeholders)
    }

    private fun dispatchCommands(commands: List<String>, placeholders: Map<String, String>) {
        if (commands.isEmpty()) return
        val consoleSender = plugin.server.consoleSender
        for (template in commands) {
            var cmd = template
            for ((key, value) in placeholders) {
                cmd = cmd.replace("{$key}", value)
            }
            if (cmd.isNotBlank()) {
                plugin.server.dispatchCommand(consoleSender, cmd)
            }
        }
    }
}
