package com.sneakymannequins.managers

import org.bukkit.entity.Mannequin
import org.bukkit.entity.Player
import org.bukkit.entity.EntityType
import org.bukkit.profile.PlayerTextures
import org.bukkit.Bukkit
import java.util.UUID
import java.net.URL
import org.bukkit.Location
import org.bukkit.NamespacedKey
import io.papermc.paper.datacomponent.item.ResolvableProfile
import com.destroystokyo.paper.profile.ProfileProperty
import com.sneakymannequins.util.TextureGenerator
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class MannequinManager {

}

data class MannequinData (
	val location: Location
) {
	var entity: Mannequin? = null
	var skinData = SkinData(
		model = PlayerTextures.SkinModel.CLASSIC
	)

	init {
		this.spawn()
	}
	
	public fun spawn() {
		val spawnedEntity = location.world?.spawnEntity(location, EntityType.MANNEQUIN) as Mannequin
		this.entity = spawnedEntity
		this.applySkin()
	}

	public fun applySkin() {
		val mannequinEntity = entity ?: throw IllegalStateException("Mannequin entity not spawned yet")
		
		// Generate a unique filename for this skin
		//val imageName = "skin-${UUID.randomUUID().toString().substring(0, 8)}.png"
		val imageName = "123.png"
		
		// Get the image URL from config
		val imageUrl = ConfigManager.instance.getImageUrl(imageName)
		//val imageUrl = "https://www.minecraftskins.com/uploads/skins/2025/10/11/magmabound-23576110.png"
		//val imageUrl = "http://textures.minecraft.net/texture/dbb0fe08a42ebefe8e8f84cb231f4fe62bc9013ce958877b2738f252c721f393"
		
		// Generate the texture JSON using our new utility
		val textureJson = TextureGenerator.generateTextureJson(imageUrl)
		
		// Create ProfileProperty with the generated texture
		val textureProperty = ProfileProperty("textures", textureJson, "")
		
		// Try to access the NMS implementation
		val newProfile = ResolvableProfile.resolvableProfile().build()
		try {
			val profileClass = newProfile.javaClass
			val implField = profileClass.getDeclaredField("impl")
			implField.isAccessible = true
			val nmsProfile = implField.get(newProfile)
			
			if (ConfigManager.instance.isDebugEnabled()) {
				println("NMS Profile type: ${nmsProfile.javaClass}")
				println("NMS Profile fields:")
				nmsProfile.javaClass.declaredFields.forEach { field ->
					println("  ${field.name}: ${field.type}")
				}
			}
			
			// Try to access the contents field
			try {
				val contentsField = nmsProfile.javaClass.getDeclaredField("contents")
				contentsField.isAccessible = true
				val contents = contentsField.get(nmsProfile)
				
				if (ConfigManager.instance.isDebugEnabled()) {
					println("Contents type: ${contents.javaClass}")
					println("Contents value: $contents")
				}
				
				// Try to find a field that contains the value
				val possibleFieldNames = listOf("value", "right", "data", "content")
				for (fieldName in possibleFieldNames) {
					try {
						val field = contents.javaClass.getDeclaredField(fieldName)
						field.isAccessible = true
						val value = field.get(contents)
						
						if (value != null && value.toString().contains("Partial")) {
							val partialClass = value.javaClass
							if (ConfigManager.instance.isDebugEnabled()) {
								println("Found Partial object! Trying to access its properties...")
								println("Partial class fields:")
								partialClass.declaredFields.forEach { fieldDecl ->
									println("  ${fieldDecl.name}: ${fieldDecl.type}")
								}
							}
							
							// Try to find and modify the properties field
							try {
								val propertiesField = partialClass.getDeclaredField("properties")
								propertiesField.isAccessible = true
								val propertiesValue = propertiesField.get(value)
								
								if (ConfigManager.instance.isDebugEnabled()) {
									println("Properties value: $propertiesValue (${propertiesValue?.javaClass})")
								}
								
								// Found the properties field - it's a Multimap
								try {
									val multimapField = propertiesValue.javaClass.getDeclaredField("properties")
									multimapField.isAccessible = true
									val currentMultimap = multimapField.get(propertiesValue)
									
									if (ConfigManager.instance.isDebugEnabled()) {
										println("Current Multimap: $currentMultimap (${currentMultimap?.javaClass})")
									}
									
									// Create a new mutable Multimap
									val mutableMultimap = com.google.common.collect.ArrayListMultimap.create<String, Any>()
									
									// Add our texture property using Mojang's Property class via reflection
									try {
										val propertyClass = Class.forName("com.mojang.authlib.properties.Property")
										val propertyConstructor = propertyClass.getConstructor(String::class.java, String::class.java, String::class.java)
										val mojangProperty = propertyConstructor.newInstance(textureProperty.name, textureProperty.value, textureProperty.signature)
										mutableMultimap.put("textures", mojangProperty)
										
										if (ConfigManager.instance.isDebugEnabled()) {
											println("Successfully created Mojang Property via reflection!")
										}
									} catch (e: Exception) {
										if (ConfigManager.instance.isDebugEnabled()) {
											println("Failed to create Mojang Property: ${e.message}")
										}
										// Fallback: try to use the Paper ProfileProperty directly
										mutableMultimap.put("textures", textureProperty)
									}
									
									// Replace the properties field with our mutable Multimap
									multimapField.set(propertiesValue, mutableMultimap)
									
									if (ConfigManager.instance.isDebugEnabled()) {
										println("Successfully replaced properties field with mutable Multimap!")
									}
									
								} catch (e: Exception) {
									if (ConfigManager.instance.isDebugEnabled()) {
										println("Properties field replacement failed: ${e.message}")
									}
								}
								
							} catch (e: Exception) {
								if (ConfigManager.instance.isDebugEnabled()) {
									println("Properties field access failed: ${e.message}")
								}
							}
							break
						}
					} catch (e: Exception) {
						if (ConfigManager.instance.isDebugEnabled()) {
							println("Field $fieldName not found or failed: ${e.message}")
						}
					}
				}
			} catch (e: Exception) {
				if (ConfigManager.instance.isDebugEnabled()) {
					println("Contents field access failed: ${e.message}")
				}
			}
		} catch (e: Exception) {
			if (ConfigManager.instance.isDebugEnabled()) {
				println("NMS reflection failed: ${e.message}")
			}
		}
		
		mannequinEntity.profile = newProfile
	}
	
	/**
	 * Saves a skin image to the configured storage directory
	 * @param imageData The image data as a byte array
	 * @return The filename of the saved image, or null if failed
	 */
	public fun saveSkinImage(imageData: ByteArray): String? {
		return try {
			// Ensure storage directory exists
			if (!ConfigManager.instance.ensureImageStorageDirectory()) {
				return null
			}
			
			// Generate unique filename
			val imageName = "skin-${UUID.randomUUID().toString().substring(0, 8)}.png"
			val storagePath = ConfigManager.instance.getImageStoragePath()
			val imageFile = storagePath.resolve(imageName).toFile()
			
			// Save the image
			Files.write(imageFile.toPath(), imageData)
			
			if (ConfigManager.instance.isDebugEnabled()) {
				println("Saved skin image: $imageFile")
			}
			
			imageName
		} catch (e: Exception) {
			if (ConfigManager.instance.isDebugEnabled()) {
				println("Failed to save skin image: ${e.message}")
			}
			null
		}
	}
	
	/**
	 * Downloads and saves a skin image from a URL
	 * @param imageUrl The URL to download the image from
	 * @return The filename of the saved image, or null if failed
	 */
	public fun downloadAndSaveSkinImage(imageUrl: String): String? {
		return try {
			// Download the image
			val url = URL(imageUrl)
			val imageData = url.openStream().use { it.readBytes() }
			
			// Save it using our save method
			saveSkinImage(imageData)
		} catch (e: Exception) {
			if (ConfigManager.instance.isDebugEnabled()) {
				println("Failed to download skin image from $imageUrl: ${e.message}")
			}
			null
		}
	}
}

data class SkinData (
	var model: PlayerTextures.SkinModel = PlayerTextures.SkinModel.CLASSIC
) {
	/**
	 * Generates a texture property using the new TextureGenerator
	 * @param imageUrl The URL of the skin image
	 * @return ProfileProperty with the generated texture JSON
	 */
	public fun getTextureProperty(imageUrl: String): ProfileProperty {
		val textureJson = TextureGenerator.generateTextureJson(imageUrl)
		return ProfileProperty("textures", textureJson, "")
	}
}
