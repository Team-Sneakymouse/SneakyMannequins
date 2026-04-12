package com.sneakymannequins.managers

import com.google.gson.GsonBuilder
import com.sneakymannequins.SneakyMannequins
import com.sneakymannequins.model.SessionData
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Manages usage statistics for parts and colors.
 * Persists data to `usage_stats.json` in the plugin directory.
 */
class StatsManager(private val plugin: SneakyMannequins, dataFolder: File) {
    private val statsFile = File(dataFolder, "usage_stats.json")
    private val gson = GsonBuilder().setPrettyPrinting().create()

    private val partUsage = ConcurrentHashMap<String, AtomicLong>()
    private val colorUsage = ConcurrentHashMap<String, AtomicLong>()

    init {
        load()
    }

    /**
     * Records usage for all parts and colors within a session.
     */
    fun record(session: SessionData) {
        session.layers.forEach { (layerId, layerData) ->
            val optionId = layerData.option
            if (optionId != null && optionId != "none") {
                val partKey = "$layerId:$optionId"
                partUsage.computeIfAbsent(partKey) { AtomicLong(0) }.incrementAndGet()
            }

            layerData.channelColors.values.forEach { hex ->
                colorUsage.computeIfAbsent(hex.uppercase()) { AtomicLong(0) }.incrementAndGet()
            }

            layerData.texturedColors.values.forEach { subMap ->
                subMap.values.forEach { hex ->
                    colorUsage.computeIfAbsent(hex.uppercase()) { AtomicLong(0) }.incrementAndGet()
                }
            }
        }
        save()
    }

    private fun load() {
        if (!statsFile.exists()) return
        try {
            val json = statsFile.readText()
            val data = gson.fromJson(json, StatsData::class.java) ?: return
            data.parts.forEach { (k, v) -> partUsage[k] = AtomicLong(v) }
            data.colors.forEach { (k, v) -> colorUsage[k] = AtomicLong(v) }
        } catch (e: Exception) {
            plugin.logger.warning("Failed to load usage stats: ${e.message}")
        }
    }

    /**
     * Synchronously saves current stats to disk.
     */
    fun save() {
        try {
            val data = StatsData(
                parts = partUsage.mapValues { it.value.get() },
                colors = colorUsage.mapValues { it.value.get() }
            )
            statsFile.writeText(gson.toJson(data))
        } catch (e: Exception) {
            plugin.logger.warning("Failed to save usage stats: ${e.message}")
        }
    }

    private data class StatsData(
        val parts: Map<String, Long>,
        val colors: Map<String, Long>
    )
}
