"""Shared build context: deterministic stages, skip-unchanged outputs and the assets manifest."""
from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable, Iterable

from . import paths


def digest(*parts: object) -> str:
    """Stable hash of a stage's inputs (source code, parameters, seeds)."""
    h = hashlib.sha256()
    for part in parts:
        if isinstance(part, Path):
            h.update(part.read_bytes())
        else:
            h.update(repr(part).encode())
    return h.hexdigest()[:16]


def source_digest(*modules: str) -> str:
    """Hash of the Python sources that produce an output, so code changes invalidate it."""
    files: list[Path] = []
    for module in modules:
        base = paths.ROOT / "tools" / "assetgen" / module
        files += sorted(base.rglob("*.py")) if base.is_dir() else [base]
    files.append(paths.ROOT / "tools" / "assetgen" / "common" / "build.py")
    return digest(*files)


@dataclass
class Output:
    path: Path
    referenced_by: str
    category: str

    def manifest_entry(self) -> dict:
        rel = self.path.relative_to(paths.ROOT).as_posix()
        return {"path": rel, "bytes": self.path.stat().st_size, "category": self.category, "referencedBy": self.referenced_by}


@dataclass
class Context:
    force: bool = False
    outputs: list[Output] = field(default_factory=list)
    stamps: dict = field(default_factory=dict)
    stamp_file: Path = paths.CACHE / "stamps.json"

    def load(self) -> None:
        if self.stamp_file.exists():
            self.stamps = json.loads(self.stamp_file.read_text())

    def save(self) -> None:
        self.stamp_file.parent.mkdir(parents=True, exist_ok=True)
        self.stamp_file.write_text(json.dumps(self.stamps, indent=1, sort_keys=True))

    def produce(
        self,
        key: str,
        inputs_digest: str,
        targets: Iterable[Path],
        build: Callable[[], None],
        referenced_by: str,
        category: str,
    ) -> None:
        """Runs [build] unless every target exists and was built from the same inputs."""
        targets = list(targets)
        fresh = (
            not self.force
            and self.stamps.get(key) == inputs_digest
            and all(t.exists() for t in targets)
        )
        if not fresh:
            for t in targets:
                t.parent.mkdir(parents=True, exist_ok=True)
            build()
            missing = [t for t in targets if not t.exists()]
            if missing:
                raise RuntimeError(f"{key}: build did not produce {missing}")
            self.stamps[key] = inputs_digest
            self.save()
            print(f"  built   {key}")
        for t in targets:
            self.outputs.append(Output(t, referenced_by, category))

    def is_fresh(self, key: str, inputs_digest: str, targets: Iterable[Path]) -> bool:
        return not self.force and self.stamps.get(key) == inputs_digest and all(t.exists() for t in targets)

    def record(self, key: str, inputs_digest: str, targets: Iterable[Path], referenced_by: str, category: str, built: bool) -> None:
        """Registers outputs built outside [produce] (for example in a process pool)."""
        targets = list(targets)
        missing = [t for t in targets if not t.exists()]
        if missing:
            raise RuntimeError(f"{key}: missing outputs {missing}")
        if built:
            self.stamps[key] = inputs_digest
            self.save()
            print(f"  built   {key}")
        for t in targets:
            self.outputs.append(Output(t, referenced_by, category))

    def write_manifest(self) -> None:
        entries = sorted((o.manifest_entry() for o in self.outputs), key=lambda e: e["path"])
        total = sum(e["bytes"] for e in entries)
        by_category: dict[str, int] = {}
        for e in entries:
            by_category[e["category"]] = by_category.get(e["category"], 0) + e["bytes"]
        paths.MANIFEST.write_text(
            json.dumps({"totalBytes": total, "bytesByCategory": dict(sorted(by_category.items())), "files": entries}, indent=1) + "\n"
        )
