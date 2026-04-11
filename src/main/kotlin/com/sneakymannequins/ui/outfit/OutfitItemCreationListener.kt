package com.sneakymannequins.ui.outfit

import com.sneakymannequins.SneakyMannequins
import com.sneakymannequins.items.OutfitItem
import com.sneakymannequins.managers.LayerManager
import com.sneakymouse.sneakyholos.util.TextUtility
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.persistence.PersistentDataType

class OutfitItemCreationListener(
        private val plugin: SneakyMannequins,
        private val layerManager: LayerManager,
        private val nameChat: OutfitNameChatListener
) : Listener {

    @EventHandler(ignoreCancelled = false)
    fun onClick(event: InventoryClickEvent) {
        val top = event.view.topInventory
        if (top.holder !is OutfitMainGuiHolder) return

        if (event.clickedInventory != top) {
            event.isCancelled = true
            return
        }

        event.isCancelled = true

        val player = event.whoClicked as? Player ?: return
        val item = event.currentItem ?: return
        val meta = item.itemMeta ?: return
        val action =
                meta.persistentDataContainer.get(
                        OutfitItem.guiActionKey(plugin),
                        PersistentDataType.STRING
                )
                        ?: return

        val draft = OutfitItemCreationService.get(player.uniqueId) ?: return

        Bukkit.getScheduler()
                .runTask(
                        plugin,
                        Runnable {
                            when (action) {
                                "icon" -> {
                                    OutfitGuiSessionGuard.beginMainGuiIntentionalClose(
                                            player.uniqueId
                                    )
                                    OutfitIconPickerUi.open(plugin, player, 0)
                                }
                                "name" -> {
                                    OutfitGuiSessionGuard.beginMainGuiIntentionalClose(
                                            player.uniqueId
                                    )
                                    player.closeInventory()
                                    player.sendMessage(
                                            TextUtility.convertToComponent(
                                                    "&eType the outfit item name in chat (max 30 characters, no color codes)."
                                            )
                                    )
                                    nameChat.expectName(player) { name ->
                                        draft.displayNamePlain = name
                                        OutfitItemCreationUi.reopenMain(plugin, layerManager, player)
                                    }
                                }
                                "preview" -> {
                                    if (!OutfitItem.isGuiPreviewStack(meta.persistentDataContainer, plugin)) {
                                        return@Runnable
                                    }
                                    val displayName =
                                            draft.displayNamePlain?.let {
                                                Component.text(it).color(NamedTextColor.GREEN)
                                            }
                                    val finalStack =
                                            OutfitItem.build(
                                                    plugin,
                                                    layerManager,
                                                    draft.uid,
                                                    draft.layers,
                                                    material = draft.material,
                                                    customModelData = draft.customModelData,
                                                    displayName = displayName,
                                                    guiPreview = false
                                            )
                                    player.inventory.addItem(finalStack)
                                    OutfitItemCreationService.remove(player.uniqueId)
                                    player.closeInventory()
                                    player.sendMessage(
                                            TextUtility.convertToComponent(
                                                    "&aOutfit item added. &7Right-click to apply; 5s cooldown between uses."
                                            )
                                    )
                                }
                            }
                        }
                )
    }

    @EventHandler
    fun onDrag(event: InventoryDragEvent) {
        if (event.inventory.holder is OutfitMainGuiHolder) {
            event.isCancelled = true
        }
    }
}
