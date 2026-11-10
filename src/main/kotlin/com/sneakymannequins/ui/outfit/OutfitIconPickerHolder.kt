package com.sneakymannequins.ui.outfit

import com.sneakymannequins.SneakyMannequins
import com.sneakyutils.util.DisplayOnlyInventoryHolder
import java.util.UUID
import org.bukkit.Bukkit
import org.bukkit.inventory.Inventory

class OutfitIconPickerHolder(
        val plugin: SneakyMannequins,
        val playerId: UUID,
        val page: Int,
        title: net.kyori.adventure.text.Component
) : DisplayOnlyInventoryHolder {

    private val backing: Inventory = Bukkit.createInventory(this, 54, title)

    override fun getInventory(): Inventory = backing
}
