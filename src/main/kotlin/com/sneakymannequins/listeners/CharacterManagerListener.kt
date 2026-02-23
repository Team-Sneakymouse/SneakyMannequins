package com.sneakymannequins.listeners

import com.sneakymannequins.managers.AppliedSessionRegistry
import net.sneakycharactermanager.paper.handlers.character.CharacterSkinChangeEvent
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener

class CharacterManagerListener(
    private val appliedSessionRegistry: AppliedSessionRegistry
) : Listener {

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onCharacterSkinChange(event: CharacterSkinChangeEvent) {
        appliedSessionRegistry.clearCharacter(event.player.uniqueId, event.characterUUID)
    }
}
