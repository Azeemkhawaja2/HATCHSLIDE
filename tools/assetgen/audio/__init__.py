"""Audio stage: music and stingers (HD pack), ambience loops and effects (base module), all as OGG Vorbis.

A file named like an output in assets_src/audio/override/ (for example `world3.ogg`) replaces the
synthesised version; record its CC0 source in LICENSES.md.
"""
from __future__ import annotations

import os
import shutil
from concurrent.futures import ProcessPoolExecutor
from pathlib import Path

import numpy as np
import soundfile as sf

from ..common import paths
from ..common.build import Context, digest, source_digest
from .ambience import AMBIENCE
from .sequencer import envelope, render
from .sfx import SFX
from .sfx import render as render_sfx
from .synth import SR
from .tracks import STINGERS, TRACKS

REF = "core/audio/src/main/kotlin/com/hatchslide/core/audio/AudioCatalog.kt"

#: Vorbis quality: libsndfile's compression level 0.4 is quality 0.6 (q6) for music, q4 for effects.
MUSIC_LEVEL = 0.4
EFFECT_LEVEL = 0.6


def write_ogg(path: Path, audio: np.ndarray, level: float) -> None:
    """Writes in one-second chunks (libsndfile's Vorbis encoder crashes on very large single writes)."""
    path.parent.mkdir(parents=True, exist_ok=True)
    channels = 1 if audio.ndim == 1 else audio.shape[1]
    with sf.SoundFile(str(path), "w", SR, channels, format="OGG", subtype="VORBIS", compression_level=level) as f:
        for i in range(0, len(audio), SR):
            f.write(np.ascontiguousarray(audio[i:i + SR]))


def _override(name: str) -> Path | None:
    candidate = paths.OVERRIDES / f"{name}.ogg"
    return candidate if candidate.exists() else None


def music_targets(name: str) -> list[Path]:
    """A track and its loudness envelope (for ambience ducking); stingers need no envelope."""
    if name not in TRACKS:
        return [paths.STINGERS / f"{name}.ogg"]
    return [paths.MUSIC / f"{name}.ogg", paths.MUSIC / f"{name}.env"]


def build_track(name: str) -> str:
    target = music_targets(name)[0]
    source = _override(name)
    if source:
        shutil.copyfile(source, target)
        audio, _ = sf.read(str(source), dtype="float32", always_2d=True)
    else:
        if name in TRACKS:
            audio = render(TRACKS[name]())
        else:
            factory, seconds = STINGERS[name]
            audio = render(factory(), length=seconds)
        write_ogg(target, audio, MUSIC_LEVEL)
    if name in TRACKS:
        music_targets(name)[1].write_bytes(envelope(audio))
    return name


def build_ambience(name: str) -> str:
    target = paths.AMBIENCE / f"{name}.ogg"
    source = _override(name)
    if source:
        shutil.copyfile(source, target)
    else:
        write_ogg(target, AMBIENCE[name](), EFFECT_LEVEL)
    return name


def build_sfx(name: str) -> str:
    target = paths.SFX / f"{name}.ogg"
    source = _override(name)
    if source:
        shutil.copyfile(source, target)
    else:
        write_ogg(target, render_sfx(name), EFFECT_LEVEL)
    return name


def _override_digest(name: str) -> str:
    source = _override(name)
    return digest(source) if source else "synth"


def build(ctx: Context) -> None:
    code = source_digest("audio")
    jobs = []
    for name in list(TRACKS) + list(STINGERS):
        jobs.append(("music", name, music_targets(name), build_track))
    for name in AMBIENCE:
        jobs.append(("ambience", name, [paths.AMBIENCE / f"{name}.ogg"], build_ambience))
    for name in SFX:
        jobs.append(("sfx", name, [paths.SFX / f"{name}.ogg"], build_sfx))
    keyed = [(f"audio/{kind}/{name}", digest(code, name, _override_digest(name)), kind, name, targets, fn) for kind, name, targets, fn in jobs]
    stale = [j for j in keyed if not ctx.is_fresh(j[0], j[1], j[4])]
    if stale:
        # Long music renders first so the pool stays busy.
        stale.sort(key=lambda j: j[2] != "music")
        with ProcessPoolExecutor(max_workers=max(1, min(len(stale), os.cpu_count() or 2))) as pool:
            for name in pool.map(_run, [(j[5], j[3]) for j in stale]):
                print(f"  audio {name}")
    stale_keys = {j[0] for j in stale}
    for key, inputs, kind, name, targets, _ in keyed:
        ctx.record(key, inputs, targets, REF, kind, built=key in stale_keys)


def _run(job) -> str:
    fn, name = job
    return fn(name)
