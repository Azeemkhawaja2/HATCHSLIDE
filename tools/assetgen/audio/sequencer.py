"""A small beat-based sequencer: notes on parts, humanised timing, panning, reverb send and loop wrap."""
from __future__ import annotations

from dataclasses import dataclass, field

import numpy as np

from . import fx
from .synth import DRUMS, INSTRUMENTS, SR, midi_hz


@dataclass
class Note:
    start: float  # beats from the start of the song
    length: float  # beats
    pitch: float  # MIDI note (drums: optional pitch in Hz, 0 for the default)
    vel: float


@dataclass
class Part:
    instrument: str
    pan: float = 0.0
    gain: float = 1.0
    send: float = 0.25  # reverb send
    drum: bool = False
    humanise: float = 0.006  # timing jitter, seconds (standard deviation)
    notes: list[Note] = field(default_factory=list)

    def add(self, start: float, length: float, pitch: float, vel: float = 0.8) -> None:
        self.notes.append(Note(start, length, pitch, vel))


@dataclass
class Song:
    name: str
    tempo: float
    bars: int
    seed: int
    swing: float = 0.0  # 0 straight, 0.33 strong shuffle on off-beat eighths
    room: float = 0.75
    damp: float = 0.4
    target_lufs: float = -14.0
    loop: bool = True
    parts: list[Part] = field(default_factory=list)

    @property
    def beat_seconds(self) -> float:
        return 60.0 / self.tempo

    @property
    def length_seconds(self) -> float:
        return self.bars * 4 * self.beat_seconds

    def part(self, instrument: str, **kwargs) -> Part:
        p = Part(instrument, **kwargs)
        self.parts.append(p)
        return p


def _swung(beat: float, swing: float) -> float:
    """Delays off-beat eighths for a shuffle feel."""
    frac = beat % 1.0
    if swing and abs(frac - 0.5) < 1e-6:
        return beat + swing * 0.5
    return beat


def render(song: Song, tail: float = 6.0, length: float | None = None) -> np.ndarray:
    """Renders [song] to a mastered (n, 2) float32 array. Looping songs wrap their reverb tail to the start."""
    rng = np.random.default_rng(song.seed)
    main = song.length_seconds if length is None else length
    total = int((main + tail) * SR)
    dry = np.zeros((total, 2), np.float32)
    send = np.zeros((total, 2), np.float32)
    cache: dict[tuple, np.ndarray] = {}
    for part in song.parts:
        left, right = fx.pan_gains(part.pan)
        for k, note in enumerate(part.notes):
            when = _swung(note.start, song.swing) * song.beat_seconds
            jitter = float(np.clip(rng.normal(0.0, part.humanise), -2.5 * part.humanise, 2.5 * part.humanise)) if part.humanise else 0.0
            vel = float(np.clip(note.vel * rng.uniform(0.9, 1.08), 0.05, 1.0))
            key = (part.instrument, round(note.pitch * 4) / 4, round(note.length * 8) / 8, round(vel * 24), k % 3)
            sample = cache.get(key)
            if sample is None:
                if part.drum:
                    sample = DRUMS[part.instrument](vel, song.seed + k % 7, note.pitch)
                else:
                    sample = INSTRUMENTS[part.instrument](midi_hz(note.pitch), note.length * song.beat_seconds, vel, song.seed + k % 3)
                cache[key] = sample
            start = int(max(0.0, when + jitter) * SR)
            end = min(total, start + len(sample))
            if end <= start:
                continue
            chunk = sample[: end - start] * part.gain
            dry[start:end, 0] += chunk * left
            dry[start:end, 1] += chunk * right
            if part.send:
                send[start:end, 0] += chunk * left * part.send
                send[start:end, 1] += chunk * right * part.send
    mix = dry + fx.freeverb(send, song.room, song.damp)
    n = int(main * SR)
    if song.loop:
        # Seamless loop: whatever rings past the end continues at the start.
        mix[: total - n] += mix[n:]
        mix = mix[:n]
    else:
        # One-shots (stingers) end exactly at [main] with a short fade.
        mix = mix[:n]
        f = min(n, int(0.35 * SR))
        mix[-f:] *= np.linspace(1.0, 0.0, f)[:, None]
    return fx.master(mix, song.target_lufs)


def envelope(audio: np.ndarray, step: float = 0.25) -> bytes:
    """Loudness envelope (one byte per [step] seconds) so the game can duck ambience when music swells."""
    hop = int(step * SR)
    frames = len(audio) // hop
    rms = np.sqrt((audio[: frames * hop] ** 2).mean(axis=1).reshape(frames, hop).mean(axis=1))
    db = 20 * np.log10(np.maximum(rms, 1e-6))
    scaled = np.clip((db + 40.0) / 40.0, 0.0, 1.0)
    return (scaled * 255 + 0.5).astype(np.uint8).tobytes()
