"""Sky stage (spec section 8): one HDR equirectangular sky per environment at 4096x2048, converted with
Filament's cmgen into image-based lighting (prefiltered specular cubemap + spherical harmonics).

Conventions, verified against cmgen's own output: Filament samples cubemaps with the world-space ray
direction, and cmgen maps the panorama centre to world +Z (u = 0.25 is +X). The skybox is a
compressed LDR cubemap built here from the same sky function (cmgen's skybox is uncompressed
R11G11B10F, which at a crisp resolution would not fit the size budget): each face texel holds the sky
seen along the standard GL face direction.
"""
from __future__ import annotations

import os
import shutil
import subprocess
import sys
from concurrent.futures import ProcessPoolExecutor
from dataclasses import dataclass

import numpy as np

from ..common import paths
from ..common.build import Context, digest, source_digest
from ..textures.ktx import Encoding, linear_to_srgb, srgb_to_linear, write_ktx
from ..textures.noise import fbm, remap, smoothstep
from ..textures.noise import hex_color as srgb_hex


def hex_color(value: int) -> np.ndarray:
    """Palette colours are authored in sRGB; the sky is computed in linear radiance."""
    return srgb_to_linear(srgb_hex(value))

PANORAMA = (4096, 2048)
FACE = 768
SKYBOX = Encoding("color", (6, 6), mips=False)
INTERMEDIATE = paths.ASSETS_SRC / "intermediate" / "skies"
REF = "core/render/src/main/kotlin/com/hatchslide/core/render/assets/AssetCatalog.kt"


@dataclass(frozen=True)
class SkySpec:
    name: str
    sun: tuple[float, float, float]  # direction toward the sun (or moon), world space
    zenith: int
    horizon: int
    ground: int
    sun_color: int
    sun_power: float
    glow: float
    clouds: float  # coverage 0..1
    cloud_light: int
    cloud_shadow: int
    night: bool = False
    haze: int | None = None
    seed: int = 0


# Sun positions must match WorldTheme.sunDirection (= -sun) in core/render/Environment.kt.
SKIES = [
    SkySpec("title", (-0.45, 0.16, -0.88), 0x4F8FD0, 0xFFB98F, 0x6C5A54, 0xFFD2A0, 60.0, 1.4, 0.55, 0xFFE6D2, 0xA77C96, seed=1),
    SkySpec("world1", (-0.7, 0.32, -0.64), 0x5A9EDC, 0xFFC9A0, 0x75655B, 0xFFDCB2, 70.0, 1.1, 0.5, 0xFFF2E4, 0xB592A6, seed=2),
    SkySpec("world2", (0.2, 0.92, 0.33), 0x1F74CF, 0xAEDFFF, 0x6E8A92, 0xFFFBF0, 110.0, 0.5, 0.45, 0xFFFFFF, 0xA9BFD8, seed=3),
    SkySpec("world3", (0.35, 0.55, -0.76), 0x040A1E, 0x16244E, 0x0B1226, 0xDCE6FF, 25.0, 0.35, 0.3, 0x4E5E8C, 0x10162E, night=True, seed=4),
    SkySpec("world4", (0.72, 0.3, -0.62), 0x4A84C4, 0xF2A877, 0x6A5848, 0xFFC490, 75.0, 1.2, 0.52, 0xFFE4C6, 0x9A7486, seed=5),
    SkySpec("world5", (0.6, 0.12, -0.79), 0x241A38, 0xF0703A, 0x3A2A2E, 0xFF8A4C, 50.0, 1.8, 0.6, 0xF7A178, 0x40293A, haze=0xC2593A, seed=6),
]


def _normalize(v):
    v = np.asarray(v, np.float32)
    return v / np.linalg.norm(v)


class SkyModel:
    """Analytic sky: gradient, sun or moon disc with glow, lit procedural clouds, stars and haze."""

    def __init__(self, spec: SkySpec):
        self.spec = spec
        self.sun = _normalize(spec.sun)
        self.cloud_tex = remap(fbm(1024, spec.seed * 31 + 7, base=3, octaves=7))
        self.cloud_detail = remap(fbm(1024, spec.seed * 31 + 9, base=8, octaves=5))
        rng = np.random.default_rng(spec.seed)
        self.star_dirs = rng.normal(size=(9000, 3)).astype(np.float32)
        self.star_dirs /= np.linalg.norm(self.star_dirs, axis=1, keepdims=True)
        self.star_mag = rng.random(9000).astype(np.float32) ** 6

    def _sample_tex(self, tex: np.ndarray, x: np.ndarray, y: np.ndarray) -> np.ndarray:
        n = tex.shape[0]
        fx = (x % 1.0) * n
        fy = (y % 1.0) * n
        x0 = np.floor(fx).astype(np.int64) % n
        y0 = np.floor(fy).astype(np.int64) % n
        tx = fx - np.floor(fx)
        ty = fy - np.floor(fy)
        x1 = (x0 + 1) % n
        y1 = (y0 + 1) % n
        top = tex[y0, x0] * (1 - tx) + tex[y0, x1] * tx
        bottom = tex[y1, x0] * (1 - tx) + tex[y1, x1] * tx
        return top * (1 - ty) + bottom * ty

    def __call__(self, d: np.ndarray) -> np.ndarray:
        s = self.spec
        x, y, z = d[..., 0], d[..., 1], d[..., 2]
        elevation = np.arcsin(np.clip(y, -1, 1))
        cos_gamma = np.clip(d @ self.sun, -1, 1)
        gamma = np.arccos(cos_gamma)
        up = np.clip(elevation / (np.pi / 2), 0, 1)
        t = up ** 0.42
        color = hex_color(s.horizon)[None, :] * (1 - t[..., None]) + hex_color(s.zenith)[None, :] * t[..., None]
        brightness = 1.0 if not s.night else 0.22
        color = color * brightness
        horizon_band = np.exp(-np.abs(elevation) * 9.0)
        glow = (0.8 * np.exp(-gamma * 7.0) + 0.3 * np.exp(-gamma * 2.2)) * s.glow * (0.4 + 0.6 * horizon_band)
        color = color + hex_color(s.sun_color)[None, :] * glow[..., None] * (0.25 if s.night else 1.0)
        if s.haze is not None:
            haze = np.exp(-np.maximum(elevation, 0) * 5.0) * 0.9
            color = color * (1 - 0.5 * haze[..., None]) + hex_color(s.haze)[None, :] * haze[..., None]
        # Below the horizon: dim ground/ocean tone for the lower hemisphere of the IBL.
        below = elevation < 0
        ground = hex_color(s.ground)[None, :] * (0.25 + 0.6 * np.exp(elevation * 6.0))[..., None] * brightness
        color = np.where(below[..., None], ground, color)
        # Clouds on a plane above the viewer.
        cloud_mask = y > 0.015
        py = np.maximum(y, 0.015)
        cu = x / py * 0.12 + 0.37
        cv = z / py * 0.12 + 0.61
        base = self._sample_tex(self.cloud_tex, cu, cv)
        detail = self._sample_tex(self.cloud_detail, cu * 3.1, cv * 3.1)
        density = smoothstep(1.0 - s.clouds, 1.0 - s.clouds + 0.25, base * 0.8 + detail * 0.2)
        density *= smoothstep(0.015, 0.16, y) * cloud_mask
        lit = 0.5 + 0.5 * cos_gamma
        cloud = hex_color(s.cloud_shadow)[None, :] * (1 - lit[..., None]) + hex_color(s.cloud_light)[None, :] * lit[..., None]
        lining = np.exp(-gamma * 5.0) * 2.5 * (1 - density) * smoothstep(0.0, 0.3, density * 3)
        cloud = cloud * (1.0 if not s.night else 0.35) + hex_color(s.sun_color)[None, :] * lining[..., None] * (0.3 if s.night else 1.0)
        color = color * (1 - density[..., None] * 0.92) + cloud * density[..., None] * 0.92
        # Stars and Milky Way at night.
        if s.night:
            band = np.exp(-((d @ _normalize((0.3, 0.6, 0.74))) ** 2) * 18.0) * smoothstep(0.0, 0.1, y)
            color = color + hex_color(0x6E7FB8)[None, :] * (band * 0.12 * (1 - density))[..., None]
        # Sun or moon disc.
        radius = np.radians(1.4 if s.night else 0.75)
        disc = smoothstep(radius, radius * 0.85, gamma) * (1 - density * 0.9)
        if s.night:
            crater = 0.75 + 0.25 * self._sample_tex(self.cloud_detail, (d @ np.array([1, 0, 0], np.float32)) * 8, y * 8)
            color = color + hex_color(s.sun_color)[None, :] * (disc * 2.6 * crater)[..., None]
        else:
            limb = np.sqrt(np.clip(1 - (gamma / radius) ** 2, 0, 1))
            color = color + hex_color(s.sun_color)[None, :] * (disc * s.sun_power * (0.6 + 0.4 * limb))[..., None]
        return np.maximum(color, 0).astype(np.float32)

    def add_stars(self, image: np.ndarray, to_pixel) -> None:
        if not self.spec.night:
            return
        dirs = self.star_dirs[self.star_dirs[:, 1] > 0.05]
        mags = self.star_mag[self.star_dirs[:, 1] > 0.05]
        px, py = to_pixel(dirs)
        h, w = image.shape[:2]
        for (ix, iy, m) in zip(px, py, mags):
            if 0 <= iy < h:
                image[iy, ix % w] += 0.4 + 3.0 * m


def panorama_dirs(width: int, height: int) -> np.ndarray:
    v, u = np.mgrid[0:height, 0:width].astype(np.float32)
    lon = ((u + 0.5) / width - 0.5) * 2 * np.pi
    lat = (0.5 - (v + 0.5) / height) * np.pi
    return np.stack([-np.cos(lat) * np.sin(lon), np.sin(lat), np.cos(lat) * np.cos(lon)], axis=-1).astype(np.float32)


def dir_to_panorama(d: np.ndarray, width: int, height: int):
    lon = np.arctan2(-d[:, 0], d[:, 2])
    lat = np.arcsin(np.clip(d[:, 1], -1, 1))
    px = ((lon / (2 * np.pi) + 0.5) * width).astype(np.int64)
    py = ((0.5 - lat / np.pi) * height).astype(np.int64)
    return px, py


def face_dirs(face: int, n: int) -> np.ndarray:
    """World directions for each texel of a GL cubemap face (+X, -X, +Y, -Y, +Z, -Z), first row on top."""
    t, s = np.mgrid[0:n, 0:n].astype(np.float32)
    sc = (s + 0.5) / n * 2 - 1
    tc = (t + 0.5) / n * 2 - 1
    one = np.ones_like(sc)
    vectors = [
        (one, -tc, -sc),
        (-one, -tc, sc),
        (sc, one, tc),
        (sc, -one, -tc),
        (sc, -tc, one),
        (-sc, -tc, -one),
    ][face]
    d = np.stack(vectors, axis=-1)
    return (d / np.linalg.norm(d, axis=-1, keepdims=True)).astype(np.float32)


def write_hdr(path, image: np.ndarray) -> None:
    """Radiance RGBE (.hdr), uncompressed scanlines."""
    h, w, _ = image.shape
    m = np.max(image, axis=2)
    e = np.floor(np.log2(np.maximum(m, 1e-32))) + 1
    scale = np.where(m > 1e-32, 2.0 ** (-e) * 256.0, 0)
    rgbe = np.zeros((h, w, 4), np.uint8)
    rgbe[..., :3] = np.clip(image * scale[..., None], 0, 255).astype(np.uint8)
    rgbe[..., 3] = np.where(m > 1e-32, e + 128, 0).astype(np.uint8)
    path.write_bytes(b"#?RADIANCE\nFORMAT=32-bit_rle_rgbe\n\n-Y %d +X %d\n" % (h, w) + rgbe.tobytes())


def soft_clip(x: np.ndarray) -> np.ndarray:
    """LDR skybox values: identical to the HDR sky below 0.8, rolling off smoothly to 1.0."""
    return np.where(x < 0.8, x, 0.8 + 0.2 * (1 - np.exp(-(x - 0.8) / 0.2))).astype(np.float32)


def tools_dir():
    out = subprocess.run([sys.executable, str(paths.ROOT / "tools" / "filament" / "fetch_tools.py"), "--print-dir"], check=True, capture_output=True, text=True)
    return out.stdout.strip()


def build_one(spec: SkySpec) -> str:
    model = SkyModel(spec)
    w, h = PANORAMA
    pano = np.zeros((h, w, 3), np.float32)
    rows = 128
    for r0 in range(0, h, rows):
        d = panorama_dirs(w, h)[r0:r0 + rows] if r0 == 0 else None
        if d is None:
            v, u = np.mgrid[r0:min(r0 + rows, h), 0:w].astype(np.float32)
            lon = ((u + 0.5) / w - 0.5) * 2 * np.pi
            lat = (0.5 - (v + 0.5) / h) * np.pi
            d = np.stack([-np.cos(lat) * np.sin(lon), np.sin(lat), np.cos(lat) * np.cos(lon)], axis=-1).astype(np.float32)
        pano[r0:r0 + rows] = model(d)
    model.add_stars(pano, lambda dirs: dir_to_panorama(dirs, w, h))
    INTERMEDIATE.mkdir(parents=True, exist_ok=True)
    hdr = INTERMEDIATE / f"{spec.name}.hdr"
    write_hdr(hdr, pano)
    deploy = INTERMEDIATE / spec.name
    shutil.rmtree(deploy, ignore_errors=True)
    subprocess.run([os.path.join(tools_dir(), "cmgen"), "-q", "--format=ktx", "--size=256", f"--deploy={deploy}", str(hdr)], check=True)
    paths.SKIES.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(deploy / f"{spec.name}_ibl.ktx", paths.SKIES / f"{spec.name}_ibl.ktx")
    faces = []
    for f in range(6):
        d = face_dirs(f, FACE)
        face = model(d)
        if spec.night:
            px, py = dir_to_panorama(d.reshape(-1, 3), w, h)
            face = np.maximum(face, pano[np.clip(py, 0, h - 1), px % w].reshape(FACE, FACE, 3) * (d[..., 1:2] > 0.05))
        faces.append([linear_to_srgb(soft_clip(face))])
    write_ktx(paths.TEXTURES_ASTC / f"sky_{spec.name}.ktx", faces, SKYBOX, "astc")
    write_ktx(paths.TEXTURES_ETC2 / f"sky_{spec.name}.ktx", faces, SKYBOX, "etc2")
    # Preview for visual checks.
    from PIL import Image

    preview = linear_to_srgb(soft_clip(pano[::8, ::8]))
    (paths.CACHE / "previews").mkdir(parents=True, exist_ok=True)
    Image.fromarray((preview * 255).astype(np.uint8)).save(paths.CACHE / "previews" / f"sky_{spec.name}.png")
    return spec.name


def targets(spec: SkySpec):
    return [
        paths.SKIES / f"{spec.name}_ibl.ktx",
        paths.TEXTURES_ASTC / f"sky_{spec.name}.ktx",
        paths.TEXTURES_ETC2 / f"sky_{spec.name}.ktx",
    ]


def build(ctx: Context) -> None:
    code = source_digest("skies") + source_digest("textures/ktx.py")
    stale = [s for s in SKIES if not ctx.is_fresh(f"skies/{s.name}", digest(code, repr(s)), targets(s))]
    if stale:
        with ProcessPoolExecutor(max_workers=min(len(stale), os.cpu_count() or 2)) as pool:
            for name in pool.map(build_one, stale):
                print(f"  sky {name}")
    names = {s.name for s in stale}
    for s in SKIES:
        ctx.record(f"skies/{s.name}", digest(code, repr(s)), targets(s), REF, "skies", built=s.name in names)
