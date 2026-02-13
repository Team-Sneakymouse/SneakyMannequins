package com.sneakymannequins.model

import org.bukkit.Location
import java.util.UUID

data class Mannequin(
    val id: UUID = UUID.randomUUID(),
    val location: Location,
    var selection: SkinSelection,
    var lastFrame: PixelFrame = PixelFrame.blank()
)

