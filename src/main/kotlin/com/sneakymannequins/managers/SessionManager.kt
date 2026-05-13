package com.sneakymannequins.managers

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.sneakymannequins.SneakyMannequins
import com.sneakymannequins.integrations.CharacterManagerBridge
import com.sneakymannequins.model.LayerSelection
import com.sneakymannequins.model.LayerSessionData
import com.sneakymannequins.model.Mannequin
import com.sneakymannequins.model.SessionData
import com.sneakymannequins.model.SkinSelection
import com.sneakymannequins.util.SkinComposer
import com.sneakymannequins.util.SkinUv
import com.sneakymouse.sneakyholos.util.TextUtility
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.time.Instant
import java.util.BitSet
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ThreadLocalRandom
import javax.imageio.ImageIO
import org.bukkit.entity.Player
import org.bukkit.profile.PlayerTextures.SkinModel

data class FinalizedResult(val file: File, val slim: Boolean, val uid: String)

class SessionManager(
        private val plugin: SneakyMannequins,
        private val dataFolder: File,
        private val layerManager: LayerManager,
        private val characterManagerBridge: CharacterManagerBridge,
        private val statsManager: StatsManager
) {
    private fun hexToColor(hex: String?): Color? {
        if (hex == null) return null
        return try {
            val normalized = if (hex.startsWith("#")) hex.substring(1) else hex
            val argb = normalized.toLong(16).toInt()
            Color(argb, normalized.length > 6)
        } catch (e: Exception) {
            null
        }
    }

    private val sessionsDir = File(dataFolder, "sessions")
    private val templatesDir = File(dataFolder, "templates")
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    /** Shared skin download + session UID decode per texture URL (see [SkinTextureSessionCache]). */
    val skinTextureSessionCache = SkinTextureSessionCache(this)

    companion object {
        private const val UID_LENGTH = 8
        private const val UID_CHARS =
                "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

        fun encodeUidToImage(image: BufferedImage, uid: String) {
            val paddedUid = uid.padEnd(UID_LENGTH, '0').take(UID_LENGTH)
            val b = paddedUid.toByteArray(Charsets.US_ASCII)
            // Legacy layout: Use (3, 48) and (3, 49) with full 4-byte ARGB storage per pixel.
            // This avoids UV map overlaps at (4, 48) and (5, 48).
            val p0 =
                    ((b[0].toInt() and 0xFF) shl 24) or
                            ((b[1].toInt() and 0xFF) shl 16) or
                            ((b[2].toInt() and 0xFF) shl 8) or
                            (b[3].toInt() and 0xFF)
            val p1 =
                    ((b[4].toInt() and 0xFF) shl 24) or
                            ((b[5].toInt() and 0xFF) shl 16) or
                            ((b[6].toInt() and 0xFF) shl 8) or
                            (b[7].toInt() and 0xFF)
            image.setRGB(3, 48, p0)
            image.setRGB(3, 49, p1)
        }

        /**
         * The 64×64 painting/UID grid: **crop** the top-left of HD skins (1:1). Scaling a 128×128
         * atlas into 64×64 blends pixels and breaks [decodeUidFromImage].
         */
        fun skinTopLeft64Argb(skin: BufferedImage): BufferedImage {
            val out = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB)
            val g = out.createGraphics()
            g.composite = java.awt.AlphaComposite.Src
            when {
                skin.width < 64 || skin.height < 64 ->
                        g.drawImage(skin, 0, 0, 64, 64, null)
                skin.width == 64 && skin.height == 64 ->
                        g.drawImage(skin, 0, 0, null)
                else -> g.drawImage(skin, 0, 0, 64, 64, 0, 0, 64, 64, null)
            }
            g.dispose()
            return out
        }

        fun decodeUidFromImage(image: BufferedImage): String? {
            val grid = if (image.width == 64 && image.height == 64) image else skinTopLeft64Argb(image)
            if (grid.width < 4 || grid.height < 50) return null

            // Primary: Legacy layout (3, 48) and (3, 49) using all channels including alpha.
            val p1 = grid.getRGB(3, 48)
            val p2 = grid.getRGB(3, 49)
            val bytes = ByteArray(8)
            bytes[0] = ((p1 ushr 24) and 0xFF).toByte()
            bytes[1] = ((p1 ushr 16) and 0xFF).toByte()
            bytes[2] = ((p1 ushr 8) and 0xFF).toByte()
            bytes[3] = (p1 and 0xFF).toByte()
            bytes[4] = ((p2 ushr 24) and 0xFF).toByte()
            bytes[5] = ((p2 ushr 16) and 0xFF).toByte()
            bytes[6] = ((p2 ushr 8) and 0xFF).toByte()
            bytes[7] = (p2 and 0xFF).toByte()
            val str = String(bytes, Charsets.US_ASCII)
            if (str.length == 8 && str.all { it in UID_CHARS }) {
                return str
            }

            return null
        }

        /**
         * Stable filesystem key for a profile skin URL: Mojang `textures.minecraft.net/texture/<64
         * hex>` id (lowercase), or the first 32 hex chars of SHA-256 of the full URL string.
         */
        fun skinTextureStorageKey(url: URL): String {
            val external = url.toExternalForm()
            val m =
                    Regex(
                                    "https?://textures\\.minecraft\\.net/texture/([a-fA-F0-9]{64})(?:\\?.*)?$",
                                    RegexOption.IGNORE_CASE
                            )
                            .find(external)
            if (m != null) return m.groupValues[1].lowercase(Locale.US)
            val digest = MessageDigest.getInstance("SHA-256").digest(external.toByteArray(Charsets.UTF_8))
            return digest.take(16).joinToString("") { b -> "%02x".format(b) }
        }

        /** True for UIDs produced by [generateUid] (and persisted session files). */
        fun isWellFormedSessionUid(uid: String): Boolean =
                uid.length == UID_LENGTH && uid.all { it in UID_CHARS }
    }

    init {
        sessionsDir.mkdirs()
        templatesDir.mkdirs()
    }

    fun save(
            mannequin: Mannequin,
            player: Player,
            renderedImage: BufferedImage? = null,
            characterUuid: String? = null,
            characterName: String? = null
    ): SessionData {
        val uid = generateUid()
        val layers = snapshotLayers(mannequin)
        val charContext = characterManagerBridge.currentCharacter(player)
        val session =
                SessionData(
                        uid = uid,
                        creator = player.uniqueId.toString(),
                        createdAt = Instant.now().toString(),
                        slimModel = mannequin.slimModel,
                        layers = layers,
                        characterUuid = characterUuid ?: charContext?.characterUuid,
                        characterName = characterName ?: charContext?.characterName
                )
        val jsonString = gson.toJson(session)
        try {
            sessionsDir.mkdirs()
            File(sessionsDir, "$uid.json").writeText(jsonString)
            if (renderedImage != null) {
                runCatching { ImageIO.write(renderedImage, "PNG", File(sessionsDir, "$uid.png")) }
            }
            statsManager.record(session)
        } catch (e: Exception) {
            plugin.logger.severe("Failed to save session $uid: ${e.message}")
            throw e
        }
        return session
    }

    /** Writes `sessions/<uid>.json` before the skin is finalized so disk state matches encoded pixels. */
    private fun persistSession(session: SessionData, recordStats: Boolean = true) {
        val uid = session.uid
        val jsonString = gson.toJson(session)
        try {
            sessionsDir.mkdirs()
            File(sessionsDir, "$uid.json").writeText(jsonString)
            if (recordStats) {
                statsManager.record(session)
            }
        } catch (e: Exception) {
            plugin.logger.severe("Failed to persist session $uid: ${e.message}")
            throw e
        }
    }

    fun load(id: String): SessionData? {
        val normalized = id.trim()
        val sessionFile = File(sessionsDir, "$normalized.json")
        if (sessionFile.exists()) {
            return runCatching { gson.fromJson(sessionFile.readText(), SessionData::class.java) }
                    .getOrNull()
        }
        val templateName = id.lowercase().trim()
        val templateFile = File(templatesDir, "$templateName.json")
        if (templateFile.exists()) {
            return runCatching { gson.fromJson(templateFile.readText(), SessionData::class.java) }
                    .getOrNull()
        }
        return null
    }

    fun history(playerUuid: UUID): List<SessionData> {
        if (!sessionsDir.exists()) return emptyList()
        return sessionsDir
                .listFiles { f -> f.extension == "json" }
                ?.mapNotNull { f ->
                    runCatching { gson.fromJson(f.readText(), SessionData::class.java) }.getOrNull()
                }
                ?.filter { it.creator == playerUuid.toString() }
                ?.sortedByDescending { it.createdAt }
                ?: emptyList()
    }

    fun latest(playerUuid: UUID): SessionData? = history(playerUuid).firstOrNull()

    fun fingerprint(mannequin: Mannequin): String {
        val layers = snapshotLayers(mannequin)
        return fingerprint(mannequin.slimModel, layers)
    }

    fun fingerprint(session: SessionData): String =
            fingerprint(session.slimModel ?: false, session.layers)

    /**
     * Create a named template from an existing UID session. If [layerIds] is non-empty only those
     * layers are included. Returns a descriptive result string or null on failure.
     */
    fun createTemplate(uid: String, name: String, layerIds: List<String>, player: Player): String? {
        val source = load(uid) ?: return "Session '$uid' not found."

        val inheritBodyType = layerIds.any { it.equals("body_type", ignoreCase = true) }
        val filteredLayerIds = layerIds.filterNot { it.equals("body_type", ignoreCase = true) }

        val filteredLayers =
                if (filteredLayerIds.isNotEmpty()) {
                    source.layers.filterKeys { it in filteredLayerIds }
                } else {
                    source.layers
                }
        if (filteredLayers.isEmpty() && filteredLayerIds.isNotEmpty())
                return "No matching layers found in session."

        val safeName = name.lowercase().trim().replace(Regex("[^a-z0-9_-]"), "_")
        val templateFile = File(templatesDir, "$safeName.json")
        if (templateFile.exists()) {
            val existing =
                    runCatching { gson.fromJson(templateFile.readText(), SessionData::class.java) }
                            .getOrNull()
            if (existing != null && existing.creator != player.uniqueId.toString()) {
                return "Template '$safeName' already exists and belongs to another player."
            }
        }

        val charContext = characterManagerBridge.currentCharacter(player)
        val template =
                source.copy(
                        uid = safeName,
                        creator = player.uniqueId.toString(),
                        createdAt = Instant.now().toString(),
                        layers = filteredLayers,
                        slimModel = if (inheritBodyType) source.slimModel else null,
                        characterUuid = charContext?.characterUuid,
                        characterName = charContext?.characterName
                )
        val jsonString = gson.toJson(template)
        CompletableFuture.runAsync { templateFile.writeText(jsonString) }
        return null
    }

    /**
     * Create a NEW session UID from an existing session UID, optionally filtering to [layerIds].
     * Returns the created SessionData on success, or an error message on failure.
     */
    fun createPartialSession(uid: String, layerIds: List<String>, player: Player): Pair<SessionData?, String?> {
        val source = load(uid) ?: return null to "Session '$uid' not found."

        val inheritBodyType = layerIds.any { it.equals("body_type", ignoreCase = true) }
        val filteredLayerIds = layerIds.map { it.lowercase() }.filterNot { it == "body_type" }

        val filteredLayers =
                if (filteredLayerIds.isNotEmpty()) {
                    source.layers.filterKeys { it.lowercase() in filteredLayerIds }
                } else {
                    source.layers
                }

        if (filteredLayers.isEmpty() && filteredLayerIds.isNotEmpty()) {
            return null to "No matching layers found in session."
        }

        val newUid = generateUid()
        val charContext = characterManagerBridge.currentCharacter(player)
        val session =
                SessionData(
                        uid = newUid,
                        creator = player.uniqueId.toString(),
                        createdAt = Instant.now().toString(),
                        slimModel = if (inheritBodyType) source.slimModel else null,
                        layers = filteredLayers,
                        characterUuid = charContext?.characterUuid,
                        characterName = charContext?.characterName
                )

        persistSession(session)
        return session to null
    }

    fun merge(s1: SessionData, s2: SessionData, defaultSlim: Boolean, player: Player): SessionData {
        val mergedLayers = s2.layers.toMutableMap()
        mergedLayers.putAll(s1.layers)

        val mergedSlim = s1.slimModel ?: s2.slimModel ?: defaultSlim
        val charContext = characterManagerBridge.currentCharacter(player)

        return SessionData(
                uid = "merged",
                creator = player.uniqueId.toString(),
                createdAt = Instant.now().toString(),
                slimModel = mergedSlim,
                layers = mergedLayers,
                characterUuid = charContext?.characterUuid,
                characterName = charContext?.characterName
        )
    }

    fun isValid(session: SessionData, slim: Boolean): Boolean {
        for ((layerId, layerData) in session.layers) {
            val optionId = layerData.option ?: continue
            val options = layerManager.allOptions(layerId)
            val option = options.find { it.id == optionId }
            if (option == null) {
                plugin.logger.warning("Session validation failed: Option $optionId not found for layer $layerId")
                return false
            }

            if (optionId == "none") continue

            if (slim) {
                if (option.imageSlim == null) {
                    plugin.logger.warning("Session validation failed: Option $optionId for layer $layerId has no slim image")
                    return false
                }
            } else {
                if (option.imageDefault == null) {
                    plugin.logger.warning("Session validation failed: Option $optionId for layer $layerId has no default image")
                    return false
                }
            }

            if (layerData.selectedTexture != null) {
                if (layerManager.texture(layerData.selectedTexture) == null) {
                    plugin.logger.warning("Session validation failed: Texture ${layerData.selectedTexture} not found for layer $layerId")
                    return false
                }
            }
        }
        return true
    }

    fun isComplete(session: SessionData, slim: Boolean): Boolean {
        val covered = BitSet(64 * 64)

        for ((layerId, layerData) in session.layers) {
            val optionId = layerData.option ?: continue
            val options = layerManager.allOptions(layerId)
            val option = options.find { it.id == optionId } ?: continue

            val image = if (slim) option.imageSlim else option.imageDefault
            if (image == null) continue

            for (x in 0 until 64) {
                for (y in 0 until 64) {
                    if ((image.getRGB(x, y) ushr 24) != 0) {
                        covered.set(y * 64 + x)
                    }
                }
            }
        }

        var allCovered = true
        SkinUv.forEachInnerBasePixel { x, y ->
            if (!covered.get(y * 64 + x)) {
                allCovered = false
            }
        }
        return allCovered
    }

    /**
     * Steve/Alex for the finalized skin: always [preApplySlim] (the context player's model before
     * this apply) unless [appliedSession] covers the full inner base UV and may define body type.
     */
    private fun resultSlimAfterApply(appliedSession: SessionData, preApplySlim: Boolean): Boolean {
        if (!isComplete(appliedSession, preApplySlim)) {
            return preApplySlim
        }
        return appliedSession.slimModel ?: preApplySlim
    }

    /**
     * True when [session]'s layers fully paint every inner-base UV pixel for the same Steve/Alex
     * choice [resultSlimAfterApply] would use when finalizing (not only the context player's model).
     */
    private fun sessionFullyCoversInnerBase(session: SessionData, preApplySlim: Boolean): Boolean {
        val composeSlim = resultSlimAfterApply(session, preApplySlim)
        return isComplete(session, composeSlim)
    }

    /**
     * True when [injectPersistedSkinBaseLayer] would replace the base layer (missing, null option,
     * or [none]). If the session already selects a real body part, we must not call
     * [LayerManager.ensurePlayerSkinTextureBasePart] — that writes to disk even when injection is a
     * no-op.
     */
    private fun baseLayerNeedsPersistedSkinFill(
            layers: Map<String, LayerSessionData>,
            canonicalBaseLayerId: String
    ): Boolean {
        val existing =
                layers.entries
                        .find { it.key.equals(canonicalBaseLayerId, ignoreCase = true) }
                        ?.value
        return when {
            existing == null -> true
            existing.option == null -> true
            existing.option == "none" -> true
            else -> false
        }
    }

    /**
     * When there is no session B, select the persisted Mojang-skin base part on [canonicalBaseLayerId]
     * unless the incoming session already chose a non-[none] base option.
     */
    private fun injectPersistedSkinBaseLayer(
            layers: Map<String, LayerSessionData>,
            canonicalBaseLayerId: String,
            fullOptionId: String
    ): Map<String, LayerSessionData> {
        val existing =
                layers.entries
                        .find { it.key.equals(canonicalBaseLayerId, ignoreCase = true) }
                        ?.value
        if (existing?.option != null && existing.option != "none") {
            return layers
        }
        val out = layers.toMutableMap()
        out.keys.filter { it.equals(canonicalBaseLayerId, ignoreCase = true) }.forEach { out.remove(it) }
        out[canonicalBaseLayerId] =
                (existing ?: LayerSessionData(option = null)).copy(option = fullOptionId)
        return out
    }

    fun finalizeSession(
            requester: Player,
            man: Mannequin,
            sessionOverride: SessionData? = null,
            contextPlayer: Player = requester,
            craig: Boolean = false,
            createNewUid: Boolean = false,
            recordStats: Boolean = true
    ): CompletableFuture<FinalizedResult> {
        val playerSkinModel = contextPlayer.playerProfile.textures.skinModel
        val playerSkinUrl = contextPlayer.playerProfile.textures.skin
        val charUuid = characterManagerBridge.currentCharacter(contextPlayer)?.characterUuid
        val contextPlayerUniqueId = contextPlayer.uniqueId

        return CompletableFuture.supplyAsync {
            val targetDir = ConfigManager.instance.getImageStoragePath().toFile()
            val mannequinSession = sessionOverride ?: sessionFromMannequin(man)

            val skinUrl =
                    playerSkinUrl ?: throw IllegalStateException("Context player has no skin URL")

            // 1–3: Download skin (shared per-URL cache), read UID from pixels; resolve session B if
            // JSON exists.
            val decodedSkin =
                    try {
                        skinTextureSessionCache.getOrStartDecode(skinUrl).join()
                    } catch (e: Exception) {
                        throw IllegalStateException("Failed to download or decode skin", e)
                    }
            val skin64 = decodedSkin.skin64
            val fullImage = decodedSkin.fullImage
            val lastAppliedUid =
                    if (createNewUid || characterManagerBridge.active) {
                        decodedSkin.uid
                    } else {
                        null
                    }
            val baseSession = lastAppliedUid?.let { load(it) }
            val defaultSlim = playerSkinModel == SkinModel.SLIM

            val baseLayerId =
                    layerManager.definitionsInOrder().find { it.isBase }?.id
                            ?: layerManager.definitionsInOrder().firstOrNull()?.id

            var usedPersistedSkinCompose = false
            val sourceSession =
                    if (baseSession == null &&
                            baseLayerId != null &&
                            baseLayerNeedsPersistedSkinFill(mannequinSession.layers, baseLayerId) &&
                            !sessionFullyCoversInnerBase(mannequinSession, defaultSlim)
                    ) {
                        val optIdResult =
                                runCatching {
                                    layerManager
                                            .ensurePlayerSkinTextureBasePart(
                                                    contextPlayer,
                                                    baseLayerId,
                                                    skinUrl,
                                                    this,
                                                    uncraig = false
                                            )
                                            .join()
                                }
                        val optId = optIdResult.getOrNull()
                        optIdResult.exceptionOrNull()?.let { ex ->
                            plugin.logger.warning(
                                    "Could not ensure persisted skin base for ${contextPlayer.name}: ${ex.message}"
                            )
                        }
                        if (optId != null) {
                            usedPersistedSkinCompose = true
                            mannequinSession.copy(
                                    layers =
                                            injectPersistedSkinBaseLayer(
                                                    mannequinSession.layers,
                                                    baseLayerId,
                                                    optId
                                            )
                            )
                        } else {
                            if (optIdResult.isSuccess) {
                                plugin.logger.warning(
                                        "Could not ensure persisted skin base for ${contextPlayer.name}; composing on downloaded skin bitmap."
                                )
                            }
                            mannequinSession
                        }
                    } else {
                        mannequinSession
                    }

            var merged: SessionData =
                    if (baseSession != null) {
                        if (createNewUid) {
                            // 4–6: Discard bitmap for compose when B exists; merge A onto B (same id → A wins);
                            // new UID already assigned below, then compose + encode + apply.
                            val out = baseSession.layers.toMutableMap()
                            out.putAll(sourceSession.layers)
                            val resultSlim = resultSlimAfterApply(mannequinSession, defaultSlim)
                            val newUid = generateUid()
                            val charContext = characterManagerBridge.currentCharacter(contextPlayer)
                            SessionData(
                                    uid = newUid,
                                    creator = contextPlayerUniqueId.toString(),
                                    createdAt = Instant.now().toString(),
                                    slimModel = resultSlim,
                                    layers = out,
                                    characterUuid = charContext?.characterUuid,
                                    characterName = charContext?.characterName
                            ).also { persistSession(it, recordStats) }
                        } else {
                            merge(sourceSession, baseSession, defaultSlim, contextPlayer)
                        }
                    } else {
                        if (createNewUid) {
                            val resultSlim = resultSlimAfterApply(mannequinSession, defaultSlim)
                            val newUid = generateUid()
                            val charContext = characterManagerBridge.currentCharacter(contextPlayer)
                            SessionData(
                                    uid = newUid,
                                    creator = contextPlayerUniqueId.toString(),
                                    createdAt = Instant.now().toString(),
                                    slimModel = resultSlim,
                                    layers = sourceSession.layers,
                                    characterUuid = charContext?.characterUuid,
                                    characterName = charContext?.characterName
                            ).also { persistSession(it, recordStats) }
                        } else {
                            sourceSession.copy(
                                    slimModel = mannequinSession.slimModel ?: defaultSlim
                            )
                        }
                    }

            // Mannequin apply: persist the merged layers under one UID — reuse the session from
            // save/load when present so apply does not mint a second file/UID.
            if (!createNewUid) {
                val stableUid = mannequinSession.uid.takeIf { isWellFormedSessionUid(it) }
                merged =
                        if (stableUid != null) {
                            merged.copy(
                                    uid = stableUid,
                                    creator = mannequinSession.creator,
                                    createdAt = mannequinSession.createdAt
                            )
                        } else {
                            val newUid = generateUid()
                            merged.copy(
                                    uid = newUid,
                                    creator = contextPlayerUniqueId.toString(),
                                    createdAt = Instant.now().toString()
                            )
                        }
                persistSession(merged, recordStats)
            }

            val slim = merged.slimModel ?: false
            if (!isValid(merged, slim)) {
                throw IllegalStateException("Merged session is invalid for finalization")
            }

            // With a stored session B (from decoded UID + existing JSON), merge is authoritative;
            // the downloaded PNG is only for reading the UID. Without B, either composite A onto the
            // downloaded skin, or — when a persisted skin base part was injected — compose from layers
            // only (same as merge-with-B path).
            val composeBase: BufferedImage? =
                    when {
                        createNewUid && baseSession != null -> null
                        usedPersistedSkinCompose -> null
                        else -> fullImage
                    }

            val selection = sessionToSelection(merged)
            val layersDef = layerManager.definitionsInOrder()
            val hueSuppressSatLow =
                    plugin.config.getDouble("plugin.tinting.hue-suppress-saturation-low", 0.03).toFloat()
            val hueSuppressSatHigh =
                    plugin.config.getDouble("plugin.tinting.hue-suppress-saturation-high", 0.10).toFloat()

            val sessionImage =
                    SkinComposer.compose(
                            layers = layersDef,
                            selection = selection,
                            useSlimModel = slim,
                            optionResolver = { l, o -> layerManager.allOptions(l).find { it.id == o } },
                            textureResolver = { layerManager.texture(it) },
                            baseImage = composeBase,
                            blinkEnabled =
                                    plugin.config.getBoolean(
                                            "integrations.entity-texture-features.blink-enabled",
                                            false
                                    ),
                            jacketEnabled =
                                    plugin.config.getBoolean(
                                            "integrations.entity-texture-features.jacket-enabled",
                                            false
                                    ),
                            defaultJacketStyle =
                                    plugin.config.getInt(
                                            "integrations.entity-texture-features.jacket-dress-style",
                                            5
                                    ),
                            hueSuppressSaturationLow = hueSuppressSatLow,
                            hueSuppressSaturationHigh = hueSuppressSatHigh
                    )
            
            val finalImage = if (craig) com.sneakymannequins.util.SkinTransform.craig(sessionImage) else sessionImage

            targetDir.mkdirs()
            encodeUidToImage(finalImage, merged.uid)
            val f = File(targetDir, "finalized_${merged.uid}.png")
            ImageIO.write(finalImage, "PNG", f)
            FinalizedResult(f, slim, merged.uid)
        }
    }

    /**
     * Apply session A to [contextPlayer]: download skin → read UID → if session B exists on disk,
     * merge A onto B and compose from merged layers only (skin bitmap discarded); otherwise merge A
     * onto the downloaded skin as base. Saves a new session UID and encodes it on the result.
     * Steve/Alex follows the player unless A is UV-complete ([isComplete]).
     */
    fun finalizeSessionFromSessionData(
            requester: Player,
            session: SessionData,
            contextPlayer: Player = requester,
            craig: Boolean = false,
            recordStats: Boolean = true
    ): CompletableFuture<FinalizedResult> {
        val dummy =
                Mannequin(
                        location = contextPlayer.location,
                        selection = SkinSelection(emptyMap()),
                        slimModel = session.slimModel ?: false
                )
        return finalizeSession(
                requester = requester,
                man = dummy,
                sessionOverride = session,
                contextPlayer = contextPlayer,
                craig = craig,
                createNewUid = true,
                recordStats = recordStats
        )
    }

    private fun sessionToSelection(session: SessionData): SkinSelection {
        val selections =
                session.layers.mapValues { (layerId, data) ->
                    val option = layerManager.allOptions(layerId).find { it.id == data.option }
                    LayerSelection(
                            layerId = layerId,
                            option = option,
                            channelColors =
                                    data.channelColors
                                            .mapNotNull { (k, v) ->
                                                val idx = k.toIntOrNull() ?: return@mapNotNull null
                                                val color = hexToColor(v) ?: return@mapNotNull null
                                                idx to color
                                            }
                                            .toMap(),
                            texturedColors =
                                    data.texturedColors
                                            .mapNotNull outer@{ (k, v) ->
                                                val idx = k.toIntOrNull() ?: return@outer null
                                                val subMap =
                                                        v
                                                                .mapNotNull inner@{ (sk, sv) ->
                                                                    val sIdx =
                                                                            sk.toIntOrNull()
                                                                                    ?: return@inner null
                                                                    val color =
                                                                            hexToColor(sv)
                                                                                    ?: return@inner null
                                                                    sIdx to color
                                                                }
                                                                .toMap()
                                                idx to subMap
                                            }
                                            .toMap(),
                            selectedTexture = data.selectedTexture
                    )
                }
        return SkinSelection(selections)
    }

    fun downloadSkin(url: URL): CompletableFuture<BufferedImage> {
        return CompletableFuture.supplyAsync {
            try {
                ImageIO.read(url)
            } catch (e: Exception) {
                throw RuntimeException("Failed to download skin: ${e.message}", e)
            }
        }
    }


    private fun saveImage(image: BufferedImage, dir: File, name: String): CompletableFuture<File> {
        return CompletableFuture.supplyAsync {
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "$name.png")
            try {
                ImageIO.write(image, "png", file)
                file
            } catch (e: Exception) {
                throw RuntimeException("Failed to save image: ${e.message}", e)
            }
        }
    }

    fun listSessionUids(): List<String> {
        if (!sessionsDir.exists()) return emptyList()
        return sessionsDir.listFiles { f -> f.extension == "json" }?.map { it.nameWithoutExtension }
                ?: emptyList()
    }

    fun listTemplateNames(): List<String> {
        if (!templatesDir.exists()) return emptyList()
        return templatesDir.listFiles { f -> f.extension == "json" }?.map {
            it.nameWithoutExtension
        }
                ?: emptyList()
    }

    private fun generateUid(): String {
        val rng = ThreadLocalRandom.current()
        var uid: String
        do {
            uid = (1..UID_LENGTH).map { UID_CHARS[rng.nextInt(UID_CHARS.length)] }.joinToString("")
        } while (File(sessionsDir, "$uid.json").exists())
        return uid
    }

    fun snapshotLayers(mannequin: Mannequin): Map<String, LayerSessionData> {
        return mannequin.selection.selections.mapValues { (_, sel) ->
            LayerSessionData.fromSelection(sel)
        }
    }

    fun sessionFromMannequin(mannequin: Mannequin): SessionData {
        return SessionData(
                uid = "mannequin_${mannequin.id}",
                creator = "system",
                createdAt = Instant.now().toString(),
                slimModel = mannequin.slimModel,
                layers = snapshotLayers(mannequin)
        )
    }

    private fun fingerprint(slimModel: Boolean, layers: Map<String, LayerSessionData>): String {
        val sb = StringBuilder()
        sb.append("slim=").append(if (slimModel) "1" else "0").append('|')

        for (layerId in layers.keys.sorted()) {
            val layer = layers[layerId] ?: continue
            sb.append(layerId).append(':')
            sb.append(layer.option ?: "").append(':')
            sb.append(layer.selectedTexture ?: "").append('|')

            for (ch in layer.channelColors.keys.sortedWith(channelKeyComparator())) {
                sb.append("c").append(ch).append('=').append(layer.channelColors[ch]).append(';')
            }
            sb.append('|')

            for (ch in layer.texturedColors.keys.sortedWith(channelKeyComparator())) {
                sb.append("t").append(ch).append('=')
                val sub = layer.texturedColors[ch] ?: emptyMap()
                for (subKey in sub.keys.sortedWith(channelKeyComparator())) {
                    sb.append(subKey).append(':').append(sub[subKey]).append(',')
                }
                sb.append(';')
            }
            sb.append('|')
        }

        return sha256(sb.toString())
    }

    private fun channelKeyComparator(): Comparator<String> =
            compareBy<String> { it.toIntOrNull() ?: Int.MAX_VALUE }.thenBy { it }

    private fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun resolveSession(
            input: String,
            player: Player,
            mannequinManager: MannequinManager
    ): SessionData? {
        val lower = input.lowercase()
        if (lower == "nearest") {
            val man = mannequinManager.nearestMannequin(player.location, 5.0) ?: return null
            return sessionFromMannequin(man)
        }
        if (lower == "null") {
            return SessionData(
                    uid = "null",
                    creator = player.uniqueId.toString(),
                    createdAt = Instant.now().toString(),
                    slimModel = null,
                    layers = emptyMap()
            )
        }

        val normalized = input.trim()
        val isSession = File(sessionsDir, "$normalized.json").exists()
        val res = load(input) ?: return null

        if (isSession) {
            for ((layerId, data) in res.layers) {
                val optionId = data.option ?: continue
                val opt = layerManager.findPartById(layerId, optionId)
                if (opt != null && opt.owner != null && opt.owner != player.uniqueId) {
                    player.sendMessage(
                            TextUtility.convertToComponent(
                                    "<red>You do not own part '${opt.displayName}' in layer '$layerId'."
                            )
                    )
                    return null
                }
            }
        }

        return res
    }
}
