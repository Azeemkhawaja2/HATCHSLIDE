"""Texture stage (spec section 8): procedural albedo/normal/ORM sets compressed to mipmapped KTX in
ASTC and ETC2, written to the texture-compression-format folders of the install-time asset pack."""
from __future__ import annotations

import os
from concurrent.futures import ProcessPoolExecutor
from dataclasses import dataclass, field

import numpy as np
from PIL import Image

from ..common import paths
from ..common.build import Context, digest, source_digest
from . import generators as g
from .ktx import Encoding, mip_chain, write_ktx

COLOR = Encoding("color", (6, 6))
NORMAL = Encoding("normal", (6, 6))
ORM = Encoding("linear", (8, 8), etc2_half=True)
MASK = Encoding("linear", (8, 8), etc2_half=True)
HERO_NORMAL = Encoding("normal", (6, 6), etc2_half=True)
SPRITES = Encoding("color", (4, 4), alpha=True)

REF = "core/render/src/main/kotlin/com/hatchslide/core/render/assets/AssetCatalog.kt"


@dataclass
class TextureSpec:
    name: str
    size: int
    fn: str
    kwargs: dict = field(default_factory=dict)
    maps: dict = field(default_factory=dict)
    seed: int = 0


SPECS: list[TextureSpec] = []
for i, look in enumerate(g.SAND_LOOKS):
    SPECS.append(TextureSpec(look, 2048, "sand_albedo", {"look": look}, {"albedo": COLOR}, 100 + i))
SPECS += [
    TextureSpec("sand_fine", 2048, "sand_detail", {"coarse": False}, {"normal": NORMAL, "orm": ORM}, 120),
    TextureSpec("sand_coarse", 2048, "sand_detail", {"coarse": True}, {"normal": NORMAL, "orm": ORM}, 121),
    TextureSpec("rock", 2048, "rock", {"basalt": False}, {"albedo": COLOR, "normal": NORMAL, "orm": ORM}, 130),
    TextureSpec("basalt", 2048, "rock", {"basalt": True}, {"albedo": COLOR, "normal": NORMAL, "orm": ORM}, 131),
    TextureSpec("shells", 2048, "shell_atlas", {}, {"albedo": COLOR, "normal": NORMAL, "orm": ORM}, 140),
]
for i, species in enumerate(g.SPECIES):
    SPECS.append(TextureSpec(f"turtle_{species}", 2048, "turtle_carapace", {"species": species}, {"albedo": COLOR}, 150 + i))
SPECS += [
    TextureSpec("turtle_detail", 4096, "turtle_detail", {}, {"normal": HERO_NORMAL, "orm": Encoding("linear", (8, 8), etc2_half=True, max_size=2048)}, 160),
    TextureSpec("turtle_skin", 2048, "turtle_skin", {}, {"albedo": COLOR, "normal": NORMAL, "orm": ORM}, 161),
    TextureSpec("water_a", 2048, "water_normal", {"fine": False}, {"normal": NORMAL}, 170),
    TextureSpec("water_b", 2048, "water_normal", {"fine": True}, {"normal": NORMAL}, 171),
    TextureSpec("foam", 2048, "foam", {}, {"albedo": MASK}, 172),
    TextureSpec("caustics", 1024, "caustics", {}, {"albedo": MASK}, 173),
    TextureSpec("props", 2048, "props_atlas", {}, {"albedo": COLOR, "normal": NORMAL, "orm": ORM}, 180),
    TextureSpec("fx", 1024, "fx_atlas", {}, {"albedo": SPRITES}, 190),
]

PREVIEWS = paths.CACHE / "previews"


def targets(spec: TextureSpec) -> list:
    out = []
    for m in spec.maps:
        out.append(paths.TEXTURES_ASTC / f"{spec.name}_{m}.ktx")
        out.append(paths.TEXTURES_ETC2 / f"{spec.name}_{m}.ktx")
    return out


def _preview(name: str, image: np.ndarray) -> None:
    PREVIEWS.mkdir(parents=True, exist_ok=True)
    rgb = image[..., :3] if image.shape[2] >= 3 else np.repeat(image, 3, axis=-1)
    small = Image.fromarray((np.clip(rgb, 0, 1) * 255).astype(np.uint8)).resize((512, 512), Image.LANCZOS)
    small.save(PREVIEWS / f"{name}.png")


def build_one(spec: TextureSpec) -> str:
    result = getattr(g, spec.fn)(spec.size, spec.seed, **spec.kwargs)
    for m, encoding in spec.maps.items():
        image = result[m]
        if image.ndim == 2:
            image = np.repeat(image[..., None], 3, axis=-1)
        levels = mip_chain(image.astype(np.float32), encoding)
        write_ktx(paths.TEXTURES_ASTC / f"{spec.name}_{m}.ktx", [levels], encoding, "astc")
        write_ktx(paths.TEXTURES_ETC2 / f"{spec.name}_{m}.ktx", [levels], encoding, "etc2")
        _preview(f"{spec.name}_{m}", image)
    return spec.name


def _digest(code: str, s: TextureSpec) -> str:
    return digest(code, s.name, s.size, s.seed, s.kwargs, sorted((m, repr(e)) for m, e in s.maps.items()))


def build(ctx: Context) -> None:
    code = source_digest("textures")
    stale = [s for s in SPECS if not ctx.is_fresh(f"textures/{s.name}", _digest(code, s), targets(s))]
    if stale:
        workers = max(1, min(len(stale), (os.cpu_count() or 2)))
        with ProcessPoolExecutor(max_workers=workers) as pool:
            for name in pool.map(build_one, stale):
                print(f"  texture {name}")
    stale_names = {s.name for s in stale}
    for s in SPECS:
        key = f"textures/{s.name}"
        ctx.record(key, _digest(code, s), targets(s), REF, "textures", built=s.name in stale_names)
