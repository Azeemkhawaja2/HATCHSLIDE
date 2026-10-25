"""Parametric mesh toolkit for the model library (Y up, models face -Z, 1 unit = 1 board cell)."""
from __future__ import annotations

from dataclasses import dataclass, field

import numpy as np


@dataclass
class Part:
    """One primitive: positions, normals, UVs, per-vertex colour (baked AO), triangles, material name."""

    positions: np.ndarray
    normals: np.ndarray
    uvs: np.ndarray
    faces: np.ndarray
    material: str
    colors: np.ndarray | None = None

    def copy(self) -> "Part":
        return Part(
            self.positions.copy(), self.normals.copy(), self.uvs.copy(), self.faces.copy(), self.material,
            None if self.colors is None else self.colors.copy(),
        )

    @property
    def triangle_count(self) -> int:
        return len(self.faces)


@dataclass
class Node:
    name: str
    parts: list[Part] = field(default_factory=list)
    translation: tuple[float, float, float] = (0.0, 0.0, 0.0)
    rotation: tuple[float, float, float, float] = (0.0, 0.0, 0.0, 1.0)  # quaternion x, y, z, w
    scale: tuple[float, float, float] = (1.0, 1.0, 1.0)
    children: list["Node"] = field(default_factory=list)

    def triangles(self) -> int:
        return sum(p.triangle_count for p in self.parts) + sum(c.triangles() for c in self.children)


# ---------------------------------------------------------------- helpers


def smooth_normals(positions: np.ndarray, faces: np.ndarray, weld: bool = False) -> np.ndarray:
    """Area-weighted vertex normals. [weld] shares normals between coincident vertices (UV seams, poles);
    use it only on single-sided surfaces, since coincident back-to-back sheets would cancel out."""
    normals = np.zeros_like(positions)
    a, b, c = positions[faces[:, 0]], positions[faces[:, 1]], positions[faces[:, 2]]
    fn = np.cross(b - a, c - a)
    for i in range(3):
        np.add.at(normals, faces[:, i], fn)
    if weld:
        key = np.round(positions / 1e-5).astype(np.int64)
        _, inverse = np.unique(key, axis=0, return_inverse=True)
        inverse = inverse.reshape(-1)
        shared = np.zeros((inverse.max() + 1, 3), positions.dtype)
        np.add.at(shared, inverse, normals)
        normals = shared[inverse]
    length = np.linalg.norm(normals, axis=1, keepdims=True)
    return (normals / np.maximum(length, 1e-9)).astype(np.float32)


def grid_faces(rows: int, cols: int, wrap_cols: bool = False) -> np.ndarray:
    """Triangles for a (rows+1) x (cols+1) vertex grid (or cols columns when wrapping)."""
    stride = cols if wrap_cols else cols + 1
    faces = []
    for r in range(rows):
        for c in range(cols):
            a = r * stride + c
            b = r * stride + ((c + 1) % cols if wrap_cols else c + 1)
            d = (r + 1) * stride + c
            e = (r + 1) * stride + ((c + 1) % cols if wrap_cols else c + 1)
            faces.append((a, d, b))
            faces.append((b, d, e))
    return np.array(faces, np.int64)


def transform(part: Part, translate=(0, 0, 0), scale=(1, 1, 1), rotate_y: float = 0.0, rotate_x: float = 0.0, rotate_z: float = 0.0) -> Part:
    p = part.copy()
    s = np.array(scale, np.float32)

    def rot(axis, angle):
        c, sn = np.cos(angle), np.sin(angle)
        if axis == "x":
            return np.array([[1, 0, 0], [0, c, -sn], [0, sn, c]], np.float32)
        if axis == "y":
            return np.array([[c, 0, sn], [0, 1, 0], [-sn, 0, c]], np.float32)
        return np.array([[c, -sn, 0], [sn, c, 0], [0, 0, 1]], np.float32)

    m = rot("y", rotate_y) @ rot("x", rotate_x) @ rot("z", rotate_z)
    p.positions = (p.positions * s) @ m.T + np.array(translate, np.float32)
    n = (p.normals / s) @ m.T
    p.normals = (n / np.maximum(np.linalg.norm(n, axis=1, keepdims=True), 1e-9)).astype(np.float32)
    return p


def merge(parts: list[Part], material: str | None = None) -> Part:
    offset = 0
    pos, nrm, uv, col, faces = [], [], [], [], []
    for p in parts:
        pos.append(p.positions)
        nrm.append(p.normals)
        uv.append(p.uvs)
        col.append(p.colors if p.colors is not None else np.ones((len(p.positions), 4), np.float32))
        faces.append(p.faces + offset)
        offset += len(p.positions)
    return Part(np.concatenate(pos), np.concatenate(nrm), np.concatenate(uv), np.concatenate(faces), material or parts[0].material, np.concatenate(col))


def atlas_cell(part: Part, index: int, grid: int = 4, inset: float = 0.03) -> Part:
    """Maps UVs (0..1) into cell [index] of a grid x grid atlas, leaving an inset against bleeding."""
    p = part.copy()
    r, c = divmod(index, grid)
    size = 1.0 / grid
    u = np.clip(p.uvs[:, 0], 0, 1) * size * (1 - 2 * inset) + c * size + size * inset
    v = np.clip(p.uvs[:, 1], 0, 1) * size * (1 - 2 * inset) + r * size + size * inset
    p.uvs = np.stack([u, v], axis=1).astype(np.float32)
    return p


def bake_ao(part: Part, floor: float = 0.0, strength: float = 0.55, crevice: float = 0.25) -> Part:
    """Stylised baked AO in vertex colour: darker near the ground and in downward-facing crevices."""
    p = part.copy()
    height = p.positions[:, 1] - floor
    span = max(float(height.max()), 1e-3)
    ground = 1.0 - strength * np.exp(-np.clip(height, 0, None) / (span * 0.35))
    down = 1.0 - crevice * np.clip(-p.normals[:, 1], 0, 1)
    ao = np.clip(ground * down, 0.25, 1.0)
    p.colors = np.stack([ao, ao, ao, np.ones_like(ao)], axis=1).astype(np.float32)
    return p


def periodic_noise3(points: np.ndarray, seed: int, frequency: float, octaves: int = 3) -> np.ndarray:
    """Smooth value noise in 3D via sums of random sinusoids (cheap, deterministic, no lattice seams)."""
    rng = np.random.default_rng(seed)
    total = np.zeros(len(points), np.float32)
    amp = 1.0
    norm = 0.0
    f = frequency
    for _ in range(octaves):
        for _ in range(6):
            direction = rng.normal(size=3)
            direction /= np.linalg.norm(direction)
            phase = rng.uniform(0, 2 * np.pi)
            total += amp * np.sin(points @ direction * f * 2 * np.pi + phase).astype(np.float32)
        norm += amp * 6
        amp *= 0.5
        f *= 2.1
    return total / norm * 2.2


def flipped(part: Part) -> Part:
    """The same surface facing the other way (for inner walls and undersides)."""
    p = part.copy()
    p.faces = p.faces[:, [0, 2, 1]]
    p.normals = -p.normals
    return p


def compact(part: Part) -> Part:
    """Drops vertices no triangle references."""
    used = np.unique(part.faces)
    remap = np.full(len(part.positions), -1, np.int64)
    remap[used] = np.arange(len(used))
    return Part(
        part.positions[used], part.normals[used], part.uvs[used], remap[part.faces], part.material,
        None if part.colors is None else part.colors[used],
    )


def shelled(part: Part, thickness: float) -> Part:
    """A thin-walled version of an open surface: the outside plus an inset, inward-facing inside."""
    inner = flipped(part)
    inner.positions = part.positions - part.normals * thickness
    return merge([part, inner])


def displace(part: Part, seed: int, amount: float, frequency: float, octaves: int = 3) -> Part:
    """Pushes a single-sided surface along its normals by smooth noise."""
    p = part.copy()
    n = periodic_noise3(p.positions, seed, frequency, octaves)
    p.positions = p.positions + p.normals * (n * amount)[:, None]
    p.normals = smooth_normals(p.positions, p.faces, weld=True)
    return p


# ---------------------------------------------------------------- primitives


def ellipsoid(rx: float, ry: float, rz: float, segments: int, rings: int, material: str, uv_mode: str = "sphere") -> Part:
    phi = np.linspace(0, np.pi, rings + 1)
    theta = np.linspace(0, 2 * np.pi, segments + 1)
    t, p = np.meshgrid(theta, phi)
    x = np.sin(p) * np.cos(t)
    y = np.cos(p)
    z = np.sin(p) * np.sin(t)
    positions = np.stack([x * rx, y * ry, z * rz], axis=-1).reshape(-1, 3).astype(np.float32)
    if uv_mode == "planar":
        uvs = np.stack([x * 0.5 + 0.5, z * 0.5 + 0.5], axis=-1).reshape(-1, 2)
    else:
        uvs = np.stack([t / (2 * np.pi), p / np.pi], axis=-1).reshape(-1, 2)
    faces = grid_faces(rings, segments)[:, [0, 2, 1]]
    part = Part(positions, np.zeros_like(positions), uvs.astype(np.float32), faces, material)
    part.normals = smooth_normals(positions, faces, weld=True)
    return part


def lathe(profile: list[tuple[float, float]], segments: int, material: str) -> Part:
    """Surface of revolution around Y from a (radius, y) profile.

    Normals point to the right of the walking direction: outward for a silhouette walked bottom to top,
    up for a surface walked from its rim inward to the axis.
    """
    prof = np.array(profile, np.float32)
    theta = np.linspace(0, 2 * np.pi, segments + 1)
    rows = len(prof) - 1
    positions = []
    uvs = []
    lengths = np.concatenate([[0], np.cumsum(np.linalg.norm(np.diff(prof, axis=0), axis=1))])
    lengths /= max(lengths[-1], 1e-6)
    for i, (r, y) in enumerate(prof):
        for t in theta:
            positions.append((r * np.cos(t), y, r * np.sin(t)))
            uvs.append((t / (2 * np.pi), 1 - lengths[i]))
    positions = np.array(positions, np.float32)
    faces = grid_faces(rows, segments)
    part = Part(positions, np.zeros_like(positions), np.array(uvs, np.float32), faces, material)
    part.normals = smooth_normals(part.positions, part.faces, weld=True)
    return part


def tube(path: np.ndarray, radii: np.ndarray, segments: int, material: str, cap: bool = True) -> Part:
    """Swept circle along [path] (N, 3) with per-point radius, using parallel-transport frames."""
    path = np.asarray(path, np.float32)
    n = len(path)
    tangents = np.gradient(path, axis=0)
    tangents /= np.maximum(np.linalg.norm(tangents, axis=1, keepdims=True), 1e-9)
    ref = np.array([0, 0, 1], np.float32) if abs(tangents[0][2]) < 0.9 else np.array([1, 0, 0], np.float32)
    normal = np.cross(tangents[0], ref)
    normal /= np.linalg.norm(normal)
    frames = []
    for i in range(n):
        if i > 0:
            normal = normal - tangents[i] * np.dot(normal, tangents[i])
            normal /= max(np.linalg.norm(normal), 1e-9)
        binormal = np.cross(tangents[i], normal)
        frames.append((normal.copy(), binormal))
    theta = np.linspace(0, 2 * np.pi, segments + 1)
    positions, uvs = [], []
    lengths = np.concatenate([[0], np.cumsum(np.linalg.norm(np.diff(path, axis=0), axis=1))])
    for i in range(n):
        nn, bb = frames[i]
        for t in theta:
            offset = (np.cos(t) * nn + np.sin(t) * bb) * radii[i]
            positions.append(path[i] + offset)
            uvs.append((t / (2 * np.pi), lengths[i] / max(lengths[-1], 1e-6)))
    positions = np.array(positions, np.float32)
    faces = grid_faces(n - 1, segments)
    part = Part(positions, np.zeros_like(positions), np.array(uvs, np.float32), faces, material)
    if cap:
        # Rounded tips at both ends (the start is usually buried, but driftwood and legs show it).
        tip = len(part.positions)
        last = (n - 1) * (segments + 1)
        tips = np.concatenate([path[-1:] + tangents[-1:] * radii[-1] * 0.4, path[:1] - tangents[:1] * radii[0] * 0.4])
        part.positions = np.concatenate([part.positions, tips]).astype(np.float32)
        part.uvs = np.concatenate([part.uvs, [[0.5, 1.0], [0.5, 0.0]]]).astype(np.float32)
        end_faces = [(last + s, tip, last + s + 1) for s in range(segments)]
        start_faces = [(s + 1, tip + 1, s) for s in range(segments)]
        part.faces = np.concatenate([part.faces, np.array(end_faces + start_faces)])
    part.faces = part.faces[:, [0, 2, 1]]
    part.normals = smooth_normals(part.positions, part.faces, weld=True)
    return part


def leaf(length: float, width: float, segments: int, bend: float, material: str, twist: float = 0.0) -> Part:
    """A curved strip (palm frond, flipper, wing) along -Z with a gentle downward bend."""
    rows = segments
    cols = 4
    positions, uvs = [], []
    for r in range(rows + 1):
        t = r / rows
        z = -t * length
        y = -bend * t * t
        w = width * np.sin(np.pi * np.clip(t * 0.92 + 0.08, 0, 1)) * (1 - 0.3 * t)
        for c in range(cols + 1):
            s = c / cols * 2 - 1
            x = s * w
            yy = y - abs(s) * w * 0.25 + np.sin(twist * t) * s * w * 0.3
            positions.append((x, yy, z))
            uvs.append((c / cols, t))
    positions = np.array(positions, np.float32)
    faces = grid_faces(rows, cols)
    front = Part(positions, np.zeros_like(positions), np.array(uvs, np.float32), faces, material)
    front.normals = smooth_normals(front.positions, front.faces)
    back = front.copy()
    back.faces = back.faces[:, [0, 2, 1]]
    back.positions = back.positions - front.normals * 0.004
    back.normals = -front.normals
    return merge([front, back])


def rounded_box(sx: float, sy: float, sz: float, radius: float, material: str, segments: int = 4) -> Part:
    """Box with rounded edges (via a cube-sphere blend), UV from the top projection."""
    n = segments * 2 + 4
    parts = []
    for axis in range(3):
        for sign in (-1, 1):
            u = np.linspace(-1, 1, n + 1)
            uu, vv = np.meshgrid(u, u)
            pts = np.zeros(uu.shape + (3,), np.float32)
            a1, a2 = [i for i in range(3) if i != axis]
            pts[..., axis] = sign
            pts[..., a1] = uu
            pts[..., a2] = vv
            pts = pts.reshape(-1, 3)
            half = np.array([sx, sy, sz], np.float32) / 2
            inner = half - radius
            # Point on the rounded box: clamp to inner box, then push out by radius along the direction.
            p = pts * half
            clamped = np.clip(p, -inner, inner)
            d = p - clamped
            dl = np.maximum(np.linalg.norm(d, axis=1, keepdims=True), 1e-9)
            pos = clamped + d / dl * radius
            faces = grid_faces(n, n)
            if (sign > 0) == (axis == 1) or (axis == 0 and sign < 0) or (axis == 2 and sign < 0):
                faces = faces[:, [0, 2, 1]]
            uvs = np.stack([pos[:, 0] / sx + 0.5, pos[:, 2] / sz + 0.5], axis=1)
            part = Part(pos.astype(np.float32), np.zeros_like(pos), uvs.astype(np.float32), faces, material)
            parts.append(part)
    box = merge(parts)
    # Weld the shared edge vertices so normals are smooth across faces.
    key = np.round(box.positions / 1e-4).astype(np.int64)
    _, index, inverse = np.unique(key, axis=0, return_index=True, return_inverse=True)
    box.positions = box.positions[index]
    box.uvs = box.uvs[index]
    box.colors = box.colors[index]
    box.faces = inverse.reshape(-1)[box.faces]
    box.faces = box.faces[(box.faces[:, 0] != box.faces[:, 1]) & (box.faces[:, 1] != box.faces[:, 2]) & (box.faces[:, 0] != box.faces[:, 2])]
    box.normals = smooth_normals(box.positions, box.faces)
    # Orient all faces outward.
    centre = box.positions[box.faces].mean(axis=1)
    fn = np.cross(box.positions[box.faces[:, 1]] - box.positions[box.faces[:, 0]], box.positions[box.faces[:, 2]] - box.positions[box.faces[:, 0]])
    flip = (fn * centre).sum(axis=1) < 0
    box.faces[flip] = box.faces[flip][:, [0, 2, 1]]
    box.normals = smooth_normals(box.positions, box.faces)
    return box


def heightfield(size: float, cells: int, height_fn, material: str) -> Part:
    """Square grid of side [size] centred on the origin; height_fn(x, z) -> y."""
    axis = np.linspace(-size / 2, size / 2, cells + 1)
    xx, zz = np.meshgrid(axis, axis)
    yy = height_fn(xx, zz)
    positions = np.stack([xx, yy, zz], axis=-1).reshape(-1, 3).astype(np.float32)
    uvs = np.stack([(xx / size + 0.5), (zz / size + 0.5)], axis=-1).reshape(-1, 2).astype(np.float32)
    faces = grid_faces(cells, cells)
    part = Part(positions, np.zeros_like(positions), uvs, faces, material)
    part.normals = smooth_normals(positions, faces, weld=True)
    return part
