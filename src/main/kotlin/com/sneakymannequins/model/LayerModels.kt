package com.sneakymannequins.model

import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Path

/**
 * Represents a single layer type (e.g., hat, eyes, jacket).
 */
data class LayerDefinition(
    val id: String,
    val displayName: String,
    val directory: Path,
    val allowColorMask: Boolean,
    val defaultPalettes: List<String>
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
    val allowedPalettes: List<String>,
    val masks: Map<Int, Path> = emptyMap() // mask index -> file path (generated)
)

/**
 * Current selection for a layer.
 */
data class LayerSelection(
    val layerId: String,
    val option: LayerOption?,
    val colorMask: Color?
)

/**
 * Aggregate skin selection covering all layers in order.
 */
data class SkinSelection(
    val selections: Map<String, LayerSelection>
) {
    companion object {
        fun empty(layerIds: List<String>): SkinSelection {
            val defaults = layerIds.associateWith { LayerSelection(it, option = null, colorMask = null) }
            return SkinSelection(defaults)
        }
    }
}

