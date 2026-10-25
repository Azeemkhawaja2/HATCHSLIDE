"""The soundtrack (spec section 7): six looping tracks and two stingers, each a composed arrangement."""
from __future__ import annotations

import numpy as np

from .compose import Key, add_bass, add_chords, add_groove, add_melody, phrase
from .sequencer import Song

SHAKER_8THS = [(i * 0.5, 0.55 if i % 2 == 0 else 0.35) for i in range(8)]
SHAKER_16THS = [(i * 0.25, [0.5, 0.25, 0.35, 0.25][i % 4]) for i in range(16)]


def _phrases(key: Key, rng, chords: list[int], style: str, centre: int, bars_per_phrase: int = 4):
    """Melody for a whole section: one motif per section, repeated across its phrases with variation."""
    motif = None
    out = []
    for start in range(0, len(chords), bars_per_phrase):
        notes = phrase(key, chords[start:start + bars_per_phrase], rng, style, centre, motif)
        if motif is None:
            motif = [(b, length) for b, length, _ in notes if b < 4]
        out.append((start, notes))
    return out


def title() -> Song:
    """Warm and welcoming: marimba, soft steel drum, nylon-guitar plucks, shaker (D major, 96 BPM)."""
    key = Key(62, "major")
    rng = np.random.default_rng(101)
    song = Song("title", tempo=96, bars=0, seed=101, room=0.72)
    marimba = song.part("marimba", pan=-0.15, gain=0.9, send=0.22)
    steel = song.part("steel_drum", pan=0.3, gain=0.55, send=0.3)
    nylon = song.part("nylon", pan=0.25, gain=0.5, send=0.2)
    pad = song.part("pad", pan=0.0, gain=0.5, send=0.4, humanise=0.0)
    bass = song.part("bass", pan=0.0, gain=0.8, send=0.05)
    shaker = song.part("shaker", pan=-0.35, gain=0.6, send=0.1, drum=True, humanise=0.004)

    intro = [0, 4, 5, 3]
    a = [0, 4, 5, 3] * 4
    b = [3, 4, 2, 5, 1, 4, 0, 0, 3, 4, 2, 5, 1, 3, 4, 4]
    outro = [3, 4, 5, 3, 1, 4, 3, 4]
    form = [(intro, None, False), (a, "marimba", True), (b, "steel", True), (a, "both", True), (b, "both", True), (outro, "marimba", False)]
    bar = 0
    for chords, lead, groove in form:
        add_chords(nylon, key, chords, bar, 0.55, octave=-1, pattern="arp")
        add_chords(pad, key, chords, bar, 0.35, octave=-1, pattern="block")
        add_bass(bass, key, chords, bar, 0.7, pattern="bounce" if groove else "root")
        if groove:
            add_groove(shaker, len(chords), bar, SHAKER_8THS)
        if lead:
            for start, notes in _phrases(key, rng, chords, "gentle", 9):
                if lead in ("marimba", "both"):
                    add_melody(marimba, key, notes, bar + start, 0.8)
                if lead in ("steel", "both"):
                    harmony = [(t, length, d - 2) for t, length, d in notes] if lead == "both" else notes
                    add_melody(steel, key, harmony, bar + start, 0.7)
        bar += len(chords)
    song.bars = bar
    return song


def sunrise_cove() -> Song:
    """Light and curious with a slow build; kalimba lead (G major, 88 BPM)."""
    key = Key(67, "major")
    rng = np.random.default_rng(202)
    song = Song("world1", tempo=88, bars=0, seed=202, room=0.8)
    kalimba = song.part("kalimba", pan=0.1, gain=0.95, send=0.3)
    counter = song.part("marimba", pan=-0.35, gain=0.55, send=0.25)
    pad = song.part("pad", gain=0.45, send=0.45, humanise=0.0)
    bass = song.part("bass", gain=0.75, send=0.05)
    shaker = song.part("shaker", pan=0.4, gain=0.45, drum=True, send=0.1)
    conga = song.part("conga", pan=-0.25, gain=0.5, drum=True, send=0.12)

    sections = [
        ([0, 3, 0, 4], 0),  # kalimba alone
        ([0, 3, 5, 4, 0, 3, 1, 4], 1),
        ([0, 3, 5, 4, 0, 3, 1, 4], 2),
        ([5, 3, 0, 4, 5, 3, 1, 4], 3),
        ([0, 3, 5, 4, 0, 3, 1, 4], 4),
        ([5, 3, 0, 4, 5, 3, 1, 4], 4),
        ([0, 3, 5, 4, 0, 3, 1, 4], 3),
        ([5, 3, 0, 4, 5, 3, 1, 4], 2),
        ([3, 0, 3, 4, 3, 0, 1, 4], 1),
    ]
    bar = 0
    for chords, level in sections:
        for start, notes in _phrases(key, rng, chords, "gentle", 7):
            add_melody(kalimba, key, notes, bar + start, 0.75 + 0.04 * level)
            if level >= 3:
                add_melody(counter, key, [(t, length, d - 3) for t, length, d in notes if t % 1 == 0], bar + start, 0.55, octave=-1)
        if level >= 1:
            add_chords(pad, key, chords, bar, 0.3 + 0.03 * level, octave=-1)
        if level >= 2:
            add_bass(bass, key, chords, bar, 0.65, pattern="root" if level < 4 else "bounce")
            add_groove(shaker, len(chords), bar, SHAKER_8THS if level < 4 else SHAKER_16THS)
        if level >= 4:
            add_groove(conga, len(chords), bar, [(0, 0.7), (1.5, 0.5), (2.5, 0.55), (3, 0.6)], pitch=196.0)
        bar += len(chords)
    song.bars = bar
    return song


def coral_coast() -> Song:
    """Bright and bouncy: ukulele-like plucks and hand percussion (A major, 112 BPM)."""
    key = Key(69, "major")
    rng = np.random.default_rng(303)
    song = Song("world2", tempo=112, bars=0, seed=303, room=0.65)
    lead = song.part("ukulele", pan=-0.1, gain=0.85, send=0.18)
    strum = song.part("ukulele", pan=0.35, gain=0.45, send=0.15, humanise=0.004)
    marimba = song.part("marimba", pan=-0.4, gain=0.5, send=0.2)
    bass = song.part("bass", gain=0.85, send=0.04)
    conga = song.part("conga", pan=0.2, gain=0.6, drum=True, send=0.1)
    shaker = song.part("shaker", pan=-0.3, gain=0.5, drum=True, send=0.08)
    clap = song.part("clap", pan=0.1, gain=0.4, drum=True, send=0.2)

    a = [0, 3, 4, 3] * 2
    b = [5, 3, 0, 4, 5, 3, 1, 4]
    intro = [0, 3, 4, 4]
    form = [
        (intro, False), (a, True), (a, True), (b, True), (a, True), (b, True),
        (a, True), (b, True), (a, True), (a, True), (b, True), (intro, False),
    ]
    bar = 0
    for chords, lead_on in form:
        add_chords(strum, key, chords, bar, 0.5, octave=-1, pattern="offbeat")
        add_bass(bass, key, chords, bar, 0.75, pattern="bounce")
        add_groove(conga, len(chords), bar, [(0, 0.8), (0.75, 0.45), (1.5, 0.6), (2, 0.7), (2.75, 0.45), (3.5, 0.6)], pitch=240.0)
        add_groove(shaker, len(chords), bar, SHAKER_16THS)
        if lead_on:
            add_groove(clap, len(chords), bar, [(1, 0.6), (3, 0.6)])
            for start, notes in _phrases(key, rng, chords, "bouncy", 7):
                add_melody(lead, key, notes, bar + start, 0.8)
                add_melody(marimba, key, [(t, length, d + 2) for t, length, d in notes if t % 2 == 0], bar + start, 0.5)
        bar += len(chords)
    song.bars = bar
    return song


def moonlit_bay() -> Song:
    """Calm night ambient: glassy marimba, soft pads, gentle chimes (E flat lydian, 66 BPM)."""
    key = Key(63, "lydian")
    rng = np.random.default_rng(404)
    song = Song("world3", tempo=66, bars=0, seed=404, room=0.92, damp=0.25)
    glass = song.part("glass_marimba", pan=0.15, gain=0.8, send=0.45)
    pad = song.part("pad", gain=0.6, send=0.55, humanise=0.0)
    chimes = song.part("chime", pan=-0.4, gain=0.35, send=0.6)
    bass = song.part("bass", gain=0.55, send=0.1)

    a = [0, 1, 0, 4]
    b = [5, 1, 3, 4]
    form = [a, a, b, a, b, a, b, a, b, a, a, b, a]
    bar = 0
    for i, chords in enumerate(form):
        add_chords(pad, key, chords, bar, 0.42, octave=-1, pattern="block", seventh=True)
        add_bass(bass, key, chords, bar, 0.5, pattern="root")
        if i not in (0, len(form) - 1):
            for start, notes in _phrases(key, rng, chords, "sparse", 9):
                add_melody(glass, key, notes, bar + start, 0.65)
        for k in range(len(chords)):
            if rng.random() < 0.55:
                chimes.add((bar + k) * 4 + float(rng.choice([1.5, 2.5, 3.0])), 2, key.note(int(rng.choice([4, 6, 7, 9])), 1), 0.4)
        bar += len(chords)
    song.bars = bar
    return song


def crab_reef() -> Song:
    """Playful: staccato woodblocks and bass marimba, with a light swing (D dorian, 104 BPM)."""
    key = Key(62, "dorian")
    rng = np.random.default_rng(505)
    song = Song("world4", tempo=104, bars=0, seed=505, swing=0.22, room=0.6)
    melody = song.part("marimba", pan=0.2, gain=0.8, send=0.15, humanise=0.004)
    blocks = song.part("woodblock", pan=-0.3, gain=0.6, send=0.12, humanise=0.003)
    bass = song.part("bass_marimba", gain=0.85, send=0.08)
    rim = song.part("rim", pan=0.35, gain=0.45, drum=True, send=0.1)
    conga = song.part("conga", pan=-0.15, gain=0.45, drum=True, send=0.1)

    a = [0, 3, 0, 4, 0, 3, 6, 4]
    b = [2, 3, 0, 5, 2, 3, 4, 4]
    intro = [0, 3, 0, 4]
    form = [intro, a, a, b, a, b, a, b, a, a, b, intro]
    bar = 0
    for i, chords in enumerate(form):
        add_bass(bass, key, chords, bar, 0.75, pattern="walk", octave=-1)
        for k, root in enumerate(chords):
            for onset, vel in ((0, 0.8), (0.5, 0.5), (1.5, 0.6), (2.5, 0.55), (3, 0.7)):
                blocks.add((bar + k) * 4 + onset, 0.25, key.note(root + 4 + (2 if onset % 1 else 0), 1), vel)
        add_groove(rim, len(chords), bar, [(1, 0.6), (3, 0.6), (3.5, 0.35)])
        if i not in (0, len(form) - 1):
            add_groove(conga, len(chords), bar, [(0, 0.6), (2, 0.5), (2.5, 0.45)], pitch=180.0)
            for start, notes in _phrases(key, rng, chords, "staccato", 8):
                add_melody(melody, key, notes, bar + start, 0.8)
        bar += len(chords)
    song.bars = bar
    return song


def volcanic_isle() -> Song:
    """Deeper and tenser but friendly: low drums and brass-like swells (D dorian, F major lift; 92 BPM)."""
    key = Key(62, "dorian")
    lift = Key(65, "major")
    rng = np.random.default_rng(606)
    song = Song("world5", tempo=92, bars=0, seed=606, room=0.82, damp=0.45)
    melody = song.part("marimba", pan=0.15, gain=0.8, send=0.2)
    brass = song.part("brass", pan=-0.2, gain=0.5, send=0.35, humanise=0.0)
    bass = song.part("bass", gain=0.85, send=0.05)
    drums = song.part("low_drum", gain=0.85, drum=True, send=0.25, humanise=0.004)
    shaker = song.part("shaker", pan=0.35, gain=0.4, drum=True, send=0.1)

    a = [0, 6, 3, 4] * 2
    b = [0, 4, 5, 3, 0, 4, 1, 4]
    intro = [0, 6, 3, 4]
    form = [
        (intro, key, False), (a, key, True), (a, key, True), (b, lift, True), (a, key, True),
        (b, lift, True), (a, key, True), (b, lift, True), (a, key, True), (intro, key, False),
    ]
    bar = 0
    for chords, k, lead in form:
        add_chords(brass, k, chords, bar, 0.5, octave=-1, pattern="half")
        add_bass(bass, k, chords, bar, 0.75, pattern="pulse")
        add_groove(drums, len(chords), bar, [(0, 0.9), (1.5, 0.5), (2, 0.7), (3.5, 0.45)], pitch=70.0)
        if lead:
            add_groove(shaker, len(chords), bar, SHAKER_8THS)
            for start, notes in _phrases(k, rng, chords, "gentle", 7):
                add_melody(melody, k, notes, bar + start, 0.8)
        bar += len(chords)
    song.bars = bar
    return song


def victory() -> Song:
    """Three-second win stinger: a rising arpeggio landing on a bright chord."""
    key = Key(62, "major")
    song = Song("victory", tempo=120, bars=2, seed=707, loop=False, room=0.7)
    mar = song.part("marimba", gain=0.9, send=0.25, humanise=0.0)
    chime = song.part("chime", pan=0.3, gain=0.5, send=0.4, humanise=0.0)
    pad = song.part("pad", gain=0.5, send=0.4, humanise=0.0)
    for i, d in enumerate([0, 2, 4, 7, 9, 11]):
        mar.add(i * 0.25, 0.5, key.note(d), 0.65 + i * 0.05)
    for d in (0, 2, 4):
        mar.add(1.75, 2.5, key.note(d, 1), 0.85)
        pad.add(1.75, 2.0, key.note(d), 0.5)
    chime.add(1.75, 3, key.note(7, 1), 0.6)
    return song


def fail() -> Song:
    """Two-second soft fail stinger: a gentle, falling figure that resolves (no harshness)."""
    key = Key(62, "major")
    song = Song("fail", tempo=100, bars=1, seed=808, loop=False, room=0.75)
    kal = song.part("kalimba", gain=0.85, send=0.3, humanise=0.0)
    pad = song.part("pad", gain=0.4, send=0.4, humanise=0.0)
    for i, d in enumerate([4, 3, 1]):
        kal.add(i * 0.5, 0.75, key.note(d), 0.7 - i * 0.08)
    kal.add(1.5, 1.6, key.note(-3), 0.6)
    for d in (-3, 1, 3):
        pad.add(1.5, 1.3, key.note(d, -1), 0.4)
    return song


TRACKS = {
    "title": title,
    "world1": sunrise_cove,
    "world2": coral_coast,
    "world3": moonlit_bay,
    "world4": crab_reef,
    "world5": volcanic_isle,
}
STINGERS = {"victory": (victory, 3.0), "fail": (fail, 2.0)}
