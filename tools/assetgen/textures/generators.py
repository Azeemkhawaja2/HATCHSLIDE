"""Texture generators. Each returns a dict of maps (float32, 0..1): albedo (sRGB), normal, orm (AO, roughness, metallic).

Surfaces that tile (sand, rock, skin, water, foam, caustics) are built from wrapping noise so they
repeat seamlessly. Atlases (shells, props, FX) and turtle carapaces use local layouts instead.
"""
from __future__ import annotations

import numpy as np
from scipy.spatial import cKDTree

from .noise import (
    band_noise,
    blur_wrap,
    cavity_ao,
    fbm,
    hex_color,
    lerp_color,
    normal_from_height,
    palette_ramp,
    remap,
    smoothstep,
    warp,
    worley,
)


def _grid(size: int) -> tuple[np.ndarray, np.ndarray]:
    axis = (np.arange(size, dtype=np.float32) + 0.5) / size
    return np.meshgrid(axis, axis)


def _orm(ao: np.ndarray, rough: np.ndarray, metal: float = 0.0) -> np.ndarray:
    return np.stack([ao, np.clip(rough, 0.04, 1.0), np.full_like(ao, metal)], axis=-1).astype(np.float32)


# ---------------------------------------------------------------- sand

SAND_LOOKS = {
    # name: (colour stops low->high, sprinkle colours, ripple strength, coarse)
    "sand_golden": ([(0.0, 0xC79A5E), (0.45, 0xE3BE84), (0.8, 0xF2D6A2), (1.0, 0xFBE9C6)], [0xFFF4EA, 0xF6B8A6, 0x8B7B6B], 1.0, False),
    "sand_coral": ([(0.0, 0xD8CBB8), (0.45, 0xEFE6D7), (0.8, 0xFAF5EC), (1.0, 0xFFFFFF)], [0xFF9E8F, 0xFFD3C7, 0xC8BFB2], 0.8, False),
    "sand_silver": ([(0.0, 0x8E8C93), (0.45, 0xB4B3B9), (0.8, 0xCFCFD6), (1.0, 0xE9E9F0)], [0xF4F6FF, 0xA9C8F0, 0x6E6C75], 0.9, False),
    "sand_amber": ([(0.0, 0xA97E4C), (0.45, 0xCCA06B), (0.8, 0xE2BC86), (1.0, 0xF2D3A2)], [0xE8D2B8, 0x6E6258, 0x9A8571], 0.7, True),
    "sand_volcanic": ([(0.0, 0x161416), (0.45, 0x2B282B), (0.8, 0x403B3E), (1.0, 0x5A5357)], [0x6E7F3F, 0x8C8186, 0x2E2A2C], 0.45, True),
}


def sand_albedo(size: int, seed: int, look: str) -> dict:
    stops, sprinkles, ripple_strength, coarse = SAND_LOOKS[look]
    x, y = _grid(size)
    warp_noise = fbm(size, seed, base=2, octaves=4)
    patches = fbm(size, seed + 11, base=3, octaves=4)
    phase = (x * 12.0 + y * 3.0 + 0.32 * warp_noise) * 2.0 * np.pi
    ripples = 0.5 + 0.5 * np.sin(phase + 0.7 * np.sin(phase))
    ripples *= smoothstep(-1.0, 1.0, patches) * ripple_strength
    medium = remap(fbm(size, seed + 21, base=5, octaves=6))
    grains = band_noise(size, size / 10.0, size / 2.2, seed + 31)
    height = 0.42 * ripples + 0.4 * medium + 0.04 * grains
    tone = remap(0.55 * medium + 0.35 * ripples + 0.08 * remap(grains), 0.05, 0.95)
    albedo = palette_ramp(tone, stops)
    albedo *= (1.0 + 0.07 * np.clip(grains, -2, 2))[..., None]
    # Sprinkles: shell fragments, pebbles, coloured grains.
    f1, f2, ids = worley(size, 2400 if not coarse else 3600, seed + 41)
    rng = np.random.default_rng(seed + 43)
    kind = rng.random(ids.max() + 1)
    radius = rng.uniform(0.0016, 0.0042 if not coarse else 0.006, ids.max() + 1)
    spot = smoothstep(1.0, 0.6, f1 / radius[ids])
    for i, color in enumerate(sprinkles):
        lo = 0.0 + i * 0.05
        mask = spot * ((kind[ids] >= lo) & (kind[ids] < lo + (0.05 if i < 2 else 0.04)))
        albedo = albedo * (1 - mask[..., None]) + hex_color(color)[None, None, :] * mask[..., None]
        height = height + mask * 0.25
    rough = 0.9 + 0.05 * np.clip(grains, -1, 1) - spot * 0.25 * (kind[ids] < 0.1)
    if look == "sand_volcanic":
        # Glints of volcanic glass.
        glint = (band_noise(size, size / 4, size / 2, seed + 51) > 2.6).astype(np.float32)
        albedo += glint[..., None] * 0.35
        rough -= glint * 0.5
    ao = cavity_ao(height, 6.0, 4.0)
    albedo *= (0.85 + 0.15 * ao)[..., None]
    return {"albedo": np.clip(albedo, 0, 1), "height": height, "rough": rough, "ao": ao}


def sand_detail(size: int, seed: int, coarse: bool) -> dict:
    """Shared sand normal and ORM maps (fine for beaches, coarse for rock-pool and volcanic sand)."""
    base = sand_albedo(size, seed, "sand_amber" if coarse else "sand_golden")
    normal = normal_from_height(base["height"], 3.2 if coarse else 2.4)
    return {"normal": normal, "orm": _orm(base["ao"], base["rough"])}


# ---------------------------------------------------------------- rock


ROCK_KINDS = ("granite", "basalt", "limestone", "slate", "sandstone")


def rock(size: int, seed: int, kind: str) -> dict:
    """Cliff and boulder rock, one look per world (spec section 8: five world sets).

    granite: warm grey with quartz specks and lichen (Sunrise Cove, the title beach); basalt: black
    and vesicular with rust (Volcanic Isle); limestone: pale, porous coral rock with fossil-shell
    flecks (Coral Coast); slate: blue-grey, finely cleaved, with mica glints (Moonlit Bay);
    sandstone: red ochre in wavy cross-bedded layers (Crab Reef).
    """
    assert kind in ROCK_KINDS, kind
    x, y = _grid(size)
    f1, f2, ids = worley(size, 34, seed)
    plates = smoothstep(0.0, 0.02, f2 - f1)
    small1, small2, _ = worley(size, 260, seed + 3)
    chips = smoothstep(0.0, 0.01, small2 - small1)
    big = remap(fbm(size, seed + 5, base=2, octaves=7))
    warp = 0.18 * fbm(size, seed + 7, base=2, octaves=3)
    strata = 0.5 + 0.5 * np.sin((y * 7.0 + warp) * 2 * np.pi)
    rough = 0.82 + 0.1 * remap(band_noise(size, 40, 200, seed + 19))
    if kind == "basalt":
        height = 0.45 * big + 0.25 * plates + 0.12 * chips
        tone = remap(0.6 * big + 0.15 * chips)
        albedo = palette_ramp(tone, [(0.0, 0x262326), (0.5, 0x3C383C), (1.0, 0x5E585C)])
        ves1, _, vid = worley(size, 5200, seed + 9)
        vrad = np.random.default_rng(seed + 10).uniform(0.001, 0.0035, vid.max() + 1)
        holes = smoothstep(1.0, 0.7, ves1 / vrad[vid])
        height -= holes * 0.3
        albedo *= (1 - 0.6 * holes)[..., None]
        rust = smoothstep(0.6, 1.4, fbm(size, seed + 13, base=4, octaves=4))
        albedo = albedo * (1 - 0.35 * rust[..., None]) + hex_color(0x7A3B26)[None, None, :] * (0.35 * rust[..., None])
    elif kind == "limestone":
        # Soft, rounded blocks riddled with small solution pits and the odd fossil shell.
        height = 0.5 * big + 0.15 * plates + 0.08 * chips + 0.06 * strata
        tone = remap(0.55 * big + 0.15 * strata + 0.1 * chips + 0.2 * remap(band_noise(size, 6, 40, seed + 21)))
        albedo = palette_ramp(tone, [(0.0, 0x9C917C), (0.35, 0xC4B9A1), (0.7, 0xE2D8C2), (1.0, 0xF3ECDC)])
        pit1, _, pid = worley(size, 2600, seed + 23)
        prad = np.random.default_rng(seed + 24).uniform(0.0015, 0.006, pid.max() + 1)
        pits = smoothstep(1.0, 0.55, pit1 / prad[pid])
        height -= pits * 0.25
        albedo *= (1 - 0.35 * pits)[..., None]
        fos1, _, fid = worley(size, 90, seed + 25)
        keep = np.random.default_rng(seed + 26).random(fid.max() + 1) < 0.35
        ring = np.abs(np.sin(fos1 * size * 0.9)) * smoothstep(0.012, 0.004, fos1) * keep[fid]
        albedo = albedo * (1 - 0.25 * ring[..., None]) + ring[..., None] * 0.25 * hex_color(0xFFF7E8)[None, None, :]
        height += 0.05 * ring
        algae = smoothstep(1.2, 1.8, fbm(size, seed + 27, base=5, octaves=4)) * smoothstep(0.5, 0.2, big)
        albedo = albedo * (1 - 0.3 * algae[..., None]) + algae[..., None] * 0.3 * hex_color(0x7FA88A)[None, None, :]
    elif kind == "slate":
        # Thin cleavage planes, sharp breaks and flecks of mica that catch the moonlight.
        cleave = 0.5 + 0.5 * np.sin((y * 23.0 + 0.6 * warp + 0.04 * np.sin(x * 2 * np.pi * 3)) * 2 * np.pi)
        steps = np.floor(cleave * 4.0) / 4.0
        height = 0.35 * big + 0.3 * plates + 0.2 * steps + 0.08 * chips
        tone = remap(0.5 * big + 0.3 * steps + 0.2 * remap(band_noise(size, 4, 30, seed + 31)))
        albedo = palette_ramp(tone, [(0.0, 0x262A31), (0.4, 0x3A414B), (0.75, 0x55606D), (1.0, 0x76808C)])
        mica = (band_noise(size, size / 6, size / 2, seed + 33) > 2.6).astype(np.float32)
        albedo = albedo * (1 - 0.4 * mica[..., None]) + mica[..., None] * 0.4 * hex_color(0xC9D3DE)[None, None, :]
        rough = rough - 0.35 * mica
        stain = smoothstep(0.9, 1.5, fbm(size, seed + 35, base=3, octaves=4))
        albedo = albedo * (1 - 0.25 * stain[..., None]) + stain[..., None] * 0.25 * hex_color(0x5C4E45)[None, None, :]
    elif kind == "sandstone":
        # Wavy cross-bedding, rounded weathering and darker iron bands.
        bed = 0.5 + 0.5 * np.sin((y * 11.0 + 0.9 * warp + 0.08 * np.sin(x * 2 * np.pi * 2)) * 2 * np.pi)
        # Whole periods in x and y so the texture still tiles.
        cross = 0.5 + 0.5 * np.sin((y * 19.0 + x * 7.0 + warp) * 2 * np.pi)
        height = 0.5 * big + 0.12 * plates + 0.15 * bed + 0.06 * cross
        tone = remap(0.45 * big + 0.35 * bed + 0.1 * cross + 0.1 * chips)
        albedo = palette_ramp(tone, [(0.0, 0x6E3820), (0.3, 0x9A5634), (0.65, 0xC07E55), (1.0, 0xDDA982)])
        iron = smoothstep(0.82, 0.95, bed) * smoothstep(0.4, 0.8, remap(fbm(size, seed + 41, base=3, octaves=3)))
        albedo = albedo * (1 - 0.35 * iron[..., None]) + iron[..., None] * 0.35 * hex_color(0x4E2414)[None, None, :]
        dust = smoothstep(0.55, 0.9, big)
        albedo = albedo * (1 - 0.2 * dust[..., None]) + dust[..., None] * 0.2 * hex_color(0xE8C9A6)[None, None, :]
    else:
        height = 0.45 * big + 0.25 * plates + 0.12 * chips + 0.12 * strata
        tone = remap(0.6 * big + 0.25 * strata + 0.15 * chips)
        albedo = palette_ramp(tone, [(0.0, 0x6E6156), (0.35, 0x8F7F70), (0.7, 0xB3A08B), (1.0, 0xCFBEA6)])
        speck = (band_noise(size, size / 5, size / 2, seed + 15) > 2.2).astype(np.float32)
        albedo = albedo * (1 - 0.3 * speck[..., None]) + 0.3 * speck[..., None] * hex_color(0xEDE5D8)[None, None, :]
        lichen = smoothstep(1.1, 1.7, fbm(size, seed + 17, base=6, octaves=4)) * smoothstep(0.4, 0.8, big)
        albedo = albedo * (1 - 0.5 * lichen[..., None]) + lichen[..., None] * 0.5 * hex_color(0x9AA35E)[None, None, :]
    ao = cavity_ao(height, 5.0, 3.0) * (0.55 + 0.45 * plates)
    albedo *= (0.6 + 0.4 * ao)[..., None]
    strength = {"slate": 5.0, "limestone": 3.4, "sandstone": 3.6}.get(kind, 4.0)
    return {"albedo": np.clip(albedo, 0, 1), "normal": normal_from_height(height, strength), "orm": _orm(ao, np.clip(rough, 0.3, 1.0))}


# ---------------------------------------------------------------- shells (2x2 atlas)


def _cell_coords(n: int):
    axis = (np.arange(n, dtype=np.float32) + 0.5) / n
    return np.meshgrid(axis, axis)


def shell_atlas(size: int, seed: int) -> dict:
    half = size // 2
    albedo = np.zeros((size, size, 3), np.float32)
    height = np.zeros((size, size), np.float32)
    rough = np.zeros((size, size), np.float32)
    u, v = _cell_coords(half)
    rng = np.random.default_rng(seed)
    local_noise = remap(fbm(half, seed + 1, base=3, octaves=5))

    # Scallop: radial ribs from the hinge (bottom centre) and concentric growth lines.
    dx, dy = u - 0.5, 1.0 - v
    theta = np.arctan2(dx, dy)
    r = np.sqrt(dx * dx + dy * dy)
    ribs = 0.5 + 0.5 * np.cos(theta * 26.0)
    growth = 0.5 + 0.5 * np.sin(r * 90.0 + local_noise * 3.0)
    h = 0.7 * ribs + 0.2 * growth
    band = 0.5 + 0.5 * np.sin(r * 14.0 + local_noise * 2.0)
    col = palette_ramp(band, [(0.0, 0xF28C6B), (0.5, 0xF9C29A), (1.0, 0xFFF1E0)])
    col *= (0.85 + 0.15 * ribs)[..., None]
    albedo[:half, :half], height[:half, :half], rough[:half, :half] = col, h, 0.42 - 0.1 * ribs

    # Conch: spiral bands and speckles.
    spiral = 0.5 + 0.5 * np.sin((u * 3.0 + v * 9.0 + local_noise * 0.6) * 2 * np.pi)
    spots_f1, _, spot_id = worley(half, 900, seed + 3)
    spots = smoothstep(0.012, 0.004, spots_f1) * (rng.random(spot_id.max() + 1)[spot_id] < 0.5)
    col = palette_ramp(spiral, [(0.0, 0xE9A07E), (0.5, 0xF6D2B4), (1.0, 0xFFF6EA)])
    col = col * (1 - 0.6 * spots[..., None]) + 0.6 * spots[..., None] * hex_color(0x8A4E36)[None, None, :]
    albedo[:half, half:], height[:half, half:], rough[:half, half:] = col, 0.3 * spiral + 0.1 * local_noise, 0.38

    # Cowrie: glossy, leopard spots with white dots.
    c1, _, cid = worley(half, 420, seed + 5)
    leopard = smoothstep(0.03, 0.015, c1)
    dots_f1, _, _ = worley(half, 1600, seed + 7)
    dots = smoothstep(0.006, 0.002, dots_f1)
    col = palette_ramp(remap(local_noise), [(0.0, 0xC79A72), (1.0, 0xF2DEC4)])
    col = col * (1 - 0.7 * leopard[..., None]) + 0.7 * leopard[..., None] * hex_color(0x6B3F2A)[None, None, :]
    col = col * (1 - dots[..., None]) + dots[..., None] * hex_color(0xFFFBF2)[None, None, :]
    albedo[half:, :half], height[half:, :half], rough[half:, :half] = col, 0.08 * local_noise, 0.18

    # Cracked shell: pale cowrie with dark crack network.
    k1, k2, _ = worley(half, 28, seed + 9)
    cracks = smoothstep(0.006, 0.0, k2 - k1)
    fine1, fine2, _ = worley(half, 140, seed + 11)
    fine = smoothstep(0.003, 0.0, fine2 - fine1) * smoothstep(0.08, 0.02, k2 - k1)
    crack = np.maximum(cracks, fine * 0.7)
    col = palette_ramp(remap(local_noise), [(0.0, 0xD8C6AE), (1.0, 0xF5E9D6)])
    col *= (1 - 0.75 * crack)[..., None]
    albedo[half:, half:], height[half:, half:], rough[half:, half:] = col, 0.15 * local_noise - 0.4 * crack, 0.35 + 0.4 * crack

    ao = np.clip(1.0 - (blur_wrap(height, 4.0) - height) * 3.0, 0.4, 1.0)
    return {"albedo": np.clip(albedo, 0, 1), "normal": normal_from_height(height, 3.0), "orm": _orm(ao, rough)}


# ---------------------------------------------------------------- turtles

# Species: (scute colour stops, seam colour, pattern style, costal pairs, vertebrals)
SPECIES = {
    "green": ([(0.0, 0x4F5A33), (0.6, 0x6E7A45), (1.0, 0x9C8F57)], 0x2A2B1C, "streaks", 4, 5),
    "loggerhead": ([(0.0, 0x7A4524), (0.6, 0x9C5A2E), (1.0, 0xC27C43)], 0x3B2214, "plain", 5, 5),
    "hawksbill": ([(0.0, 0x5B3214), (0.45, 0xA8651F), (1.0, 0xE3A646)], 0x2C170B, "tortoiseshell", 4, 5),
    "leatherback": ([(0.0, 0x1E2227), (0.6, 0x2E343B), (1.0, 0x434A52)], 0x15181C, "leather", 0, 0),
    "kemps_ridley": ([(0.0, 0x5C5F55), (0.6, 0x7B7E70), (1.0, 0x9C9E8C)], 0x33352E, "plain", 5, 5),
    "olive_ridley": ([(0.0, 0x4D5530), (0.6, 0x6A7440), (1.0, 0x8C9356)], 0x2B301B, "plain", 7, 6),
    "flatback": ([(0.0, 0x8A8970), (0.6, 0xA8A68A), (1.0, 0xC9C6AA)], 0x55543F, "soft", 4, 5),
}


def _scute_seeds(costals: int, vertebrals: int) -> np.ndarray:
    pts = []
    for i in range(vertebrals):
        pts.append((0.0, -0.66 + 1.32 * i / max(vertebrals - 1, 1)))
    for i in range(costals):
        y = -0.5 + 1.0 * i / max(costals - 1, 1)
        pts.append((-0.5, y))
        pts.append((0.5, y))
    marginals = 26
    for i in range(marginals):
        a = 2 * np.pi * (i + 0.5) / marginals
        pts.append((0.92 * np.sin(a), 0.92 * np.cos(a)))
    return np.array(pts, np.float32)


def turtle_carapace(size: int, seed: int, species: str) -> dict:
    """Carapace albedo in planar shell space: u across, v from head (0) to tail (1)."""
    stops, seam_color, style, costals, vertebrals = SPECIES[species]
    u, v = _grid(size)
    ex, ey = u * 2 - 1, v * 2 - 1
    r = np.sqrt(ex * ex + ey * ey)
    noise = remap(fbm(size, seed, base=4, octaves=6))
    fine = band_noise(size, 80, 400, seed + 1)
    if style == "leather":
        ridges = np.zeros_like(u)
        for rx in (-0.62, -0.32, 0.0, 0.32, 0.62):
            ridges = np.maximum(ridges, smoothstep(0.035, 0.0, np.abs(ex - rx * (1 - 0.3 * ey * ey))))
        speck1, _, sid = worley(size, 2600, seed + 3)
        speck = smoothstep(0.006, 0.002, speck1) * (np.random.default_rng(seed).random(sid.max() + 1)[sid] < 0.45)
        tone = remap(0.7 * noise + 0.3 * ridges)
        albedo = palette_ramp(tone, stops)
        albedo = albedo * (1 - speck[..., None] * 0.8) + speck[..., None] * 0.8 * hex_color(0xD9DEE2)[None, None, :]
        albedo += ridges[..., None] * 0.08
        seam = ridges * 0.0
    else:
        seeds = _scute_seeds(costals, vertebrals)
        tree = cKDTree(seeds)
        q = np.stack([ex.ravel(), ey.ravel()], axis=1)
        dist, idx = tree.query(q, k=2, workers=-1)
        f1 = dist[:, 0].reshape(size, size)
        f2 = dist[:, 1].reshape(size, size)
        nearest = idx[:, 0].reshape(size, size)
        softness = 0.02 if style == "soft" else 0.012
        seam = smoothstep(softness, 0.0, f2 - f1)
        rng = np.random.default_rng(seed + 5)
        per_scute = rng.uniform(-0.12, 0.12, len(seeds))[nearest]
        rings = 0.5 + 0.5 * np.sin(f1 * 70.0)
        tone = np.clip(0.55 + per_scute + 0.25 * (noise - 0.5) - 0.15 * rings * 0.4, 0, 1)
        if style == "streaks" or style == "tortoiseshell":
            centre = seeds[nearest]
            angle = np.arctan2(ex - centre[..., 0], ey - centre[..., 1])
            rays = 0.5 + 0.5 * np.sin(angle * (9 if style == "streaks" else 6) + noise * 5.0)
            tone = np.clip(tone + (rays - 0.5) * (0.35 if style == "tortoiseshell" else 0.22), 0, 1)
        albedo = palette_ramp(tone, stops)
        albedo = albedo * (1 - 0.85 * seam[..., None]) + 0.85 * seam[..., None] * hex_color(seam_color)[None, None, :]
    albedo *= (1.0 + 0.04 * np.clip(fine, -2, 2))[..., None]
    # Outside the shell outline: belly-skin tone for safe filtering at the rim.
    outside = smoothstep(0.98, 1.02, r)
    albedo = albedo * (1 - outside[..., None]) + outside[..., None] * hex_color(0xB9B49A)[None, None, :]
    return {"albedo": np.clip(albedo, 0, 1)}


def turtle_detail(size: int, seed: int) -> dict:
    """Shared carapace micro-detail: keratin growth ridges and pores (normal) plus AO/roughness."""
    ridges = band_noise(size, 40, 160, seed)
    pores = band_noise(size, size / 6, size / 2.5, seed + 1)
    dome = 0.0
    height = 0.6 * remap(ridges) + 0.15 * remap(pores) + dome
    normal = normal_from_height(height, 1.6)
    ao = cavity_ao(height, 6.0, 1.5)
    rough = 0.48 + 0.12 * remap(pores)
    return {"normal": normal, "orm": _orm(ao, rough)}


def turtle_skin(size: int, seed: int) -> dict:
    """Shared scaly skin for head and flippers; tinted per species by the turtle material."""
    f1, f2, ids = worley(size, 2200, seed)
    scale = smoothstep(0.0, 0.012, f2 - f1)
    big1, big2, bid = worley(size, 260, seed + 1)
    plates = smoothstep(0.0, 0.02, big2 - big1)
    height = 0.6 * plates + 0.35 * scale + 0.1 * remap(fbm(size, seed + 2, base=6, octaves=4))
    rng = np.random.default_rng(seed + 3)
    tone = np.clip(0.5 + rng.uniform(-0.18, 0.18, bid.max() + 1)[bid] + 0.2 * (scale - 0.5), 0, 1)
    albedo = palette_ramp(tone, [(0.0, 0x8E9A86), (0.5, 0xB7BFA8), (1.0, 0xDCE0CC)])
    albedo *= (0.7 + 0.3 * plates)[..., None]
    ao = cavity_ao(height, 3.0, 3.0)
    return {"albedo": np.clip(albedo, 0, 1), "normal": normal_from_height(height, 3.0), "orm": _orm(ao, 0.55 + 0.15 * (1 - scale))}


# ---------------------------------------------------------------- water and foam


def water_normal(size: int, seed: int, fine: bool) -> dict:
    """Tileable wave normals from a directional, Phillips-like spectrum."""
    rng = np.random.default_rng(seed)
    f = np.fft.fftfreq(size) * size
    kx, ky = np.meshgrid(f, f)
    k = np.sqrt(kx * kx + ky * ky)
    peak = 18.0 if not fine else 60.0
    with np.errstate(divide="ignore", invalid="ignore"):
        spectrum = np.exp(-((np.log(np.maximum(k, 1e-6) / peak)) ** 2) / 0.6) / np.maximum(k, 1.0) ** 1.6
        direction = np.where(k > 0, (kx / np.maximum(k, 1e-6)) ** 2, 0.0)
    amp = np.sqrt(spectrum * (0.35 + 0.65 * direction))
    field = np.real(np.fft.ifft2(amp * np.exp(1j * rng.uniform(0, 2 * np.pi, (size, size)))))
    field = (field - field.mean()) / field.std()
    return {"normal": normal_from_height(field.astype(np.float32), 0.9 if not fine else 0.6)}


def foam(size: int, seed: int) -> dict:
    f1, f2, ids = worley(size, 900, seed)
    lace = smoothstep(0.0, 0.025, f2 - f1)
    b1, _, bid = worley(size, 6000, seed + 1)
    rng = np.random.default_rng(seed + 2)
    br = rng.uniform(0.002, 0.006, bid.max() + 1)[bid]
    bubbles = smoothstep(1.0, 0.75, b1 / br) - smoothstep(0.6, 0.3, b1 / br) * 0.6
    mass = remap(fbm(size, seed + 3, base=3, octaves=5))
    value = np.clip((1 - lace) * 0.8 * mass + bubbles * 0.6 + mass * 0.25, 0, 1)
    rgb = np.repeat(value[..., None], 3, axis=-1)
    return {"albedo": rgb}


def caustics(size: int, seed: int) -> dict:
    n1 = fbm(size, seed, base=3, octaves=3)
    n2 = fbm(size, seed + 1, base=3, octaves=3)
    a = warp(fbm(size, seed + 2, base=4, octaves=3), n1, n2, 18.0)
    lines = np.exp(-np.abs(np.sin(a * 3.0)) * 6.0)
    value = remap(lines) ** 1.4
    return {"albedo": np.repeat(value[..., None], 3, axis=-1)}


# ---------------------------------------------------------------- atlases


PROP_CELLS = [
    "coconut_husk", "coconut_shell", "crab_shell", "egg_shell",
    "palm_bark", "palm_frond", "driftwood", "umbrella_coral",
    "umbrella_teal", "lantern_paper", "coral_pink", "coral_orange",
    "starfish", "gull_feathers", "pebbles", "wood_plank",
]


def _prop_cell(name: str, n: int, seed: int) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    u, v = _cell_coords(n)
    noise = remap(fbm(n, seed, base=3, octaves=5))
    fine = band_noise(n, 30, 160, seed + 1)
    if name == "coconut_husk":
        fibres = 0.5 + 0.5 * np.sin((u * 40 + 0.4 * noise) * 2 * np.pi)
        col = palette_ramp(remap(0.6 * fibres + 0.4 * noise), [(0, 0x4A2E1A), (0.6, 0x7A5133), (1, 0xA1774F)])
        return col, 0.5 * fibres + 0.3 * noise, 0.9 + 0 * u
    if name == "coconut_shell":
        col = palette_ramp(noise, [(0, 0x5B3A22), (1, 0x8A6141)])
        return col, noise * 0.4, 0.7 + 0 * u
    if name == "crab_shell":
        s1, _, sid = worley(n, 160, seed + 2)
        spots = smoothstep(0.03, 0.012, s1) * (np.random.default_rng(seed).random(sid.max() + 1)[sid] < 0.5)
        col = palette_ramp(noise, [(0, 0xC2401F), (0.6, 0xE8623A), (1, 0xF58B55)])
        col = col * (1 - 0.5 * spots[..., None]) + 0.5 * spots[..., None] * hex_color(0xFFC9A6)[None, None, :]
        return col, 0.4 * noise + 0.3 * spots, 0.45 + 0 * u
    if name == "egg_shell":
        s1, _, sid = worley(n, 700, seed + 3)
        speck = smoothstep(0.008, 0.002, s1) * (np.random.default_rng(seed + 1).random(sid.max() + 1)[sid] < 0.4)
        col = palette_ramp(noise, [(0, 0xEDE3CF), (1, 0xFFFAEF)])
        col = col * (1 - 0.5 * speck[..., None]) + 0.5 * speck[..., None] * hex_color(0xC9B08D)[None, None, :]
        return col, 0.2 * noise + 0.1 * fine, 0.5 + 0 * u
    if name == "palm_bark":
        rings = 0.5 + 0.5 * np.sin((v * 24 + 0.2 * noise) * 2 * np.pi)
        col = palette_ramp(remap(0.6 * rings + 0.4 * noise), [(0, 0x5E4632), (0.6, 0x8C6D4E), (1, 0xB49270)])
        return col, rings * 0.6 + noise * 0.2, 0.92 + 0 * u
    if name == "palm_frond":
        vein = smoothstep(0.03, 0.0, np.abs(u - 0.5)) + 0.5 * smoothstep(0.02, 0.0, np.abs(((u - 0.5) * 2 + v * 0.5) % 0.12 - 0.06))
        col = palette_ramp(remap(noise + 0.4 * v), [(0, 0x2F6A2A), (0.5, 0x4E9A3B), (1, 0x8CC25A)])
        col = col * (1 - 0.3 * vein[..., None]) + 0.3 * vein[..., None] * hex_color(0xC9E08A)[None, None, :]
        return col, vein * 0.4 + noise * 0.1, 0.6 + 0 * u
    if name == "driftwood":
        grain = 0.5 + 0.5 * np.sin((u * 28 + 1.2 * noise + 0.3 * fine) * 2 * np.pi)
        col = palette_ramp(remap(0.5 * grain + 0.5 * noise), [(0, 0x7E7468), (0.6, 0xA79C8E), (1, 0xD2C8B8)])
        return col, grain * 0.5, 0.9 + 0 * u
    if name in ("umbrella_coral", "umbrella_teal"):
        stripe = (np.floor(u * 8) % 2).astype(np.float32)
        a, b = (0xFF7F6B, 0xFFF3DC) if name == "umbrella_coral" else (0x3FB8A8, 0xFFF3DC)
        col = lerp_color(hex_color(a), hex_color(b), stripe) * (0.92 + 0.08 * noise)[..., None]
        return col, 0.05 * fine, 0.75 + 0 * u
    if name == "lantern_paper":
        ribs = smoothstep(0.02, 0.0, np.abs((v * 8) % 1.0 - 0.5) - 0.48)
        col = palette_ramp(noise, [(0, 0xF08A3E), (1, 0xFFC27A)]) * (1 - 0.3 * ribs[..., None])
        return col, ribs * 0.3, 0.8 + 0 * u
    if name in ("coral_pink", "coral_orange"):
        b1, _, _ = worley(n, 500, seed + 4)
        bumps = smoothstep(0.03, 0.0, b1)
        stops = [(0, 0xD9567A), (1, 0xFFA3B8)] if name == "coral_pink" else [(0, 0xE0662E), (1, 0xFFB15E)]
        col = palette_ramp(remap(0.6 * noise + 0.4 * bumps), stops)
        return col, bumps * 0.6 + noise * 0.2, 0.7 + 0 * u
    if name == "starfish":
        b1, _, _ = worley(n, 900, seed + 5)
        bumps = smoothstep(0.02, 0.0, b1)
        col = palette_ramp(remap(noise), [(0, 0xE06A2B), (1, 0xFF9B4F)])
        col = col * (1 - 0.4 * bumps[..., None]) + 0.4 * bumps[..., None] * hex_color(0xFFE2B8)[None, None, :]
        return col, bumps * 0.7, 0.7 + 0 * u
    if name == "gull_feathers":
        barbs = 0.5 + 0.5 * np.sin((u * 60 + v * 10 + 0.3 * noise) * 2 * np.pi)
        grey = smoothstep(0.55, 0.75, v)
        col = lerp_color(hex_color(0xF7F7F4), hex_color(0x9AA3AD), grey)
        tip = smoothstep(0.9, 0.97, v)
        col = col * (1 - tip[..., None]) + tip[..., None] * hex_color(0x2A2D33)[None, None, :]
        col *= (0.94 + 0.06 * barbs)[..., None]
        return col, barbs * 0.15, 0.85 + 0 * u
    if name == "pebbles":
        p1, p2, pid = worley(n, 70, seed + 6)
        pebble = smoothstep(0.0, 0.03, p2 - p1)
        tone = np.random.default_rng(seed + 7).random(pid.max() + 1)[pid]
        col = palette_ramp(np.clip(0.5 * tone + 0.3 * noise, 0, 1), [(0, 0x6D6A66), (0.5, 0xA39E96), (1, 0xD9D2C6)])
        col *= (0.65 + 0.35 * pebble)[..., None]
        return col, pebble * 0.8, 0.6 + 0 * u
    # wood_plank
    grain = 0.5 + 0.5 * np.sin((v * 30 + 0.8 * noise) * 2 * np.pi)
    col = palette_ramp(remap(0.5 * grain + 0.5 * noise), [(0, 0x7A5536), (1, 0xB5865A)])
    return col, grain * 0.4, 0.75 + 0 * u


def props_atlas(size: int, seed: int) -> dict:
    n = size // 4
    albedo = np.zeros((size, size, 3), np.float32)
    height = np.zeros((size, size), np.float32)
    rough = np.zeros((size, size), np.float32)
    for i, name in enumerate(PROP_CELLS):
        r, c = divmod(i, 4)
        col, h, ro = _prop_cell(name, n, seed + i * 101)
        albedo[r * n:(r + 1) * n, c * n:(c + 1) * n] = col
        height[r * n:(r + 1) * n, c * n:(c + 1) * n] = h
        rough[r * n:(r + 1) * n, c * n:(c + 1) * n] = ro
    ao = np.clip(1.0 - (blur_wrap(height, 3.0) - height) * 2.0, 0.45, 1.0)
    return {"albedo": np.clip(albedo, 0, 1), "normal": normal_from_height(height, 2.5), "orm": _orm(ao, rough)}


FX_CELLS = [
    "sand_puff", "droplet", "spray", "sparkle",
    "confetti_pink", "confetti_teal", "confetti_yellow", "glow",
    "ash", "footprint", "ripple", "soft_circle",
    "bubble", "gull", "star", "glint",
]


def _fx_cell(name: str, n: int, seed: int) -> np.ndarray:
    u, v = _cell_coords(n)
    dx, dy = u - 0.5, v - 0.5
    r = np.sqrt(dx * dx + dy * dy) * 2
    a = np.arctan2(dy, dx)
    noise = remap(fbm(n, seed, base=3, octaves=4))
    white = np.ones((n, n, 3), np.float32)
    if name == "sand_puff":
        alpha = smoothstep(1.0, 0.2, r + (noise - 0.5) * 0.5) * 0.85
        rgb = palette_ramp(noise, [(0, 0xD8B88A), (1, 0xF6E2BE)])
    elif name == "droplet":
        shape = np.sqrt(dx * dx * 4 + (dy + 0.1) ** 2 * (1.0 + 2.0 * np.clip(-dy, 0, 1)) * 4)
        alpha = smoothstep(0.75, 0.6, shape)
        rgb = lerp_color(hex_color(0xBFF2F0), hex_color(0xFFFFFF), smoothstep(0.5, 0.2, shape))
    elif name == "spray":
        alpha = smoothstep(1.0, 0.0, r) * (0.4 + 0.6 * noise)
        rgb = white
    elif name in ("sparkle", "star", "glint"):
        points = 4 if name == "sparkle" else 5
        if name == "glint":
            alpha = np.exp(-r * r * 30) + 0.6 * np.exp(-np.abs(dx) * 60) * np.exp(-np.abs(dy) * 8) + 0.6 * np.exp(-np.abs(dy) * 60) * np.exp(-np.abs(dx) * 8)
        else:
            star = np.cos(a * points) * 0.5 + 0.5
            alpha = smoothstep(0.9, 0.7, r / (0.35 + 0.65 * star ** 3))
        alpha = np.clip(alpha, 0, 1)
        rgb = lerp_color(hex_color(0xFFF3B0), hex_color(0xFFFFFF), smoothstep(0.4, 0.0, r))
    elif name.startswith("confetti"):
        color = {"confetti_pink": 0xFF8FA8, "confetti_teal": 0x5CC9B8, "confetti_yellow": 0xFFD34E}[name]
        fan = np.sqrt(dx * dx + (dy - 0.3) ** 2) * 2.2
        ribs = 0.85 + 0.15 * np.cos(np.arctan2(dx, -(dy - 0.3)) * 12)
        alpha = smoothstep(1.0, 0.92, fan) * (dy < 0.3)
        rgb = hex_color(color)[None, None, :] * ribs[..., None]
    elif name == "glow":
        alpha = np.exp(-r * r * 4.0)
        rgb = lerp_color(hex_color(0x9CFFE4), hex_color(0xFFFFFF), np.exp(-r * r * 20))
    elif name == "ash":
        alpha = smoothstep(0.8, 0.5, r + (noise - 0.5) * 0.8) * 0.9
        rgb = palette_ramp(noise, [(0, 0x3A3638), (1, 0x8A8285)])
    elif name == "footprint":
        marks = np.zeros_like(u)
        for sx in (-0.22, 0.22):
            for sy in (-0.18, 0.18):
                e = np.sqrt(((u - 0.5 - sx) / 0.11) ** 2 + ((v - 0.5 - sy) / 0.06) ** 2)
                marks = np.maximum(marks, smoothstep(1.0, 0.7, e))
        drag = smoothstep(0.03, 0.0, np.abs(u - 0.5)) * 0.5
        alpha = np.clip(marks + drag, 0, 1) * 0.6
        rgb = hex_color(0x6E5537)[None, None, :] * np.ones((n, n, 1), np.float32)
    elif name == "ripple":
        alpha = smoothstep(0.08, 0.0, np.abs(r - 0.8)) * 0.9
        rgb = white
    elif name == "soft_circle":
        alpha = smoothstep(1.0, 0.3, r)
        rgb = np.zeros((n, n, 3), np.float32)
    elif name == "bubble":
        alpha = smoothstep(0.08, 0.0, np.abs(r - 0.75)) + 0.25 * smoothstep(0.75, 0.0, r)
        alpha += 0.8 * np.exp(-(((u - 0.38) ** 2 + (v - 0.36) ** 2) * 400))
        alpha = np.clip(alpha, 0, 1)
        rgb = white
    else:  # gull: soft silhouette for the hazard shadow, flying toward the top of the cell
        span = np.abs(dx)
        body = np.sqrt((dx / 0.07) ** 2 + ((dy + 0.0) / 0.3) ** 2)
        bow = -0.05 + 0.3 * (span / 0.47) ** 2
        thickness = np.maximum(0.1 * (1.0 - (span / 0.47) ** 1.6), 1e-3)
        wing = np.where(span < 0.47, np.abs(dy - bow) / thickness, 9.0)
        tail = np.sqrt((dx / (0.04 + 0.5 * np.clip(dy - 0.2, 0, 1))) ** 2 + ((dy - 0.26) / 0.09) ** 2)
        alpha = np.maximum(np.maximum(smoothstep(1.0, 0.6, body), smoothstep(1.0, 0.5, wing)), smoothstep(1.0, 0.6, tail))
        rgb = np.zeros((n, n, 3), np.float32)
    out = np.zeros((n, n, 4), np.float32)
    out[..., :3] = rgb
    out[..., 3] = alpha
    return out


def fx_atlas(size: int, seed: int) -> dict:
    n = size // 4
    atlas = np.zeros((size, size, 4), np.float32)
    for i, name in enumerate(FX_CELLS):
        r, c = divmod(i, 4)
        atlas[r * n:(r + 1) * n, c * n:(c + 1) * n] = _fx_cell(name, n, seed + i * 31)
    return {"albedo": np.clip(atlas, 0, 1)}
