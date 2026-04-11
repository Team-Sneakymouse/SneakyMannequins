package com.sneakymannequins.ui.outfit

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Suppresses automatic "reopen outfit GUI" logic when the inventory is closed on purpose (icon
 * picker, name chat, icon pagination, returning to main after picking an icon).
 */
object OutfitGuiSessionGuard {

    private val skipMainReopen = ConcurrentHashMap.newKeySet<UUID>()
    private val skipIconPickerReopenMain = ConcurrentHashMap.newKeySet<UUID>()

    /** Call immediately before closing the main outfit GUI to open the icon picker or name chat. */
    fun beginMainGuiIntentionalClose(playerId: UUID) {
        skipMainReopen.add(playerId)
    }

    fun consumeSkipMainReopen(playerId: UUID): Boolean = skipMainReopen.remove(playerId)

    /**
     * Call immediately before replacing the icon picker inventory (pagination, pick icon → main, or
     * opening the picker from main is **not** needed — use [beginMainGuiIntentionalClose] for that).
     */
    fun beginIconPickerTransition(playerId: UUID) {
        skipIconPickerReopenMain.add(playerId)
    }

    fun consumeSkipIconPickerReopenMain(playerId: UUID): Boolean =
            skipIconPickerReopenMain.remove(playerId)

    fun clearPlayer(playerId: UUID) {
        skipMainReopen.remove(playerId)
        skipIconPickerReopenMain.remove(playerId)
    }
}
