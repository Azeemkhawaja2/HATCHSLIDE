#!/usr/bin/env python3
"""Downloads Filament's host tools (matc, cmgen) for the Filament version pinned in the version catalog.

The executables are published on Maven Central as com.google.android.filament:matc and :cmgen, so the
host tools always come from the same release as the runtime libraries. Each download is checked
against the SHA-256 that Maven Central publishes next to it.

Usage: python tools/filament/fetch_tools.py [--print-dir]
Environment:
  FILAMENT_TOOLS_DIR    use executables from this directory instead of downloading
  MAVEN_CENTRAL_MIRROR  base URL of a Maven Central mirror (default https://repo1.maven.org/maven2)
"""
from __future__ import annotations

import argparse
import hashlib
import os
import platform
import re
import sys
import time
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TOOLS = ("matc", "cmgen")


def filament_version() -> str:
    catalog = (ROOT / "gradle" / "libs.versions.toml").read_text()
    match = re.search(r'^filament\s*=\s*"([^"]+)"', catalog, re.M)
    if not match:
        raise SystemExit("filament version not found in gradle/libs.versions.toml")
    return match.group(1)


def classifier() -> str:
    system, machine = platform.system(), platform.machine().lower()
    if system == "Linux" and machine in ("x86_64", "amd64"):
        return "linux-x86_64"
    if system == "Darwin" and machine in ("arm64", "aarch64"):
        return "osx-aarch_64"
    if system == "Windows":
        return "windows-x86_64"
    raise SystemExit(f"No published Filament host tools for {system}/{machine}")


def fetch(url: str) -> bytes:
    delay = 2
    for attempt in range(6):
        try:
            with urllib.request.urlopen(url, timeout=120) as response:
                return response.read()
        except Exception as error:  # retry transient HTTP and network errors
            if attempt == 5:
                raise SystemExit(f"download failed: {url}: {error}")
            time.sleep(delay)
            delay *= 2
    raise AssertionError("unreachable")


def tools_dir() -> Path:
    override = os.environ.get("FILAMENT_TOOLS_DIR")
    if override:
        return Path(override)
    version = filament_version()
    target = ROOT / "build" / "filament-tools" / version
    base = os.environ.get("MAVEN_CENTRAL_MIRROR", "https://repo1.maven.org/maven2").rstrip("/")
    suffix = ".exe"
    for tool in TOOLS:
        exe = target / (tool + (".exe" if platform.system() == "Windows" else ""))
        if exe.exists():
            continue
        url = f"{base}/com/google/android/filament/{tool}/{version}/{tool}-{version}-{classifier()}{suffix}"
        expected = fetch(url + ".sha256").decode().split()[0].strip()
        data = fetch(url)
        actual = hashlib.sha256(data).hexdigest()
        if actual != expected:
            raise SystemExit(f"{tool}: checksum mismatch ({actual} != {expected})")
        target.mkdir(parents=True, exist_ok=True)
        exe.write_bytes(data)
        exe.chmod(0o755)
        print(f"fetched {tool} {version} ({len(data) // 1024} KiB, sha256 ok)", file=sys.stderr)
    return target


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--print-dir", action="store_true", help="print the tools directory")
    args = parser.parse_args()
    directory = tools_dir()
    if args.print_dir:
        print(directory)
    return 0


if __name__ == "__main__":
    sys.exit(main())
