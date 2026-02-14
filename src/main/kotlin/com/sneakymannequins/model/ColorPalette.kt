package com.sneakymannequins.model

import java.awt.Color

data class NamedColor(val name: String, val color: Color)

data class ColorPalette(
    val id: String,
    val colors: List<NamedColor>
)

