package com.sneakymannequins.model

import java.util.UUID
import org.bukkit.Location

data class Mannequin(
        val id: UUID = UUID.randomUUID(),
        val location: Location,
        var selection: SkinSelection,
        var slimModel: Boolean = false,
        var showOverlay: Boolean = true,
        var lastFrame: PixelFrame = PixelFrame.blank()
)
