package com.sneakymannequins.nms

import com.sneakymannequins.SneakyMannequins
import com.sneakymannequins.nms.v1_21_4.VolatileHandler1214
import org.bukkit.Bukkit

object VolatileHandlerRegistry {

    fun resolve(plugin: SneakyMannequins): VolatileHandler {
        val version = Bukkit.getMinecraftVersion()
        return when (version) {
            "1.21.4" -> VolatileHandler1214(plugin)
            else -> {
                plugin.logger.warning("Minecraft version $version is not explicitly supported; mannequin rendering disabled.")
                UnsupportedVolatileHandler(version, plugin)
            }
        }
    }
}

