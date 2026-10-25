"""Sound effects (spec section 7): every one synthesised, short and soft-edged for an all-ages game."""
from __future__ import annotations

import numpy as np

from . import synth
from .ambience import circular_noise
from .synth import SR, bandpass, chime, highpass, kalimba, lowpass, marimba, midi_hz


def _t(seconds: float) -> np.ndarray:
    return np.arange(int(seconds * SR)) / SR


def _noise(seconds: float, seed: int) -> np.ndarray:
    return np.random.default_rng(seed).normal(size=int(seconds * SR)).astype(np.float32)


def _env(t: np.ndarray, attack: float, decay: float) -> np.ndarray:
    return (np.minimum(t / max(attack, 1e-4), 1.0) * np.exp(-np.maximum(t - attack, 0) / decay)).astype(np.float32)


def _sweep_noise(seconds: float, f0: float, f1: float, seed: int, q: float = 0.6) -> np.ndarray:
    """Noise through a band-pass whose centre glides from f0 to f1 (processed in short blocks)."""
    x = _noise(seconds, seed)
    out = np.zeros_like(x)
    block = 512
    for i in range(0, len(x), block):
        k = i / len(x)
        centre = f0 * (f1 / f0) ** k
        lo, hi = centre * (1 - q / 2), centre * (1 + q / 2)
        seg = np.concatenate([x[max(0, i - block):i], x[i:i + block]])
        out[i:i + block] = bandpass(seg, max(30.0, lo), hi)[-len(x[i:i + block]):]
    return out


def _tone(freq_fn, seconds: float) -> np.ndarray:
    t = _t(seconds)
    f = freq_fn(t)
    return np.sin(2 * np.pi * np.cumsum(f) / SR).astype(np.float32)


def _mix(*parts: np.ndarray) -> np.ndarray:
    n = max(len(p) for p in parts)
    out = np.zeros(n, np.float32)
    for p in parts:
        out[: len(p)] += p
    return out


def swipe() -> np.ndarray:
    s = _sweep_noise(0.28, 500, 2600, 1)
    return s * _env(_t(0.28), 0.06, 0.08)


def slide_loop() -> np.ndarray:
    """One-second seamless grainy sand hiss; the game loops it and ties its rate to slide speed."""
    n = SR
    grains = circular_noise(n, 2, 900, 6000, tilt=-0.4)
    flutter = (0.6 + 0.4 * np.abs(np.sin(2 * np.pi * 12 * np.arange(n) / n))).astype(np.float32)
    return grains * flutter * 0.6


def stop() -> np.ndarray:
    t = _t(0.22)
    thump = _tone(lambda t: 110 + 60 * np.exp(-t / 0.02), 0.22) * _env(t, 0.002, 0.05)
    sand = lowpass(_noise(0.22, 3), 1800) * _env(t, 0.001, 0.03) * 0.5
    return thump + sand


def bump() -> np.ndarray:
    t = _t(0.25)
    a = _tone(lambda t: np.where(t < 0.1, 330.0, 247.0), 0.25)
    return a * _env(t, 0.005, 0.08) * 0.6


def shell_clack() -> np.ndarray:
    return _mix(
        synth.modal(2500, [(1.0, 1.0, 0.05), (1.48, 0.6, 0.03)], 0.1, 0.7),
        np.pad(synth.modal(3700, [(1.0, 1.0, 0.04)], 0.08, 0.4), (int(0.012 * SR), 0)),
    )


def shell_crack() -> np.ndarray:
    t = _t(0.35)
    crackle = highpass(_noise(0.35, 4), 1500) * _env(t, 0.001, 0.05)
    snaps = np.zeros_like(t, dtype=np.float32)
    for k, at in enumerate((0.0, 0.03, 0.07, 0.12)):
        i = int(at * SR)
        snaps[i:i + 400] += (np.random.default_rng(k).normal(size=400) * np.exp(-np.arange(400) / 60)).astype(np.float32)
    return crackle * 0.6 + bandpass(snaps, 1200, 6000)


def wet_squish() -> np.ndarray:
    t = _t(0.3)
    wobble = 600 + 400 * np.sin(2 * np.pi * 9 * t)
    body = lowpass(_noise(0.3, 5), 1200) * _env(t, 0.01, 0.08)
    blip = _tone(lambda t: wobble, 0.3) * _env(t, 0.005, 0.06) * 0.3
    return body + blip


def current() -> np.ndarray:
    t = _t(0.6)
    rush = _sweep_noise(0.6, 300, 1400, 6, q=0.9) * _env(t, 0.08, 0.25)
    bubbles = np.zeros_like(rush)
    rng = np.random.default_rng(7)
    for _ in range(8):
        at = rng.uniform(0.05, 0.45)
        f = rng.uniform(600, 1400)
        b = _tone(lambda tt: f * (1 + 2.5 * tt), 0.05) * _env(_t(0.05), 0.002, 0.012)
        i = int(at * SR)
        bubbles[i:i + len(b)] += b * 0.25
    return rush + bubbles


def coconut_roll() -> np.ndarray:
    out = np.zeros(int(0.5 * SR), np.float32)
    for k in range(5):
        knock = synth.modal(160 + 15 * k, [(1.0, 1.0, 0.06), (2.4, 0.3, 0.03)], 0.08, 0.5 - k * 0.05)
        i = int(k * 0.09 * SR)
        out[i:i + len(knock)] += knock[: len(out) - i]
    return out


def coconut_plop() -> np.ndarray:
    t = _t(0.45)
    bloop = _tone(lambda t: 260 + 900 * t, 0.12) * _env(_t(0.12), 0.003, 0.04)
    splash = lowpass(_noise(0.45, 8), 3000) * _env(t, 0.01, 0.12) * 0.5
    return _mix(bloop, splash)


def pit_fall() -> np.ndarray:
    t = _t(0.6)
    whistle = _tone(lambda t: 900 * np.exp(-t * 2.2), 0.6) * _env(t, 0.02, 0.25) * 0.3
    pour = lowpass(_noise(0.6, 9), 2500) * _env(t, 0.05, 0.3) * 0.5
    return whistle + pour


def pit_fill() -> np.ndarray:
    t = _t(0.4)
    thud = _tone(lambda t: 90 + 40 * np.exp(-t / 0.03), 0.4) * _env(t, 0.002, 0.08)
    sand = lowpass(_noise(0.4, 10), 2000) * _env(t, 0.03, 0.15) * 0.5
    return thud + sand


def crab_click() -> np.ndarray:
    a = synth.modal(1800, [(1.0, 1.0, 0.025), (2.3, 0.5, 0.015)], 0.05, 0.7)
    b = synth.modal(2100, [(1.0, 1.0, 0.025)], 0.05, 0.6)
    return _mix(a, np.pad(b, (int(0.07 * SR), 0)))


def pool_shimmer() -> np.ndarray:
    out = np.zeros(int(1.2 * SR), np.float32)
    for k, note in enumerate((79, 83, 86, 91)):
        c = chime(midi_hz(note), 1.0, 0.35)
        i = int(k * 0.05 * SR)
        out[i:i + len(c)] += c[: len(out) - i]
    return out


def egg_wobble() -> np.ndarray:
    out = np.zeros(int(0.35 * SR), np.float32)
    for k in range(4):
        tap = synth.woodblock(520 + 40 * (k % 2), 0.05, 0.35)
        i = int(k * 0.07 * SR)
        out[i:i + len(tap)] += tap[: len(out) - i]
    return out


def egg_crack() -> np.ndarray:
    crack = shell_crack()[: int(0.2 * SR)] * 0.7
    pop = _tone(lambda t: 500 + 1500 * t, 0.06) * _env(_t(0.06), 0.002, 0.015) * 0.5
    return _mix(crack, np.pad(pop, (int(0.1 * SR), 0)))


def hatch_chirp() -> np.ndarray:
    out = np.zeros(int(0.3 * SR), np.float32)
    for k, (f0, f1) in enumerate(((2200, 3400), (2600, 3800))):
        t = _t(0.07)
        tweet = _tone(lambda tt: f0 + (f1 - f0) * (tt / 0.07) ** 0.7, 0.07) * np.sin(np.pi * t / 0.07).astype(np.float32)
        i = int(k * 0.11 * SR)
        out[i:i + len(tweet)] += tweet * 0.4
    return out


def save_splash() -> np.ndarray:
    t = _t(0.7)
    body = lowpass(_noise(0.7, 11), 2400) * _env(t, 0.004, 0.18)
    spray = highpass(_noise(0.7, 12), 3000) * _env(t, 0.01, 0.12) * 0.4
    drops = np.zeros_like(body)
    rng = np.random.default_rng(13)
    for _ in range(10):
        at = rng.uniform(0.08, 0.55)
        f = rng.uniform(900, 2200)
        d = _tone(lambda tt: f * (1 + 3 * tt), 0.04) * _env(_t(0.04), 0.001, 0.01)
        i = int(at * SR)
        drops[i:i + len(d)] += d * 0.2
    return body + spray + drops


def save_chime() -> np.ndarray:
    """A bell at E6; the game raises its playback rate for each extra hatchling saved in one move."""
    return chime(midi_hz(88), 1.0, 0.6)[: int(1.4 * SR)]


def _gull(seconds: float, f0: float, f1: float, seed: int) -> np.ndarray:
    """Cartoon gull: a nasal FM voice whose pitch arcs from f0 to f1 through two vowel formants."""
    t = _t(seconds)
    f = f0 + (f1 - f0) * np.sin(np.pi * np.clip(t / seconds, 0, 1) * 0.5)
    phase = 2 * np.pi * np.cumsum(f) / SR
    voice = np.sin(phase + 2.2 * np.sin(phase * 2.0))
    voice = bandpass(voice.astype(np.float32), 700, 2600) + 0.4 * bandpass(voice.astype(np.float32), 2600, 4200)
    return voice * _env(t, 0.02, seconds * 0.5) * 0.8


def gull_call() -> np.ndarray:
    return _mix(_gull(0.32, 1150, 900, 14), np.pad(_gull(0.28, 1250, 950, 15), (int(0.36 * SR), 0)))


def gull_swoop() -> np.ndarray:
    t = _t(0.8)
    whoosh = _sweep_noise(0.8, 1800, 400, 16, q=0.7) * np.sin(np.pi * t / 0.8).astype(np.float32)
    return whoosh * 0.8


def gull_squawk() -> np.ndarray:
    return _gull(0.25, 1300, 1000, 17)


def _star(n: int) -> np.ndarray:
    notes = [(81,), (81, 85), (81, 85, 88)][n - 1]
    out = np.zeros(int(1.6 * SR), np.float32)
    for k, note in enumerate(notes):
        c = chime(midi_hz(note + 3 * (n - 1)), 1.2, 0.5)
        i = int(k * 0.06 * SR)
        out[i:i + len(c)] += c[: len(out) - i]
    return out


def ui_tap() -> np.ndarray:
    t = _t(0.08)
    return _tone(lambda t: 900 + 500 * np.exp(-t / 0.01), 0.08) * _env(t, 0.001, 0.02) * 0.5


def ui_toggle() -> np.ndarray:
    a = ui_tap() * 0.8
    return _mix(a, np.pad(synth.modal(1500, [(1.0, 1.0, 0.03)], 0.05, 0.4), (int(0.05 * SR), 0)))


def page_swipe() -> np.ndarray:
    t = _t(0.25)
    return _sweep_noise(0.25, 1200, 4000, 18) * _env(t, 0.03, 0.07) * 0.6


def unlock_fanfare() -> np.ndarray:
    out = np.zeros(int(1.8 * SR), np.float32)
    for k, note in enumerate((74, 78, 81, 86)):
        m = marimba(midi_hz(note), 0.3, 0.7)
        i = int(k * 0.11 * SR)
        out[i:i + len(m)] += m[: len(out) - i]
    for note in (74, 78, 81):
        m = marimba(midi_hz(note + 12), 0.6, 0.55)
        i = int(0.5 * SR)
        out[i:i + len(m)] += m[: len(out) - i]
    return out


def achievement() -> np.ndarray:
    out = np.zeros(int(1.5 * SR), np.float32)
    for k, note in enumerate((84, 88, 91, 96)):
        c = chime(midi_hz(note), 1.0, 0.4)
        i = int(k * 0.07 * SR)
        out[i:i + len(c)] += c[: len(out) - i]
    return out


def teleport_pop() -> np.ndarray:
    t = _t(0.18)
    return _tone(lambda t: 400 + 1800 * (t / 0.18), 0.18) * _env(t, 0.003, 0.05) * 0.5


def hint_sparkle() -> np.ndarray:
    out = np.zeros(int(0.9 * SR), np.float32)
    for k, note in enumerate((91, 95, 98)):
        c = kalimba(midi_hz(note), 0.3, 0.35)
        i = int(k * 0.08 * SR)
        out[i:i + len(c)] += c[: len(out) - i]
    return out


def undo() -> np.ndarray:
    return swipe()[::-1].copy() * 0.8


def restart() -> np.ndarray:
    t = _t(0.5)
    return _sweep_noise(0.5, 3000, 400, 19) * _env(t, 0.05, 0.2) * 0.7


def tuck() -> np.ndarray:
    """Hatchlings pulling into their shells: a soft descending boop."""
    t = _t(0.3)
    return _tone(lambda t: 520 * np.exp(-t * 3.0), 0.3) * _env(t, 0.005, 0.1) * 0.5


def overlay_open() -> np.ndarray:
    t = _t(0.35)
    return _sweep_noise(0.35, 300, 1500, 20, q=1.0) * _env(t, 0.08, 0.12) * 0.5


def swim_paddle() -> np.ndarray:
    out = np.zeros(int(0.6 * SR), np.float32)
    for k in range(3):
        stroke = lowpass(_noise(0.12, 21 + k), 1600) * _env(_t(0.12), 0.02, 0.04) * 0.4
        i = int(k * 0.2 * SR)
        out[i:i + len(stroke)] += stroke
    return out


SFX = {
    "swipe": swipe,
    "slide_loop": slide_loop,
    "stop": stop,
    "bump": bump,
    "shell_clack": shell_clack,
    "shell_crack": shell_crack,
    "wet_squish": wet_squish,
    "current": current,
    "coconut_roll": coconut_roll,
    "coconut_plop": coconut_plop,
    "pit_fall": pit_fall,
    "pit_fill": pit_fill,
    "crab_click": crab_click,
    "pool_shimmer": pool_shimmer,
    "egg_wobble": egg_wobble,
    "egg_crack": egg_crack,
    "hatch_chirp": hatch_chirp,
    "save_splash": save_splash,
    "save_chime": save_chime,
    "gull_call": gull_call,
    "gull_swoop": gull_swoop,
    "gull_squawk": gull_squawk,
    "star_1": lambda: _star(1),
    "star_2": lambda: _star(2),
    "star_3": lambda: _star(3),
    "ui_tap": ui_tap,
    "ui_toggle": ui_toggle,
    "page_swipe": page_swipe,
    "unlock_fanfare": unlock_fanfare,
    "achievement": achievement,
    "teleport_pop": teleport_pop,
    "hint_sparkle": hint_sparkle,
    "undo": undo,
    "restart": restart,
    "tuck": tuck,
    "overlay_open": overlay_open,
    "swim_paddle": swim_paddle,
}

# Effects that are mono point sources keep one channel; the game pans them in 3D.
LOOPS = {"slide_loop"}


def render(name: str) -> np.ndarray:
    x = np.asarray(SFX[name](), np.float32)
    if name not in LOOPS:
        x = synth.fade(x, 0.0015, 0.02)
    peak = float(np.abs(x).max()) or 1.0
    return (x / peak * 0.7).astype(np.float32)
