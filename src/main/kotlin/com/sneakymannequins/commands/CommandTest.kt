package com.sneakymannequins.commands

import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import com.sneakymannequins.managers.ConfigManager
import com.sneakymannequins.util.TextureGenerator
import com.destroystokyo.paper.profile.ProfileProperty

class CommandTest : CommandBase("test") {

	override fun execute(sender: CommandSender, label: String, args: Array<String>): Boolean {
		val player = sender as? Player ?: run {
			sender.sendMessage("You must be a player to use this command")
			return true
		}
		//val url = ConfigManager.instance.getImageUrl("123.png")
		val url = "http://textures.minecraft.net/texture/dbb0fe08a42ebefe8e8f84cb231f4fe62bc9013ce958877b2738f252c721f393"
		val newProfile = player.playerProfile
		
		println("Player: ${player.name}")
		println("PlayerProfile type: ${newProfile.javaClass.name}")
		println("PlayerProfile toString: $newProfile")

		// Generate the texture JSON using our new utility
		val textureJson = TextureGenerator.generateTextureJson(url)
		
		// Create ProfileProperty with the generated texture
		val textureProperty = ProfileProperty("textures", textureJson, "")
		
		// Try to access the NMS implementation
		try {
			val profileClass = newProfile.javaClass
			println("PlayerProfile class: ${profileClass.name}")
			println("PlayerProfile fields:")
			profileClass.declaredFields.forEach { field ->
				println("  ${field.name}: ${field.type}")
			}
			
			// Try to find the impl field or similar
			val implField = try {
				profileClass.getDeclaredField("impl")
			} catch (e: NoSuchFieldException) {
				println("No 'impl' field found, trying other possibilities...")
				// Try other common field names
				val possibleFields = listOf("profile", "gameProfile", "handle", "nmsProfile")
				var foundField: java.lang.reflect.Field? = null
				for (fieldName in possibleFields) {
					try {
						foundField = profileClass.getDeclaredField(fieldName)
						println("Found field: $fieldName")
						break
					} catch (e: NoSuchFieldException) {
						// Continue trying
					}
				}
				foundField
			}
			
			if (implField == null) {
				println("No suitable field found for NMS profile access")
				return true
			}
			
			implField.isAccessible = true
			val gameProfile = implField.get(newProfile)
			
			println("GameProfile type: ${gameProfile.javaClass}")
			println("GameProfile fields:")
			gameProfile.javaClass.declaredFields.forEach { field: java.lang.reflect.Field ->
				println("  ${field.name}: ${field.type}")
			}
			
			// Access the PropertyMap directly using reflection
			val propertiesField = gameProfile.javaClass.getDeclaredField("properties")
			propertiesField.isAccessible = true
			val propertyMap = propertiesField.get(gameProfile)
			
			println("PropertyMap type: ${propertyMap.javaClass}")
			println("PropertyMap fields:")
			propertyMap.javaClass.declaredFields.forEach { field: java.lang.reflect.Field ->
				println("  ${field.name}: ${field.type}")
			}
			
			// Try to access the internal Multimap
			try {
				val multimapField = propertyMap.javaClass.getDeclaredField("properties")
				multimapField.isAccessible = true
				val currentMultimap = multimapField.get(propertyMap)
				
				println("Current Multimap: $currentMultimap (${currentMultimap?.javaClass})")
				
				// Create a new mutable Multimap
				val mutableMultimap = com.google.common.collect.ArrayListMultimap.create<String, Any>()
				
				// Add our texture property using Mojang's Property class via reflection
				try {
					val propertyClass = Class.forName("com.mojang.authlib.properties.Property")
					val propertyConstructor = propertyClass.getConstructor(String::class.java, String::class.java, String::class.java)
					val mojangProperty = propertyConstructor.newInstance(textureProperty.name, textureProperty.value, textureProperty.signature)
					mutableMultimap.put("textures", mojangProperty)
					
					println("Successfully created Mojang Property via reflection!")
				} catch (e: Exception) {
					println("Failed to create Mojang Property: ${e.message}")
					// Fallback: try to use the Paper ProfileProperty directly
					mutableMultimap.put("textures", textureProperty)
				}
				
				// Replace the properties field with our mutable Multimap
				multimapField.set(propertyMap, mutableMultimap)
				
				println("Successfully replaced properties field with mutable Multimap!")
				
			} catch (e: Exception) {
				println("PropertyMap manipulation failed: ${e.message}")
				e.printStackTrace()
			}
		} catch (e: Exception) {
			if (ConfigManager.instance.isDebugEnabled()) {
				println("NMS reflection failed: ${e.message}")
			}
		}
		
		player.playerProfile = newProfile

		return true
	}

}