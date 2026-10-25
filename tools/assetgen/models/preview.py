"""Software preview renderer for model QA (build-time only; output goes to the gitignored cache).

Flattens a Node tree, then z-buffers its triangles with back-face culling, Lambert shading, baked vertex AO
and albedo sampled from the texture previews, so a model can be checked by eye without a device.
"""
from __future__ import annotations

from pathlib import Path

import numpy as np
from PIL import Image

from ..common import paths
from .mesh import Node, Part

TEXTURE_PREVIEWS = paths.CACHE / "previews"
OUT = paths.CACHE / "model_previews"

MATERIAL_TEXTURE = {
    "turtle_shell": "turtle_green_albedo",
    "turtle_skin": "turtle_skin_albedo",
    "rock": "rock_albedo",
    "basalt": "basalt_albedo",
    "shells": "shells_albedo",
    "props": "props_albedo",
    "sand": "sand_golden_albedo",
}
MATERIAL_COLOR = {
    "turtle_eye": (0.04, 0.035, 0.03),
    "eye_glint": (1.0, 1.0, 1.0),
    "pit": (0.18, 0.12, 0.08),
    "current": (0.2, 0.55, 0.6),
    "pool_water": (0.25, 0.55, 0.75),
    "lantern_glow": (1.0, 0.8, 0.45),
    "lava": (1.0, 0.45, 0.1),
}
_textures: dict[str, np.ndarray] = {}


def _texture(name: str) -> np.ndarray | None:
    if name not in _textures:
        path = TEXTURE_PREVIEWS / f"{name}.png"
        _textures[name] = np.asarray(Image.open(path).convert("RGB"), np.float32) / 255.0 if path.exists() else None
    return _textures[name]


def _quat_matrix(q) -> np.ndarray:
    x, y, z, w = q
    return np.array(
        [
            [1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
            [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
            [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)],
        ],
        np.float32,
    )


def flatten(node: Node, parent: np.ndarray | None = None) -> list[Part]:
    local = np.eye(4, dtype=np.float32)
    local[:3, :3] = _quat_matrix(node.rotation) * np.array(node.scale, np.float32)[None, :]
    local[:3, 3] = node.translation
    world = local if parent is None else parent @ local
    out = []
    normal_m = np.linalg.inv(world[:3, :3]).T
    for part in node.parts:
        p = part.copy()
        p.positions = p.positions @ world[:3, :3].T + world[:3, 3]
        n = p.normals @ normal_m.T
        p.normals = n / np.maximum(np.linalg.norm(n, axis=1, keepdims=True), 1e-9)
        out.append(p)
    for child in node.children:
        out += flatten(child, world)
    return out


def _look_at(eye: np.ndarray, target: np.ndarray) -> np.ndarray:
    f = target - eye
    f /= np.linalg.norm(f)
    r = np.cross(f, np.array([0, 1, 0], np.float32))
    r /= np.linalg.norm(r)
    u = np.cross(r, f)
    return np.stack([r, u, -f])


def render(node: Node, size: int = 384, view: str = "front") -> np.ndarray:
    parts = flatten(node)
    allpos = np.concatenate([p.positions for p in parts])
    lo, hi = allpos.min(axis=0), allpos.max(axis=0)
    centre = (lo + hi) / 2
    radius = float(np.linalg.norm(hi - lo)) / 2 + 1e-3
    direction = {"front": np.array([0.55, 0.6, -1.0]), "top": np.array([0.05, 1.0, -0.25]), "side": np.array([-1.0, 0.35, 0.1])}[view]
    direction = direction / np.linalg.norm(direction)
    eye = centre + direction * radius * 4.0
    rot = _look_at(eye, centre)
    focal = size * 1.85
    sun = np.array([-0.45, 0.8, -0.4], np.float32)
    sun /= np.linalg.norm(sun)
    color = np.zeros((size, size, 3), np.float32)
    color[:] = (0.86, 0.9, 0.93)
    depth = np.full((size, size), np.inf, np.float32)
    for part in parts:
        cam = (part.positions - eye) @ rot.T
        z = -cam[:, 2]
        sx = cam[:, 0] / z * focal + size / 2
        sy = -cam[:, 1] / z * focal + size / 2
        tex = _texture(MATERIAL_TEXTURE.get(part.material, ""))
        flat = np.array(MATERIAL_COLOR.get(part.material, (0.8, 0.8, 0.8)), np.float32)
        colors = part.colors if part.colors is not None else np.ones((len(part.positions), 4), np.float32)
        light = np.clip(part.normals @ sun, 0, 1) * 0.75 + 0.3 + 0.1 * part.normals[:, 1]
        light = light * colors[:, 0]
        if part.material in ("lantern_glow", "lava", "eye_glint"):
            light[:] = 1.0
        uv = np.clip(part.uvs, 0, 0.9999)
        for f in part.faces:
            xs, ys = sx[f], sy[f]
            area = (xs[1] - xs[0]) * (ys[2] - ys[0]) - (xs[2] - xs[0]) * (ys[1] - ys[0])
            if area >= 0 or np.any(z[f] <= 0):
                continue
            x0, x1 = max(int(xs.min()), 0), min(int(xs.max()) + 1, size)
            y0, y1 = max(int(ys.min()), 0), min(int(ys.max()) + 1, size)
            if x0 >= x1 or y0 >= y1:
                continue
            gx, gy = np.meshgrid(np.arange(x0, x1) + 0.5, np.arange(y0, y1) + 0.5)
            w0 = ((xs[1] - gx) * (ys[2] - gy) - (xs[2] - gx) * (ys[1] - gy)) / area
            w1 = ((xs[2] - gx) * (ys[0] - gy) - (xs[0] - gx) * (ys[2] - gy)) / area
            w2 = 1 - w0 - w1
            inside = (w0 >= 0) & (w1 >= 0) & (w2 >= 0)
            if not inside.any():
                continue
            zz = w0 * z[f[0]] + w1 * z[f[1]] + w2 * z[f[2]]
            region = depth[y0:y1, x0:x1]
            closer = inside & (zz < region)
            if not closer.any():
                continue
            region[closer] = zz[closer]
            a, b, c = w0[closer], w1[closer], w2[closer]
            lit = a * light[f[0]] + b * light[f[1]] + c * light[f[2]]
            if tex is not None:
                u = a * uv[f[0], 0] + b * uv[f[1], 0] + c * uv[f[2], 0]
                v = a * uv[f[0], 1] + b * uv[f[1], 1] + c * uv[f[2], 1]
                th, tw = tex.shape[:2]
                albedo = tex[np.clip((v * th).astype(int), 0, th - 1), np.clip((u * tw).astype(int), 0, tw - 1)]
            else:
                albedo = np.broadcast_to(flat, (len(a), 3))
            color[y0:y1, x0:x1][closer] = albedo * lit[:, None]
    return color


def save(name: str, node: Node) -> Path:
    OUT.mkdir(parents=True, exist_ok=True)
    views = [render(node, view=v) for v in ("front", "side", "top")]
    image = np.concatenate(views, axis=1)
    path = OUT / f"{name}.png"
    Image.fromarray((np.clip(image, 0, 1) * 255).astype(np.uint8)).save(path)
    return path
