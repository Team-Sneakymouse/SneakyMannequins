"""
Generates sample texture maps (64x64) for SneakyMannequins.

Blend maps: RGB images where R/G/B weight sub-channel color contributions.
AO maps:    Grayscale where 128 = neutral (1.0x brightness). Darker = shadow, brighter = highlight.
Roughness:  Grayscale where 128 = neutral (1.0x saturation). Darker = desaturated, brighter = vivid.
"""

import os
import math
import random
from PIL import Image

OUT = "src/main/resources/textures"
SIZE = 64

os.makedirs(OUT, exist_ok=True)


# ── Blend maps ───────────────────────────────────────────────────────────

def blend_horizontal_stripes(stripe_height=4):
    """Alternating R/G horizontal stripes — two sub-channels in bands."""
    img = Image.new("RGB", (SIZE, SIZE))
    for y in range(SIZE):
        band = (y // stripe_height) % 2
        color = (255, 0, 0) if band == 0 else (0, 255, 0)
        for x in range(SIZE):
            img.putpixel((x, y), color)
    return img


def blend_vertical_stripes(stripe_width=4):
    """Alternating R/G vertical stripes."""
    img = Image.new("RGB", (SIZE, SIZE))
    for x in range(SIZE):
        band = (x // stripe_width) % 2
        color = (255, 0, 0) if band == 0 else (0, 255, 0)
        for y in range(SIZE):
            img.putpixel((x, y), color)
    return img


def blend_checkerboard(cell=8):
    """Checkerboard of R and G cells."""
    img = Image.new("RGB", (SIZE, SIZE))
    for y in range(SIZE):
        for x in range(SIZE):
            checker = ((x // cell) + (y // cell)) % 2
            img.putpixel((x, y), (255, 0, 0) if checker == 0 else (0, 255, 0))
    return img


def blend_gradient_horizontal(period=8):
    """Left-to-right R→G gradient that repeats every `period` pixels."""
    img = Image.new("RGB", (SIZE, SIZE))
    for x in range(SIZE):
        t = (x % period) / (period - 1)
        r = int(255 * (1 - t))
        g = int(255 * t)
        for y in range(SIZE):
            img.putpixel((x, y), (r, g, 0))
    return img


def blend_gradient_diagonal(period=8):
    """Top-left (R) to bottom-right (G) diagonal gradient, tiled every `period` pixels."""
    img = Image.new("RGB", (SIZE, SIZE))
    for y in range(SIZE):
        for x in range(SIZE):
            t = ((x + y) % (2 * period)) / (2 * period - 1)
            r = int(255 * (1 - t))
            g = int(255 * t)
            img.putpixel((x, y), (r, g, 0))
    return img


def blend_three_channel_stripes(stripe_height=4):
    """Cycling R/G/B horizontal stripes — three sub-channels."""
    img = Image.new("RGB", (SIZE, SIZE))
    colors = [(255, 0, 0), (0, 255, 0), (0, 0, 255)]
    for y in range(SIZE):
        color = colors[(y // stripe_height) % 3]
        for x in range(SIZE):
            img.putpixel((x, y), color)
    return img


# ── AO maps ──────────────────────────────────────────────────────────────

def ao_fabric_noise(seed=42):
    """Subtle random grain simulating fabric / wool texture."""
    rng = random.Random(seed)
    img = Image.new("L", (SIZE, SIZE))
    for y in range(SIZE):
        for x in range(SIZE):
            img.putpixel((x, y), 128 + rng.randint(-20, 20))
    return img


def ao_soft_vignette(cell=8):
    """Tiled radial darkening — each cell gets its own vignette for depth/curvature."""
    img = Image.new("L", (SIZE, SIZE))
    half = cell / 2
    max_dist = math.hypot(half, half)
    for y in range(SIZE):
        for x in range(SIZE):
            lx = (x % cell) - half + 0.5
            ly = (y % cell) - half + 0.5
            dist = math.hypot(lx, ly) / max_dist
            val = int(128 * (1 - 0.5 * dist * dist))
            img.putpixel((x, y), max(0, min(255, val)))
    return img


def ao_horizontal_folds(period=8):
    """Sinusoidal horizontal brightness variation — cloth fold effect."""
    img = Image.new("L", (SIZE, SIZE))
    for y in range(SIZE):
        v = int(128 + 30 * math.sin(2 * math.pi * y / period))
        for x in range(SIZE):
            img.putpixel((x, y), max(0, min(255, v)))
    return img


# ── Roughness maps ───────────────────────────────────────────────────────

def roughness_patchy(seed=99, cell=8):
    """Blocky patches of varying saturation — weathered / worn look."""
    rng = random.Random(seed)
    img = Image.new("L", (SIZE, SIZE))
    grid = {}
    for cy in range(SIZE // cell + 1):
        for cx in range(SIZE // cell + 1):
            grid[(cx, cy)] = 128 + rng.randint(-40, 40)
    for y in range(SIZE):
        for x in range(SIZE):
            img.putpixel((x, y), grid[(x // cell, y // cell)])
    return img


def roughness_grain(seed=77):
    """Fine per-pixel noise around neutral — subtle saturation variation."""
    rng = random.Random(seed)
    img = Image.new("L", (SIZE, SIZE))
    for y in range(SIZE):
        for x in range(SIZE):
            img.putpixel((x, y), 128 + rng.randint(-15, 15))
    return img


def roughness_vertical_gradient(period=8):
    """Top-to-bottom desaturated→vivid gradient, repeating every `period` pixels."""
    img = Image.new("L", (SIZE, SIZE))
    for y in range(SIZE):
        t = (y % period) / (period - 1)
        val = int(90 + (165 - 90) * t)
        for x in range(SIZE):
            img.putpixel((x, y), val)
    return img


# ── Generate all ─────────────────────────────────────────────────────────

textures = {
    "blend_stripes_h.png":   blend_horizontal_stripes(stripe_height=1),
    "blend_stripes_v.png":   blend_vertical_stripes(stripe_width=1),
    "blend_checkerboard.png": blend_checkerboard(cell=2),
    "blend_gradient.png":    blend_gradient_horizontal(),
    "blend_gradient_diag.png": blend_gradient_diagonal(),
    "blend_three_stripes.png": blend_three_channel_stripes(stripe_height=1),
    "ao_fabric.png":         ao_fabric_noise(),
    "ao_vignette.png":       ao_soft_vignette(),
    "ao_folds.png":          ao_horizontal_folds(),
    "roughness_patchy.png":  roughness_patchy(cell=2),
    "roughness_grain.png":   roughness_grain(),
    "roughness_gradient.png": roughness_vertical_gradient(),
}

for name, img in textures.items():
    path = os.path.join(OUT, name)
    img.save(path)
    print(f"  {path}")

print(f"\nGenerated {len(textures)} sample textures in {OUT}/")
