package com.sneakymannequins.managers

import com.sneakymannequins.SneakyMannequins
import com.sneakymannequins.model.Mannequin
import java.io.File
import java.util.UUID
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.configuration.file.YamlConfiguration

class MannequinPersistence(private val plugin: SneakyMannequins) {

    data class MannequinData(
            val id: UUID,
            val location: Location,
            val slim: Boolean,
            val savedUid: String?,
            val styleId: String
    )

    private val file: File = File(plugin.dataFolder, "mannequins.yml")
    private val config = YamlConfiguration()

    fun load(): List<MannequinData> {
        if (!file.exists()) {
            plugin.dataFolder.mkdirs()
            file.createNewFile()
        }
        config.load(file)
        val mannequins = mutableListOf<MannequinData>()

        val manSection = config.getConfigurationSection("mannequins")
        manSection?.getKeys(false)?.forEach { key ->
            runCatching {
                val id = UUID.fromString(key)
                val world =
                        Bukkit.getWorld(manSection.getString("$key.world") ?: return@runCatching)
                val x = manSection.getDouble("$key.x")
                val y = manSection.getDouble("$key.y")
                val z = manSection.getDouble("$key.z")
                val yaw = manSection.getDouble("$key.yaw", 0.0).toFloat()
                val slim = manSection.getBoolean("$key.slim", false)
                val savedUid = manSection.getString("$key.savedUid")
                val styleId = manSection.getString("$key.styleId", "default") ?: "default"
                if (world != null) {
                    mannequins +=
                            MannequinData(id, Location(world, x, y, z, yaw, 0f), slim, savedUid, styleId)
                }
            }
        }

        // Clean up legacy "controls" section if present
        if (config.contains("controls")) {
            config.set("controls", null)
            config.save(file)
        }

        return mannequins
    }

    fun save(mannequins: Collection<Mannequin>) {
        config.set("mannequins", null)
        mannequins.forEach { man ->
            val path = "mannequins.${man.id}"
            config.set("$path.world", man.location.world?.name)
            config.set("$path.x", man.location.x)
            config.set("$path.y", man.location.y)
            config.set("$path.z", man.location.z)
            config.set("$path.yaw", man.location.yaw.toDouble())
            config.set("$path.slim", man.slimModel)
            config.set("$path.savedUid", man.savedUid)
            config.set("$path.styleId", man.styleId)
        }
        config.save(file)
    }
}
