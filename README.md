# SneakyMannequins

A Paper 1.21.4 plugin that renders fully interactive mannequin previews built from per-pixel text displays. Players can walk up to a mannequin, browse a holographic HUD, and customise a live 3D skin preview assembled on the server from configurable layers. This plugin utilizes the [HoloUI](../HoloUI) library for high-performance interaction and UI management.

## Features

### Per-Pixel Skin Rendering
Each mannequin is drawn with individual `TextDisplay` entities — one per visible pixel — positioned and coloured to recreate all faces of the Minecraft player model. Only changed pixels are updated when selections change, keeping network traffic minimal.

### Layered Skin Composition
Skins are composed from stacked PNG layers (body, pants, shirt, hair, etc.) configured in `config.yml`. Each layer can have multiple part options (e.g. several hairstyles) and supports two independent colour channels derived automatically from the source artwork.

### Colour Masking & Tinting
When a layer PNG is loaded, the plugin analyses its pixels and splits them into two colour channels using configurable clustering strategies:
- **HSB** — k-means in hue/saturation/brightness (default, best all-rounder)
- **HUE** — largest hue gap (fast, works well when colours are clearly different hues)
- **RGB** — k-means in Euclidean RGB space

Each channel can be tinted independently from a named colour palette. Tinting preserves the original luminance and colour variance of the artwork, so shading detail is maintained.

### Holographic HUD (via HoloUI)
When a player approaches a mannequin, a virtual control panel spawns as packet-only `TextDisplay` entities. This HUD is managed by the **HoloUI** library, which provides zero-raycast click detection via virtual per-player `Interaction` entities.

**Buttons:**

| Left column | Right column |
|---|---|
| Model (classic / slim) | Layer |
| Pose | Part |
| Random *(placeholder)* | Channel |
| | Color |

A status line at the top shows the last action taken.

**Left-click** cycles forward; **right-click** cycles backward. The Part and Color buttons have an "active mode" — click once to activate, then click anywhere on the Interaction entity to cycle through options.

### Configurable HUD Appearance
Every button's text (with full [MiniMessage](https://docs.advntr.dev/minimessage/format.html) support), translation, line width, and background colours (default & highlighted) are configurable in `config.yml`. An optional `ItemDisplay` frame can be enabled as a backdrop art asset behind the buttons.

### Command Triggers
Server admins can attach console commands to mannequin events — hover, click, part-change (per-layer), and color-change. Placeholders like `{player}`, `{part}`, `{color}`, `{color_r}/{color_g}/{color_b}`, and `{x}/{y}/{z}` are substituted automatically. The defaults ship with themed sounds and coloured particle effects.

### Palettes
A rich set of built-in colour palettes: skin tones, hair, eyes, primary, pastel, neon, jewel, warm, cool, greyscale, metallic, fabric, and earth tones — all easily extended in config.

## Commands

| Command | Description |
|---|---|
| `/mannequin` | Spawn a mannequin at your feet |
| `/mannequin remove` | Remove the nearest mannequin |
| `/mannequin reload` | Reload config and re-render all mannequins |
| `/mannequin remask <HSB\|HUE\|RGB> <layer> <part>` | Re-run colour-channel masking for a specific part |

Permission node: `sneakymannequins.command.mannequin`

## Configuration

The full config lives at `plugins/SneakyMannequins/config.yml` (auto-generated from defaults on first run). Key sections:

- **`plugin`** — debug mode, default skin model, preprocessing/masking settings.
- **`triggers`** — console commands fired on hover, click, part-change, and color-change events.
- **`hud-buttons`** — per-button text (MiniMessage), translation, line-width, background colours.
- **`hud-frame`** — optional `ItemDisplay` backdrop (item, display context, translation, scale).
- **`layers.palettes`** — named colour palettes (hex RGB values).
- **`layers.order`** — rendering order (bottom to top).
- **`layers.definitions`** — per-layer display name, directory, colour masking toggle, and default palettes.

### Adding Content

Place 64×64 RGBA PNGs in `plugins/SneakyMannequins/layers/<Layer>/`. On load, the plugin will automatically generate `*_mask_1.png` and `*_mask_2.png` colour-channel files alongside each source image. For the body layer, if no PNGs exist, `default.png` and `default_slim.png` are seeded automatically.

## Development

This module is part of the [HoloUX Workspace](../) and depends on the `:HoloUI` library.

- **Build Library + Plugin:** `./gradlew build` (from workspace root)
- **Run Test Server:** `./gradlew runServer` (from workspace root)
- **Standalone Build:** `./gradlew build` (from this directory)
- **Entry point:** `com.sneakymannequins.SneakyMannequins`

## Architecture

```
SneakyMannequins (plugin submodule)
├── commands/         Command registration (Brigadier)
├── managers/
│   ├── MannequinManager   Lifecycle, HoloTrigger registration, triggers
│   ├── LayerManager        Layer/option/palette loading, colour masking
│   └── MannequinPersistence   Save/load mannequin locations
├── model/            Data classes (Mannequin, SkinSelection, LayerOption, …)
├── render/           PixelProjector (maps 2D skin pixels → 3D world positions)
└── util/             SkinComposer, TextUtility, …
```

Highly visual and interactive logic is delegated to the **HoloUI** library.

## Known Limitations

- Per-pixel text displays are network-heavy; batching and LOD optimisation may be added later.
- Only Paper 1.21.4 has a concrete NMS handler; other versions are non-functional.
- The **Random** button is a placeholder with no behaviour yet.
- Pose toggling is tracked but not yet visually applied to the mannequin model.
