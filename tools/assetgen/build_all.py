#!/usr/bin/env python3
"""Builds every generated HATCHSLIDE asset deterministically.

Usage: python tools/assetgen/build_all.py [--force] [--only STAGE ...]

Stages run in order; each skips outputs whose inputs have not changed. The run ends by writing
assets_manifest.json, which lists every output file, its size and the code that references it.
"""
from __future__ import annotations

import argparse
import importlib
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from tools.assetgen.common import paths  # noqa: E402
from tools.assetgen.common.build import Context  # noqa: E402

# (stage name, module path). Each module exposes build(ctx: Context).
STAGES: list[tuple[str, str]] = [
    ("fonts", "tools.assetgen.fonts"),
    ("textures", "tools.assetgen.textures"),
    ("skies", "tools.assetgen.skies"),
    ("models", "tools.assetgen.models"),
    ("audio", "tools.assetgen.audio"),
]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--force", action="store_true", help="rebuild everything")
    parser.add_argument("--only", nargs="*", default=None, help="run only these stages")
    args = parser.parse_args()

    for d in (paths.BASE_ASSETS, paths.PACK_ASSETS, paths.CACHE):
        d.mkdir(parents=True, exist_ok=True)

    ctx = Context(force=args.force)
    ctx.load()
    for name, module in STAGES:
        if args.only and name not in args.only:
            continue
        start = time.time()
        print(f"[{name}]")
        importlib.import_module(module).build(ctx)
        print(f"[{name}] done in {time.time() - start:.1f}s")
    if not args.only:
        ctx.write_manifest()
    return 0


if __name__ == "__main__":
    sys.exit(main())
