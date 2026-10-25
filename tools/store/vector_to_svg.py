"""Converts the launcher icon's VectorDrawable layers into one SVG (for the 512 px Play Store icon).

Supports what the icon uses: paths with fill or stroke colours and alphas, linear and radial gradient
fills (aapt:attr), line caps and joins. Usage:
    python tools/store/vector_to_svg.py <crop_dp> <out.svg> <layer.xml>...
The layers are stacked in order on the 108 dp adaptive-icon canvas; the SVG shows the central
crop_dp x crop_dp square.
"""
from __future__ import annotations

import sys
import xml.etree.ElementTree as ET

A = "{http://schemas.android.com/apk/res/android}"


def colour(value: str) -> tuple[str, float]:
    """#AARRGGBB or #RRGGBB to (css colour, alpha)."""
    v = value.lstrip("#")
    if len(v) == 8:
        return "#" + v[2:], int(v[:2], 16) / 255
    return "#" + v, 1.0


def layer(path: str, prefix: str) -> tuple[list[str], list[str]]:
    root = ET.parse(path).getroot()
    defs, shapes = [], []
    for i, p in enumerate(root.iter("path")):
        d = p.get(A + "pathData")
        attrs = [f'd="{d}"']
        fill = p.get(A + "fillColor")
        gradient = p.find("{http://schemas.android.com/aapt}attr")
        if gradient is not None:
            g = gradient.find("gradient")
            gid = f"{prefix}g{i}"
            stops = "".join(
                f'<stop offset="{s.get(A + "offset")}" stop-color="{colour(s.get(A + "color"))[0]}" '
                f'stop-opacity="{colour(s.get(A + "color"))[1]:.3f}"/>'
                for s in g.iter("item")
            )
            if g.get(A + "type") == "radial":
                defs.append(
                    f'<radialGradient id="{gid}" gradientUnits="userSpaceOnUse" cx="{g.get(A + "centerX")}" '
                    f'cy="{g.get(A + "centerY")}" r="{g.get(A + "gradientRadius")}">{stops}</radialGradient>'
                )
            else:
                defs.append(
                    f'<linearGradient id="{gid}" gradientUnits="userSpaceOnUse" x1="{g.get(A + "startX")}" '
                    f'y1="{g.get(A + "startY")}" x2="{g.get(A + "endX")}" y2="{g.get(A + "endY")}">{stops}</linearGradient>'
                )
            attrs.append(f'fill="url(#{gid})"')
        elif fill:
            css, alpha = colour(fill)
            attrs.append(f'fill="{css}" fill-opacity="{alpha * float(p.get(A + "fillAlpha", "1")):.3f}"')
        else:
            attrs.append('fill="none"')
        stroke = p.get(A + "strokeColor")
        if stroke:
            css, alpha = colour(stroke)
            attrs.append(f'stroke="{css}" stroke-opacity="{alpha * float(p.get(A + "strokeAlpha", "1")):.3f}"')
            attrs.append(f'stroke-width="{p.get(A + "strokeWidth", "1")}"')
            attrs.append(f'stroke-linecap="{p.get(A + "strokeLineCap", "butt")}"')
            attrs.append(f'stroke-linejoin="{p.get(A + "strokeLineJoin", "miter")}"')
        shapes.append("<path " + " ".join(attrs) + "/>")
    return defs, shapes


def main() -> None:
    crop = float(sys.argv[1])
    out = sys.argv[2]
    defs, shapes = [], []
    for k, path in enumerate(sys.argv[3:]):
        d, s = layer(path, f"l{k}")
        defs += d
        shapes += s
    origin = (108 - crop) / 2
    svg = (
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="{origin} {origin} {crop} {crop}" width="512" height="512">'
        f'<defs>{"".join(defs)}</defs>{"".join(shapes)}</svg>'
    )
    with open(out, "w") as f:
        f.write(svg)


if __name__ == "__main__":
    main()
