package com.sneakymannequins.model

import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Path

/**
 * Reference to a colour palette with an optional permission node.
 * When [permission] is non-null the palette is only offered to
 * players who have that permission.
 */
data class PaletteRef(val id: String, val permission: String? = null)

/**
 * Three-tier palette specification.  Can exist at default, layer, or part
 * level.  A `null` list means "not specified at this level – inherit from
 * above"; an **empty** list means "explicitly nothing".
 */
data class PaletteSpec(
    val first: List<PaletteRef>? = null,
    val palettes: List<PaletteRef>? = null,
    val last: List<PaletteRef>? = null
) {
    companion object {
        /** No categories specified – inherits everything. */
        val INHERIT = PaletteSpec()
    }
}

/**
 * Represents a single layer type (e.g., hat, eyes, jacket).
 */
data class LayerDefinition(
    val id: String,
    val displayName: String,
    val directory: Path,
    val allowColorMask: Boolean,
    val paletteSpec: PaletteSpec = PaletteSpec.INHERIT
)

/**
 * One concrete option for a layer, backed by a PNG on disk.
 */
data class LayerOption(
    val id: String,
    val displayName: String,
    val fileDefault: Path?,
    val fileSlim: Path?,
    val imageDefault: BufferedImage?,
    val imageSlim: BufferedImage?,
    val paletteSpec: PaletteSpec = PaletteSpec.INHERIT,
    val masks: Map<Int, Path> = emptyMap() // mask index -> file path (generated)
)

/**
 * Current selection for a layer.
 * [channelColors] maps channel index → Color for each independently tinted channel.
 */
data class LayerSelection(
    val layerId: String,
    val option: LayerOption?,
    val channelColors: Map<Int, Color> = emptyMap()
)

/**
 * Aggregate skin selection covering all layers in order.
 */
data class SkinSelection(
    val selections: Map<String, LayerSelection>
) {
    companion object {
        fun empty(layerIds: List<String>): SkinSelection {
            val defaults = layerIds.associateWith { LayerSelection(it, option = null) }
            return SkinSelection(defaults)
        }
    }
}

