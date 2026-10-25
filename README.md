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

Every model, texture, sky, font, music track, ambience loop and sound effect is generated at build time and git-ignored. Install the Python tools once, then build:

```bash
pip install -r tools/assetgen/requirements.txt
python tools/assetgen/build_all.py            # all stages; unchanged outputs are skipped
python tools/assetgen/build_all.py --only models
python tools/assetgen/build_all.py --only audio  # music, stingers, ambience, effects (about 2 minutes)
python tools/assetgen/build_all.py --force    # rebuild everything (textures take a few minutes)
```

Gradle runs the same script (`generateAssets`) before merging assets. Filament's `matc` and `cmgen` are downloaded from Maven Central for the pinned Filament version by `tools/filament/fetch_tools.py`. `assets_manifest.json` lists every output with its size and the code that loads it. To replace a synthesised sound with a CC0 recording, drop an OGG named like the output (for example `world3.ogg` or `gull_call.ogg`) into `assets_src/audio/override/` and add it to `LICENSES.md`.

## Levels

Levels live in `app/src/main/assets/levels/` (`world_1.json` to `world_5.json`, `daily.json`) and are committed so builds are reproducible. Regenerate them with:

```bash
./gradlew :tools:levelgen:run --args="generate"              # all worlds + Daily Tide (about 7 minutes on 4 cores)
./gradlew :tools:levelgen:run --args="generate --worlds 3"   # one world
./gradlew :tools:levelgen:run --args="report"                # par, states and mechanics per level
./gradlew :tools:levelgen:run --args="tutorial"              # solver report for the hand-made levels
```

`./gradlew check` validates every committed puzzle with the solver.

## Performance

The `baselineprofile` module holds the Macrobenchmark journeys (title, menu, map, a level). With a device or emulator connected:

```bash
./gradlew :app:generateBaselineProfile                       # regenerate the baseline profile
./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest   # startup and gameplay frame timing
```

Debug builds log load and input timings under the `HatchslidePerf` tag (`adb logcat -s HatchslidePerf`).
