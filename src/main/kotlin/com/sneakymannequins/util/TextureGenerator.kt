package com.sneakymannequins.util

import java.util.Base64
import java.util.UUID
import com.google.gson.Gson
import com.google.gson.JsonObject

object TextureGenerator {
    
    /**
     * Generates a texture JSON with cape support
     * @param skinUrl The URL of the skin texture
     * @param capeUrl The URL of the cape texture (optional)
     * @return Base64 encoded texture JSON string
     */
    fun generateTextureJson(skinUrl: String, capeUrl: String? = null): String {
        val timestamp = System.currentTimeMillis()
        val profileId = UUID.randomUUID().toString().replace("-", "")
        val profileName = "Mannequin"
        
        // Create the texture JSON structure
        val textureJson = JsonObject().apply {
            addProperty("timestamp", timestamp)
            addProperty("profileId", profileId)
            addProperty("profileName", profileName)
            addProperty("signatureRequired", false)
            
            val textures = JsonObject().apply {
                val skin = JsonObject().apply {
                    addProperty("url", skinUrl)
                }
                add("SKIN", skin)
                
                // Add cape if provided
                capeUrl?.let { cape ->
                    val capeObj = JsonObject().apply {
                        addProperty("url", cape)
                    }
                    add("CAPE", capeObj)
                }
            }
            add("textures", textures)
        }
        
        // Convert to JSON string and encode to base64
        val jsonString = Gson().toJson(textureJson)
        return Base64.getEncoder().encodeToString(jsonString.toByteArray())
    }
}
