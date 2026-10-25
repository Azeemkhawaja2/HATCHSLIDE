# HATCHSLIDE

A 3D sliding-tile puzzle game for Android: one swipe slides every baby sea turtle at once; get them all into the surf before the seagull swoops. Kotlin, Filament and Jetpack Compose; fully offline, no permissions, no ads.

The full specification is in [HATCHSLIDE_SPEC.md](HATCHSLIDE_SPEC.md); choices and deviations are logged in [DECISIONS.md](DECISIONS.md).

## Build

Requirements: JDK 17+, Android SDK Platform 37 and Build-Tools 37 (`ANDROID_HOME` or `local.properties`).

```bash
./gradlew check            # unit tests, lint, ktlint and the release gates
./gradlew bundleRelease    # app bundle (signed if keystore.properties exists)
```
