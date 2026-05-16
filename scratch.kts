import java.util.concurrent.ConcurrentHashMap

data class LayerSessionData(val option: String?, val disabled: Boolean = false)
data class SessionData(val uid: String, val slimModel: Boolean? = null, val layers: Map<String, LayerSessionData>)

val baseSession = SessionData("uid1", null, mapOf("shirt" to LayerSessionData("opt1", false)))

val updatedLayers = baseSession.layers.toMutableMap()
val sessionKey = baseSession.layers.keys.find { it.equals("shirt", true) }!!
val layerData = baseSession.layers[sessionKey]!!

val disabled = !layerData.disabled // true
updatedLayers[sessionKey] = layerData.copy(disabled = disabled)
val updatedSession = baseSession.copy(layers = updatedLayers)

val out = baseSession.layers.toMutableMap()
out.putAll(updatedSession.layers)

println("out shirt disabled: ${out["shirt"]?.disabled}")
