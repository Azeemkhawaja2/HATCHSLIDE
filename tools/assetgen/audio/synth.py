"""Instruments for the HATCHSLIDE soundtrack: additive, modal, FM and Karplus-Strong voices.

Every function returns a mono float32 array at SR for one note; the sequencer mixes and pans them.
Frequencies are in Hz, durations in seconds, velocity in 0..1.
"""
from __future__ import annotations

import numpy as np
from scipy import signal

SR = 48000


def midi_hz(note: float) -> float:
    return 440.0 * 2.0 ** ((note - 69.0) / 12.0)


def _t(seconds: float) -> np.ndarray:
    return np.arange(int(seconds * SR)) / SR


def _rng(seed: int) -> np.random.Generator:
    return np.random.default_rng(seed)


def fade(x: np.ndarray, attack: float = 0.002, release: float = 0.01) -> np.ndarray:
    """Short linear fades so no note clicks at its edges."""
    y = x.copy()
    a = min(len(y), max(1, int(attack * SR)))
    r = min(len(y), max(1, int(release * SR)))
    y[:a] *= np.linspace(0.0, 1.0, a)
    y[-r:] *= np.linspace(1.0, 0.0, r)
    return y


def lowpass(x: np.ndarray, cutoff: float, order: int = 2) -> np.ndarray:
    sos = signal.butter(order, min(cutoff, SR * 0.45), "low", fs=SR, output="sos")
    return signal.sosfilt(sos, x).astype(np.float32)


def highpass(x: np.ndarray, cutoff: float, order: int = 2) -> np.ndarray:
    sos = signal.butter(order, cutoff, "high", fs=SR, output="sos")
    return signal.sosfilt(sos, x).astype(np.float32)


def bandpass(x: np.ndarray, low: float, high: float, order: int = 2) -> np.ndarray:
    sos = signal.butter(order, [low, min(high, SR * 0.45)], "band", fs=SR, output="sos")
    return signal.sosfilt(sos, x).astype(np.float32)


def modal(freq: float, partials: list[tuple[float, float, float]], length: float, vel: float, attack: float = 0.001) -> np.ndarray:
    """Sum of exponentially decaying sines: (ratio, amplitude, decay time to -60 dB)."""
    t = _t(length)
    out = np.zeros_like(t)
    for ratio, amp, decay in partials:
        f = freq * ratio
        if f >= SR * 0.45:
            continue
        out += amp * np.sin(2 * np.pi * f * t) * np.exp(-6.9 * t / decay)
    return fade((out * vel).astype(np.float32), attack, 0.02)


def click(length: float, centre: float, width: float, seed: int, level: float) -> np.ndarray:
    noise = _rng(seed).normal(size=int(length * SR)).astype(np.float32)
    burst = bandpass(noise, max(60.0, centre - width), centre + width)
    return burst * np.exp(-np.arange(len(burst)) / (SR * length * 0.25)).astype(np.float32) * level


def _pad_to(x: np.ndarray, n: int) -> np.ndarray:
    return x if len(x) >= n else np.pad(x, (0, n - len(x)))


# ---------------------------------------------------------------- tuned percussion


def marimba(freq: float, dur: float, vel: float, seed: int = 0) -> np.ndarray:
    scale = (220.0 / freq) ** 0.35
    body = modal(freq, [(1.0, 1.0, 1.4 * scale), (3.93, 0.32, 0.28 * scale), (9.2, 0.08, 0.07 * scale)], 1.8 * scale + 0.2, vel)
    mallet = click(0.012, min(freq * 4.0, 6000.0), freq, seed, 0.12 * vel)
    return body + _pad_to(mallet, len(body))[: len(body)]


def glass_marimba(freq: float, dur: float, vel: float, seed: int = 0) -> np.ndarray:
    """Brighter, longer and gently shimmering (two voices a few cents apart)."""
    detune = 2 ** (3.0 / 1200.0)
    partials = [(1.0, 1.0, 2.8), (4.0, 0.3, 0.9), (10.03, 0.1, 0.25)]
    a = modal(freq, partials, 3.2, vel * 0.55)
    b = modal(freq * detune, partials, 3.2, vel * 0.55)
    return a + b


def bass_marimba(freq: float, dur: float, vel: float, seed: int = 0) -> np.ndarray:
    return modal(freq, [(1.0, 1.0, 1.9), (3.93, 0.22, 0.4), (9.2, 0.04, 0.1)], 2.2, vel) + _pad_to(
        click(0.015, freq * 3.0, freq, seed, 0.1 * vel), int(2.2 * SR)
    )[: int(2.2 * SR)]


def kalimba(freq: float, dur: float, vel: float, seed: int = 0) -> np.ndarray:
    t = _t(2.4)
    glide = 1.0 + 0.005 * np.exp(-t / 0.02)
    phase = 2 * np.pi * freq * np.cumsum(glide) / SR
    out = np.sin(phase) * np.exp(-6.9 * t / 2.2)
    out += 0.22 * np.sin(phase * 5.92) * np.exp(-6.9 * t / 0.22)
    out += 0.07 * np.sin(phase * 12.1) * np.exp(-6.9 * t / 0.06)
    out = fade((out * vel).astype(np.float32), 0.0015, 0.03)
    return out + _pad_to(click(0.006, 3500, 1500, seed, 0.08 * vel), len(out))[: len(out)]


def steel_drum(freq: float, dur: float, vel: float, seed: int = 0) -> np.ndarray:
    partials = [(1.0, 1.0, 1.3), (2.0, 0.55, 0.9), (3.01, 0.22, 0.55), (4.02, 0.18, 0.35), (5.04, 0.06, 0.2)]
    a = modal(freq, partials, 1.6, vel * 0.6, attack=0.004)
    b = modal(freq * 2 ** (6 / 1200), partials, 1.6, vel * 0.45, attack=0.004)
    return lowpass(a + b, 5200)


def chime(freq: float, dur: float, vel: float, seed: int = 0) -> np.ndarray:
    return modal(freq, [(1.0, 1.0, 3.6), (2.756, 0.45, 2.2), (5.404, 0.22, 1.2), (8.933, 0.1, 0.6), (13.34, 0.05, 0.3)], 4.0, vel * 0.8)


def woodblock(freq: float, dur: float, vel: float, seed: int = 0) -> np.ndarray:
    body = modal(freq, [(1.0, 1.0, 0.07), (2.71, 0.35, 0.04)], 0.12, vel)
    return body + click(0.12, freq * 2.2, freq, seed, 0.25 * vel)[: len(body)]


# ---------------------------------------------------------------- strings and plucks


def karplus(freq: float, length: float, vel: float, seed: int, brightness: float, decay: float) -> np.ndarray:
    """Karplus-Strong string: a filtered noise burst recirculating in a delay line (vectorised with lfilter)."""
    n = max(2, int(round(SR / freq)))
    total = int(length * SR)
    excite = _rng(seed).uniform(-1, 1, n).astype(np.float64)
    excite = signal.lfilter([brightness], [1, -(1 - brightness)], excite)
    x = np.zeros(total)
    x[:n] = excite
    # Per-period loss so the string fades to -60 dB over [decay] seconds.
    g = 10 ** (-3.0 / (decay * freq))
    a = np.zeros(n + 2)
    a[0] = 1.0
    a[n] = -0.5 * g
    a[n + 1] = -0.5 * g
    y = signal.lfilter([1.0], a, x)
    return fade((y * vel).astype(np.float32), 0.001, 0.03)


def nylon(freq: float, dur: float, vel: float, seed: int = 0) -> np.ndarray:
    raw = karplus(freq, max(dur + 0.6, 1.4), vel * 0.8, seed, 0.35, 2.4)
    return lowpass(raw, 3200 + freq * 2)


def ukulele(freq: float, dur: float, vel: float, seed: int = 0) -> np.ndarray:
    raw = karplus(freq, max(dur + 0.3, 0.9), vel * 0.75, seed, 0.6, 1.1)
    return lowpass(raw, 6000)


# ---------------------------------------------------------------- sustained voices


def _additive_saw(freq: float, t: np.ndarray, harmonics: int, vibrato: float = 0.0) -> np.ndarray:
    phase = 2 * np.pi * freq * t
    if vibrato:
        phase += vibrato * np.sin(2 * np.pi * 5.1 * t) * 2 * np.pi * freq / (2 * np.pi * 5.1) * 0.003
    out = np.zeros_like(t)
    for k in range(1, harmonics + 1):
        if freq * k >= SR * 0.45:
            break
        out += np.sin(k * phase) / k
    return out


def pad(freq: float, dur: float, vel: float, seed: int = 0) -> np.ndarray:
    """Soft, slowly breathing pad: three detuned band-limited saws, rolled off and enveloped."""
    length = dur + 1.6
    t = _t(length)
    out = np.zeros_like(t)
    for cents in (-7.0, 0.0, 7.0):
        out += _additive_saw(freq * 2 ** (cents / 1200.0), t + cents * 0.01, 10)
    attack = 0.8
    env = np.clip(t / attack, 0, 1) ** 1.5 * np.where(t > dur, np.exp(-(t - dur) / 0.45), 1.0)
    out = lowpass((out * env * vel * 0.12).astype(np.float32), 1400 + freq)
    return fade(out, 0.01, 0.05)


def brass_swell(freq: float, dur: float, vel: float, seed: int = 0) -> np.ndarray:
    """FM brass: 1:1 carrier and modulator, the index swelling with the note for a warm horn colour."""
    length = dur + 0.5
    t = _t(length)
    swell = np.clip(t / max(dur * 0.6, 0.2), 0, 1)
    env = swell * np.where(t > dur, np.exp(-(t - dur) / 0.18), 1.0)
    index = 0.6 + 2.6 * swell
    out = np.sin(2 * np.pi * freq * t + index * np.sin(2 * np.pi * freq * t))
    return fade(lowpass((out * env * vel * 0.35).astype(np.float32), 2200), 0.01, 0.05)


def soft_bass(freq: float, dur: float, vel: float, seed: int = 0) -> np.ndarray:
    length = dur + 0.25
    t = _t(length)
    env = np.minimum(t / 0.012, 1.0) * np.exp(-t / (dur * 1.5 + 0.2)) * np.where(t > dur, np.exp(-(t - dur) / 0.08), 1.0)
    out = np.sin(2 * np.pi * freq * t) + 0.25 * np.sin(4 * np.pi * freq * t) + 0.08 * np.sin(6 * np.pi * freq * t)
    return fade((out * env * vel * 0.6).astype(np.float32), 0.004, 0.03)


# ---------------------------------------------------------------- drums


def kick(vel: float, seed: int = 0) -> np.ndarray:
    t = _t(0.45)
    f = 45 + 75 * np.exp(-t / 0.05)
    out = np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t / 0.16)
    return fade((out * vel).astype(np.float32), 0.001, 0.02) + click(0.45, 2500, 1500, seed, 0.05 * vel)


def low_drum(vel: float, seed: int = 0, pitch: float = 72.0) -> np.ndarray:
    """Taiko-like floor drum: a deep pitch drop with a soft skin noise."""
    t = _t(1.0)
    f = pitch * (1 + 0.45 * np.exp(-t / 0.04))
    out = np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t / 0.32)
    skin = lowpass(_rng(seed).normal(size=len(t)).astype(np.float32), 400) * np.exp(-t / 0.08)
    return fade((out * 0.9 + skin * 0.25).astype(np.float32) * vel, 0.002, 0.03)


def shaker(vel: float, seed: int = 0) -> np.ndarray:
    n = int(0.09 * SR)
    noise = _rng(seed).normal(size=n).astype(np.float32)
    t = np.arange(n) / SR
    env = np.minimum(t / 0.008, 1.0) * np.exp(-t / 0.025)
    return bandpass(noise, 5000, 11000) * env.astype(np.float32) * vel * 0.5


def conga(freq: float, vel: float, seed: int = 0) -> np.ndarray:
    t = _t(0.4)
    f = freq * (1 + 0.25 * np.exp(-t / 0.015))
    out = np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t / 0.11)
    slap = click(0.4, 1800, 900, seed, 0.25)
    return fade((out + slap).astype(np.float32) * vel, 0.001, 0.02)


def rim(vel: float, seed: int = 0) -> np.ndarray:
    t = _t(0.08)
    tone = np.sin(2 * np.pi * 820 * t) * np.exp(-t / 0.012)
    return (tone.astype(np.float32) * 0.6 + click(0.08, 2600, 1400, seed, 0.6)) * vel


def clap(vel: float, seed: int = 0) -> np.ndarray:
    n = int(0.25 * SR)
    noise = bandpass(_rng(seed).normal(size=n).astype(np.float32), 900, 3200)
    t = np.arange(n) / SR
    env = np.zeros(n)
    for start in (0.0, 0.011, 0.022):
        env += np.where(t >= start, np.exp(-(t - start) / 0.006), 0) * (t >= start)
    env += np.where(t >= 0.03, np.exp(-(t - 0.03) / 0.06), 0)
    return (noise * env.astype(np.float32) * vel * 0.5).astype(np.float32)


INSTRUMENTS = {
    "marimba": marimba,
    "glass_marimba": glass_marimba,
    "bass_marimba": bass_marimba,
    "kalimba": kalimba,
    "steel_drum": steel_drum,
    "chime": chime,
    "woodblock": woodblock,
    "nylon": nylon,
    "ukulele": ukulele,
    "pad": pad,
    "brass": brass_swell,
    "bass": soft_bass,
}

DRUMS = {
    "kick": lambda vel, seed, pitch: kick(vel, seed),
    "low_drum": lambda vel, seed, pitch: low_drum(vel, seed, pitch or 72.0),
    "shaker": lambda vel, seed, pitch: shaker(vel, seed),
    "conga": lambda vel, seed, pitch: conga(pitch or 220.0, vel, seed),
    "rim": lambda vel, seed, pitch: rim(vel, seed),
    "clap": lambda vel, seed, pitch: clap(vel, seed),
}
