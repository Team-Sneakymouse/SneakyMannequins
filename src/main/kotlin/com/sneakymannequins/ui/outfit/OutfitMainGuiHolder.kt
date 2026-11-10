package com.sneakymannequins.ui.outfit

import com.sneakymannequins.SneakyMannequins
import com.sneakyutils.util.DisplayOnlyInventoryHolder
import java.util.UUID
import org.bukkit.Bukkit
import org.bukkit.inventory.Inventory

class OutfitMainGuiHolder(
        val plugin: SneakyMannequins,
        val playerId: UUID,
        val draft: OutfitItemDraft,
        title: net.kyori.adventure.text.Component
) : DisplayOnlyInventoryHolder {

    private val backing: Inventory = Bukkit.createInventory(this, 27, title)

    override fun getInventory(): Inventory = backing
}
