package com.sneakymannequins.ui.outfit

import com.sneakymannequins.SneakyMannequins
import java.util.UUID
import org.bukkit.Bukkit
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder

class OutfitIconPickerHolder(
        val plugin: SneakyMannequins,
        val playerId: UUID,
        val page: Int,
        title: net.kyori.adventure.text.Component
) : InventoryHolder {

    private val backing: Inventory = Bukkit.createInventory(this, 54, title)

    override fun getInventory(): Inventory = backing
}
