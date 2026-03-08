package com.sneakymannequins.managers

import com.sneakymannequins.SneakyMannequins
import java.nio.file.Path
import org.bukkit.configuration.file.FileConfiguration

class ConfigManager {

    private val plugin = SneakyMannequins.instance
    private val config: FileConfiguration = plugin.config

    companion object {
        @JvmStatic val instance: ConfigManager by lazy { ConfigManager() }
    }

    /**
     * Gets the storage path for images relative to the Minecraft server directory
     * @return Path object pointing to the image storage directory
     */
    fun getImageStoragePath(): Path {
        val relativePath =
                config.getString("images.storage-path", "../web/images/") ?: "../web/images/"
        val serverDir = plugin.server.worldContainer.toPath()
        return serverDir.resolve(relativePath).normalize()
    }

    /**
     * Gets the full URL for an image by its filename
     * @param imageName The filename of the image (e.g., "skin-123.png")
     * @return Full URL where the image can be accessed
     */
    fun getImageUrl(imageName: String): String {
        val urlPrefix =
                config.getString("images.url-prefix", "http://localhost:8080/images/")
                        ?: "http://localhost:8080/images/"
        return if (urlPrefix.endsWith("/")) {
            "$urlPrefix$imageName"
        } else {
            "$urlPrefix/$imageName"
        }
    }

    /**
     * Gets the URL prefix for images
     * @return The URL prefix configured in the config
     */
    fun getImageUrlPrefix(): String {
        return config.getString("images.url-prefix", "http://localhost:8080/images/")
                ?: "http://localhost:8080/images/"
    }

    /**
     * Gets the relative storage path as configured
     * @return The relative path string from config
     */
    fun getRelativeStoragePath(): String {
        return config.getString("images.storage-path", "../web/images/") ?: "../web/images/"
    }

    /**
     * Ensures the image storage directory exists
     * @return true if directory exists or was created successfully
     */
    fun ensureImageStorageDirectory(): Boolean {
        return try {
            val storagePath = getImageStoragePath()
            val directory = storagePath.toFile()
            if (!directory.exists()) {
                directory.mkdirs()
                plugin.logger.info("Created image storage directory: $storagePath")
            }
            true
        } catch (e: Exception) {
            plugin.logger.severe("Failed to create image storage directory: ${e.message}")
            false
        }
    }

    /**
     * Gets a debug setting from config
     * @return true if debug mode is enabled
     */
    fun isDebugEnabled(): Boolean {
        return config.getBoolean("plugin.debug", false)
    }

    /**
     * Gets the default skin model from config
     * @return The default skin model string
     */
    fun getDefaultSkinModel(): String {
        return config.getString("plugin.default-skin-model", "CLASSIC") ?: "CLASSIC"
    }

    /**
     * Gets the application strategy from config
     * @return The application strategy string (CLASSIC or SNEAKY_CHARACTER_MANAGER)
     */
    fun getApplicationStrategy(): String {
        return config.getString("plugin.application-strategy", "CLASSIC") ?: "CLASSIC"
    }

    /** Reloads the configuration from disk */
    fun reloadConfig() {
        plugin.reloadConfig()
    }
}
