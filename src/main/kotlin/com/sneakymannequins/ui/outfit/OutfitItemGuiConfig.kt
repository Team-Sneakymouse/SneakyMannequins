package com.sneakymannequins.ui.outfit

import com.sneakymannequins.SneakyMannequins
import com.sneakymannequins.items.ItemModelApplier
import com.sneakymouse.sneakyholos.util.TextUtility
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.NamespacedKey
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

    /**
     * Base item for the icon picker “previous page” button (slot 45); cloned on each open, then
     * display name and GUI action are applied. Define [iconPickerNavPrevItem] via optional
     * `icon-picker-nav-prev` (same shape as `corner-item`) for custom model data; otherwise
     * `buttons.icon-picker-prev-material` is used.
     */
    lateinit var iconPickerNavPrevItem: ItemStack
        private set

    /**
     * Base item for the icon picker “next page” button (slot 53); see [iconPickerNavPrevItem].
     */
    lateinit var iconPickerNavNextItem: ItemStack
        private set

    /**
     * Legacy `&` / MiniMessage prefix (via [TextUtility]) prepended to the outfit item display
     * name: the player-entered name, or [outfitItemDefaultDisplayNamePlain] when unset.
     */
    var outfitItemNamePrefix: String = "&a"
        private set

    /** Plain text default when the player has not set a custom name (grant command, etc.). */
    var outfitItemDefaultDisplayNamePlain: String = "Outfit"
        private set

    /**
     * Prepended to each auto-generated layer summary line and the preview footer only. Not
     * applied to [outfitItemExtraLoreBeforeLines] / [outfitItemExtraLoreAfterLines] (those are full
     * lines).
     */
    var outfitItemLoreLinePrefix: String = ""
        private set

    /**
     * Full lore lines shown before procedurally generated layer summaries (no
     * [outfitItemLoreLinePrefix]).
     */
    var outfitItemExtraLoreBeforeLines: List<String> = emptyList()
        private set

    /**
     * Full lore lines shown after layer summaries and before the preview-only footer (no
     * [outfitItemLoreLinePrefix]).
     */
    var outfitItemExtraLoreAfterLines: List<String> = emptyList()
        private set

    /**
     * Additional PDC entries written before SneakyMannequins’ own keys (so ours override on
     * conflict). Keys are full `namespace:key` strings (any namespace, for interoperability).
     */
    var outfitItemExtraPersistentData: List<Pair<NamespacedKey, String>> = emptyList()
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
        iconPickerNavPrevItem =
                parseIconPickerNavTemplate(
                        root,
                        itemSectionKey = "icon-picker-nav-prev",
                        materialFallbackKey = "buttons.icon-picker-prev-material"
                )
        iconPickerNavNextItem =
                parseIconPickerNavTemplate(
                        root,
                        itemSectionKey = "icon-picker-nav-next",
                        materialFallbackKey = "buttons.icon-picker-next-material"
                )

        loadOutfitItemSettings(root.getConfigurationSection("outfit-item"))

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
        iconPickerNavPrevItem = ItemStack(Material.ARROW, 1)
        iconPickerNavNextItem = ItemStack(Material.ARROW, 1)
        applyOutfitItemSettingsDefaults()
        loadedIcons.clear()
        loadedIcons.addAll(defaultIconList())
    }

    private fun applyOutfitItemSettingsDefaults() {
        outfitItemNamePrefix = "&a"
        outfitItemDefaultDisplayNamePlain = "Outfit"
        outfitItemLoreLinePrefix = ""
        outfitItemExtraLoreBeforeLines = listOf("&3Right-click to apply")
        outfitItemExtraLoreAfterLines = emptyList()
        outfitItemExtraPersistentData = emptyList()
    }

    private fun loadOutfitItemSettings(section: ConfigurationSection?) {
        if (section == null) {
            applyOutfitItemSettingsDefaults()
            return
        }
        outfitItemNamePrefix = section.getString("name-prefix") ?: ""
        outfitItemDefaultDisplayNamePlain =
                section.getString("default-display-name") ?: "Outfit"
        outfitItemLoreLinePrefix = section.getString("lore-line-prefix") ?: ""
        val loreBefore = section.getStringList("extra-lore-before")
        val loreAfter = section.getStringList("extra-lore-after")
        val legacyExtraLore = section.getStringList("extra-lore")
        outfitItemExtraLoreBeforeLines = loreBefore
        outfitItemExtraLoreAfterLines =
                when {
                    loreAfter.isNotEmpty() -> loreAfter
                    legacyExtraLore.isNotEmpty() -> legacyExtraLore
                    else -> emptyList()
                }
        outfitItemExtraPersistentData =
                parseOutfitItemExtraPersistentData(section.get("extra-persistent-data"))
    }

    /**
     * Accepts a YAML **map** (`namespace:key: value`) or a **list** of single-key maps (as in
     * `- "ns:key": "value"`). A plain list does not become a [ConfigurationSection], so the
     * previous `getConfigurationSection`-only path never saw list-shaped data.
     */
    private fun parseOutfitItemExtraPersistentData(raw: Any?): List<Pair<NamespacedKey, String>> {
        return when (raw) {
            null -> emptyList()
            is ConfigurationSection ->
                    parseOutfitItemPersistentEntries(
                            raw.getKeys(false).associateWith { key -> raw.get(key) }
                    )
            is Map<*, *> ->
                    parseOutfitItemPersistentEntries(
                            raw.entries.associate { (k, v) -> k.toString() to v }
                    )
            is List<*> -> {
                val out = mutableListOf<Pair<NamespacedKey, String>>()
                raw.forEachIndexed { index, elem ->
                    when (elem) {
                        is Map<*, *> ->
                                out.addAll(
                                        parseOutfitItemPersistentEntries(
                                                elem.entries.associate { (k, v) ->
                                                    k.toString() to v
                                                }
                                        )
                                )
                        else ->
                                plugin.logger
                                        .warning(
                                                "[outfit-item-gui] outfit-item.extra-persistent-data[$index] must be a map (one namespace:key per entry); got: ${elem?.javaClass?.simpleName}"
                                        )
                    }
                }
                out
            }
            else -> {
                plugin.logger
                        .warning(
                                "[outfit-item-gui] outfit-item.extra-persistent-data must be a map or a list of maps; got: ${raw::class.simpleName}"
                        )
                emptyList()
            }
        }
    }

    private fun parseOutfitItemPersistentEntries(
            entries: Map<String, Any?>
    ): List<Pair<NamespacedKey, String>> {
        val out = mutableListOf<Pair<NamespacedKey, String>>()
        for ((rawKey, rawValue) in entries) {
            if (rawKey.isBlank()) continue
            val ns = NamespacedKey.fromString(rawKey)
            if (ns == null) {
                plugin.logger
                        .warning(
                                "[outfit-item-gui] Invalid namespaced key in outfit-item.extra-persistent-data (use namespace:key, e.g. otherplugin:foo): '$rawKey'"
                        )
                continue
            }
            val value =
                    when (rawValue) {
                        is String -> rawValue
                        null -> ""
                        else -> rawValue.toString()
                    }
            out.add(ns to value)
        }
        return out
    }

    /**
     * Optional `icon-picker-nav-prev` / `icon-picker-nav-next` blocks use the same keys as
     * `corner-item` (`material`, `custom-model-data`, `item-model`, `custom-model-data-floats`,
     * `custom-model-data-floats-range`, `hide-tooltip`). If absent, falls back to a plain stack
     * from `buttons.icon-picker-*-material`.
     */
    private fun parseIconPickerNavTemplate(
            root: ConfigurationSection,
            itemSectionKey: String,
            materialFallbackKey: String
    ): ItemStack {
        val section = root.getConfigurationSection(itemSectionKey)
        if (section != null) {
            return parseDecorItem(section)
        }
        val matKey = root.getString(materialFallbackKey) ?: "arrow"
        val mat = Material.matchMaterial(matKey) ?: Material.ARROW
        return ItemStack(mat, 1)
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
        // Use isSet + typed read: getInt alone returns 0 when unset; contains() can be unreliable
        // for some YAML shapes. Paper expects integer legacy CMD here.
        val legacy: Int? =
                if (!sec.isSet("custom-model-data")) {
                    null
                } else {
                    when (val raw = sec.get("custom-model-data")) {
                        is Number -> raw.toInt()
                        is String -> raw.trim().toIntOrNull()
                        else -> sec.getInt("custom-model-data")
                    }
                }
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
