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

    fun key(plugin: SneakyMannequins): NamespacedKey = NamespacedKey(plugin, PDC_KEY)

    fun readUid(pdc: PersistentDataContainer, plugin: SneakyMannequins): String? =
            pdc.get(key(plugin), PersistentDataType.STRING)

    fun hasUid(pdc: PersistentDataContainer, plugin: SneakyMannequins): Boolean =
            pdc.has(key(plugin), PersistentDataType.STRING)

    fun build(
            plugin: SneakyMannequins,
            layerManager: LayerManager,
            uid: String,
            layers: Map<String, LayerSessionData>
    ): ItemStack {
        val stack = ItemStack(Material.RABBIT_FOOT, 1)
        val meta = stack.itemMeta

        meta.displayName(Component.text("Outfit").color(NamedTextColor.GREEN))

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
                }
        meta.lore(lore)

        meta.persistentDataContainer.set(key(plugin), PersistentDataType.STRING, uid)
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

