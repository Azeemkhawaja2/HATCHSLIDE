"""The HATCHSLIDE model library. Each builder returns a Node tree for a level of detail (0 or 1).

Materials are bound by name at runtime (core/render ModelArt): turtle_shell, turtle_skin, turtle_eye,
eye_glint, rock, basalt, shells (2x2 atlas: scallop, conch, cowrie, cracked), props (4x4 atlas, see
textures.generators.PROP_CELLS), sand, pit, current, pool_water, lantern_glow, lava.
"""
from __future__ import annotations

import numpy as np

from ..textures.generators import PROP_CELLS
from .mesh import (
    Node,
    Part,
    atlas_cell,
    bake_ao,
    compact,
    displace,
    ellipsoid,
    flipped,
    grid_faces,
    heightfield,
    lathe,
    leaf,
    merge,
    rounded_box,
    shelled,
    smooth_normals,
    transform,
    tube,
)


def cell(name: str) -> int:
    return PROP_CELLS.index(name)


def q_y(angle: float) -> tuple[float, float, float, float]:
    return (0.0, float(np.sin(angle / 2)), 0.0, float(np.cos(angle / 2)))


def q_x(angle: float) -> tuple[float, float, float, float]:
    return (float(np.sin(angle / 2)), 0.0, 0.0, float(np.cos(angle / 2)))


#: Eyelid hinge angles about the lid's local X axis (the runtime blinks between them).
LID_OPEN = 1.35
LID_CLOSED = -1.5


def res(lod: int, high: int, low: int) -> int:
    return high if lod == 0 else low


# ---------------------------------------------------------------- hatchling (hero)


def _carapace(lod: int) -> Part:
    rings = res(lod, 26, 10)
    segs = res(lod, 64, 22)
    rx, ry, rz = 0.25, 0.15, 0.31
    positions, uvs = [], []
    for i in range(rings + 1):
        r = i / rings
        for j in range(segs + 1):
            t = 2 * np.pi * j / segs
            s = np.sin(t)
            tail = 1.0 + 0.14 * max(-s, 0) * r
            scallop = 1.0 + 0.025 * np.cos(t * 13) * r ** 6
            x = rx * r * np.cos(t) * scallop
            z = -rz * r * s * tail * scallop
            dome = ry * (1 - r ** 2.2) ** 0.75
            keel = 0.012 * np.exp(-((x / 0.05) ** 2)) * (1 - r)
            positions.append((x, dome + keel - 0.02 * r ** 8, z))
            uvs.append((x / (2 * rx) + 0.5, z / (2 * rz * 1.14) + 0.5))
    positions = np.array(positions, np.float32)
    faces = grid_faces(rings, segs)
    part = Part(positions, np.zeros_like(positions), np.array(uvs, np.float32), faces, "turtle_shell")
    part.normals = smooth_normals(positions, faces)
    # Underside fan from the outer ring so the shell reads as solid from low angles.
    ring = positions[rings * (segs + 1):]
    under_pos = np.concatenate([ring, [[0.0, -0.01, 0.0]]]).astype(np.float32)
    centre = len(ring)
    under_faces = np.array([(j, j + 1, centre) for j in range(segs)])
    under_uvs = np.concatenate([np.array(uvs[rings * (segs + 1):], np.float32), [[0.5, 0.5]]]).astype(np.float32)
    under = Part(under_pos, np.zeros_like(under_pos), under_uvs, under_faces, "turtle_shell")
    under.normals = smooth_normals(under.positions, under.faces)
    if under.normals[centre, 1] > 0:
        under = flipped(under)
    return merge([part, under], "turtle_shell")


def hatchling(lod: int) -> Node:
    seg = res(lod, 28, 12)
    ring = res(lod, 16, 8)
    shell = Node("shell", [bake_ao(_carapace(lod), floor=-0.18, strength=0.25)], translation=(0, 0.17, 0.04))
    body = Node("body", [bake_ao(transform(ellipsoid(0.22, 0.075, 0.27, seg, ring, "turtle_skin"), translate=(0, 0.1, 0.03)))])
    head_shape = ellipsoid(0.11, 0.095, 0.13, seg, ring, "turtle_skin")
    head_shape = transform(head_shape, translate=(0, 0.02, -0.09))
    neck = tube(np.array([[0, -0.01, 0.06], [0, 0.0, 0.0], [0, 0.01, -0.05]]), np.array([0.07, 0.065, 0.06]), seg // 2, "turtle_skin", cap=False)
    beak = transform(ellipsoid(0.05, 0.035, 0.04, seg // 2, ring // 2, "turtle_skin"), translate=(0, -0.005, -0.21))
    eye_r = 0.052
    eyes, glints, lids = [], [], []
    # Eyelid: a 100-degree cap hinged about its local X axis; open tucks it back into the head.
    cap_profile = [(eye_r * 1.1 * np.sin(np.radians(a)), eye_r * 1.1 * np.cos(np.radians(a))) for a in np.linspace(100, 0, res(lod, 9, 4))]
    for side, name in ((-1, "l"), (1, "r")):
        eye_pos = (side * 0.068, 0.055, -0.13)
        eyes.append(transform(ellipsoid(eye_r, eye_r, eye_r * 0.9, seg, ring, "turtle_eye"), translate=eye_pos))
        glints.append(transform(ellipsoid(0.014, 0.014, 0.01, 8, 6, "eye_glint"), translate=(eye_pos[0] - side * 0.012, eye_pos[1] + 0.02, eye_pos[2] - 0.042)))
        cap = lathe(cap_profile, seg, "turtle_skin")
        lid = Node(f"lid_{name}", [cap], rotation=q_x(LID_OPEN))
        lids.append(Node(f"eye_{name}", translation=eye_pos, rotation=q_y(-side * 0.52), children=[lid]))
    head = Node(
        "head",
        [merge([head_shape, neck, beak], "turtle_skin"), merge(eyes, "turtle_eye"), merge(glints, "eye_glint")],
        translation=(0, 0.15, -0.28),
        children=lids,
    )
    flippers = []
    for name, x, z, length, width, yaw in (
        ("flipper_fl", -0.19, -0.14, 0.26, 0.065, 1.15),
        ("flipper_fr", 0.19, -0.14, 0.26, 0.065, -1.15),
        ("flipper_bl", -0.15, 0.2, 0.14, 0.05, 2.3),
        ("flipper_br", 0.15, 0.2, 0.14, 0.05, -2.3),
    ):
        blade = leaf(length, width, res(lod, 12, 5), 0.02, "turtle_skin")
        blade = transform(blade, rotate_y=yaw)
        flippers.append(Node(name, [blade], translation=(x, 0.1, z)))
    tail = Node("tail", [transform(tube(np.array([[0, 0, 0], [0, -0.01, 0.06], [0, -0.03, 0.1]]), np.array([0.03, 0.02, 0.004]), 8, "turtle_skin"), translate=(0, 0.1, 0.3))])
    return Node("hatchling", children=[body, shell, head, tail] + flippers, scale=(0.85, 0.85, 0.85))


# ---------------------------------------------------------------- eggs


def egg(lod: int) -> Node:
    seg, ring = res(lod, 28, 12), res(lod, 20, 10)
    shell = ellipsoid(0.2, 0.26, 0.2, seg, ring, "props")
    # Split along a zigzag crack line so the top can pop off when the hatchling emerges.
    y = shell.positions[:, 1]
    angle = np.arctan2(shell.positions[:, 2], shell.positions[:, 0])
    crack = 0.05 + 0.025 * np.sign(np.sin(angle * 7))
    top_faces = [f for f in shell.faces if y[f].mean() > crack[f].mean()]
    bottom_faces = [f for f in shell.faces if y[f].mean() <= crack[f].mean()]
    top = shell.copy()
    top.faces = np.array(top_faces)
    bottom = shell.copy()
    bottom.faces = np.array(bottom_faces)
    top = atlas_cell(shelled(compact(top), 0.012), cell("egg_shell"))
    bottom = atlas_cell(shelled(compact(bottom), 0.012), cell("egg_shell"))
    return Node(
        "egg",
        children=[
            Node("egg_bottom", [bake_ao(transform(bottom, translate=(0, 0.26, 0)))]),
            Node("egg_top", [transform(top, translate=(0, 0.0, 0))], translation=(0, 0.26, 0)),
        ],
    )


# ---------------------------------------------------------------- rocks and shells

ROCK_SHAPES = [
    (0.36, 0.3, 0.34, 11), (0.4, 0.26, 0.32, 12), (0.33, 0.36, 0.3, 13),
    (0.42, 0.32, 0.38, 14), (0.3, 0.28, 0.36, 15), (0.38, 0.34, 0.3, 16),
]


def rock(index: int, lod: int, material: str = "rock", scale: float = 1.0) -> Node:
    rx, ry, rz, seed = ROCK_SHAPES[index]
    part = ellipsoid(rx * scale, ry * scale, rz * scale, res(lod, 40, 14), res(lod, 26, 9), material)
    part = displace(part, seed, 0.07 * scale, 1.6 / scale, octaves=res(lod, 4, 2))
    part.positions[:, 1] = np.where(part.positions[:, 1] < 0, part.positions[:, 1] * 0.35, part.positions[:, 1])
    part.positions[:, 1] += ry * scale * 0.3
    part.normals = smooth_normals(part.positions, part.faces)
    part.uvs = part.uvs * np.array([2.0, 1.0], np.float32)
    return Node(f"rock_{index}", [bake_ao(part, strength=0.5)])


def shell_scallop(lod: int) -> Node:
    rows, cols = res(lod, 22, 9), res(lod, 52, 18)
    positions, uvs = [], []
    for i in range(rows + 1):
        r = i / rows
        for j in range(cols + 1):
            th = np.radians(-72 + 144 * j / cols)
            rib = 0.012 * np.cos(th * 26) * r
            x = r * np.sin(th) * 0.32
            z = -r * np.cos(th) * 0.32 + 0.14
            y = 0.075 * (1 - r ** 2) ** 0.6 + rib + 0.01
            positions.append((x, y, z))
            uvs.append((x / 0.64 + 0.5, 1 - r))
    positions = np.array(positions, np.float32)
    faces = grid_faces(rows, cols)[:, [0, 2, 1]]
    top = Part(positions, np.zeros_like(positions), np.array(uvs, np.float32), faces, "shells")
    top.normals = smooth_normals(positions, faces)
    under = top.copy()
    under.positions[:, 1] = 0.005
    under = flipped(under)
    under.normals = smooth_normals(under.positions, under.faces)
    shell = atlas_cell(merge([top, under]), 0, grid=2)
    return Node("shell_scallop", [bake_ao(shell, strength=0.3)])


def shell_conch(lod: int) -> Node:
    """Conical helicospiral: whorls grow exponentially and overlap, ending in an open aperture."""
    turns = 4.5
    n = res(lod, 96, 32)
    growth = np.log(2.0) / (2 * np.pi)  # each whorl twice the size of the one before
    u = np.linspace(-turns * 2 * np.pi, 0.0, n)
    scale = np.exp(growth * u)
    path = np.stack([0.1 * scale * np.cos(u), -0.2 * scale, 0.1 * scale * np.sin(u)], axis=1)
    flare = 1.0 + 0.35 * np.clip((u + 1.2) / 1.2, 0.0, 1.0) ** 2
    radii = 0.088 * scale * flare
    body = tube(path, radii, res(lod, 20, 9), "shells", cap=False)
    body = displace(body, 21, 0.004, 14.0, octaves=2)
    body.uvs = np.stack([body.uvs[:, 1], body.uvs[:, 0]], axis=1)
    shell = shelled(body, 0.008)
    # Lay it on its side with the aperture facing the camera-left, resting on the ground.
    shell = transform(shell, rotate_z=np.pi / 2 - 0.25, rotate_y=0.6)
    shell.positions[:, 1] -= shell.positions[:, 1].min()
    return Node("shell_conch", [bake_ao(atlas_cell(shell, 1, grid=2), strength=0.35)])


def _cowrie(lod: int, index: int, seed: int) -> Part:
    dome = ellipsoid(0.17, 0.12, 0.25, res(lod, 32, 12), res(lod, 18, 8), "shells", uv_mode="planar")
    dome.positions[:, 1] = np.maximum(dome.positions[:, 1], 0.0) + 0.004
    dome.normals = smooth_normals(dome.positions, dome.faces)
    if index == 3:
        dome = displace(dome, seed, 0.006, 9.0, 2)
    return atlas_cell(dome, index, grid=2)


def shell_cowrie(lod: int) -> Node:
    return Node("shell_cowrie", [bake_ao(_cowrie(lod, 2, 31), strength=0.3)])


def shell_cracked(lod: int) -> Node:
    part = transform(_cowrie(lod, 3, 41), scale=(1.25, 1.1, 1.2))
    return Node("shell_cracked", [bake_ao(part, strength=0.3)])


# ---------------------------------------------------------------- pieces and hazards


def coconut(lod: int) -> Node:
    part = ellipsoid(0.26, 0.24, 0.27, res(lod, 28, 12), res(lod, 18, 8), "props")
    part = displace(part, 51, 0.012, 7.0, 2)
    part = transform(part, translate=(0, 0.24, 0))
    return Node("coconut", [bake_ao(atlas_cell(part, cell("coconut_husk")))])


def crab(lod: int) -> Node:
    seg, ring = res(lod, 24, 10), res(lod, 14, 7)
    body = displace(ellipsoid(0.26, 0.11, 0.2, seg, ring, "props"), 61, 0.012, 5.0, 2)
    body = atlas_cell(transform(body, translate=(0, 0.15, 0)), cell("crab_shell"))
    stalks = []
    eyes = []
    for side in (-1, 1):
        stalks.append(tube(np.array([[side * 0.07, 0.2, -0.12], [side * 0.08, 0.27, -0.15]]), np.array([0.018, 0.015]), 8, "props"))
        eyes.append(transform(ellipsoid(0.03, 0.03, 0.03, 10, 8, "turtle_eye"), translate=(side * 0.08, 0.29, -0.155)))
    legs = []
    for side in (-1, 1):
        for k, z in enumerate((-0.04, 0.05, 0.13)):
            path = np.array([[side * 0.2, 0.14, z], [side * 0.33, 0.16, z + 0.02], [side * 0.4, 0.02, z + 0.05]])
            legs.append(tube(path, np.array([0.03, 0.022, 0.01]), 8, "props"))
    limbs = atlas_cell(merge(stalks + legs, "props"), cell("crab_shell"))
    claws = []
    for side, name in ((-1, "l"), (1, "r")):
        arm = tube(np.array([[0, 0, 0], [side * 0.08, 0.02, -0.06], [side * 0.1, 0.03, -0.12]]), np.array([0.035, 0.03, 0.028]), 10, "props")
        upper = transform(ellipsoid(0.08, 0.055, 0.1, seg // 2, ring // 2, "props"), translate=(side * 0.11, 0.05, -0.2))
        lower = transform(ellipsoid(0.06, 0.03, 0.08, seg // 2, ring // 2, "props"), translate=(0, -0.025, -0.04))
        claws.append(
            Node(
                f"claw_{name}",
                [atlas_cell(merge([arm, upper], "props"), cell("crab_shell"))],
                translation=(side * 0.2, 0.13, -0.12),
                children=[Node(f"pincer_{name}", [atlas_cell(lower, cell("crab_shell"))], translation=(side * 0.11, 0.03, -0.18))],
            )
        )
    return Node("crab", [bake_ao(body), bake_ao(limbs), merge(eyes, "turtle_eye")], children=claws)


def pit(lod: int) -> Node:
    seg = res(lod, 48, 18)
    rim = lathe([(0.5, -0.015), (0.43, 0.03), (0.33, 0.025), (0.27, -0.02)], seg, "sand")
    rim = displace(rim, 71, 0.008, 6.0, 2)
    hole = lathe([(0.29, -0.015), (0.25, -0.06), (0.12, -0.17), (0.0, -0.22)], seg, "pit")
    # Shown instead of the hole once a coconut fills the pit: a low mound of fresh sand.
    fill = lathe([(0.3, -0.012), (0.24, 0.006), (0.12, 0.016), (0.0, 0.018)], seg, "sand")
    fill = displace(fill, 73, 0.004, 9.0, 2)
    return Node(
        "pit",
        children=[
            Node("rim", [bake_ao(rim, floor=-0.02, strength=0.3)]),
            Node("hole", [hole]),
            Node("fill", [bake_ao(fill, floor=-0.02, strength=0.2)]),
        ],
    )


def current(lod: int) -> Node:
    slab = rounded_box(0.94, 0.03, 0.94, 0.012, "current", segments=res(lod, 2, 1))
    slab = transform(slab, translate=(0, 0.0, 0))
    return Node("current", [slab])


def tide_pool(lod: int) -> Node:
    seg = res(lod, 48, 18)
    basin = lathe([(0.48, 0.0), (0.44, 0.04), (0.37, 0.015), (0.3, -0.05), (0.0, -0.07)], seg, "rock")
    basin = displace(basin, 81, 0.01, 5.0, 2)
    water = lathe([(0.36, 0.0), (0.0, 0.0)], seg, "pool_water")
    water.normals[:] = (0, 1, 0)
    pebbles = []
    rng = np.random.default_rng(83)
    for k in range(res(lod, 9, 5)):
        a = 2 * np.pi * k / res(lod, 9, 5) + rng.uniform(-0.2, 0.2)
        s = rng.uniform(0.04, 0.07)
        p = ellipsoid(s, s * 0.7, s * 1.1, 10, 6, "rock")
        pebbles.append(transform(p, translate=(np.cos(a) * 0.44, 0.04, np.sin(a) * 0.44), rotate_y=a))
    return Node("tide_pool", [bake_ao(merge([basin] + pebbles, "rock"), floor=-0.07, strength=0.35), water])


def seagull(lod: int) -> Node:
    seg, ring = res(lod, 24, 10), res(lod, 14, 7)
    body = atlas_cell(ellipsoid(0.12, 0.1, 0.3, seg, ring, "props"), cell("gull_feathers"))
    head = atlas_cell(transform(ellipsoid(0.08, 0.075, 0.085, seg, ring, "props"), translate=(0, 0.08, -0.27)), cell("gull_feathers"))
    beak = atlas_cell(tube(np.array([[0, 0.07, -0.33], [0, 0.06, -0.4], [0, 0.05, -0.44]]), np.array([0.025, 0.015, 0.004]), 8, "props"), cell("coral_orange"))
    eyes = [transform(ellipsoid(0.014, 0.014, 0.014, 8, 6, "turtle_eye"), translate=(s * 0.055, 0.1, -0.31)) for s in (-1, 1)]
    tail = atlas_cell(transform(leaf(0.18, 0.07, 6, 0.01, "props"), rotate_y=np.pi, translate=(0, 0.02, 0.24)), cell("gull_feathers"))
    wings = []
    for side, name in ((-1, "wing_l"), (1, "wing_r")):
        blade = leaf(0.58, 0.13, res(lod, 14, 6), 0.06, "props", twist=0.6)
        blade = transform(blade, rotate_y=-side * np.pi / 2)
        blade = atlas_cell(blade, cell("gull_feathers"))
        wings.append(Node(name, [blade], translation=(side * 0.09, 0.06, -0.02)))
    return Node("seagull", [merge([body, head, tail], "props"), beak, merge(eyes, "turtle_eye")], children=wings)


# ---------------------------------------------------------------- decor


def palm(variant: int, lod: int) -> Node:
    n = res(lod, 18, 7)
    height = 2.3 if variant == 0 else 1.8
    lean = 0.45 if variant == 0 else -0.3
    path = np.array([[lean * (t ** 2), height * t, 0.12 * np.sin(t * 2.0)] for t in np.linspace(0, 1, n)])
    radii = np.linspace(0.11, 0.07, n)
    trunk = atlas_cell(tube(path, radii, res(lod, 12, 6), "props", cap=False), cell("palm_bark"))
    top = path[-1]
    fronds = []
    count = res(lod, 9, 6)
    for k in range(count):
        yaw = 2 * np.pi * k / count + variant * 0.3
        blade = leaf(1.15, 0.2, res(lod, 12, 5), 0.55, "props", twist=0.4)
        blade = transform(blade, rotate_x=0.45, rotate_y=yaw, translate=tuple(top))
        fronds.append(atlas_cell(blade, cell("palm_frond")))
    nuts = [atlas_cell(transform(ellipsoid(0.07, 0.07, 0.07, 10, 7, "props"), translate=(top[0] + np.cos(a) * 0.09, top[1] - 0.08, top[2] + np.sin(a) * 0.09)), cell("coconut_husk")) for a in (0.3, 2.4, 4.4)]
    return Node(f"palm_{variant}", [bake_ao(trunk, strength=0.4), merge(fronds, "props"), merge(nuts, "props")])


def driftwood(variant: int, lod: int) -> Node:
    rng = np.random.default_rng(91 + variant)
    n = res(lod, 14, 6)
    length = 1.3 if variant == 0 else 0.9
    path = np.array([[t * length - length / 2, 0.06 + 0.04 * np.sin(t * 3 + variant), 0.1 * np.sin(t * 4)] for t in np.linspace(0, 1, n)])
    main = tube(path, np.linspace(0.07, 0.035, n), res(lod, 10, 6), "props")
    parts = [main]
    for b in range(2 if variant == 0 else 1):
        start = path[rng.integers(2, n - 3)]
        branch = np.array([start, start + (0.15, 0.1, 0.25 * (1 if b else -1)), start + (0.25, 0.12, 0.4 * (1 if b else -1))])
        parts.append(tube(branch, np.array([0.035, 0.025, 0.012]), 8, "props"))
    wood = displace(merge(parts, "props"), 93 + variant, 0.006, 8.0, 2)
    return Node(f"driftwood_{variant}", [bake_ao(atlas_cell(wood, cell("driftwood")))])


def umbrella(color: str, lod: int) -> Node:
    seg = res(lod, 32, 12)
    pole = atlas_cell(tube(np.array([[0, 0, 0], [0, 0.8, 0], [0, 1.5, 0]]), np.array([0.025, 0.022, 0.02]), 8, "props"), cell("wood_plank"))
    canopy = lathe([(0.8, 1.15), (0.78, 1.18), (0.4, 1.38), (0.0, 1.5)], seg, "props")
    angle = np.arctan2(canopy.positions[:, 2], canopy.positions[:, 0])
    radial = np.linalg.norm(canopy.positions[:, [0, 2]], axis=1)
    canopy.positions[:, 1] += 0.03 * np.abs(np.sin(angle * 4)) * (radial / 0.8) ** 2
    canopy.normals = smooth_normals(canopy.positions, canopy.faces)
    cloth = atlas_cell(merge([canopy, flipped(canopy)]), cell(f"umbrella_{color}"))
    return Node(f"umbrella_{color}", [pole, cloth])


def lantern(lod: int) -> Node:
    seg = res(lod, 24, 10)
    post = atlas_cell(tube(np.array([[0, 0, 0], [0, 0.6, 0], [0.12, 0.8, 0], [0.22, 0.78, 0]]), np.array([0.025, 0.022, 0.02, 0.015]), 8, "props"), cell("wood_plank"))
    body = lathe([(0.02, 0.38), (0.11, 0.42), (0.13, 0.52), (0.11, 0.62), (0.02, 0.66)], seg, "props")
    body = atlas_cell(transform(body, translate=(0.22, 0.0, 0)), cell("lantern_paper"))
    glow = transform(ellipsoid(0.08, 0.1, 0.08, 12, 8, "lantern_glow"), translate=(0.22, 0.52, 0))
    string = atlas_cell(tube(np.array([[0.22, 0.79, 0], [0.22, 0.72, 0], [0.22, 0.65, 0]]), np.full(3, 0.005), 6, "props"), cell("wood_plank"))
    return Node("lantern", [bake_ao(merge([post, string], "props")), body, glow])


def coral(kind: str, lod: int) -> Node:
    rng = np.random.default_rng({"branch": 101, "brain": 103, "fan": 105}[kind])
    if kind == "brain":
        dome = ellipsoid(0.3, 0.2, 0.3, res(lod, 32, 12), res(lod, 18, 8), "props")
        dome.positions[:, 1] = np.maximum(dome.positions[:, 1], 0)
        dome = displace(dome, 107, 0.02, 10.0, 2)
        return Node("coral_brain", [bake_ao(atlas_cell(dome, cell("coral_pink")))])
    parts = []
    if kind == "fan":
        # Sea fan: a flat lattice of forking branches in one plane.
        def fan(start, angle, length, radius, depth):
            end = start + np.array([np.cos(angle), np.sin(angle), rng.normal(scale=0.02)]) * length
            parts.append(tube(np.array([start, (start + end) / 2, end]), np.array([radius, radius * 0.9, radius * 0.8]), res(lod, 6, 4), "props"))
            if depth == 0:
                return
            for spread in (-0.38, 0.38):
                fan(end, angle + spread + rng.normal(scale=0.08), length * 0.78, radius * 0.78, depth - 1)

        fan(np.zeros(3), np.pi / 2, 0.16, 0.03, res(lod, 5, 3))
        return Node("coral_fan", [bake_ao(atlas_cell(merge(parts, "props"), cell("coral_orange")))])

    def grow(start, direction, length, radius, depth):
        end = start + direction * length
        mid = (start + end) / 2 + rng.normal(scale=0.03, size=3)
        parts.append(tube(np.array([start, mid, end]), np.array([radius, radius * 0.85, radius * 0.7]), res(lod, 8, 5), "props"))
        if depth == 0:
            return
        for _ in range(2):
            d = direction + rng.normal(scale=0.5, size=3)
            d[1] = abs(d[1]) + 0.4
            grow(end, d / np.linalg.norm(d), length * 0.72, radius * 0.7, depth - 1)

    grow(np.zeros(3), np.array([0, 1.0, 0]), 0.28, 0.06, res(lod, 3, 2))
    return Node("coral_branch", [bake_ao(atlas_cell(merge(parts, "props"), cell("coral_pink")))])


def starfish(lod: int) -> Node:
    rows, cols = res(lod, 10, 4), res(lod, 80, 30)
    positions, uvs = [], []
    for i in range(rows + 1):
        r = i / rows
        for j in range(cols + 1):
            t = 2 * np.pi * j / cols
            arm = 0.35 + 0.65 * (0.5 + 0.5 * np.cos(5 * t)) ** 1.5
            radius = 0.28 * r * arm
            y = 0.05 * (1 - r ** 1.5) * (0.6 + 0.4 * arm)
            positions.append((radius * np.cos(t), y + 0.005, radius * np.sin(t)))
            uvs.append((0.5 + 0.5 * r * np.cos(t), 0.5 + 0.5 * r * np.sin(t)))
    positions = np.array(positions, np.float32)
    faces = grid_faces(rows, cols)
    faces = faces[:, [0, 2, 1]]
    part = Part(positions, np.zeros_like(positions), np.array(uvs, np.float32), faces, "props")
    part.normals = smooth_normals(positions, faces)
    return Node("starfish", [bake_ao(atlas_cell(part, cell("starfish")), strength=0.25)])


def cliff_rock(index: int, lod: int) -> Node:
    node = rock(index, lod, material="rock", scale=2.2)
    node.name = f"cliff_{index}"
    return node


def sandcastle(lod: int) -> Node:
    seg = res(lod, 20, 8)
    parts = []
    for x, z, h, r in ((-0.22, -0.1, 0.42, 0.1), (0.22, -0.1, 0.36, 0.09), (0.0, 0.18, 0.5, 0.12)):
        tower = lathe([(r * 1.15, 0.0), (r, h * 0.9), (r * 1.12, h * 0.92), (r * 1.12, h), (r * 0.8, h), (r * 0.8, h - 0.03), (0.0, h - 0.03)], seg * 2, "sand")
        # Crenellations: notch the parapet ring in six places.
        angle = np.arctan2(tower.positions[:, 2], tower.positions[:, 0])
        parapet = (tower.positions[:, 1] > h - 1e-4)
        notch = np.sin(angle * 6) > 0.35
        tower.positions[parapet & notch, 1] -= 0.035
        tower.normals = smooth_normals(tower.positions, tower.faces, weld=True)
        parts.append(transform(tower, translate=(x, 0, z)))
    wall = rounded_box(0.5, 0.2, 0.08, 0.02, "sand", segments=res(lod, 2, 1))
    parts.append(transform(wall, translate=(0, 0.1, -0.1)))
    castle = displace(merge(parts, "sand"), 111, 0.006, 9.0, 2)
    flag_pole = atlas_cell(tube(np.array([[0, 0.5, 0.18], [0, 0.72, 0.18]]), np.array([0.008, 0.006]), 6, "props"), cell("wood_plank"))
    flag = atlas_cell(transform(leaf(0.12, 0.05, 4, 0.0, "props"), rotate_y=np.pi / 2, translate=(0, 0.68, 0.18)), cell("umbrella_coral"))
    return Node("sandcastle", [bake_ao(castle, strength=0.4), merge([flag_pole, flag], "props")])


def nest(lod: int) -> Node:
    seg = res(lod, 40, 16)
    mound = lathe([(0.85, 0.0), (0.6, 0.06), (0.42, 0.12), (0.22, 0.05), (0.0, 0.03)], seg, "sand")
    mound = displace(mound, 121, 0.012, 5.0, 2)
    eggs = []
    for k, (x, z) in enumerate(((-0.12, -0.05), (0.1, -0.08), (0.0, 0.12))):
        e = ellipsoid(0.12, 0.15, 0.12, res(lod, 20, 10), res(lod, 12, 6), "props")
        eggs.append(transform(e, translate=(x, 0.17, z), rotate_z=0.15 * (k - 1)))
    return Node("nest", [bake_ao(mound, strength=0.35), atlas_cell(merge(eggs, "props"), cell("egg_shell"))])


def pebbles(lod: int) -> Node:
    rng = np.random.default_rng(131)
    parts = []
    for _ in range(res(lod, 9, 5)):
        s = rng.uniform(0.04, 0.09)
        p = ellipsoid(s, s * 0.6, s * rng.uniform(0.8, 1.3), 10, 7, "props")
        p = transform(p, translate=(rng.uniform(-0.35, 0.35), s * 0.5, rng.uniform(-0.35, 0.35)), rotate_y=rng.uniform(0, 6))
        parts.append(atlas_cell(p, cell("pebbles")))
    return Node("pebbles", [bake_ao(merge(parts, "props"), strength=0.3)])


def bucket(lod: int) -> Node:
    seg = res(lod, 28, 12)
    pail = lathe([(0.0, 0.0), (0.16, 0.0), (0.2, 0.3), (0.21, 0.31), (0.19, 0.31), (0.15, 0.02), (0.0, 0.02)], seg, "props")
    pail = atlas_cell(pail, cell("umbrella_teal"))
    handle = tube(np.array([[-0.2, 0.28, 0], [-0.12, 0.45, 0], [0.0, 0.5, 0], [0.12, 0.45, 0], [0.2, 0.28, 0]]), np.full(5, 0.008), 6, "props", cap=False)
    return Node("bucket", [bake_ao(pail, strength=0.3), atlas_cell(handle, cell("wood_plank"))])


# ---------------------------------------------------------------- archipelago islands (world map)

ISLANDS = {
    1: dict(seed=141, peak=0.8, stretch=(1.15, 0.85), cove=True, volcano=False, reef=False, pools=False),
    2: dict(seed=143, peak=0.5, stretch=(1.0, 1.0), cove=False, volcano=False, reef=True, pools=False),
    3: dict(seed=145, peak=0.9, stretch=(0.85, 1.2), cove=True, volcano=False, reef=False, pools=False),
    4: dict(seed=147, peak=1.0, stretch=(1.2, 0.9), cove=False, volcano=False, reef=False, pools=True),
    5: dict(seed=149, peak=1.6, stretch=(1.0, 1.0), cove=False, volcano=True, reef=False, pools=False),
}


def island(number: int, lod: int) -> Node:
    cfg = ISLANDS[number]
    rng = np.random.default_rng(cfg["seed"])
    noise_dirs = rng.normal(size=(12, 2))
    noise_phase = rng.uniform(0, 2 * np.pi, 12)

    sx, sz = cfg["stretch"]

    def height(x, z):
        x = x / sx
        z = z / sz
        r = np.sqrt(x * x + z * z)
        n = sum(np.sin((x * d[0] + z * d[1]) * (1.5 + i * 0.4) + p) for i, (d, p) in enumerate(zip(noise_dirs, noise_phase))) / 12.0
        base = np.clip(1.0 - (r / (2.2 + 0.4 * n)) ** 2.2, 0.0, 1.0)
        h = base * cfg["peak"] * (0.8 + 0.4 * n) - 0.12 - 0.4 * np.clip((r - 2.0 - 0.3 * n) / 0.7, 0.0, 1.0)
        if cfg["cove"]:
            cove = np.exp(-(((x - 0.9) ** 2 + (z + 0.6) ** 2) / 0.5))
            h -= cove * 0.5
        if cfg["volcano"]:
            h += np.exp(-(r ** 2) / 0.9) * 0.6
            h -= np.exp(-(r ** 2) / 0.08) * 0.9
        if cfg["pools"]:
            h -= 0.12 * np.exp(-(((x + 0.6) ** 2 + (z - 0.4) ** 2) / 0.05))
        if cfg["reef"]:
            h += 0.18 * np.exp(-(((r - 1.9) / 0.18) ** 2))
        return np.maximum(h, -0.45)

    field = heightfield(6.0, res(lod, 72, 26), height, "sand")
    y = field.positions[:, 1]
    # Drop the deep sea floor so the coastline, not a square, is what shows through the water.
    field.faces = field.faces[y[field.faces].max(axis=1) > -0.3]
    face_height = y[field.faces].mean(axis=1)
    threshold = 0.32 if not cfg["volcano"] else 0.25
    sand = field.copy()
    sand.faces = field.faces[face_height < threshold]
    sand = compact(sand)
    rocky = field.copy()
    rocky.material = "basalt" if cfg["volcano"] else "rock"
    rocky.faces = field.faces[face_height >= threshold]
    rocky = compact(rocky) if len(rocky.faces) else rocky
    parts = [bake_ao(sand, floor=-0.25, strength=0.3)]
    if len(rocky.faces):
        parts.append(bake_ao(rocky, floor=-0.25, strength=0.3))
    children = []
    if cfg["volcano"]:
        lava = lathe([(0.25, 0.0), (0.0, 0.0)], 24, "lava")
        lava.normals[:] = (0, 1, 0)
        children.append(Node("lava", [lava], translation=(0, float(height(np.array(0.0), np.array(0.0))) + 0.05, 0)))
    else:
        for k in range(res(lod, 4, 2)):
            a = rng.uniform(0, 2 * np.pi)
            rr = rng.uniform(0.6, 1.4)
            x, z = np.cos(a) * rr, np.sin(a) * rr
            tree = palm(k % 2, 1)
            tree.name = f"island_palm_{k}"
            tree.translation = (float(x), float(height(np.array(x), np.array(z))), float(z))
            tree.scale = (0.45, 0.45, 0.45)
            children.append(tree)
    return Node(f"island_{number}", parts, children=children)


# ---------------------------------------------------------------- registry

MODELS = {
    "hatchling": hatchling,
    "egg": egg,
    **{f"rock_{i}": (lambda lod, i=i: rock(i, lod)) for i in range(6)},
    "shell_scallop": shell_scallop,
    "shell_conch": shell_conch,
    "shell_cowrie": shell_cowrie,
    "shell_cracked": shell_cracked,
    "coconut": coconut,
    "crab": crab,
    "pit": pit,
    "current": current,
    "tide_pool": tide_pool,
    "seagull": seagull,
    "palm_0": lambda lod: palm(0, lod),
    "palm_1": lambda lod: palm(1, lod),
    "driftwood_0": lambda lod: driftwood(0, lod),
    "driftwood_1": lambda lod: driftwood(1, lod),
    "umbrella_coral": lambda lod: umbrella("coral", lod),
    "umbrella_teal": lambda lod: umbrella("teal", lod),
    "lantern": lantern,
    "coral_branch": lambda lod: coral("branch", lod),
    "coral_brain": lambda lod: coral("brain", lod),
    "coral_fan": lambda lod: coral("fan", lod),
    "starfish": starfish,
    "cliff_0": lambda lod: cliff_rock(3, lod),
    "cliff_1": lambda lod: cliff_rock(1, lod),
    **{f"island_{n}": (lambda lod, n=n: island(n, lod)) for n in range(1, 6)},
    "sandcastle": sandcastle,
    "nest": nest,
    "pebbles": pebbles,
    "bucket": bucket,
}
