package com.sneakymannequins.items

import com.sneakymannequins.SneakyMannequins
import com.sneakymannequins.managers.LayerManager
import com.sneakymannequins.model.LayerSessionData
import com.sneakymannequins.ui.outfit.OutfitItemGuiConfig
import com.sneakymouse.sneakyholos.util.TextUtility
import java.util.Locale
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataContainer
import org.bukkit.persistence.PersistentDataType
import org.bukkit.entity.Player

object OutfitItem {
    private const val PDC_KEY = "outfit_uid"
    private const val GUI_ACTION_KEY = "outfit_gui_action"
    private const val GUI_PREVIEW_KEY = "outfit_gui_preview"
    private const val ICON_PICKER_DATA_KEY = "outfit_icon_pick"

    /** Default skin state name when not applying from an outfit item. */
    const val REGULAR_SKIN_STATE_NAME: String = "Regular"

    fun key(plugin: SneakyMannequins): NamespacedKey = NamespacedKey(plugin, PDC_KEY)

    fun guiActionKey(plugin: SneakyMannequins): NamespacedKey = NamespacedKey(plugin, GUI_ACTION_KEY)

    fun guiPreviewKey(plugin: SneakyMannequins): NamespacedKey = NamespacedKey(plugin, GUI_PREVIEW_KEY)

    fun iconPickerDataKey(plugin: SneakyMannequins): NamespacedKey = NamespacedKey(plugin, ICON_PICKER_DATA_KEY)

    fun readUid(pdc: PersistentDataContainer, plugin: SneakyMannequins): String? =
            pdc.get(key(plugin), PersistentDataType.STRING)

    /**
     * Visible item title as plain text (no color/decoration codes), for SneakyCharacterManager skin
     * state labels. Falls back to [REGULAR_SKIN_STATE_NAME] when absent or blank.
     */
    fun skinStateNameFromOutfitStack(stack: ItemStack): String {
        val meta = stack.itemMeta ?: return REGULAR_SKIN_STATE_NAME
        val comp = meta.displayName() ?: return REGULAR_SKIN_STATE_NAME
        val plain = PlainTextComponentSerializer.plainText().serialize(comp).trim()
        return plain.ifEmpty { REGULAR_SKIN_STATE_NAME }
    }

    fun hasUid(pdc: PersistentDataContainer, plugin: SneakyMannequins): Boolean =
            pdc.has(key(plugin), PersistentDataType.STRING)

    fun isGuiPreviewStack(pdc: PersistentDataContainer, plugin: SneakyMannequins): Boolean =
            pdc.has(guiPreviewKey(plugin), PersistentDataType.BYTE)

    /**
     * @param displayNamePlain Player-chosen name without formatting; combined with
     *   `outfit-item-gui.outfit-item.name-prefix` from config. If null, uses configured default
     *   display name.
     */
    fun build(
            plugin: SneakyMannequins,
            player: Player,
            layerManager: LayerManager,
            uid: String,
            layers: Map<String, LayerSessionData>,
            material: Material = Material.RABBIT_FOOT,
            modelSpec: ItemModelApplier.Spec? = null,
            customModelData: Int? = null,
            displayNamePlain: String? = null,
            guiPreview: Boolean = false
    ): ItemStack {
        val stack = ItemStack(material, 1)
        val meta = stack.itemMeta ?: return stack
        val cfg = plugin.outfitItemGuiConfig

        val nameBody =
                displayNamePlain?.ifBlank { null }
                        ?: cfg.outfitItemDefaultDisplayNamePlain
        meta.displayName(TextUtility.convertToComponent(cfg.outfitItemNamePrefix + nameBody))
        meta.setMaxStackSize(99)

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
                    for (line in cfg.outfitItemExtraLoreBeforeLines) {
                        add(TextUtility.convertToComponent(line))
                    }
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
                                procLoreLine(cfg, "$layerName: $partName")
                            }
                    )
                    for (line in cfg.outfitItemExtraLoreAfterLines) {
                        add(TextUtility.convertToComponent(line, player))
                    }
                    if (guiPreview) {
                        add(Component.empty())
                        add(procLoreLine(cfg, "Click to add to your inventory"))
                    }
                }
        meta.lore(lore)

        val pdc = meta.persistentDataContainer
        for ((dataKey, value) in cfg.outfitItemExtraPersistentData) {
            pdc.set(dataKey, PersistentDataType.STRING, value)
        }
        pdc.set(key(plugin), PersistentDataType.STRING, uid)
        if (guiPreview) {
            pdc.set(guiPreviewKey(plugin), PersistentDataType.BYTE, 1)
        }

        stack.itemMeta = meta
        return stack
    }

    private fun procLoreLine(cfg: OutfitItemGuiConfig, body: String) =
            TextUtility.convertToComponent(cfg.outfitItemLoreLinePrefix + body)

    private fun beautify(id: String): String {
        val clean = id.replace('_', ' ').replace('-', ' ').trim()
        if (clean.isEmpty()) return id
        return clean.split(Regex("\\s+"))
                .joinToString(" ") { part ->
                    part.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }
                }
    }
}
