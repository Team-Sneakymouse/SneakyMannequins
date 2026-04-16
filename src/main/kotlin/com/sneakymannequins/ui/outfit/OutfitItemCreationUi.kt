package com.sneakymannequins.ui.outfit

import com.sneakymannequins.SneakyMannequins
import com.sneakymannequins.items.ItemModelApplier
import com.sneakymannequins.items.OutfitItem
import com.sneakymannequins.managers.LayerManager
import com.sneakymannequins.model.SessionData
import com.sneakymouse.sneakyholos.util.TextUtility
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

object OutfitItemCreationUi {

    fun open(plugin: SneakyMannequins, layerManager: LayerManager, player: Player, session: SessionData) {
        val draft = OutfitItemDraft(uid = session.uid, layers = session.layers)
        OutfitItemCreationService.put(player.uniqueId, draft)
        val cfg = plugin.outfitItemGuiConfig
        val holder = OutfitMainGuiHolder(plugin, player.uniqueId, draft, cfg.mainTitle)
        populate(holder.getInventory(), plugin, layerManager, cfg, draft)
        player.openInventory(holder.getInventory())
    }

    fun refresh(plugin: SneakyMannequins, layerManager: LayerManager, player: Player) {
        val draft = OutfitItemCreationService.get(player.uniqueId) ?: return
        val open = player.openInventory.topInventory
        if (open.holder !is OutfitMainGuiHolder) return
        populate(open, plugin, layerManager, plugin.outfitItemGuiConfig, draft)
    }

    /** Opens the main outfit GUI again without replacing the stored draft (e.g. after chat name entry). */
    fun reopenMain(plugin: SneakyMannequins, layerManager: LayerManager, player: Player) {
        val draft = OutfitItemCreationService.get(player.uniqueId) ?: return
        val cfg = plugin.outfitItemGuiConfig
        val holder = OutfitMainGuiHolder(plugin, player.uniqueId, draft, cfg.mainTitle)
        populate(holder.getInventory(), plugin, layerManager, cfg, draft)
        player.openInventory(holder.getInventory())
    }

    private fun populate(
            inv: org.bukkit.inventory.Inventory,
            plugin: SneakyMannequins,
            layerManager: LayerManager,
            cfg: OutfitItemGuiConfig,
            draft: OutfitItemDraft
    ) {
        for (i in 0 until inv.size) {
            inv.setItem(i, null)
        }

        inv.setItem(cfg.slotCorner, cfg.cornerItem.clone())

        inv.setItem(
                cfg.slotIconButton,
                actionButton(
                        plugin,
                        cfg.iconButtonMaterial,
                        "&eIcon",
                        "&7Choose item appearance",
                        "icon"
                )
        )
        inv.setItem(
                cfg.slotNameButton,
                actionButton(
                        plugin,
                        cfg.nameButtonMaterial,
                        "&eName",
                        "&7Set display name in chat",
                        "name"
                )
        )

        val displayName =
                draft.displayNamePlain?.let { Component.text(it).color(NamedTextColor.GREEN) }
        val preview =
                OutfitItem.build(
                        plugin,
                        layerManager,
                        draft.uid,
                        draft.layers,
                        material = draft.material,
                        customModelData = draft.customModelData,
                        displayName = displayName,
                        guiPreview = true
                )
        preview.itemMeta =
                preview.itemMeta?.also { m ->
                    ItemModelApplier.apply(
                            m,
                            ItemModelApplier.Spec(
                                    itemModel = draft.itemModel,
                                    customModelDataFloats = draft.customModelDataFloats,
                                    legacyCustomModelData = draft.customModelData
                            )
                    )
                    m.persistentDataContainer.set(
                            OutfitItem.guiActionKey(plugin),
                            PersistentDataType.STRING,
                            "preview"
                    )
                }
        inv.setItem(cfg.slotPreview, preview)
    }

    private fun actionButton(
            plugin: SneakyMannequins,
            material: org.bukkit.Material,
            name: String,
            loreLine: String,
            action: String
    ): ItemStack {
        val stack = ItemStack(material, 1)
        stack.itemMeta =
                stack.itemMeta?.also { m ->
                    m.displayName(TextUtility.convertToComponent(name))
                    m.lore(listOf(TextUtility.convertToComponent(loreLine)))
                    m.persistentDataContainer.set(
                            OutfitItem.guiActionKey(plugin),
                            PersistentDataType.STRING,
                            action
                    )
                }
        return stack
    }
}
