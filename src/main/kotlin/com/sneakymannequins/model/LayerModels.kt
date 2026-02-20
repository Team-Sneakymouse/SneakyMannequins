package com.sneakymannequins.model

import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Path

// ── Palette references ──────────────────────────────────────────────────────────

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

// ── Texture references ──────────────────────────────────────────────────────────

/**
 * Reference to a named texture with an optional permission node.
 * When [permission] is non-null the texture is only offered to
 * players who have that permission.
 */
data class TextureRef(val id: String, val permission: String? = null)

/**
 * Texture specification.  Can exist at default, layer, or part level.
 * A `null` list means "not specified at this level – inherit from above";
 * an **empty** list means "explicitly no textures".
 */
data class TextureSpec(
    val textures: List<TextureRef>? = null
) {
    companion object {
        /** Not specified – inherits from the level above. */
        val INHERIT = TextureSpec()
    }
}

/**
 * How the detail map interacts with the original art's brightness / saturation.
 */
enum class DetailMode {
    /** Multiply: detail modulates *on top of* the original art's B/S (128 = neutral). */
    ADD,
    /** Replace: detail *overwrites* the original art's B/S (128 = 1.0, 0 = 0.0, 255 = ~2.0). */
    REPLACE
}

/**
 * A named texture definition loaded from config.  Consists of an optional
 * blend map (RGB sub-channel weights) and an optional detail map (grayscale
 * brightness/saturation modulation).  When neither map is provided, the
 * texture behaves identically to flat-colour masking ("Default").
 *
 * [activeSubChannels] is auto-detected from the blend map image at load
 * time: the set of RGB channel indices (0=R, 1=G, 2=B) that have at least
 * one non-zero pixel anywhere in the image.
 */
data class TextureDefinition(
    val id: String,
    val displayName: String,
    val blendMapPath: Path? = null,
    val detailMapPath: Path? = null,
    val blendMapImage: BufferedImage? = null,
    val detailMapImage: BufferedImage? = null,
    /** How the detail map affects brightness/saturation: [DetailMode.ADD] or [DetailMode.REPLACE]. */
    val detailMode: DetailMode = DetailMode.ADD,
    /** Active sub-channels auto-detected from the blend map (0=R, 1=G, 2=B). */
    val activeSubChannels: Set<Int> = emptySet()
)

// ── Layer / Option / Selection ──────────────────────────────────────────────────

/**
 * Represents a single layer type (e.g., hat, eyes, jacket).
 */
data class LayerDefinition(
    val id: String,
    val displayName: String,
    val directory: Path,
    val allowColorMask: Boolean,
    val paletteSpec: PaletteSpec = PaletteSpec.INHERIT,
    val textureSpec: TextureSpec = TextureSpec.INHERIT
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
    val textureSpec: TextureSpec = TextureSpec.INHERIT,
    val masks: Map<Int, Path> = emptyMap()      // channel idx -> mask PNG
)

/**
 * Current selection for a layer.
 * [channelColors] maps channel index → Color for each independently tinted channel
 * (used when no texture is selected, or the selected texture has no blend map).
 * [texturedColors] maps channel index → (sub-channel index → Color) for channels
 * when a texture with a blend map is active.  Sub-channel indices are 0=R, 1=G, 2=B.
 * [selectedTexture] is the ID of the currently selected texture, or null for "Default".
 */
data class LayerSelection(
    val layerId: String,
    val option: LayerOption?,
    val channelColors: Map<Int, Color> = emptyMap(),
    val texturedColors: Map<Int, Map<Int, Color>> = emptyMap(),
    val selectedTexture: String? = null
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
