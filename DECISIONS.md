# HATCHSLIDE decisions log

Every choice the spec left open, every deviation from it, and every milestone gate result is recorded here.

## Toolchain (pinned 2026-10-25, latest stable that day)

| Tool | Version | Notes |
| --- | --- | --- |
| Android Gradle Plugin | 9.4.1 | Built-in Kotlin (no `kotlin-android` plugin), new DSL |
| Gradle | 9.8.2 | Wrapper committed |
| Kotlin | 2.4.21 | Compose compiler plugin `org.jetbrains.kotlin.plugin.compose` at the same version. The spec's baseline said 2.3.x; 2.4.21 was the newest stable on build day |
| Compose BOM | 2026.09.00 | Compose UI 1.12.x, Material3 1.4.0 |
| Filament | 1.77.3 | `filament-android`, `gltfio-android`, `filament-utils-android`; host tools `matc` and `cmgen` from the same release |
| compileSdk / targetSdk / minSdk | 37 / 37 / 26 | Platform `android-37.0`, build-tools 37.0.0 |
| AndroidX | core-ktx 1.19.1, activity-compose 1.13.0, lifecycle 2.11.0, navigation 2.10.2, datastore 1.3.0, core-splashscreen 1.2.0, window 1.5.1, material3-adaptive 1.3.0, profileinstaller 1.4.1, benchmark/baselineprofile 1.5.0 | |
| Media3 | 1.11.1 | ExoPlayer only, local assets |
| kotlinx | coroutines 1.10.2, serialization-json 1.12.0 | |
| ktlint | 1.8.0 | Run as a CLI from Gradle (`ktlintCheck`, `ktlintFormat`) |
| bundletool | 1.18.3 | Size report |
| JDK | 21 (bytecode target 17) | |

## M0 Setup

- **Filament host tools.** `matc` and `cmgen` 1.77.3 are published on Maven Central as `com.google.android.filament:matc` and `:cmgen` (Linux, macOS, Windows executables). `tools/filament/fetch_tools.py` downloads them and checks the published SHA-256. `mipgen` is not published there and GitHub release downloads were unavailable from the build machine, so `tools/assetgen/textures/ktx.py` replaces it: it writes the same mipmapped KTX1 containers that Filament's `KTX1Loader` reads, with ASTC 4x4 (Arm astcenc via `astc-encoder-py`) and ETC2 (etcpak) payloads.
- **ktlint without a Gradle plugin.** The ktlint Gradle plugins hook into the old Android DSL that AGP 9 removed, so the root build runs the official `ktlint-cli` jar directly. Code style: `android_studio` with trailing commas allowed.
- **Gates in `./gradlew check`.** `verifyReleaseManifest` (app) parses the merged release manifest and fails on any `<uses-permission>`/`<permission>`, wrong SDK levels, missing `appCategory="game"`, or a debuggable flag. `verifyNoNetworkCode` and `verifyNoTodo` (root) scan every source tree. Android lint aborts on errors.
- **DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION.** Both the `<permission>` and the `<uses-permission>` that AndroidX Core adds are removed. The app registers no dynamic receivers. Media3 registers its audio-capabilities receiver through its own `Util.registerReceiverNotExported`, which uses the `RECEIVER_NOT_EXPORTED` flag on API 33+ and needs no permission below that.
- **Application ID.** `com.hatchslide.game`. It cannot change after the first Play upload, so the developer should replace it with a domain they control before uploading.
- **Signing.** The release build signs with an upload key from `keystore.properties` or `HATCHSLIDE_UPLOAD_*` environment variables; `tools/release/create_upload_key.sh` creates one locally. Without a key `bundleRelease` still builds, unsigned, so CI can run the release gates. R8 full mode is the AGP default and stays on; `shrinkResources` and `debuggable false` are set explicitly.
- **Build machine limits.** The build container has no KVM, so no Android emulator can run there. Instrumented tests, Macrobenchmark, the monkey run and rendering smoke tests are written and wired, but they have to be executed on a developer machine or device farm. Results from that run belong in the "Device verification" section below.

Gate result: `./gradlew check` green (manifest gate: zero permissions, targetSdk 37, appCategory game; no-network and no-TODO gates pass; lint clean).
