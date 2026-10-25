"""Music theory helpers: keys, chords, progressions, motif-based melodies, bass lines and grooves."""
from __future__ import annotations

from dataclasses import dataclass

import numpy as np

from .sequencer import Part

MODES = {
    "major": [0, 2, 4, 5, 7, 9, 11],
    "minor": [0, 2, 3, 5, 7, 8, 10],
    "dorian": [0, 2, 3, 5, 7, 9, 10],
    "mixolydian": [0, 2, 4, 5, 7, 9, 10],
    "lydian": [0, 2, 4, 6, 7, 9, 11],
}


@dataclass(frozen=True)
class Key:
    tonic: int  # MIDI note of the tonic in octave 4 (e.g. 62 = D4)
    mode: str = "major"

    def note(self, degree: int, octave: int = 0) -> int:
        """Scale degree (0-based, may exceed 6 or go negative) to MIDI."""
        steps = MODES[self.mode]
        o, d = divmod(degree, 7)
        return self.tonic + 12 * (o + octave) + steps[d]

    def chord(self, root: int, seventh: bool = False, octave: int = 0) -> list[int]:
        degrees = [root, root + 2, root + 4] + ([root + 6] if seventh else [])
        return [self.note(d, octave) for d in degrees]

    def chord_degrees(self, root: int, seventh: bool = False) -> list[int]:
        return [root, root + 2, root + 4] + ([root + 6] if seventh else [])


# Rhythm cells for one 4-beat bar: (onset, length) in beats.
RHYTHMS = {
    "gentle": [
        [(0, 1.5), (1.5, 0.5), (2, 2)],
        [(0, 1), (1, 1), (2, 1.5), (3.5, 0.5)],
        [(0, 0.5), (0.5, 0.5), (1, 1), (2, 2)],
        [(0, 2), (2, 1), (3, 1)],
    ],
    "bouncy": [
        [(0, 0.5), (0.5, 0.5), (1, 0.5), (1.5, 1), (2.5, 0.5), (3, 1)],
        [(0, 0.75), (0.75, 0.75), (1.5, 0.5), (2, 1), (3, 0.5), (3.5, 0.5)],
        [(0.5, 0.5), (1, 0.5), (1.5, 0.5), (2, 1.5), (3.5, 0.5)],
        [(0, 1), (1, 0.5), (1.5, 0.5), (2, 0.5), (2.5, 1.5)],
    ],
    "sparse": [
        [(0, 3), (3, 1)],
        [(0, 2), (2, 2)],
        [(1, 2), (3, 1)],
        [(0, 4)],
    ],
    "staccato": [
        [(0, 0.25), (0.5, 0.25), (1, 0.25), (2, 0.25), (2.5, 0.25), (3, 0.5)],
        [(0, 0.25), (0.75, 0.25), (1.5, 0.25), (2, 0.5), (3, 0.25), (3.5, 0.25)],
        [(0, 0.5), (1, 0.25), (1.5, 0.25), (2.5, 0.25), (3, 0.75)],
        [(0.5, 0.25), (1, 0.25), (1.5, 0.5), (2.5, 0.25), (3, 0.25), (3.5, 0.25)],
    ],
}

CADENCES = {
    "gentle": [(0, 1), (1, 3)],
    "bouncy": [(0, 0.5), (0.5, 0.5), (1, 3)],
    "sparse": [(0, 4)],
    "staccato": [(0, 0.25), (0.5, 0.25), (1, 0.5), (2, 2)],
}


def _nearest(target: int, options: list[int]) -> int:
    return min(options, key=lambda d: abs(d - target))


def phrase(
    key: Key,
    chords: list[int],
    rng: np.random.Generator,
    style: str,
    centre: int = 7,
    motif: list[tuple[float, float]] | None = None,
) -> list[tuple[float, float, int]]:
    """A melody over one chord per bar as (beat, length, scale degree) tuples, built from a repeated motif.

    Bars 1 and 3 share the motif rhythm, bar 2 varies it, the last bar is a cadence; strong beats take
    chord tones and the rest move by step toward them, so the line stays singable.
    """
    bank = RHYTHMS[style]
    motif = motif or bank[rng.integers(len(bank))]
    variant = bank[rng.integers(len(bank))]
    notes: list[tuple[float, float, int]] = []
    degree = centre
    for bar, root in enumerate(chords):
        last = bar == len(chords) - 1
        cell = CADENCES[style] if last else (motif if bar % 2 == 0 else variant)
        tones = [d + 7 * o for d in key.chord_degrees(root) for o in (-1, 0, 1, 2)]
        for i, (onset, length) in enumerate(cell):
            strong = onset in (0, 2) or (last and i == len(cell) - 1)
            if strong:
                degree = _nearest(degree + int(rng.integers(-2, 3)), tones)
            else:
                degree += int(rng.choice([-1, 1, 1, -2, 2]))
            # Pull back toward the centre so the line keeps its register.
            if degree > centre + 5:
                degree -= 2
            elif degree < centre - 4:
                degree += 2
            if last and i == len(cell) - 1:
                degree = _nearest(degree, [root + 7 * o for o in (0, 1, 2)] + [root + 2 + 7 * o for o in (0, 1)])
            notes.append((bar * 4 + onset, length, degree))
    return notes


def add_melody(part: Part, key: Key, notes: list[tuple[float, float, int]], start_bar: int, vel: float, octave: int = 0) -> None:
    for beat, length, degree in notes:
        accent = 1.0 if beat % 2 == 0 else 0.85
        part.add(start_bar * 4 + beat, length, key.note(degree, octave), vel * accent)


def add_chords(part: Part, key: Key, chords: list[int], start_bar: int, vel: float, octave: int = -1, pattern: str = "block", seventh: bool = False) -> None:
    """Accompaniment: block chords, broken arpeggios or strums, one chord per bar."""
    for bar, root in enumerate(chords):
        notes = key.chord(root, seventh, octave)
        base = (start_bar + bar) * 4
        if pattern == "block":
            for n in notes:
                part.add(base, 4, n, vel)
        elif pattern == "half":
            for half in (0, 2):
                for n in notes:
                    part.add(base + half, 2, n, vel * (1.0 if half == 0 else 0.85))
        elif pattern == "arp":
            order = notes + [notes[1] + 12, notes[2], notes[1]]
            for i in range(8):
                part.add(base + i * 0.5, 0.5, order[i % len(order)], vel * (1.0 if i % 2 == 0 else 0.8))
        elif pattern == "strum":
            for hit, length in ((0, 1.5), (1.5, 1), (2.5, 0.5), (3, 1)):
                for j, n in enumerate(notes + [notes[0] + 12]):
                    part.add(base + hit + j * 0.02, length, n, vel * (1.0 if hit in (0, 3) else 0.75))
        elif pattern == "offbeat":
            for i in range(4):
                for n in notes:
                    part.add(base + i + 0.5, 0.35, n, vel * 0.8)


def add_bass(part: Part, key: Key, chords: list[int], start_bar: int, vel: float, pattern: str = "root", octave: int = -2) -> None:
    for bar, root in enumerate(chords):
        base = (start_bar + bar) * 4
        r = key.note(root, octave)
        fifth = key.note(root + 4, octave)
        if pattern == "root":
            part.add(base, 3.5, r, vel)
        elif pattern == "walk":
            nxt = chords[(bar + 1) % len(chords)]
            approach = key.note(nxt - 1, octave)
            for i, n in enumerate((r, fifth, r + 12, approach)):
                part.add(base + i, 0.9, n, vel * (1.0 if i == 0 else 0.8))
        elif pattern == "bounce":
            for onset, n in ((0, r), (1.5, fifth), (2, r), (3, fifth)):
                part.add(base + onset, 0.6, n, vel * (1.0 if onset == 0 else 0.75))
        elif pattern == "pulse":
            for i in range(4):
                part.add(base + i, 0.45, r, vel * (1.0 if i == 0 else 0.7))


def add_groove(part: Part, bars: int, start_bar: int, hits: list[tuple[float, float]], pitch: float = 0.0) -> None:
    """Repeats a one-bar percussion pattern of (beat, velocity) hits."""
    for bar in range(bars):
        for beat, vel in hits:
            part.add((start_bar + bar) * 4 + beat, 0.25, pitch, vel)
