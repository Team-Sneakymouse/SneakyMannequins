# Agent instructions (SneakyMannequins)

Team Sneakymouse / Lords of Minecraft plugin. Follow the shared conventions in EmptyPlugin's AGENTS.md.

## Repo-specific rules

- **Supported Minecraft version is hardcoded.** nms/VolatileHandlerRegistry.resolve() only maps '26.2' to VolatileHandler262. When Paper moves to a new Minecraft version, add a new nms/vXX handler and registry entry; never reuse the old handler blindly, and keep all net.minecraft usage inside nms/.
- **Async callbacks must not touch Bukkit.** Code in CompletableFuture.runAsync/supplyAsync (SessionManager.kt:336,555,823,834, LayerManager.kt:925) may only do file IO, image processing and updates to the ConcurrentHashMaps; hop back with Bukkit.getScheduler().runTask before spawning/removing entities, sending packets or opening GUIs.
- **Session JSON and UID format are persistent API.** SessionData (model/SessionData.kt) fields and the UID encoded into the composed skin are read from sessions/<uid>.json and from decoded skin textures (outfit items, /mannequin debug checkskin, PlaceholderAPI %sneakymannequins_skin_session_uid%). Only add optional fields with defaults; never rename or change UID encoding.
- **Images are served over HTTP.** Composed skins are written to images.storage-path and referenced as images.url-prefix + file name; both must be set on any non-local server. Don't hardcode localhost URLs in code or assume web/images exists; keep the 64-hex texture-hash regexp for textures.minecraft.net intact.
- **Layer definitions are data, tested manually.** Layers/parts/palettes are configured in layers/definitions.yml and config.yml, loaded by LayerManager. Add new parts by data, not code; pixel/UV changes (util/SkinUv.kt, SkinTransform.kt, SkinComposer.kt, PixelProjector.kt) need a render check with scripts/compare_skins.py or exhaustive_compare.py and a unit test beside BlinkEyeGeometryTest.
- **Custom events and command triggers are a public hook.** Keep MannequinEvents (MannequinEvent, MannequinFirst/Hover/Control/Submenu/SessionSave events) and the {player},{part},{color},{color_r/g/b},{x/y/z} placeholders of TriggerListener unchanged; other plugins and server config use them.
- **Never leave HUD or display entities behind.** Mannequin pixels and HUD are packet or display entities; spawn through MannequinManager/PixelRenderManager so shutdown() (called from onDisable) and holoController.shutdown() clean them up. Do not spawn raw entities outside those managers.
