"""Ambience loops (spec section 7): ocean surf, gentle wind, night insects, distant volcanic rumble.

Every layer is built to be periodic over the loop length (noise shaped in the frequency domain of a
circular buffer, envelopes with whole numbers of cycles), so the loops have no seam at all. The loops
are about two minutes long, with irregular, seeded timing (wave spacing and strength, gusts, chirps,
booms), so the repeat is hard to hear.
"""
from __future__ import annotations

import numpy as np

from . import fx
from .synth import SR


def circular_noise(n: int, seed: int, low: float, high: float, tilt: float = -0.5) -> np.ndarray:
    """Periodic noise of n samples, band-limited to [low, high] Hz with a spectral tilt (dB/oct ~ 3*tilt)."""
    rng = np.random.default_rng(seed)
    freqs = np.fft.rfftfreq(n, 1 / SR)
    mag = np.where((freqs >= low) & (freqs <= high), np.maximum(freqs, 1.0) ** tilt, 0.0)
    edge = np.clip((freqs - low) / max(low * 0.3, 1.0), 0, 1) * np.clip((high - freqs) / max(high * 0.2, 1.0), 0, 1)
    spectrum = mag * edge * np.exp(2j * np.pi * rng.random(len(freqs)))
    x = np.fft.irfft(spectrum, n)
    return (x / (np.abs(x).max() + 1e-9)).astype(np.float32)


def periodic(n: int, cycles: float, phase: float = 0.0) -> np.ndarray:
    """0..1 cosine that completes an integer number of cycles over the loop."""
    t = np.arange(n) / n
    return (0.5 - 0.5 * np.cos(2 * np.pi * (cycles * t + phase))).astype(np.float32)


def stereo(left: np.ndarray, right: np.ndarray) -> np.ndarray:
    return np.stack([left, right], axis=1).astype(np.float32)


def surf(seconds: float = 120.0) -> np.ndarray:
    """Waves building, breaking and fizzing out about every 6.7 s, unevenly spaced and of uneven
    strength, each landing slightly left or right."""
    n = int(seconds * SR)
    waves = round(seconds / 6.7)
    rng = np.random.default_rng(11)
    spacing = rng.uniform(-0.3, 0.3, waves)
    strength = rng.uniform(0.7, 1.15, waves)
    side = rng.uniform(-0.06, 0.06, waves)
    t = np.arange(n) / n
    channels = []
    for ch in range(2):
        body = circular_noise(n, 10 + ch, 40, 900, tilt=-0.8)
        hiss = circular_noise(n, 20 + ch, 1500, 9000, tilt=-0.3)
        swell = np.zeros(n, np.float32)
        crash = np.zeros(n, np.float32)
        for w in range(waves):
            centre = (w + 0.5 + spacing[w]) / waves + (side[w] if ch else -side[w]) / waves
            until = (centre - t) % 1.0
            since = (t - centre) % 1.0
            swell += strength[w] * np.where(until < 0.8 / waves, np.exp(-until * waves * 2.0), 0.0).astype(np.float32)
            crash += strength[w] * np.where(since < 1.0 / waves, np.exp(-since * waves * 2.6), 0.0).astype(np.float32)
        channels.append(body * (0.35 + 0.45 * np.minimum(swell + crash, 1.0)) + hiss * (0.05 + 0.35 * np.minimum(crash, 1.2)))
    return fx.normalise(stereo(*channels), -22.0)


def wind(seconds: float = 120.0) -> np.ndarray:
    """Gentle wind with gusts of uneven length and strength (whole cycles, so it still loops)."""
    n = int(seconds * SR)
    channels = []
    for ch in range(2):
        low = circular_noise(n, 30 + ch, 120, 700, tilt=-0.6)
        mid = circular_noise(n, 40 + ch, 500, 2200, tilt=-0.4)
        gust = periodic(n, 9, 0.2 * ch) * 0.4 + periodic(n, 14, 0.37 + ch * 0.1) * 0.35 + periodic(n, 23, 0.71) * 0.25
        whistle = periodic(n, 6, 0.6 + 0.15 * ch) * periodic(n, 5, 0.3)
        channels.append(low * (0.4 + 0.5 * gust) + mid * (0.1 + 0.35 * gust * whistle))
    return fx.normalise(stereo(*channels), -24.0)


def insects(seconds: float = 90.0) -> np.ndarray:
    """Night insects: four crickets chirping at their own rates, each fading in and out over the loop."""
    n = int(seconds * SR)
    t = np.arange(n) / SR
    left = circular_noise(n, 50, 200, 3000, tilt=-0.9) * 0.08
    right = circular_noise(n, 51, 200, 3000, tilt=-0.9) * 0.08
    crickets = [(4600.0, 120, -0.6), (5100.0, 156, 0.5), (4300.0, 102, 0.1), (5600.0, 180, -0.2)]
    for i, (freq, chirps, pan) in enumerate(crickets):
        period = seconds / chirps
        phase = (t / period + i * 0.37) % 1.0
        # Each chirp: three 18 ms pulses at the start of its period.
        pulse = np.zeros(n, np.float32)
        for k in range(3):
            local = (phase * period - k * 0.03) / 0.018
            pulse += np.where((local >= 0) & (local < 1), np.sin(np.pi * np.clip(local, 0, 1)), 0).astype(np.float32)
        tone = np.sin(2 * np.pi * freq * t).astype(np.float32) * pulse * (0.3 + 0.7 * periodic(n, 2 + i, i * 0.2) * periodic(n, 3 + 2 * i, 0.4))
        gl, gr = fx.pan_gains(pan)
        left += tone * gl * 0.25
        right += tone * gr * 0.25
    return fx.normalise(stereo(left, right), -26.0)


def rumble(seconds: float = 120.0) -> np.ndarray:
    """A distant volcano: a deep, slowly breathing roar with an occasional boom."""
    n = int(seconds * SR)
    t = np.arange(n) / SR
    channels = []
    for ch in range(2):
        deep = circular_noise(n, 60 + ch, 18, 110, tilt=-1.0)
        roar = circular_noise(n, 70 + ch, 60, 400, tilt=-1.2) * (0.2 + 0.2 * periodic(n, 11, ch * 0.3) * periodic(n, 7, 0.6))
        channels.append(deep * (0.6 + 0.3 * periodic(n, 5, 0.1)) + roar)
    out = stereo(*channels)
    # Five distant booms per loop at uneven times, each a slow 38 Hz thump.
    for start in (0.08, 0.27, 0.46, 0.71, 0.88):
        local = (t - start * seconds) % seconds
        boom = (np.sin(2 * np.pi * 38 * local) * np.exp(-local / 1.2) * (local < 5)).astype(np.float32)
        out += boom[:, None] * 0.5
    return fx.normalise(out, -24.0)


AMBIENCE = {"surf": surf, "wind": wind, "insects": insects, "rumble": rumble}
