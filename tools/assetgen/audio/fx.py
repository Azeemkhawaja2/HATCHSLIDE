"""Mix and master processing: Freeverb, gentle compression, a look-ahead limiter and BS.1770 loudness."""
from __future__ import annotations

import numpy as np
from scipy import ndimage, signal

from .synth import SR

# Freeverb tunings (samples at 44.1 kHz), scaled to SR; the right channel is offset by 23 samples.
_COMBS = [1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617]
_ALLPASSES = [556, 441, 341, 225]
_SPREAD = 23


def _comb(x: np.ndarray, delay: int, feedback: float, damp: float) -> np.ndarray:
    """Freeverb lowpass-feedback comb, processed one delay-length block at a time.

    y[n] = x[n] + feedback * f[n],  f[n] = (1 - damp) * y[n - delay] + damp * f[n - 1]; output y[n - delay].
    Within a block every y[n - delay] is already known, so only the one-pole filter runs sequentially.
    """
    n = len(x)
    y = np.zeros(n + delay)
    zi = np.zeros(1)
    for start in range(0, n, delay):
        end = min(start + delay, n)
        past = y[start:end]  # y[n - delay] lives at index n in the padded buffer
        f, zi = signal.lfilter([1 - damp], [1, -damp], past, zi=zi)
        y[start + delay:end + delay] = x[start:end] + feedback * f
    return y[:n]


def _allpass(x: np.ndarray, delay: int, feedback: float = 0.5) -> np.ndarray:
    n = len(x)
    buf = np.zeros(n + delay)
    out = np.zeros(n)
    for start in range(0, n, delay):
        end = min(start + delay, n)
        past = buf[start:end]
        out[start:end] = past - x[start:end]
        buf[start + delay:end + delay] = x[start:end] + feedback * past
    return out


def freeverb(stereo: np.ndarray, room: float = 0.78, damp: float = 0.35, width: float = 1.0) -> np.ndarray:
    """Wet-only Freeverb of a (n, 2) signal: eight parallel combs and four series allpasses per channel."""
    scale = SR / 44100.0
    mono = stereo.mean(axis=1) * 0.03
    feedback = room * 0.28 + 0.7
    wet = []
    for spread in (0, _SPREAD):
        acc = np.zeros(len(mono))
        for d in _COMBS:
            acc += _comb(mono, int((d + spread) * scale), feedback, damp)
        for d in _ALLPASSES:
            acc = _allpass(acc, int((d + spread) * scale))
        wet.append(acc)
    left, right = wet
    wet1 = width / 2 + 0.5
    wet2 = (1 - width) / 2
    return np.stack([left * wet1 + right * wet2, right * wet1 + left * wet2], axis=1).astype(np.float32)


def compress(x: np.ndarray, threshold_db: float = -18.0, ratio: float = 2.0, attack: float = 0.01, release: float = 0.25) -> np.ndarray:
    """Gentle feed-forward RMS compressor (stereo-linked)."""
    level = np.maximum(np.abs(x).max(axis=1), 1e-9)
    power = level**2
    smooth = signal.lfilter([1 - np.exp(-1 / (SR * 0.02))], [1, -np.exp(-1 / (SR * 0.02))], power)
    db = 10 * np.log10(np.maximum(smooth, 1e-12))
    over = np.maximum(db - threshold_db, 0)
    gain_db = -over * (1 - 1 / ratio)
    # Attack/release ballistics: fast to reduce gain, slow to recover.
    a = np.exp(-1 / (SR * attack))
    r = np.exp(-1 / (SR * release))
    target = gain_db
    fast = signal.lfilter([1 - a], [1, -a], target)
    slow = signal.lfilter([1 - r], [1, -r], target)
    gain = 10 ** (np.minimum(fast, slow) / 20)
    return (x * gain[:, None]).astype(np.float32)


def limit(x: np.ndarray, ceiling_db: float = -1.0, lookahead: float = 0.005) -> np.ndarray:
    """Look-ahead peak limiter: the gain needed at each peak is held over the look-ahead window and smoothed."""
    ceiling = 10 ** (ceiling_db / 20)
    peak = np.maximum(np.abs(x).max(axis=1), 1e-9)
    need = np.minimum(1.0, ceiling / peak)
    window = max(1, int(lookahead * SR))
    held = ndimage.minimum_filter1d(need, size=2 * window + 1, mode="nearest")
    smooth = ndimage.uniform_filter1d(held, size=window, mode="nearest")
    gain = np.minimum(smooth, held)
    return np.clip(x * gain[:, None], -ceiling, ceiling).astype(np.float32)


def loudness(x: np.ndarray) -> float:
    """Integrated loudness in LUFS (ITU-R BS.1770-4, 48 kHz K-weighting, gated)."""
    stage1 = signal.lfilter([1.53512485958697, -2.69169618940638, 1.19839281085285], [1.0, -1.69065929318241, 0.73248077421585], x, axis=0)
    weighted = signal.lfilter([1.0, -2.0, 1.0], [1.0, -1.99004745483398, 0.99007225036621], stage1, axis=0)
    block = int(0.4 * SR)
    step = block // 4
    squares = weighted**2
    cumulative = np.concatenate([np.zeros((1, x.shape[1])), np.cumsum(squares, axis=0)])
    starts = np.arange(0, len(x) - block + 1, step)
    if len(starts) == 0:
        return -70.0
    z = (cumulative[starts + block] - cumulative[starts]) / block
    power = z.sum(axis=1)
    lufs = -0.691 + 10 * np.log10(np.maximum(power, 1e-12))
    gated = power[lufs > -70.0]
    if len(gated) == 0:
        return -70.0
    relative = -0.691 + 10 * np.log10(gated.mean()) - 10.0
    final = power[(lufs > -70.0) & (lufs > relative)]
    return float(-0.691 + 10 * np.log10(final.mean()))


def normalise(x: np.ndarray, target_lufs: float) -> np.ndarray:
    return (x * 10 ** ((target_lufs - loudness(x)) / 20)).astype(np.float32)


def pan_gains(pan: float) -> tuple[float, float]:
    """Constant-power pan: -1 left, 0 centre, 1 right."""
    angle = (pan + 1) * np.pi / 4
    return float(np.cos(angle)), float(np.sin(angle))


def master(mix: np.ndarray, target_lufs: float = -14.0) -> np.ndarray:
    """Gentle compression, loudness normalisation to [target_lufs], then a -1 dBFS limiter."""
    y = compress(mix)
    y = normalise(y, target_lufs)
    return limit(y, -1.0)
