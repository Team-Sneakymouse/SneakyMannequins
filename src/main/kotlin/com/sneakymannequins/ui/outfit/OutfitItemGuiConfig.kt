package com.sneakymannequins.ui.outfit

import com.sneakymannequins.SneakyMannequins
import com.sneakymouse.sneakyholos.util.TextUtility
import net.kyori.adventure.text.Component
import org.bukkit.Material
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

    var backgroundMaterial: Material = Material.GRAY_STAINED_GLASS_PANE
        private set
    var backgroundCustomModelData: Int? = null
        private set
    var backgroundHideTooltip: Boolean = true
        private set
    private var backgroundFillExcept: Set<Int> = emptySet()

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

        val bg = root.getConfigurationSection("background")
        backgroundMaterial =
                Material.matchMaterial(bg?.getString("material") ?: "gray_stained_glass_pane")
                        ?: Material.GRAY_STAINED_GLASS_PANE
        backgroundCustomModelData =
                bg?.getInt("custom-model-data")?.takeIf { bg.contains("custom-model-data") }
        backgroundHideTooltip = bg?.getBoolean("hide-tooltip", true) ?: true
        val fillExcept = root.getIntegerList("background.fill-all-except")
        backgroundFillExcept =
                if (fillExcept.isEmpty()) {
                    setOf(slotIconButton, slotNameButton, slotPreview, slotCorner)
                } else {
                    fillExcept.map { it.coerceIn(0, 26) }.toSet()
                }

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
        backgroundMaterial = Material.GRAY_STAINED_GLASS_PANE
        backgroundCustomModelData = null
        backgroundHideTooltip = true
        backgroundFillExcept =
                setOf(slotIconButton, slotNameButton, slotPreview, slotCorner)
        cornerItem = defaultJigsawDecor(3047)
        iconPickerDecorationSlot = 52
        iconPickerDecoration = defaultJigsawDecor(3050)
        iconButtonMaterial = Material.PAINTING
        nameButtonMaterial = Material.NAME_TAG
        loadedIcons.clear()
        loadedIcons.addAll(defaultIconList())
    }

    fun slotsToFillBackground(): Set<Int> {
        val reserved =
                setOf(slotIconButton, slotNameButton, slotPreview, slotCorner) +
                        backgroundFillExcept
        return (0..26).filter { it !in reserved }.toSet()
    }

    private fun parseDecorItem(section: org.bukkit.configuration.ConfigurationSection?): ItemStack {
        if (section == null) return defaultJigsawDecor(3047)
        val mat = Material.matchMaterial(section.getString("material") ?: "jigsaw") ?: Material.JIGSAW
        val stack = ItemStack(mat, 1)
        val meta = stack.itemMeta ?: return stack
        if (section.contains("custom-model-data")) {
            meta.setCustomModelData(section.getInt("custom-model-data"))
        }
        meta.isHideTooltip = section.getBoolean("hide-tooltip", true)
        stack.itemMeta = meta
        return stack
    }

    private fun defaultJigsawDecor(cmd: Int): ItemStack {
        return ItemStack(Material.JIGSAW, 1).apply {
            itemMeta =
                    itemMeta?.also { m ->
                        m.setCustomModelData(cmd)
                        m.isHideTooltip = true
                    }
        }
    }

    private fun loadIconsFromConfig(cfg: FileConfiguration): List<IconEntry> {
        val section = cfg.getConfigurationSection("outfit-item-icons") ?: return defaultIconList()
        val out = mutableListOf<IconEntry>()
        for (materialKey in section.getKeys(false)) {
            val material = Material.matchMaterial(materialKey) ?: continue
            for (entry in section.getStringList(materialKey)) {
                if (entry.contains("-")) {
                    val parts = entry.split("-")
                    val start = parts[0].toIntOrNull() ?: continue
                    val end = parts[1].toIntOrNull() ?: continue
                    for (md in start..end) {
                        out.add(IconEntry(material, md))
                    }
                } else {
                    val md = entry.toIntOrNull() ?: continue
                    out.add(IconEntry(material, md))
                }
            }
        }
        return out.ifEmpty { defaultIconList() }
    }

    private fun defaultIconList(): List<IconEntry> {
        return listOf(
                IconEntry(Material.RABBIT_FOOT, 0),
                IconEntry(Material.LEATHER_CHESTPLATE, 0),
                IconEntry(Material.ARMOR_STAND, 0)
        )
    }

    data class IconEntry(val material: Material, val modelData: Int)
}
