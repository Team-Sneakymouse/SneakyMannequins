package com.sneakymannequins.items

import org.bukkit.NamespacedKey
import org.bukkit.inventory.meta.ItemMeta

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
        runCatching {
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

        // Fallback to legacy integer CMD (works on older + newer, though deprecated in 1.21.4+).
        if (legacyInt != null) {
            runCatching {
                val m = meta.javaClass.methods.firstOrNull {
                    it.name == "setCustomModelData" &&
                            it.parameterTypes.size == 1 &&
                            (it.parameterTypes[0] == Int::class.javaPrimitiveType ||
                                    it.parameterTypes[0] == Int::class.javaObjectType)
                } ?: return
                m.invoke(meta, legacyInt)
            }
        }
    }

    /**
     * Attempts to set the CUSTOM_MODEL_DATA data component using Paper's API:
     * `meta.setCustomModelDataComponent(CustomModelData.customModelData().floats(...).build())`.
     *
     * Returns true if it looks like it succeeded, false if the API is unavailable.
     */
    private fun applyCustomModelDataFloatsComponent(meta: ItemMeta, floats: List<Float>): Boolean {
        val customModelDataClass =
                runCatching { Class.forName("io.papermc.paper.datacomponent.item.CustomModelData") }
                        .getOrNull()
                        ?: return false

        val builder =
                runCatching {
                    customModelDataClass.methods.firstOrNull {
                        it.name == "customModelData" && it.parameterTypes.isEmpty()
                    }?.invoke(null)
                }
                        .getOrNull()
                        ?: return false

        // builder.floats(List<Float>)
        runCatching {
            val floatsMethod =
                    builder.javaClass.methods.firstOrNull {
                        it.name == "floats" && it.parameterTypes.size == 1 && List::class.java.isAssignableFrom(it.parameterTypes[0])
                    } ?: return false
            floatsMethod.invoke(builder, floats)
        }.getOrElse { return false }

        val built =
                runCatching {
                    builder.javaClass.methods.firstOrNull {
                        it.name == "build" && it.parameterTypes.isEmpty()
                    }?.invoke(builder)
                }
                        .getOrNull()
                        ?: return false

        // meta.setCustomModelDataComponent(CustomModelData)
        val setComponent =
                meta.javaClass.methods.firstOrNull {
                    it.name == "setCustomModelDataComponent" &&
                            it.parameterTypes.size == 1 &&
                            it.parameterTypes[0].name == "io.papermc.paper.datacomponent.item.CustomModelData"
                }
                        ?: return false

        runCatching { setComponent.invoke(meta, built) }.getOrElse { return false }
        return true
    }
}

