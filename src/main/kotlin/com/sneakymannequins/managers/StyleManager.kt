package com.sneakymannequins.managers

import com.sneakymannequins.SneakyMannequins
import com.sneakymannequins.model.*
import com.sneakymannequins.render.RenderMode
import com.sneakymannequins.render.RenderSettings
import com.sneakymouse.sneakyholos.util.TextUtility
import java.io.File
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration

class StyleManager(private val plugin: SneakyMannequins) {
    private val styles = mutableMapOf<String, MannequinStyle>()
    private val stylesFolder = File(plugin.dataFolder, "mannequin_presets")

    fun loadStyles() {
        styles.clear()
        if (!stylesFolder.exists()) {
            stylesFolder.mkdirs()
        }

        val files = stylesFolder.listFiles { file -> file.extension == "yml" }
        if (files == null || files.isEmpty()) {
            // If no styles exist, create a dummy default one or alert
            plugin.logger.warning("No mannequin styles found in mannequin_presets/ folder!")
            return
        }

        files.forEach { file ->
            val id = file.nameWithoutExtension
            val config = YamlConfiguration.loadConfiguration(file)
            val style = parseStyle(id, config)
            if (style != null) {
                styles[id] = style
                plugin.logger.info("Loaded mannequin style: $id")
            }
        }
    }

    fun getStyle(id: String?): MannequinStyle? =
            styles[id ?: "default"] ?: styles["default"] ?: styles.values.firstOrNull()

    fun listStyleIds(): List<String> = styles.keys.toList()

    private fun parseStyle(id: String, config: YamlConfiguration): MannequinStyle? {
        val configMenu =
                parseMenuLayout(
                        config.getConfigurationSection("config-menu"),
                        0.3f,
                        -0.3f,
                        -1.8f,
                        -0.35f,
                        0f
                )
        val colorGrid =
                parseMenuLayout(
                        config.getConfigurationSection("color-grid"),
                        0.3f,
                        -0.3f,
                        -1.8f,
                        -0.35f,
                        0f
                )
        val rendering = parseRendering(config.getConfigurationSection("rendering"))
        val hudButtons =
                parseHudButtons(
                        config.getConfigurationSection("hud-buttons"),
                        configMenu,
                        colorGrid
                )
        val hudFrame = parseHudFrame(config.getConfigurationSection("hud-frame"))

        return MannequinStyle(id, rendering, hudButtons, hudFrame, configMenu, colorGrid)
    }

    private fun parseRendering(sec: ConfigurationSection?): RenderingConfig {
        if (sec == null)
                return RenderingConfig(
                        firstSeen = RenderSettings(RenderMode.BUILD, 2, 0.5, 5),
                        update = RenderSettings(RenderMode.BUILD, 1, 0.5, 5)
                )

        val scale = sec.getString("scale", "auto") ?: "auto"
        val viewRadius = sec.getDouble("view-radius", 8.0)
        val updateRadius = sec.getDouble("update-radius", 30.0)
        val applyHides = sec.getBoolean("apply-hides-mannequin", true)

        val firstSeen =
                parseRenderSettings(
                        sec.getConfigurationSection("first-seen"),
                        RenderMode.BUILD,
                        2,
                        0.5,
                        5
                )
        val update =
                parseRenderSettings(
                        sec.getConfigurationSection("update"),
                        RenderMode.BUILD,
                        1,
                        0.5,
                        5
                )

        return RenderingConfig(scale, viewRadius, updateRadius, applyHides, firstSeen, update)
    }

    private fun parseRenderSettings(
            sec: ConfigurationSection?,
            defMode: RenderMode,
            defInterval: Int,
            defSkip: Double,
            defFlyIn: Int
    ): RenderSettings {
        if (sec == null) return RenderSettings(defMode, defInterval, defSkip, defFlyIn)
        val modeStr = sec.getString("mode")?.uppercase()
        val mode = runCatching { RenderMode.valueOf(modeStr ?: "") }.getOrDefault(defMode)
        val interval = sec.getInt("tick-interval", defInterval)
        val skip = sec.getDouble("skip-chance", defSkip)
        val flyIn = sec.getInt("fly-in-count", defFlyIn)
        return RenderSettings(mode, interval, skip, flyIn)
    }

    private fun parseHudButtons(
            sec: ConfigurationSection?,
            configLayout: MenuLayout,
            colorLayout: MenuLayout
    ): List<HudButton> {
        if (sec == null) return emptyList()
        val globalBgDef = parseArgb(sec.getString("bg-default")) ?: 0x78000000.toInt()
        val globalBgHi = parseArgb(sec.getString("bg-highlight")) ?: 0xB8336699.toInt()

        val keys =
                sec.getKeys(false).filter {
                    it != "bg-default" &&
                            it != "bg-highlight" &&
                            it != "config-menu" &&
                            it != "color-grid"
                }
        return keys.mapNotNull { name ->
            parseHudButton(
                    name,
                    sec.getConfigurationSection(name),
                    globalBgDef,
                    globalBgHi,
                    configLayout,
                    colorLayout
            )
        }
    }

    private fun parseHudButton(
            name: String,
            sec: ConfigurationSection?,
            globalBgDef: Int,
            globalBgHi: Int,
            configLayout: MenuLayout,
            colorLayout: MenuLayout
    ): HudButton? {
        if (sec == null) return null

        val type = sec.getString("type") ?: name
        val textMM = sec.getString("text") ?: "<white>${name.replaceFirstChar { it.uppercase() }}"
        val activeMM = sec.getString("active-text")
        val disabledMM = sec.getString("disabled-text")
        val confirmMM = sec.getString("confirm-text")

        val tx = sec.getDouble("translation.x", 0.0).toFloat()
        val ty = sec.getDouble("translation.y", 0.0).toFloat()
        val tz = sec.getDouble("translation.z", 0.0).toFloat()

        val lw = sec.getInt("line-width", 200)
        val bgDef = parseArgb(sec.getString("bg-default")) ?: globalBgDef
        val bgHi = parseArgb(sec.getString("bg-highlight")) ?: globalBgHi

        val sx = if (sec.contains("scale-x")) sec.getDouble("scale-x").toFloat() else null
        val sy = if (sec.contains("scale-y")) sec.getDouble("scale-y").toFloat() else null

        val targetLayer = sec.getString("target-layer")
        val palette = sec.getString("palette")
        val colorHex = sec.getString("color")

        val openByDefault = sec.getBoolean("open-by-default", false)

        val submenuLayout =
                if (sec.contains("submenu-layout")) {
                    parseMenuLayout(
                            sec.getConfigurationSection("submenu-layout"),
                            0.3f,
                            -0.3f,
                            -1.8f,
                            -0.35f,
                            0f
                    )
                } else if (name == "config") {
                    configLayout
                } else if (name == "color_grid") {
                    colorLayout
                } else null

        val itemsSec = sec.getConfigurationSection("items")
        val itemsMap =
                if (itemsSec != null) {
                    val map = mutableMapOf<String, HudButton>()
                    for (key in itemsSec.getKeys(false)) {
                        val itemBtn =
                                parseHudButton(
                                        key,
                                        itemsSec.getConfigurationSection(key),
                                        globalBgDef,
                                        globalBgHi,
                                        configLayout,
                                        colorLayout
                                )
                        if (itemBtn != null) {
                            map[key] = itemBtn
                        }
                    }
                    map.ifEmpty { null }
                } else null

        val bgHeader = parseArgb(sec.getString("bg-header"))

        return HudButton(
                name = name,
                textMM = textMM,
                textJson = TextUtility.mmToJson(textMM),
                activeTextJson = activeMM?.let { TextUtility.mmToJson(it) },
                disabledTextJson = disabledMM?.let { TextUtility.mmToJson(it) },
                confirmTextJson = confirmMM?.let { TextUtility.mmToJson(it) },
                tx = tx,
                ty = ty,
                tz = tz,
                lineWidth = lw,
                bgDefault = bgDef,
                bgHighlight = bgHi,
                scaleX = sx,
                scaleY = sy,
                type = type,
                targetLayer = targetLayer,
                palette = palette,
                colorHex = colorHex,
                openByDefault = openByDefault,
                submenuLayout = submenuLayout,
                items = itemsMap,
                maxRows = sec.getInt("max-rows", 4),
                cellSpacingX = sec.getDouble("cell-spacing-x", 0.12).toFloat(),
                cellSpacingY =
                        (sec.get("cell-spacing-y") ?: sec.get("item-spacing-y") ?: 0.18).let {
                            if (it is Number) it.toFloat() else 0.18f
                        },
                cellLineWidth = sec.getInt("cell-line-width", 18),
                cellScaleX = sec.getDouble("cell-scale-x", 1.0).toFloat(),
                cellScaleY = sec.getDouble("cell-scale-y", 1.0).toFloat(),
                headerLineWidth = sec.getInt("header-line-width", 80),
                headerScale = sec.getDouble("header-scale", 0.6).toFloat(),
                headerTextMM = sec.getString("header-text", "<white>{message}")
                                ?: "<white>{message}",
                bgHeader = bgHeader,
                headerPaddingLen = sec.getInt("header-padding-len", 0),
                headerPaddingSide = sec.getString("header-padding-side", "none")?.lowercase()
                                ?: "none",
                headerColumn = sec.getInt("header-column", 0)
        )
    }

    private fun parseHudFrame(sec: ConfigurationSection?): HudFrameConfig {
        if (sec == null) return HudFrameConfig()
        return HudFrameConfig(
                enabled = sec.getBoolean("enabled", false),
                item = sec.getString("item") ?: "minecraft:glass_pane",
                customModelData = sec.getInt("custom-model-data", 0),
                displayContext = sec.getString("display-context") ?: "FIXED",
                tx = sec.getDouble("translation.x", 0.0).toFloat(),
                ty = sec.getDouble("translation.y", 1.7).toFloat(),
                tz = sec.getDouble("translation.z", -2.0).toFloat(),
                sx = sec.getDouble("scale.x", 3.0).toFloat(),
                sy = sec.getDouble("scale.y", 3.0).toFloat(),
                sz = sec.getDouble("scale.z", 0.05).toFloat()
        )
    }

    private fun parseMenuLayout(
            sec: ConfigurationSection?,
            defX: Float,
            defY: Float,
            defZ: Float,
            defP: Float,
            defYw: Float
    ): MenuLayout {
        if (sec == null) return MenuLayout(defX, defY, defZ, defP, defYw)
        return MenuLayout(
                originX = sec.getDouble("origin-x", defX.toDouble()).toFloat(),
                originY = sec.getDouble("origin-y", defY.toDouble()).toFloat(),
                originZ = sec.getDouble("origin-z", defZ.toDouble()).toFloat(),
                pitch = sec.getDouble("pitch", defP.toDouble()).toFloat(),
                yaw = sec.getDouble("yaw", defYw.toDouble()).toFloat()
        )
    }

    private fun parseArgb(hex: String?): Int? {
        if (hex.isNullOrBlank()) return null
        return hex.removePrefix("#").toLongOrNull(16)?.toInt()
    }
}
