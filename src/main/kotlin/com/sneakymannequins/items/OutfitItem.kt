package com.sneakymannequins.items

import com.sneakymannequins.SneakyMannequins
import com.sneakymannequins.managers.LayerManager
import com.sneakymannequins.model.LayerSessionData
import java.util.Locale
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataContainer
import org.bukkit.persistence.PersistentDataType

object OutfitItem {
    private const val PDC_KEY = "outfit_uid"
    private const val GUI_ACTION_KEY = "outfit_gui_action"
    private const val GUI_PREVIEW_KEY = "outfit_gui_preview"
    private const val ICON_PICKER_DATA_KEY = "outfit_icon_pick"

    fun key(plugin: SneakyMannequins): NamespacedKey = NamespacedKey(plugin, PDC_KEY)

    fun guiActionKey(plugin: SneakyMannequins): NamespacedKey = NamespacedKey(plugin, GUI_ACTION_KEY)

    fun guiPreviewKey(plugin: SneakyMannequins): NamespacedKey = NamespacedKey(plugin, GUI_PREVIEW_KEY)

    fun iconPickerDataKey(plugin: SneakyMannequins): NamespacedKey = NamespacedKey(plugin, ICON_PICKER_DATA_KEY)

    fun readUid(pdc: PersistentDataContainer, plugin: SneakyMannequins): String? =
            pdc.get(key(plugin), PersistentDataType.STRING)

    fun hasUid(pdc: PersistentDataContainer, plugin: SneakyMannequins): Boolean =
            pdc.has(key(plugin), PersistentDataType.STRING)

    fun isGuiPreviewStack(pdc: PersistentDataContainer, plugin: SneakyMannequins): Boolean =
            pdc.has(guiPreviewKey(plugin), PersistentDataType.BYTE)

    fun build(
            plugin: SneakyMannequins,
            layerManager: LayerManager,
            uid: String,
            layers: Map<String, LayerSessionData>,
            material: Material = Material.RABBIT_FOOT,
            modelSpec: ItemModelApplier.Spec? = null,
            customModelData: Int? = null,
            displayName: Component? = null,
            guiPreview: Boolean = false
    ): ItemStack {
        val stack = ItemStack(material, 1)
        val meta = stack.itemMeta ?: return stack

        meta.displayName(displayName ?: Component.text("Outfit").color(NamedTextColor.GREEN))

        // Prefer modern component-based model spec; fall back to legacy integer CMD if provided.
        ItemModelApplier.apply(
                meta,
                modelSpec ?: ItemModelApplier.Spec(legacyCustomModelData = customModelData)
        )

        val defsById = layerManager.definitionsInOrder().associateBy { it.id }
        val orderedLayerIds =
                layerManager.definitionsInOrder().map { it.id }.filter { it in layers.keys } +
                        layers.keys.filter { it !in defsById.keys }.sorted()

        val lore =
                buildList {
                    add(
                            Component.text("Right-click to apply")
                                    .color(NamedTextColor.DARK_AQUA)
                    )
                    addAll(
                            orderedLayerIds.mapNotNull { layerId ->
                                val layerData = layers[layerId] ?: return@mapNotNull null
                                val layerName = defsById[layerId]?.displayName ?: beautify(layerId)
                                val optionId = layerData.option
                                val partName =
                                        if (optionId.isNullOrBlank()) {
                                            "None"
                                        } else {
                                            layerManager.findPartById(layerId, optionId)
                                                    ?.displayName ?: beautify(optionId)
                                        }
                                Component.text("$layerName: $partName")
                                        .color(NamedTextColor.GRAY)
                            }
                    )
                    if (guiPreview) {
                        add(Component.empty())
                        add(
                                Component.text("Click to add to your inventory")
                                        .color(NamedTextColor.YELLOW)
                        )
                    }
                }
        meta.lore(lore)

        val pdc = meta.persistentDataContainer
        pdc.set(key(plugin), PersistentDataType.STRING, uid)
        if (guiPreview) {
            pdc.set(guiPreviewKey(plugin), PersistentDataType.BYTE, 1)
        }

        stack.itemMeta = meta
        return stack
    }

    private fun beautify(id: String): String {
        val clean = id.replace('_', ' ').replace('-', ' ').trim()
        if (clean.isEmpty()) return id
        return clean.split(Regex("\\s+"))
                .joinToString(" ") { part ->
                    part.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }
                }
    }
}
