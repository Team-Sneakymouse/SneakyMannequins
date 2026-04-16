package com.sneakymannequins.ui.outfit

import com.sneakymannequins.SneakyMannequins
import com.sneakymannequins.items.ItemModelApplier
import com.sneakymouse.sneakyholos.util.TextUtility
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.inventory.ItemStack

/** Loads outfit maker GUI layout from plugin config. */
class OutfitItemGuiConfig(private val plugin: SneakyMannequins) {

    lateinit var mainTitle: Component
        private set
    lateinit var iconPickerTitle: Component
        private set

    var slotIconButton: Int = 12
        private set
    var slotNameButton: Int = 14
        private set
    var slotPreview: Int = 16
        private set
    var slotCorner: Int = 26
        private set

    lateinit var cornerItem: ItemStack
        private set

    var iconPickerDecorationSlot: Int = 52
        private set
    lateinit var iconPickerDecoration: ItemStack
        private set

    var iconButtonMaterial: Material = Material.PAINTING
        private set
    var nameButtonMaterial: Material = Material.NAME_TAG
        private set

    private val loadedIcons = mutableListOf<IconEntry>()

    val icons: List<IconEntry>
        get() = loadedIcons.toList()

    fun reload() {
        val cfg = plugin.config
        val root = cfg.getConfigurationSection("outfit-item-gui") ?: run {
            applyDefaults()
            return
        }

        mainTitle = TextUtility.convertToComponent(root.getString("title") ?: "&8Outfit Maker")
        iconPickerTitle =
                TextUtility.convertToComponent(
                        root.getString("icon-picker-title") ?: "&6Select icon"
                )

        slotIconButton = root.getInt("slots.icon-button", 12).coerceIn(0, 26)
        slotNameButton = root.getInt("slots.name-button", 14).coerceIn(0, 26)
        slotPreview = root.getInt("slots.preview", 16).coerceIn(0, 26)
        slotCorner = root.getInt("slots.corner-decoration", 26).coerceIn(0, 26)

        cornerItem = parseDecorItem(root.getConfigurationSection("corner-item"))

        iconPickerDecorationSlot =
                root.getInt("icon-picker-decoration-slot", 52).coerceIn(0, 53)
        iconPickerDecoration =
                parseDecorItem(root.getConfigurationSection("icon-picker-decoration"))

        iconButtonMaterial =
                Material.matchMaterial(root.getString("buttons.icon-material") ?: "painting")
                        ?: Material.PAINTING
        nameButtonMaterial =
                Material.matchMaterial(root.getString("buttons.name-material") ?: "name_tag")
                        ?: Material.NAME_TAG

        loadedIcons.clear()
        loadedIcons.addAll(loadIconsFromConfig(cfg))
    }

    private fun applyDefaults() {
        mainTitle = TextUtility.convertToComponent("&8Outfit Maker")
        iconPickerTitle = TextUtility.convertToComponent("&6Select icon")
        slotIconButton = 12
        slotNameButton = 14
        slotPreview = 16
        slotCorner = 26
        cornerItem = defaultJigsawDecor(3047)
        iconPickerDecorationSlot = 52
        iconPickerDecoration = defaultJigsawDecor(3050)
        iconButtonMaterial = Material.PAINTING
        nameButtonMaterial = Material.NAME_TAG
        loadedIcons.clear()
        loadedIcons.addAll(defaultIconList())
    }

    private fun parseDecorItem(section: org.bukkit.configuration.ConfigurationSection?): ItemStack {
        if (section == null) return defaultJigsawDecor(3047)
        val mat = Material.matchMaterial(section.getString("material") ?: "jigsaw") ?: Material.JIGSAW
        val stack = ItemStack(mat, 1)
        val meta = stack.itemMeta ?: return stack
        ItemModelApplier.apply(meta, parseModelSpec(section))
        meta.isHideTooltip = section.getBoolean("hide-tooltip", true)
        stack.itemMeta = meta
        return stack
    }

    private fun defaultJigsawDecor(cmd: Int): ItemStack {
        return ItemStack(Material.JIGSAW, 1).apply {
            itemMeta =
                    itemMeta?.also { m ->
                        ItemModelApplier.apply(
                                m,
                                ItemModelApplier.Spec(legacyCustomModelData = cmd)
                        )
                        m.isHideTooltip = true
                    }
        }
    }

    private fun parseModelSpec(sec: ConfigurationSection?): ItemModelApplier.Spec? {
        if (sec == null) return null
        val itemModel = sec.getString("item-model")?.trim().takeIf { !it.isNullOrEmpty() }
        val floatsRaw = sec.get("custom-model-data-floats")
        val floats = parseFloatList(floatsRaw).takeIf { it.isNotEmpty() }
        val range = sec.getString("custom-model-data-floats-range")?.trim()
        val rangeFloats = range?.let { parseFloatRange(it) }?.takeIf { it.isNotEmpty() }
        val mergedFloats = ((floats ?: emptyList()) + (rangeFloats ?: emptyList())).distinct()
        val legacy =
                sec.getInt("custom-model-data").takeIf { sec.contains("custom-model-data") }
        if (itemModel == null && mergedFloats.isEmpty() && legacy == null) return null
        return ItemModelApplier.Spec(
                itemModel = itemModel,
                customModelDataFloats = mergedFloats.takeIf { it.isNotEmpty() },
                legacyCustomModelData = legacy
        )
    }

    private fun loadIconsFromConfig(cfg: FileConfiguration): List<IconEntry> {
        val section = cfg.getConfigurationSection("outfit-item-icons") ?: return defaultIconList()
        val out = mutableListOf<IconEntry>()
        for (materialKey in section.getKeys(false)) {
            val material = Material.matchMaterial(materialKey) ?: continue
            val raw = section.getList(materialKey) ?: section.getStringList(materialKey)
            for (entry in raw) {
                when (entry) {
                    is String -> out.addAll(parseLegacyOrRange(material, entry))
                    is Number -> out.add(IconEntry(material = material, legacyModelData = entry.toInt()))
                    is Map<*, *> -> out.addAll(parseComponentIcon(material, entry))
                }
            }
        }
        return out.ifEmpty { defaultIconList() }
    }

    private fun parseLegacyOrRange(material: Material, entry: String): List<IconEntry> {
        val e = entry.trim()
        if (e.isEmpty()) return emptyList()
        if (e.contains("-")) {
            val parts = e.split("-", limit = 2)
            val start = parts[0].trim().toIntOrNull() ?: return emptyList()
            val end = parts[1].trim().toIntOrNull() ?: return emptyList()
            if (end < start) return emptyList()
            return (start..end).map { md -> IconEntry(material = material, legacyModelData = md) }
        }
        val md = e.toIntOrNull() ?: return emptyList()
        return listOf(IconEntry(material = material, legacyModelData = md))
    }

    private fun parseComponentIcon(material: Material, entry: Map<*, *>): List<IconEntry> {
        val itemModel = (entry["item-model"] as? String)?.trim().takeIf { !it.isNullOrEmpty() }

        val legacyModelData =
                when (val rawCmd = entry["custom-model-data"]) {
                    is Number -> rawCmd.toInt()
                    is String -> rawCmd.trim().toIntOrNull()
                    else -> null
                }

        val floats = parseFloatList(entry["custom-model-data-floats"])
        val range = (entry["custom-model-data-floats-range"] as? String)?.trim()
        val rangeFloats = range?.let { parseFloatRange(it) } ?: emptyList()
        val allFloats = (floats + rangeFloats).distinct()

        // Expand: each float becomes a distinct pickable entry.
        if (allFloats.isNotEmpty()) {
            return allFloats.map { f ->
                IconEntry(material = material, itemModel = itemModel, customModelDataFloats = listOf(f))
            }
        }

        // If no floats specified, still allow item-model-only (or legacy cmd) entry.
        return listOf(
                IconEntry(
                        material = material,
                        itemModel = itemModel,
                        legacyModelData = legacyModelData
                )
        )
    }

    private fun parseFloatList(raw: Any?): List<Float> {
        return when (raw) {
            is List<*> ->
                    raw.mapNotNull {
                        when (it) {
                            is Number -> it.toFloat()
                            is String -> it.trim().toFloatOrNull()
                            else -> null
                        }
                    }
            is Number -> listOf(raw.toFloat())
            is String -> listOfNotNull(raw.trim().toFloatOrNull())
            else -> emptyList()
        }
    }

    private fun parseFloatRange(raw: String): List<Float> {
        if (!raw.contains("-")) return emptyList()
        val parts = raw.split("-", limit = 2)
        val start = parts[0].trim().toIntOrNull() ?: return emptyList()
        val end = parts[1].trim().toIntOrNull() ?: return emptyList()
        if (end < start) return emptyList()
        return (start..end).map { it.toFloat() }
    }

    private fun defaultIconList(): List<IconEntry> {
        return listOf(
                IconEntry(material = Material.RABBIT_FOOT, legacyModelData = 0),
                IconEntry(material = Material.LEATHER_CHESTPLATE, legacyModelData = 0),
                IconEntry(material = Material.ARMOR_STAND, legacyModelData = 0)
        )
    }

    data class IconEntry(
            val material: Material,
            val itemModel: String? = null,
            val customModelDataFloats: List<Float>? = null,
            val legacyModelData: Int? = null
    )
}
