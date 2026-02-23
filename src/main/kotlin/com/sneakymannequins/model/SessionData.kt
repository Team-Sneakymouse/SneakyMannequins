package com.sneakymannequins.model

import java.awt.Color

/**
 * Serializable snapshot of a mannequin's customization state.
 * Stored as JSON in the sessions/ or templates/ directory.
 */
data class SessionData(
    val uid: String,
    val creator: String,
    val createdAt: String,
    val slimModel: Boolean,
    val layers: Map<String, LayerSessionData>
)

/**
 * Per-layer portion of a saved session.
 * All colours are stored as hex strings (e.g. "#FF0000") for readability.
 */
data class LayerSessionData(
    val option: String?,
    val channelColors: Map<String, String> = emptyMap(),
    val texturedColors: Map<String, Map<String, String>> = emptyMap(),
    val selectedTexture: String? = null
) {
    companion object {
        fun fromSelection(sel: LayerSelection): LayerSessionData {
            return LayerSessionData(
                option = sel.option?.id,
                channelColors = sel.channelColors.mapKeys { it.key.toString() }
                    .mapValues { colorToHex(it.value) },
                texturedColors = sel.texturedColors.mapKeys { it.key.toString() }
                    .mapValues { (_, subMap) ->
                        subMap.mapKeys { it.key.toString() }.mapValues { colorToHex(it.value) }
                    },
                selectedTexture = sel.selectedTexture
            )
        }
    }
}

fun colorToHex(c: Color): String =
    String.format("#%02X%02X%02X", c.red, c.green, c.blue)

fun hexToColor(hex: String): Color? {
    val h = hex.removePrefix("#")
    if (h.length != 6) return null
    val rgb = h.toLongOrNull(16)?.toInt() ?: return null
    return Color(rgb)
}
