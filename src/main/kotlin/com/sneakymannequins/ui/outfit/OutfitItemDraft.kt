package com.sneakymannequins.ui.outfit

import com.sneakymannequins.model.LayerSessionData
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.bukkit.Material

data class OutfitItemDraft(
        val uid: String,
        val layers: Map<String, LayerSessionData>,
        var material: Material = Material.RABBIT_FOOT,
        var itemModel: String? = null,
        var customModelDataFloats: List<Float>? = null,
        var customModelData: Int? = null,
        var displayNamePlain: String? = null
)

object OutfitItemCreationService {
    private val drafts = ConcurrentHashMap<UUID, OutfitItemDraft>()

    fun put(playerId: UUID, draft: OutfitItemDraft) {
        drafts[playerId] = draft
    }

    fun get(playerId: UUID): OutfitItemDraft? = drafts[playerId]

    fun remove(playerId: UUID) {
        drafts.remove(playerId)
    }
}
