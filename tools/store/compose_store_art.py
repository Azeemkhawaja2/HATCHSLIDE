"""Turns raw captures from tools/store/capture.sh into Play listing images (spec section 13).

Phone screenshots: 1080x1920 portrait with a short caption band. Tablet screenshots: 1920x1200
(7-inch) and 2560x1600 (10-inch) landscape, uncaptioned. Feature graphic: 1024x500 from the title
beach with the UI hidden, plus the logo in large type (no small text).
Usage: python tools/store/compose_store_art.py <raw dir> <store dir>
"""
from __future__ import annotations

import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = Path(__file__).resolve().parents[2]
FONT = ROOT / "core" / "designsystem" / "src" / "main" / "res" / "font"

CAPTIONS = {
    "01_title": "Guide baby sea turtles to the surf",
    "02_sunrise_cove": "One swipe slides every hatchling",
    "03_coral_coast": "Cracked shells, wet sand and more",
    "04_moonlit_bay": "Glowing tide pools under the moon",
    "05_crab_reef": "Dodge the scuttling crabs",
    "06_volcanic_isle": "Five islands, 150 puzzles",
    "07_islands": "Sail across the archipelago",
    "08_journal": "Meet seven sea turtle species",
}
SAND = (243, 217, 164)
OCEAN = (14, 59, 92)
CREAM = (255, 251, 242)


def font(size: int) -> ImageFont.FreeTypeFont:
    for name in ("fredoka.ttf", "fredoka_variable.ttf"):
        if (FONT / name).exists():
            f = ImageFont.truetype(str(FONT / name), size)
            try:
                f.set_variation_by_axes([600])
            except (OSError, AttributeError):
                pass
            return f
    candidates = sorted(FONT.glob("fredoka*.ttf"))
    return ImageFont.truetype(str(candidates[0]), size) if candidates else ImageFont.load_default()


def phone(raw: Path, store: Path) -> None:
    out = store / "screenshots" / "phone"
    out.mkdir(parents=True, exist_ok=True)
    band = 260
    for shot in sorted(raw.glob("*.png")):
        image = Image.open(shot).convert("RGB")
        canvas = Image.new("RGB", (1080, 1920), OCEAN)
        scaled = image.resize((round(1080 * (1920 - band) / 1920), 1920 - band), Image.LANCZOS)
        canvas.paste(scaled, ((1080 - scaled.width) // 2, band))
        draw = ImageDraw.Draw(canvas)
        text = CAPTIONS.get(shot.stem, "")
        f = font(64)
        w = draw.textlength(text, font=f)
        while w > 1000 and f.size > 40:
            f = font(f.size - 4)
            w = draw.textlength(text, font=f)
        draw.text(((1080 - w) / 2, band / 2 - f.size * 0.6), text, font=f, fill=CREAM)
        canvas.save(out / f"phone_{shot.stem}.png", optimize=True)


def tablet(raw: Path, store: Path, name: str, size: tuple[int, int]) -> None:
    out = store / "screenshots" / name
    out.mkdir(parents=True, exist_ok=True)
    for shot in sorted(raw.glob("*.png")):
        image = Image.open(shot).convert("RGB")
        if image.size != size:
            image = image.resize(size, Image.LANCZOS)
        image.save(out / f"{name}_{shot.stem}.png", optimize=True)


def feature(raw: Path, store: Path) -> None:
    image = Image.open(raw / "feature_raw.png").convert("RGB").resize((1024, 500), Image.LANCZOS)
    draw = ImageDraw.Draw(image)
    f = font(112)
    text = "HATCHSLIDE"
    w = draw.textlength(text, font=f)
    x, y = (1024 - w) / 2, 70
    shadow = Image.new("RGBA", image.size, (0, 0, 0, 0))
    ImageDraw.Draw(shadow).text((x + 4, y + 6), text, font=f, fill=(14, 59, 92, 150))
    image.paste(shadow.filter(ImageFilter.GaussianBlur(6)), (0, 0), shadow.filter(ImageFilter.GaussianBlur(6)))
    draw.text((x, y), text, font=f, fill=SAND, stroke_width=4, stroke_fill=OCEAN)
    image.save(store / "feature_graphic.png", optimize=True)


def main() -> None:
    raw, store = Path(sys.argv[1]), Path(sys.argv[2])
    if (raw / "phone").exists():
        phone(raw / "phone", store)
    if (raw / "tablet7").exists():
        tablet(raw / "tablet7", store, "tablet7", (1920, 1200))
    if (raw / "tablet10").exists():
        tablet(raw / "tablet10", store, "tablet10", (2560, 1600))
    if (raw / "feature").exists():
        feature(raw / "feature", store)


if __name__ == "__main__":
    main()
