"""Model stage: every model in the library as binary glTF, LOD0 and LOD1, into the base module assets."""
from __future__ import annotations

import os
from concurrent.futures import ProcessPoolExecutor

from ..common import paths
from ..common.build import Context, digest, source_digest
from .glb import write_glb
from .library import MODELS

REF = "core/render/src/main/kotlin/com/hatchslide/core/render/assets/AssetCatalog.kt"
LODS = (0, 1)


def targets(name: str) -> list:
    return [paths.MODELS / f"{name}.glb", paths.MODELS / f"{name}_lod1.glb"]


def build_one(name: str) -> str:
    for lod, target in zip(LODS, targets(name)):
        write_glb(target, MODELS[name](lod))
    return name


def build(ctx: Context) -> None:
    # The textures stage owns the props atlas layout the models index into.
    code = source_digest("models", "textures/generators.py")
    stale = [n for n in MODELS if not ctx.is_fresh(f"models/{n}", digest(code, n), targets(n))]
    if stale:
        with ProcessPoolExecutor(max_workers=max(1, min(len(stale), os.cpu_count() or 2))) as pool:
            for name in pool.map(build_one, stale):
                print(f"  model {name}")
    for name in MODELS:
        ctx.record(f"models/{name}", digest(code, name), targets(name), REF, "models", built=name in stale)
