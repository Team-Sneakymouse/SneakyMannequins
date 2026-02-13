# SneakyMannequins

A Paper 1.21.4 plugin that renders interactive mannequin previews built from per‑pixel text displays. Players can stand in a booth, click UI elements (TBD), and see a live 3D preview of a composed skin assembled on the server from configurable layers. No resource pack is required; all rendering is clientbound NMS packets (no spawned entities in the world).

## Current state
- Command: `/mannequin` spawns a mannequin at your feet for testing; renders for the caller.
- Rendering: per-pixel `TextDisplay` entities using NMS packets; all faces of the player model are populated, oriented correctly, and scaled to fill the space.
- Layers: configurable via `config.yml` (`layers.order` + `definitions`). PNGs in `plugins/SneakyMannequins/layers/<Layer>/`. Empty base layers are auto-seeded with `default.png`.
- Defaults: `plugins/SneakyMannequins/default.png` is copied from the bundled `src/main/resources/default.png` if missing.
- Debug: `plugin.debug: true` logs render info and writes `plugins/SneakyMannequins/debug-composed.png` showing the composed skin.
- Versioning: NMS handler implemented for 1.21.4; other versions fall back to a no-op handler.

## Goals
- Full “character creator” flow with layered options (hat, coat, sleeves, pants, etc.), per-option palettes, and color masking.
- In-booth UI with interactable entities to select layers/options/colors.
- Efficient diffs: only changed pixels respawn/update when selections change.
- Export: compose final PNG server-side and make it downloadable via the bundled dev web server.

## Configuration
`plugins/SneakyMannequins/config.yml` (generated from `src/main/resources/config.yml`):
- `layers.order`: rendering order (top to bottom).
- `layers.definitions.<id>.directory`: where to drop PNG options.
- `layers.palettes`: named color palettes; per-layer defaults and per-option overrides are supported.
- `plugin.debug`: enables verbose logging and debug image output.

Place 64x64 RGBA PNGs in `plugins/SneakyMannequins/layers/<Layer>/`. For the base layer, if no PNGs exist, `default.png` will be copied in automatically.

## Development
- Build: `./gradlew build`
- Run test server: `./gradlew runServer` (configured for 1.21.4)
- Main entry: `com.sneakymannequins.SneakyMannequins`
- Primary command: `/mannequin`

## Known limitations
- Per-pixel text displays are heavy; optimization (batching/quads) may be added later.
- Only 1.21.4 has a concrete NMS handler; other versions are disabled.
- UI/interaction flow is stubbed; only the test command is wired.

