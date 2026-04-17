package com.sneakymannequins.items

import org.bukkit.NamespacedKey
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.inventory.meta.components.CustomModelDataComponent

/**
 * Applies 1.21.4+ item model data-components when available.
 *
 * We use reflection to keep compilation/runtime flexible across server API variants while still
 * supporting the modern `item_model` + `custom_model_data` (floats) approach.
 */
object ItemModelApplier {
    data class Spec(
            val itemModel: String? = null,
            /** CustomModelData component floats; if set, takes precedence over legacy integer CMD. */
            val customModelDataFloats: List<Float>? = null,
            /** Legacy integer CustomModelData; used as fallback when no floats are provided. */
            val legacyCustomModelData: Int? = null
    )

    fun apply(meta: ItemMeta, spec: Spec?) {
        if (spec == null) return
        applyItemModel(meta, spec.itemModel)
        applyCustomModelData(meta, spec.customModelDataFloats, spec.legacyCustomModelData)
    }

    private fun applyItemModel(meta: ItemMeta, itemModel: String?) {
        val raw = itemModel?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val key = NamespacedKey.fromString(raw) ?: return

        // Paper 1.21.4+: ItemMeta#setItemModel(NamespacedKey)
        // Call directly (more reliable than reflection; reflection failure was silent).
        runCatching {
            meta.setItemModel(key)
        }.recoverCatching {
            // Safety fallback for non-Paper runtimes (shouldn't happen for this project).
            val m = meta.javaClass.methods.firstOrNull {
                it.name == "setItemModel" &&
                        it.parameterTypes.size == 1 &&
                        it.parameterTypes[0] == NamespacedKey::class.java
            } ?: return
            m.invoke(meta, key)
        }
    }

    private fun applyCustomModelData(meta: ItemMeta, floats: List<Float>?, legacyInt: Int?) {
        val floatList = floats?.filterNotNull()?.takeIf { it.isNotEmpty() }

        // Prefer component floats if provided and supported by the runtime API.
        if (floatList != null) {
            val applied = runCatching { applyCustomModelDataFloatsComponent(meta, floatList) }.getOrNull()
            if (applied == true) return
        }

        // Fallback to legacy integer CMD (Paper exposes setCustomModelData(Integer), not int).
        if (legacyInt != null) {
            runCatching {
                meta.setCustomModelData(legacyInt)
            }.recoverCatching {
                val m =
                        meta.javaClass.methods.firstOrNull { method ->
                            method.name == "setCustomModelData" &&
                                    method.parameterTypes.size == 1 &&
                                    method.parameterTypes[0] == java.lang.Integer::class.java
                        }
                                ?: throw it
                m.invoke(meta, legacyInt)
            }
        }
    }

    /**
     * Attempts to set the custom model data component using the Bukkit/Paper 1.21.4 API:
     * `meta.customModelDataComponent.floats = ...; meta.setCustomModelDataComponent(component)`.
     *
     * Returns true if it looks like it succeeded, false if the API is unavailable.
     */
    private fun applyCustomModelDataFloatsComponent(meta: ItemMeta, floats: List<Float>): Boolean {
        // On Paper 1.21.4, this method exists and uses org.bukkit.inventory.meta.components.CustomModelDataComponent.
        return runCatching {
            val current: CustomModelDataComponent = meta.customModelDataComponent
            current.floats = floats
            meta.setCustomModelDataComponent(current)
            true
        }.getOrDefault(false)
    }
}

