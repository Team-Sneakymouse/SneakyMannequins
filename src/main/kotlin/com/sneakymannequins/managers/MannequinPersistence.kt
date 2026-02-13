package com.sneakymannequins.managers

import com.sneakymannequins.SneakyMannequins
import com.sneakymannequins.model.Mannequin
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.util.UUID

class MannequinPersistence(private val plugin: SneakyMannequins) {

    private val file: File = File(plugin.dataFolder, "mannequins.yml")
    private val config = YamlConfiguration()

    fun load(): Pair<List<Pair<UUID, Location>>, Map<UUID, List<Location>>> {
        if (!file.exists()) {
            plugin.dataFolder.mkdirs()
            file.createNewFile()
        }
        config.load(file)
        val mannequins = mutableListOf<Pair<UUID, Location>>()
        val controls = mutableMapOf<UUID, MutableList<Location>>()

        val manSection = config.getConfigurationSection("mannequins")
        manSection?.getKeys(false)?.forEach { key ->
            runCatching {
                val id = UUID.fromString(key)
                val world = Bukkit.getWorld(manSection.getString("$key.world") ?: return@runCatching)
                val x = manSection.getDouble("$key.x")
                val y = manSection.getDouble("$key.y")
                val z = manSection.getDouble("$key.z")
                val yaw = manSection.getDouble("$key.yaw", 0.0).toFloat()
                if (world != null) {
                    mannequins += id to Location(world, x, y, z, yaw, 0f)
                }
            }
        }

        val ctrlSection = config.getConfigurationSection("controls")
        ctrlSection?.getKeys(false)?.forEach { key ->
            runCatching {
                val id = UUID.fromString(key)
                val listSection = ctrlSection.getConfigurationSection(key) ?: return@runCatching
                listSection.getKeys(false).forEach { idx ->
                    val world = Bukkit.getWorld(listSection.getString("$idx.world") ?: return@forEach)
                    val x = listSection.getDouble("$idx.x")
                    val y = listSection.getDouble("$idx.y")
                    val z = listSection.getDouble("$idx.z")
                    val yaw = listSection.getDouble("$idx.yaw", 0.0).toFloat()
                    if (world != null) {
                        controls.computeIfAbsent(id) { mutableListOf() }
                            .add(Location(world, x, y, z, yaw, 0f))
                    }
                }
            }
        }

        return mannequins to controls
    }

    fun save(mannequins: Collection<Mannequin>, controls: Map<UUID, List<Location>>) {
        config.set("mannequins", null)
        mannequins.forEach { man ->
            val path = "mannequins.${man.id}"
            config.set("$path.world", man.location.world?.name)
            config.set("$path.x", man.location.x)
            config.set("$path.y", man.location.y)
            config.set("$path.z", man.location.z)
            config.set("$path.yaw", man.location.yaw.toDouble())
        }
        config.set("controls", null)
        controls.forEach { (id, list) ->
            val base = "controls.$id"
            list.forEachIndexed { idx, loc ->
                val path = "$base.$idx"
                config.set("$path.world", loc.world?.name)
                config.set("$path.x", loc.x)
                config.set("$path.y", loc.y)
                config.set("$path.z", loc.z)
                config.set("$path.yaw", loc.yaw.toDouble())
            }
        }
        config.save(file)
    }
}

