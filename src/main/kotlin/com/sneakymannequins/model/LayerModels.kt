package com.sneakymannequins.model

import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Path

// ── Palette references ──────────────────────────────────────────────────────────

/**
 * Reference to a colour palette with an optional permission node. When [permission] is non-null the
 * palette is only offered to players who have that permission.
 */
data class PaletteRef(val id: String, val permission: String? = null)

/**
 * Three-tier palette specification. Can exist at default, layer, or part level. A `null` list means
 * "not specified at this level – inherit from above"; an **empty** list means "explicitly nothing".
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
 * Reference to a named texture with an optional permission node. When [permission] is non-null the
 * texture is only offered to players who have that permission.
 */
data class TextureRef(val id: String, val permission: String? = null)

/**
 * Texture specification. Can exist at default, layer, or part level. A `null` list means "not
 * specified at this level – inherit from above"; an **empty** list means "explicitly no textures".
 */
data class TextureSpec(val textures: List<TextureRef>? = null) {
    companion object {
        /** Not specified – inherits from the level above. */
        val INHERIT = TextureSpec()
    }
}

/**
 * A named texture definition loaded from config. Consists of an optional blend map (RGB sub-channel
 * weights), an optional AO map (grayscale brightness modulation), an optional roughness map
 * (grayscale saturation modulation), and an optional alpha map (grayscale alpha modulation). When
 * none of the maps are provided, the texture behaves identically to flat-colour masking
 * ("Default").
 *
 * [aoMapImage] and [roughnessMapImage] are grayscale: 128 = neutral (1.0×), lower values
 * darken/desaturate, higher values brighten/saturate. [alphaMapImage] is grayscale: 128 = neutral
 * (1.0×), lower values make pixels more transparent, higher values make them more opaque. They can
 * point to the same file if you want coupled behaviour.
 *
 * [activeSubChannels] is auto-detected from the blend map image at load time: the set of RGB
 * channel indices (0=R, 1=G, 2=B) that have at least one non-zero pixel anywhere in the image.
 */
data class TextureDefinition(
        val id: String,
        val displayName: String,
        val blendMapPath: Path? = null,
        val blendMapImage: BufferedImage? = null,
        /** Grayscale AO (ambient occlusion) map — modulates brightness (128 = neutral). */
        val aoMapPath: Path? = null,
        val aoMapImage: BufferedImage? = null,
        /** Grayscale roughness map — modulates saturation (128 = neutral). */
        val roughnessMapPath: Path? = null,
        val roughnessMapImage: BufferedImage? = null,
        /** Grayscale alpha map — modulates opacity (128 = neutral). */
        val alphaMapPath: Path? = null,
        val alphaMapImage: BufferedImage? = null,
        /** Active sub-channels auto-detected from the blend map (0=R, 1=G, 2=B). */
        val activeSubChannels: Set<Int> = emptySet()
)

// ── Channel slots ──────────────────────────────────────────────────────────────

/**
 * One slot in the flattened channel list. When a texture has a blend map with multiple active
 * sub-channels, each mask channel expands into N slots (1a, 1b, …). Without a blend map (or with
 * only one active sub-channel), each mask channel is a single slot (1, 2, 3).
 *
 * @param maskIdx 1-based mask channel index (matches file names: _mask_1.png …)
 * @param subChannel null for flat channels; 0=R, 1=G, 2=B when sub-channels exist
 * @param label human-readable label: "1", "2" or "1a", "1b", "2a" …
 */
data class ChannelSlot(val maskIdx: Int, val subChannel: Int?, val label: String)

private val SUB_CHANNEL_LETTERS = mapOf(0 to "a", 1 to "b", 2 to "c")

/**
 * Build a flat list of [ChannelSlot]s from the given mask channels and active sub-channels. When
 * [activeSubChannels] has 2+ entries every mask channel is expanded; otherwise sub-channels are
 * collapsed into plain channel indices.
 */
fun buildChannelSlots(maskChannels: List<Int>, activeSubChannels: Set<Int>?): List<ChannelSlot> {
    val subs = activeSubChannels?.sorted() ?: emptyList()
    val expand = subs.size >= 2
    return if (expand) {
        maskChannels.flatMap { ch ->
            subs.map { sub -> ChannelSlot(ch, sub, "$ch${SUB_CHANNEL_LETTERS[sub] ?: sub}") }
        }
    } else {
        maskChannels.map { ch -> ChannelSlot(ch, null, "$ch") }
    }
}

// ── Layer / Option / Selection ──────────────────────────────────────────────────

/** Represents a single layer type (e.g., hat, eyes, jacket). */
data class LayerDefinition(
        val id: String,
        val displayName: String,
        val directory: Path,
        val allowColorMask: Boolean,
        val paletteSpec: PaletteSpec = PaletteSpec.INHERIT,
        val textureSpec: TextureSpec = TextureSpec.INHERIT,
        val brightnessInfluence: Float? = null,
        val saturationInfluence: Float? = null,
        val isBase: Boolean = false
)

/** One concrete option for a layer, backed by a PNG on disk. */
data class LayerOption(
        val id: String,
        val displayName: String,
        val fileDefault: Path?,
        val fileSlim: Path?,
        val fileMaster: Path?,
        val imageDefault: BufferedImage?,
        val imageSlim: BufferedImage?,
        val imageMaster: BufferedImage?,
        val paletteSpec: PaletteSpec = PaletteSpec.INHERIT,
        val textureSpec: TextureSpec = TextureSpec.INHERIT,
        val brightnessInfluence: Float? = null,
        val saturationInfluence: Float? = null,
        val masks: Map<Int, Path> = emptyMap(), // master/shared masks
        val masksDefault: Map<Int, Path> = emptyMap(),
        val masksSlim: Map<Int, Path> = emptyMap(),
        val directory: Path? = null, // part's subdirectory
        val owner: java.util.UUID? = null,
        val internalKey: String? = null,
        val hasArms: Boolean = false,
        val isAlex: Boolean = false,
        val permissions: List<String>? = null
)

/**
 * Current selection for a layer. [channelColors] maps channel index → Color for each independently
 * tinted channel (used when no texture is selected, or the selected texture has no blend map).
 * [texturedColors] maps channel index → (sub-channel index → Color) for channels when a texture
 * with a blend map is active. Sub-channel indices are 0=R, 1=G, 2=B. [selectedTexture] is the ID of
 * the currently selected texture, or null for "Default".
 */
data class LayerSelection(
        val layerId: String,
        val option: LayerOption?,
        val channelColors: Map<Int, Color> = emptyMap(),
        val texturedColors: Map<Int, Map<Int, Color>> = emptyMap(),
        val selectedTexture: String? = null
)

/** Aggregate skin selection covering all layers in order. */
data class SkinSelection(val selections: Map<String, LayerSelection>) {
    companion object {
        fun empty(layerIds: List<String>): SkinSelection {
            val defaults = layerIds.associateWith { LayerSelection(it, option = null) }
            return SkinSelection(defaults)
        }
    }
}
