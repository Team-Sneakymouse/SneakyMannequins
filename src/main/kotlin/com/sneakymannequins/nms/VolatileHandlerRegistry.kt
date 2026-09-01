package com.sneakymannequins.nms

import com.sneakymannequins.SneakyMannequins
import com.sneakymannequins.nms.v26_2.VolatileHandler262
import org.bukkit.Bukkit

object VolatileHandlerRegistry {

    fun resolve(plugin: SneakyMannequins): VolatileHandler {
        val version = Bukkit.getMinecraftVersion()
        return when (version) {
            "26.2" -> VolatileHandler262(plugin)
            else -> {
                plugin.logger.warning("Minecraft version $version is not explicitly supported; mannequin rendering disabled.")
                UnsupportedVolatileHandler(version, plugin)
            }
        }
    }
}

