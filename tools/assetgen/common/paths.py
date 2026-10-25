"""Output locations for generated assets.

Base-module assets (models, effects, ambience) go to app/src/main/assets-gen; HD content delivered
through the install-time asset pack (textures, skies, music) goes to hd_assets/src/main/assets.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
ASSETS_SRC = ROOT / "assets_src"
CACHE = ASSETS_SRC / "cache"
OVERRIDES = ASSETS_SRC / "audio" / "override"

BASE_ASSETS = ROOT / "app" / "src" / "main" / "assets-gen"
PACK_ASSETS = ROOT / "hd_assets" / "src" / "main" / "assets"
FONT_RES = ROOT / "core" / "designsystem" / "src" / "main" / "res" / "font"
MANIFEST = ROOT / "assets_manifest.json"

TEXTURES_ASTC = PACK_ASSETS / "textures#tcf_astc"
TEXTURES_ETC2 = PACK_ASSETS / "textures#tcf_etc2"
SKIES = PACK_ASSETS / "skies"
MUSIC = PACK_ASSETS / "music"
# Stingers play through SoundPool, which needs uncompressed base-module assets; they share the music/ path.
STINGERS = BASE_ASSETS / "music"
MODELS = BASE_ASSETS / "models"
SFX = BASE_ASSETS / "sfx"
AMBIENCE = BASE_ASSETS / "ambience"
LICENSE_TEXTS = BASE_ASSETS / "licenses"
