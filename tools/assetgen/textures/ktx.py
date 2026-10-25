"""Mipmapped KTX1 writer with ASTC and ETC2 payloads (the role of Filament's mipgen).

Images are float32 arrays in 0..1, top row first (glTF/Filament UV convention: v = 0 is the first row).
Colour textures are sRGB-encoded and mip-filtered in linear light; normal maps are renormalised per mip.
"""
from __future__ import annotations

import struct
from dataclasses import dataclass
from pathlib import Path

import astc_encoder as astc
import etcpak
import numpy as np

KTX_IDENTIFIER = bytes([0xAB, 0x4B, 0x54, 0x58, 0x20, 0x31, 0x31, 0xBB, 0x0D, 0x0A, 0x1A, 0x0A])

GL_RGB = 0x1907
GL_RGBA = 0x1908
GL_SRGB = 0x8C40
GL_SRGB_ALPHA = 0x8C42
# ETC2
GL_COMPRESSED_RGB8_ETC2 = 0x9274
GL_COMPRESSED_SRGB8_ETC2 = 0x9275
GL_COMPRESSED_RGBA8_ETC2_EAC = 0x9278
GL_COMPRESSED_SRGB8_ALPHA8_ETC2_EAC = 0x9279
# ASTC (linear base 0x93B0, sRGB base 0x93D0), index by block size.
ASTC_INDEX = {(4, 4): 0, (5, 4): 1, (5, 5): 2, (6, 5): 3, (6, 6): 4, (8, 5): 5, (8, 6): 6, (8, 8): 7}


@dataclass(frozen=True)
class Encoding:
    """How one texture is stored: [kind] is 'color', 'linear' or 'normal'; alpha keeps the A channel."""

    kind: str
    astc_block: tuple[int, int] = (6, 6)
    alpha: bool = False
    mips: bool = True
    #: Largest stored level; bigger top mips are dropped (0 = keep everything).
    max_size: int = 0

    @property
    def srgb(self) -> bool:
        return self.kind == "color"


def srgb_to_linear(c: np.ndarray) -> np.ndarray:
    return np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4).astype(np.float32)


def linear_to_srgb(c: np.ndarray) -> np.ndarray:
    c = np.clip(c, 0.0, 1.0)
    return np.where(c <= 0.0031308, c * 12.92, 1.055 * np.power(c, 1.0 / 2.4) - 0.055).astype(np.float32)


def _downsample(img: np.ndarray) -> np.ndarray:
    h, w = img.shape[:2]
    if h == 1 and w == 1:
        return img
    h2, w2 = max(h // 2, 1), max(w // 2, 1)
    if h == 1 or w == 1:
        return img.reshape(h2, h // h2, w2, w // w2, -1).mean(axis=(1, 3))
    return img.reshape(h2, 2, w2, 2, -1).mean(axis=(1, 3))


def mip_chain(image: np.ndarray, encoding: Encoding) -> list[np.ndarray]:
    """Full mip chain (down to 1x1). Expects an (H, W, C) float array, C in {3, 4}."""
    levels = [image]
    if not encoding.mips:
        return levels
    work = image.copy()
    if encoding.srgb:
        work[..., :3] = srgb_to_linear(work[..., :3])
    while work.shape[0] > 1 or work.shape[1] > 1:
        work = _downsample(work)
        level = work.copy()
        if encoding.kind == "normal":
            n = level[..., :3] * 2.0 - 1.0
            n /= np.maximum(np.linalg.norm(n, axis=-1, keepdims=True), 1e-6)
            level[..., :3] = n * 0.5 + 0.5
        elif encoding.srgb:
            level[..., :3] = linear_to_srgb(level[..., :3])
        levels.append(level)
    return levels


def _to_rgba8(level: np.ndarray) -> np.ndarray:
    h, w = level.shape[:2]
    rgba = np.ones((h, w, 4), np.float32)
    rgba[..., : level.shape[2]] = level
    return np.ascontiguousarray((np.clip(rgba, 0.0, 1.0) * 255.0 + 0.5).astype(np.uint8))


def _pad_to_block(rgba: np.ndarray, block: int) -> np.ndarray:
    h, w = rgba.shape[:2]
    ph = (block - h % block) % block
    pw = (block - w % block) % block
    if ph == 0 and pw == 0:
        return rgba
    return np.pad(rgba, ((0, ph), (0, pw), (0, 0)), mode="edge")


_ASTC_CONTEXTS: dict = {}


def _astc_context(block: tuple[int, int], srgb: bool, normal: bool):
    key = (block, srgb, normal)
    if key not in _ASTC_CONTEXTS:
        profile = astc.ASTCProfile.LDR_SRGB if srgb else astc.ASTCProfile.LDR
        config = astc.ASTCConfig(profile, block[0], block[1], 1, 60.0)
        _ASTC_CONTEXTS[key] = astc.ASTCContext(config, 1)
    return _ASTC_CONTEXTS[key]


def encode_astc(level: np.ndarray, encoding: Encoding) -> bytes:
    rgba = _to_rgba8(level)
    h, w = rgba.shape[:2]
    ctx = _astc_context(encoding.astc_block, encoding.srgb, encoding.kind == "normal")
    image = astc.ASTCImage(astc.ASTCType.U8, w, h, 1, rgba.tobytes())
    return bytes(ctx.compress(image, astc.ASTCSwizzle.from_str("RGBA")))


def encode_etc2(level: np.ndarray, encoding: Encoding) -> bytes:
    rgba = _pad_to_block(_to_rgba8(level), 4)
    h, w = rgba.shape[:2]
    data = rgba.tobytes()
    if encoding.alpha:
        return bytes(etcpak.compress_etc2_rgba(data, w, h))
    return bytes(etcpak.compress_etc2_rgb(data, w, h))


def _internal_format(fmt: str, encoding: Encoding) -> tuple[int, int]:
    if fmt == "astc":
        index = ASTC_INDEX[encoding.astc_block]
        internal = (0x93D0 if encoding.srgb else 0x93B0) + index
        return internal, (GL_SRGB_ALPHA if encoding.srgb else GL_RGBA)
    if encoding.alpha:
        return (GL_COMPRESSED_SRGB8_ALPHA8_ETC2_EAC if encoding.srgb else GL_COMPRESSED_RGBA8_ETC2_EAC), (GL_SRGB_ALPHA if encoding.srgb else GL_RGBA)
    return (GL_COMPRESSED_SRGB8_ETC2 if encoding.srgb else GL_COMPRESSED_RGB8_ETC2), (GL_SRGB if encoding.srgb else GL_RGB)


def write_ktx(path: Path, faces_levels: list[list[np.ndarray]], encoding: Encoding, fmt: str) -> int:
    """Writes a KTX1 file. [faces_levels] is one mip list per face (6 for a cubemap). Returns bytes written."""
    internal, base = _internal_format(fmt, encoding)
    encode = encode_astc if fmt == "astc" else encode_etc2
    if encoding.max_size:
        while len(faces_levels[0]) > 1 and faces_levels[0][0].shape[1] > encoding.max_size:
            faces_levels = [levels[1:] for levels in faces_levels]
    faces = len(faces_levels)
    levels = len(faces_levels[0])
    h, w = faces_levels[0][0].shape[:2]
    header = KTX_IDENTIFIER + struct.pack(
        "<13I", 0x04030201, 0, 1, 0, internal, base, w, h, 0, 0, faces, levels, 0
    )
    chunks = [header]
    for level in range(levels):
        encoded = [encode(faces_levels[f][level], encoding) for f in range(faces)]
        size = len(encoded[0])
        chunks.append(struct.pack("<I", size))
        for data in encoded:
            chunks.append(data)
            pad = (4 - len(data) % 4) % 4
            chunks.append(b"\0" * pad)
    blob = b"".join(chunks)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(blob)
    return len(blob)
