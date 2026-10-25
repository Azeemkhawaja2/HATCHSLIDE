"""Minimal glTF 2.0 binary writer: node hierarchy, named materials, POSITION/NORMAL/TEXCOORD_0/COLOR_0."""
from __future__ import annotations

from pathlib import Path

import numpy as np
import pygltflib as gl

from .mesh import Node, Part

ARRAY_BUFFER = 34962
ELEMENT_ARRAY_BUFFER = 34963


class _Builder:
    def __init__(self) -> None:
        self.gltf = gl.GLTF2(asset=gl.Asset(generator="HATCHSLIDE assetgen", version="2.0"))
        self.blob = bytearray()
        self.materials: dict[str, int] = {}

    def _view(self, data: bytes, target: int) -> int:
        while len(self.blob) % 4:
            self.blob.append(0)
        offset = len(self.blob)
        self.blob.extend(data)
        self.gltf.bufferViews.append(gl.BufferView(buffer=0, byteOffset=offset, byteLength=len(data), target=target))
        return len(self.gltf.bufferViews) - 1

    def _accessor(self, array: np.ndarray, kind: str, component: int, target: int, normalized: bool = False, minmax: bool = False) -> int:
        view = self._view(array.tobytes(), target)
        acc = gl.Accessor(bufferView=view, componentType=component, count=len(array), type=kind, normalized=normalized)
        if minmax:
            acc.min = [float(v) for v in array.min(axis=0)]
            acc.max = [float(v) for v in array.max(axis=0)]
        self.gltf.accessors.append(acc)
        return len(self.gltf.accessors) - 1

    def material(self, name: str) -> int:
        if name not in self.materials:
            self.gltf.materials.append(
                gl.Material(name=name, pbrMetallicRoughness=gl.PbrMetallicRoughness(baseColorFactor=[1, 1, 1, 1], metallicFactor=0.0, roughnessFactor=0.7))
            )
            self.materials[name] = len(self.gltf.materials) - 1
        return self.materials[name]

    def mesh(self, name: str, parts: list[Part]) -> int:
        primitives = []
        for part in parts:
            colors = part.colors if part.colors is not None else np.ones((len(part.positions), 4), np.float32)
            attributes = gl.Attributes(
                POSITION=self._accessor(part.positions.astype(np.float32), gl.VEC3, gl.FLOAT, ARRAY_BUFFER, minmax=True),
                NORMAL=self._accessor(part.normals.astype(np.float32), gl.VEC3, gl.FLOAT, ARRAY_BUFFER),
                TEXCOORD_0=self._accessor(part.uvs.astype(np.float32), gl.VEC2, gl.FLOAT, ARRAY_BUFFER),
                COLOR_0=self._accessor((np.clip(colors, 0, 1) * 255 + 0.5).astype(np.uint8), gl.VEC4, gl.UNSIGNED_BYTE, ARRAY_BUFFER, normalized=True),
            )
            if len(part.positions) < 65536:
                indices = self._accessor(part.faces.astype(np.uint16).reshape(-1), gl.SCALAR, gl.UNSIGNED_SHORT, ELEMENT_ARRAY_BUFFER)
            else:
                indices = self._accessor(part.faces.astype(np.uint32).reshape(-1), gl.SCALAR, gl.UNSIGNED_INT, ELEMENT_ARRAY_BUFFER)
            primitives.append(gl.Primitive(attributes=attributes, indices=indices, material=self.material(part.material)))
        self.gltf.meshes.append(gl.Mesh(name=name, primitives=primitives))
        return len(self.gltf.meshes) - 1

    def node(self, node: Node) -> int:
        children = [self.node(child) for child in node.children]
        entry = gl.Node(name=node.name, translation=list(node.translation), rotation=list(node.rotation), scale=list(node.scale), children=children)
        if node.parts:
            entry.mesh = self.mesh(node.name, node.parts)
        self.gltf.nodes.append(entry)
        return len(self.gltf.nodes) - 1


def write_glb(path: Path, root: Node) -> int:
    b = _Builder()
    top = b.node(root)
    b.gltf.scenes.append(gl.Scene(nodes=[top]))
    b.gltf.scene = 0
    while len(b.blob) % 4:
        b.blob.append(0)
    b.gltf.buffers.append(gl.Buffer(byteLength=len(b.blob)))
    b.gltf.set_binary_blob(bytes(b.blob))
    path.parent.mkdir(parents=True, exist_ok=True)
    b.gltf.save_binary(str(path))
    return path.stat().st_size
