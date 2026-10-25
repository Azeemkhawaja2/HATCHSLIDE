# HATCHSLIDE

A 3D sliding-tile puzzle game for Android: one swipe slides every baby sea turtle at once; get them all into the surf before the seagull swoops. Kotlin, Filament and Jetpack Compose; fully offline, no permissions, no ads.

The full specification is in [HATCHSLIDE_SPEC.md](HATCHSLIDE_SPEC.md); choices and deviations are logged in [DECISIONS.md](DECISIONS.md).

## Build

Requirements: JDK 17+, Android SDK Platform 37 and Build-Tools 37 (`ANDROID_HOME` or `local.properties`).

```bash
./gradlew check            # unit tests, lint, ktlint and the release gates
./gradlew bundleRelease    # app bundle (signed if keystore.properties exists)
```

## Assets

Every model, texture, sky and font is generated at build time and git-ignored. Install the Python tools once, then build:

```bash
pip install -r tools/assetgen/requirements.txt
python tools/assetgen/build_all.py            # all stages; unchanged outputs are skipped
python tools/assetgen/build_all.py --only models
python tools/assetgen/build_all.py --force    # rebuild everything (textures take a few minutes)
```

Gradle runs the same script (`generateAssets`) before merging assets. Filament's `matc` and `cmgen` are downloaded from Maven Central for the pinned Filament version by `tools/filament/fetch_tools.py`. `assets_manifest.json` lists every output with its size and the code that loads it.

## Levels

Levels live in `app/src/main/assets/levels/` (`world_1.json` to `world_5.json`, `daily.json`) and are committed so builds are reproducible. Regenerate them with:

```bash
./gradlew :tools:levelgen:run --args="generate"              # all worlds + Daily Tide (about 7 minutes on 4 cores)
./gradlew :tools:levelgen:run --args="generate --worlds 3"   # one world
./gradlew :tools:levelgen:run --args="report"                # par, states and mechanics per level
./gradlew :tools:levelgen:run --args="tutorial"              # solver report for the hand-made levels
```

`./gradlew check` validates every committed puzzle with the solver.
