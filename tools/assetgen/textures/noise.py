"""Tileable procedural noise for texture synthesis (all functions wrap at the edges)."""
from __future__ import annotations

import numpy as np
from scipy.ndimage import gaussian_filter, map_coordinates
from scipy.spatial import cKDTree


def band_noise(size: int, low: float, high: float, seed: int, beta: float = 2.0) -> np.ndarray:
    """Tileable noise whose energy lies between spatial frequencies [low, high] (cycles per tile).

    Built in the frequency domain with random phases, so it repeats seamlessly. Returns zero mean,
    unit variance.
    """
    rng = np.random.default_rng(seed)
    f = np.fft.fftfreq(size) * size
    radius = np.sqrt(f[None, :] ** 2 + f[:, None] ** 2)
    with np.errstate(divide="ignore"):
        amp = np.where(radius > 0, radius ** (-beta / 2.0), 0.0)
    # Smooth band edges avoid ringing.
    amp *= 1.0 / (1.0 + np.exp(-(radius - low) * 1.5))
    amp *= 1.0 / (1.0 + np.exp((radius - high) * 0.8))
    phase = rng.uniform(0.0, 2.0 * np.pi, (size, size))
    field = np.real(np.fft.ifft2(amp * np.exp(1j * phase)))
    field -= field.mean()
    std = field.std()
    return (field / std if std > 0 else field).astype(np.float32)


def fbm(size: int, seed: int, base: float = 2.0, octaves: int = 6, gain: float = 0.5) -> np.ndarray:
    """Fractal sum of tileable bands; frequencies are whole cycles per tile so the result tiles."""
    total = np.zeros((size, size), np.float32)
    amplitude = 1.0
    frequency = base
    norm = 0.0
    for octave in range(octaves):
        hi = min(frequency * 2.0, size / 2.0)
        total += amplitude * band_noise(size, frequency, hi, seed + octave * 7919)
        norm += amplitude
        amplitude *= gain
        frequency *= 2.0
        if frequency >= size / 2.0:
            break
    return total / norm


def worley(size: int, points: int, seed: int, k: int = 2) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    """Periodic Worley noise: distances to the nearest and second-nearest feature point and the id of
    the nearest point. Distances are in tile units (0..~0.7)."""
    rng = np.random.default_rng(seed)
    feature = rng.random((points, 2))
    tree = cKDTree(feature, boxsize=1.0)
    axis = (np.arange(size) + 0.5) / size
    gx, gy = np.meshgrid(axis, axis)
    query = np.stack([gx.ravel(), gy.ravel()], axis=1)
    dist, idx = tree.query(query, k=k, workers=-1)
    f1 = dist[:, 0].reshape(size, size).astype(np.float32)
    f2 = dist[:, 1].reshape(size, size).astype(np.float32)
    nearest = idx[:, 0].reshape(size, size)
    return f1, f2, nearest


def warp(field: np.ndarray, dx: np.ndarray, dy: np.ndarray, amount: float) -> np.ndarray:
    """Domain-warps [field] by offsets (in pixels * amount), wrapping at the edges."""
    size = field.shape[0]
    yy, xx = np.mgrid[0:size, 0:size].astype(np.float32)
    coords = np.array([(yy + dy * amount) % size, (xx + dx * amount) % size])
    return map_coordinates(field, coords, order=1, mode="grid-wrap").astype(np.float32)


def remap(x: np.ndarray, lo: float | None = None, hi: float | None = None) -> np.ndarray:
    lo = float(x.min()) if lo is None else lo
    hi = float(x.max()) if hi is None else hi
    return np.clip((x - lo) / max(hi - lo, 1e-6), 0.0, 1.0).astype(np.float32)


def smoothstep(edge0: float, edge1: float, x: np.ndarray) -> np.ndarray:
    t = np.clip((x - edge0) / (edge1 - edge0), 0.0, 1.0)
    return (t * t * (3.0 - 2.0 * t)).astype(np.float32)


def blur_wrap(x: np.ndarray, sigma: float) -> np.ndarray:
    return gaussian_filter(x, sigma, mode="wrap").astype(np.float32)


def normal_from_height(height: np.ndarray, strength: float) -> np.ndarray:
    """Tangent-space normal map (0..1 encoded RGB) from a height field, wrapping at the edges."""
    size = height.shape[0]
    scale = strength * size / 1024.0
    dx = (np.roll(height, -1, axis=1) - np.roll(height, 1, axis=1)) * 0.5 * scale
    dy = (np.roll(height, -1, axis=0) - np.roll(height, 1, axis=0)) * 0.5 * scale
    n = np.stack([-dx, dy, np.ones_like(height)], axis=-1)
    n /= np.linalg.norm(n, axis=-1, keepdims=True)
    return (n * 0.5 + 0.5).astype(np.float32)


def cavity_ao(height: np.ndarray, radius: float, strength: float) -> np.ndarray:
    """Ambient occlusion approximation: points below their blurred neighbourhood are darker."""
    cavity = blur_wrap(height, radius) - height
    return np.clip(1.0 - np.maximum(cavity, 0.0) * strength, 0.25, 1.0).astype(np.float32)


def hex_color(value: int) -> np.ndarray:
    return np.array([((value >> 16) & 255) / 255.0, ((value >> 8) & 255) / 255.0, (value & 255) / 255.0], np.float32)


def lerp_color(a: np.ndarray, b: np.ndarray, t: np.ndarray) -> np.ndarray:
    return (a[None, None, :] * (1.0 - t[..., None]) + b[None, None, :] * t[..., None]).astype(np.float32)


def palette_ramp(t: np.ndarray, stops: list[tuple[float, int]]) -> np.ndarray:
    """Maps t (0..1) through a list of (position, 0xRRGGBB) colour stops."""
    out = np.zeros(t.shape + (3,), np.float32)
    positions = [p for p, _ in stops]
    colors = [hex_color(c) for _, c in stops]
    for c in range(3):
        out[..., c] = np.interp(t, positions, [col[c] for col in colors])
    return out
