package com.sneakymannequins.managers

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
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

    // Nested map: layerId -> (optionId -> count)
    private val partUsage = ConcurrentHashMap<String, ConcurrentHashMap<String, AtomicLong>>()
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
                val layerMap = partUsage.computeIfAbsent(layerId) { ConcurrentHashMap() }
                layerMap.computeIfAbsent(optionId) { AtomicLong(0) }.incrementAndGet()
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
            val element = JsonParser.parseString(json).asJsonObject
            
            // Migration & Load logic for parts
            val partsObj = element.get("parts")?.asJsonObject
            if (partsObj != null) {
                for (entry in partsObj.entrySet()) {
                    val key = entry.key
                    val value = entry.value
                    if (value.isJsonPrimitive) {
                        // Old format migration: "layer:option": count
                        val split = key.split(":")
                        if (split.size == 2) {
                            val layerId = split[0]
                            val optionId = split[1]
                            val count = value.asLong
                            val layerMap = partUsage.computeIfAbsent(layerId) { ConcurrentHashMap() }
                            layerMap.computeIfAbsent(optionId) { AtomicLong(0) }.addAndGet(count)
                        }
                    } else if (value.isJsonObject) {
                        // New format: "layer": { "option": count }
                        val layerId = key
                        val layerMap = partUsage.computeIfAbsent(layerId) { ConcurrentHashMap() }
                        for (optionEntry in value.asJsonObject.entrySet()) {
                            layerMap[optionEntry.key] = AtomicLong(optionEntry.value.asLong)
                        }
                    }
                }
            }
            
            // Load colors
            val colorsObj = element.get("colors")?.asJsonObject
            if (colorsObj != null) {
                for (entry in colorsObj.entrySet()) {
                    colorUsage[entry.key] = AtomicLong(entry.value.asLong)
                }
            }
        } catch (e: Exception) {
            plugin.logger.warning("Failed to load usage stats (it may have been reset if the format changed significantly): ${e.message}")
        }
    }

    /**
     * Synchronously saves current stats to disk.
     */
    fun save() {
        try {
            val partsData = partUsage.mapValues { layerEntry ->
                layerEntry.value.mapValues { optionEntry ->
                    optionEntry.value.get()
                }
            }
            val data = StatsData(
                parts = partsData,
                colors = colorUsage.mapValues { it.value.get() }
            )
            statsFile.writeText(gson.toJson(data))
        } catch (e: Exception) {
            plugin.logger.warning("Failed to save usage stats: ${e.message}")
        }
    }

    private data class StatsData(
        val parts: Map<String, Map<String, Long>>,
        val colors: Map<String, Long>
    )
}
