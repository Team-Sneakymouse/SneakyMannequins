package com.sneakymannequins.ui.outfit

import com.sneakymannequins.SneakyMannequins
import com.sneakymannequins.items.OutfitItem
import com.sneakymouse.sneakyholos.util.TextUtility
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

object OutfitIconPickerUi {

    fun open(plugin: SneakyMannequins, player: Player, page: Int) {
        val cfg = plugin.outfitItemGuiConfig
        val holder = OutfitIconPickerHolder(plugin, player.uniqueId, page, cfg.iconPickerTitle)
        val inv = holder.getInventory()
        val icons = cfg.icons

        val start = page * 45
        icons.drop(start).take(45).forEachIndexed { index, icon ->
            inv.setItem(index, iconStack(plugin, icon.material, icon.modelData))
        }

        val hasPrev = page > 0
        val hasNext = icons.size > (page + 1) * 45

        if (hasPrev) {
            inv.setItem(
                    45,
                    navButton(plugin, "prev_page", "&ePrevious page")
            )
        }
        if (hasNext) {
            inv.setItem(
                    53,
                    navButton(plugin, "next_page", "&eNext page")
            )
        }

        inv.setItem(cfg.iconPickerDecorationSlot, cfg.iconPickerDecoration.clone())

        player.openInventory(inv)
    }

    private fun navButton(plugin: SneakyMannequins, id: String, name: String): ItemStack {
        return ItemStack(Material.ARROW, 1).apply {
            itemMeta =
                    itemMeta?.also { m ->
                        m.displayName(TextUtility.convertToComponent(name))
                        m.persistentDataContainer.set(
                                OutfitItem.guiActionKey(plugin),
                                PersistentDataType.STRING,
                                id
                        )
                    }
        }
    }

    private fun iconStack(plugin: SneakyMannequins, material: Material, modelData: Int): ItemStack {
        return ItemStack(material, 1).apply {
            itemMeta =
                    itemMeta?.also { m ->
                        if (modelData != 0) {
                            m.setCustomModelData(modelData)
                        }
                        m.isHideTooltip = true
                        m.persistentDataContainer.set(
                                OutfitItem.iconPickerDataKey(plugin),
                                PersistentDataType.STRING,
                                "${material.name},$modelData"
                        )
                    }
        }
    }
}
