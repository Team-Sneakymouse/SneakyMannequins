package com.sneakymannequins.model

import com.sneakymannequins.render.RenderSettings

/** How a `colortab` HUD button advances the color-grid palette page. */
enum class ColorTabMode {
    /** Always move to the next page. */
    FORWARD,
    /** Always move to the previous page. */
    BACKWARD,
    /** Left-click forward, right-click backward (via `backwards` on click). */
    ALTERNATE
}

data class MenuLayout(
        val originX: Float,
        val originY: Float,
        val originZ: Float,
        val pitch: Float,
        val yaw: Float
)

data class HudButton(
        val name: String,
        val textMM: String,
        val textJson: String,
        val activeTextMM: String?,
        val activeTextJson: String?,
        val disabledTextMM: String?,
        val disabledTextJson: String?,
        val confirmTextMM: String?,
        val confirmTextJson: String?,
        val tx: Float,
        val ty: Float,
        val tz: Float,
        val lineWidth: Int,
        val bgDefault: Int,
        val bgHighlight: Int,
        val scaleX: Float? = null,
        val scaleY: Float? = null,
        val type: String? = null,
        val colorTabMode: ColorTabMode = ColorTabMode.FORWARD,
        val targetLayer: String? = null,
        val allowedLayers: List<String>? = null,
        val palette: String? = null,
        val colorHex: String? = null,
        val openByDefault: Boolean = false,
        val submenuLayout: MenuLayout? = null,
        val items: Map<String, HudButton>? = null,
        val maxRows: Int = 4,
        val cellSpacingX: Float = 0.12f,
        val cellSpacingY: Float = 0.18f,
        val cellLineWidth: Int = 18,
        val cellScaleX: Float = 1f,
        val cellScaleY: Float = 1f,
        val headerLineWidth: Int = 80,
        val headerScale: Float = 0.6f,
        val headerTextMM: String = "<white>{message}",
        val bgHeader: Int? = null,
        val headerPaddingLen: Int = 0,
        val headerPaddingSide: String = "none",
        val headerColumn: Int = 0
)

/** How hologram text display entity brightness override is chosen. */
sealed class TextDisplayBrightnessSetting {
    /**
     * Each tick, use block light and sky light at the mannequin anchor block (same values for every
     * HUD label, independent of each button’s world position).
     */
    data object Auto : TextDisplayBrightnessSetting()

    /** Fixed Minecraft light levels (0–15) for block-emitted and sky light on the display entity. */
    data class Fixed(val block: Int, val sky: Int) : TextDisplayBrightnessSetting()
}

data class RenderingConfig(
    val scale: String = "auto",
    val viewRadius: Double = 8.0,
    val updateRadius: Double = 30.0,
    val applyHidesMannequin: Boolean = true,
    val firstSeen: RenderSettings,
    val update: RenderSettings,
    val textDisplayBrightness: TextDisplayBrightnessSetting = TextDisplayBrightnessSetting.Auto,
    /** Scales sampled block/sky light when [textDisplayBrightness] is [TextDisplayBrightnessSetting.Auto]. */
    val textDisplayBrightnessAutoMultiplier: Float = 1f,
)

data class HudFrameConfig(
    val enabled: Boolean = false,
    val item: String = "minecraft:glass_pane",
    val customModelData: Int = 0,
    val displayContext: String = "FIXED",
    val tx: Float = 0f,
    val ty: Float = 1.7f,
    val tz: Float = -2.0f,
    val sx: Float = 3.0f,
    val sy: Float = 3.0f,
    val sz: Float = 0.05f
)

data class MannequinStyle(
    val id: String,
    val rendering: RenderingConfig,
    val hudButtons: List<HudButton>,
    val hudFrame: HudFrameConfig,
    val configMenu: MenuLayout,
    val colorGrid: MenuLayout,
    val availableLayers: List<String>
)
