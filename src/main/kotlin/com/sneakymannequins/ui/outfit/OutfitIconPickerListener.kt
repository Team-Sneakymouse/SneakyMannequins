package com.sneakymannequins.ui.outfit

import com.sneakymannequins.SneakyMannequins
import com.sneakymannequins.items.OutfitItem
import com.sneakymannequins.managers.LayerManager
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.persistence.PersistentDataType

class OutfitIconPickerListener(
        private val plugin: SneakyMannequins,
        private val layerManager: LayerManager
) : Listener {

    @EventHandler(ignoreCancelled = false)
    fun onClick(event: InventoryClickEvent) {
        val holder = event.inventory.holder as? OutfitIconPickerHolder ?: return

        if (event.clickedInventory != event.view.topInventory) {
            event.isCancelled = true
            return
        }

        event.isCancelled = true

        val player = event.whoClicked as? Player ?: return
        val cfg = plugin.outfitItemGuiConfig
        val slot = event.slot
        val current = event.currentItem ?: return
        val meta = current.itemMeta ?: return

        if (slot == cfg.iconPickerDecorationSlot) return

        meta.persistentDataContainer
                .get(OutfitItem.guiActionKey(plugin), PersistentDataType.STRING)
                ?.let { nav ->
                    Bukkit.getScheduler()
                            .runTask(
                                    plugin,
                                    Runnable {
                                        when (nav) {
                                            "prev_page" -> {
                                                OutfitGuiSessionGuard.beginIconPickerTransition(
                                                        player.uniqueId
                                                )
                                                OutfitIconPickerUi.open(
                                                        plugin,
                                                        player,
                                                        holder.page - 1
                                                )
                                            }
                                            "next_page" -> {
                                                OutfitGuiSessionGuard.beginIconPickerTransition(
                                                        player.uniqueId
                                                )
                                                OutfitIconPickerUi.open(
                                                        plugin,
                                                        player,
                                                        holder.page + 1
                                                )
                                            }
                                        }
                                    }
                            )
                    return
                }

        val data =
                meta.persistentDataContainer.get(
                        OutfitItem.iconPickerDataKey(plugin),
                        PersistentDataType.STRING
                )
                        ?: return
        val decoded = decodeIconPick(data) ?: return
        val material = decoded.first
        val model = decoded.second

        val draft = OutfitItemCreationService.get(player.uniqueId) ?: return
        draft.material = material
        draft.itemModel = model.itemModel
        draft.customModelDataFloats = model.customModelDataFloats
        draft.customModelData = model.legacyModelData

        Bukkit.getScheduler()
                .runTask(
                        plugin,
                        Runnable {
                            OutfitGuiSessionGuard.beginIconPickerTransition(player.uniqueId)
                            OutfitItemCreationUi.reopenMain(plugin, layerManager, player)
                        }
                )
    }

    private data class IconPickDecoded(
            val itemModel: String?,
            val customModelDataFloats: List<Float>?,
            val legacyModelData: Int?
    )

    private fun decodeIconPick(data: String): Pair<Material, IconPickDecoded>? {
        // v2|MATERIAL|itemModel|floatsCsv|legacyInt
        if (data.startsWith("v2|")) {
            val parts = data.split("|")
            if (parts.size < 3) return null
            val material = Material.matchMaterial(parts.getOrNull(1) ?: "") ?: return null
            val itemModel = parts.getOrNull(2)?.takeIf { it.isNotBlank() }
            val floats =
                    parts.getOrNull(3)
                            ?.split(',')
                            ?.mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() }?.toFloatOrNull() }
                            ?.takeIf { it.isNotEmpty() }
            val legacy = parts.getOrNull(4)?.trim()?.takeIf { it.isNotEmpty() }?.toIntOrNull()
            return material to
                    IconPickDecoded(
                            itemModel = itemModel,
                            customModelDataFloats = floats,
                            legacyModelData = legacy
                    )
        }

        // Legacy format: "MATERIAL,modelDataInt"
        val parts = data.split(",")
        if (parts.size != 2) return null
        val material = Material.matchMaterial(parts[0]) ?: return null
        val modelData = parts[1].toIntOrNull() ?: return null
        return material to IconPickDecoded(itemModel = null, customModelDataFloats = null, legacyModelData = modelData)
    }

    @EventHandler
    fun onDrag(event: InventoryDragEvent) {
        if (event.inventory.holder is OutfitIconPickerHolder) {
            event.isCancelled = true
        }
    }
}
