"""Bundled OFL fonts (spec section 8): downloaded from the Google Fonts repository at a pinned commit.

Fredoka is the rounded display face, Nunito the body face. Both are variable fonts; Compose selects
weights through font variation settings (API 26+). The OFL texts ship in assets/licenses/.
"""
from __future__ import annotations

import hashlib
import time
import urllib.request

from ..common import paths
from ..common.build import Context, digest

COMMIT = "e7db6ca603fd4862010383ff17ffd6cc4ffac1c0"
BASE = f"https://raw.githubusercontent.com/google/fonts/{COMMIT}/ofl"

# (repository path, output file, sha256, category)
FILES = [
    ("fredoka/Fredoka%5Bwdth,wght%5D.ttf", paths.FONT_RES / "fredoka.ttf", "2ba02e68b152868aef9ba28e24b3648c7d457fe6f25c761f2c2c53fb61a73fc8"),
    ("fredoka/OFL.txt", paths.LICENSE_TEXTS / "OFL-Fredoka.txt", "5c9e7eee5c6b25f4b05b8d53b2e470ea4962f9ced742d044a98f7d95d1375bab"),
    ("nunito/Nunito%5Bwght%5D.ttf", paths.FONT_RES / "nunito.ttf", "bb55a5ca5c2042335b3991af27c4d0705d0ef41cac6164ac737fd8f2a1e85207"),
    ("nunito/OFL.txt", paths.LICENSE_TEXTS / "OFL-Nunito.txt", "580df76c95a1ec5ab878ceb25bb3d85c6a076804e9c970c8c6972aea775fdf65"),
]

THEME = "core/designsystem/src/main/kotlin/com/hatchslide/core/designsystem/Theme.kt"
LICENSES = "core/designsystem/src/main/kotlin/com/hatchslide/core/designsystem/Licenses.kt"
REFERENCED_BY = {
    "fredoka.ttf": THEME,
    "nunito.ttf": THEME,
    "OFL-Fredoka.txt": LICENSES,
    "OFL-Nunito.txt": LICENSES,
}


def _download(url: str) -> bytes:
    delay = 2
    for attempt in range(5):
        try:
            with urllib.request.urlopen(url, timeout=60) as response:
                return response.read()
        except Exception:
            if attempt == 4:
                raise
            time.sleep(delay)
            delay *= 2
    raise AssertionError("unreachable")


def build(ctx: Context) -> None:
    for remote, target, sha in FILES:
        def fetch(remote=remote, target=target, sha=sha) -> None:
            data = _download(f"{BASE}/{remote}")
            actual = hashlib.sha256(data).hexdigest()
            if actual != sha:
                raise RuntimeError(f"{remote}: sha256 {actual} does not match pinned {sha}")
            target.write_bytes(data)

        category = "fonts" if target.suffix == ".ttf" else "licenses"
        ctx.produce(f"fonts/{target.name}", digest(COMMIT, sha), [target], fetch, REFERENCED_BY[target.name], category)
