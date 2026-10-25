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

## M1 Game core

- **Conflict tie-break.** Rule 4 says ties between equally frontmost pieces are broken by piece id. The engine breaks them by the pieces' current cell index (row-major, lower wins) instead. Hatchlings are identical for solving, and the solver stores them as a sorted list of cells; with an id tie-break the same canonical state could resolve two ways, so the solver could disagree with the game. Cell index is just as deterministic and depends only on what the player sees.
- **"Frontmost along the swipe direction"** is measured on the original swipe axis even for pieces a current has turned.
- **No-op definition.** A swipe is a no-op only when no piece changes cell *and* no cracked shell breaks. Hitting a cracked shell without moving still counts as a move and advances hazards; otherwise shells could be broken for free.
- **Head-on swaps.** Two pieces that would trade cells in one tick (possible only via currents or pools) both stop. Longer rotations around a ring are allowed: every piece enters a cell another piece is leaving.
- **Tide pools.** A piece entering a pool lands on the twin cell in the same tick and keeps its direction; the twin follows the normal chain rule, so it counts as occupied only by a piece that is not moving. A piece whose twin is the cell it is leaving is blocked. Twins may not touch (parse error).
- **Removal timing.** Saved hatchlings, sunk coconuts, pit falls and coconuts that fill a pit leave at the end of the tick, freeing the cell for followers. Broken shells and filled pits turn to sand at the end of the tick.
- **Hazard order after a counted move.** Crabs (in list order, each waiting if its next cell holds a piece, an unhatched egg or a crab), then gull shadows, then egg timers. A late egg emits `EggWobble` when one move remains and `Hatched` when it opens. Then the move counter rises and win/lose is evaluated: pit fall, then all saved (win), then hatchling under a shadow, then counter at zero.
- **Extra events.** Besides the spec's events, the engine emits `CoconutSank`, `PitFilled`, `EggWobble`, `CrabWaited` and `GullShadowMoved` so the renderer and audio never infer anything from state diffs.
- **Solver.** BFS over packed `LongArray` keys in an open-addressing hash set (`KeyStore`); node index order is BFS order, so the queue is implicit. It finishes the optimal layer to report every optimal first move and the number of optimal move sequences. `analyze()` explores everything within the gull limit and reports dead ends and branching for difficulty scoring. Default budget 400,000 states.
- **Level JSON.** World files are `{"world": n, "levels": [...]}` and `daily.json` is `{"puzzles": [...]}`. Two optional fields beyond the spec's example: `mechanic` (the mechanic an intro or showcase level teaches, checked against the optimal path) and `tip` as a string-resource key so tips are translatable.

Gate result: `:core:game:test` green: one fixture per cell type entering from all four directions, conflicts, chains, saves freeing cells, current loops, pits, crabs, eggs, pools, gull shadows, undo hashes, the 10,000-sequence property test, and a cross-check of the solver against a naive BFS on 300 random boards.

## M2 Levels

- **Generator** (`tools/levelgen`): each slot (world, index) gets a fixed seed. A random draft (shoreline of surf with a few reef rocks, the world's tile palette, pieces and hazards) is improved by simulated annealing over small mutations. The cost covers par distance from the slot target, a required mechanic missing from the optimal path, a board solved by repeating one direction, every first move being optimal, and clutter above the palette density. Slots run in parallel but each has its own RNG, so output is reproducible (regenerating worlds 1-3 gives byte-identical files).
- **Acceptance**: par inside the world's range; optimal path uses one of the world's new mechanics (showcase levels: their own mechanic); not solvable by repeating one direction; exhaustive state space within the gull limit under 60,000; solve from the start under 12,000 states.
- **Difficulty score**: par + 0.6 x (4 - optimal first moves) + 1.5 / sqrt(optimal solutions) + 2 x dead-end ratio + 0.3 x hatchlings. Each world generates about 25% more regular candidates than it needs, keeps an even spread after sorting by score, and puts easier "breather" levels at 5, 10, 15, 20 and 25. Level 30 is the finale.
- **Showcases**: each new mechanic gets two easy showcase levels on the world's smallest board. The first carries the tip card (`tip_wet_sand`, `tip_cracked_shell`, `tip_current`, `tip_coconut`, `tip_pit`, `tip_crab`, `tip_late_egg`, `tip_tide_pool`, `tip_gull_shadow`). World 1's rock and shell intros are hand-made levels 4-7.
- **Tutorial** (world 1, levels 1-10, `Tutorial.kt`): hand-drawn grids; par and gull moves come from the solver. L1 one hatchling, one swipe; L2 two hatchlings slide together; L3 one hatchling stops another; L4-5 rocks; L6-7 shells (L7 is the first DOWN); L8 gull-counter tip; L9 three hatchlings packed against a shell; L10 one hatchling that needs R, D, L, U. All four directions are used by level 7.
- **Gull slack**: world 5 uses par + 2 for levels 1-15 and par + 1 for 16-30. Daily Tide puzzles use par + 3.
- **Daily Tide**: 366 puzzles built from the world 2-5 rule sets with par 4-10, a little harder toward each 7-day block's end. The app picks puzzle `dayOfYear - 1`.
- **Solver speed and the hint budget**: about 1 microsecond per engine move and 5 per solver state on the build machine's JVM. The solver now prunes states with an admissible lower bound: per-cell relaxed slide distance to the ocean (slides through currents and pools, stop anywhere on the path), the maximum over hatchlings, with egg timers added. Measured on the shipped set, warm JIT: worst from-start solve 52 ms, median 0.35 ms. Hints from 12,948 random mid-game positions (4,686 provably unsolvable) peaked at 56 ms. Phones are slower; the 200 ms target still needs confirming on a mid-range device (see Device verification).
- **Names**: theme word lists per world ("Moon Current" style), unique per world and across the Daily Tide.

Gate result: `LevelValidationTest` green: 150 world levels and 366 dailies load, solve within their gull counter, store the solver's par, stay under the hint budget, and every intro/showcase level uses its mechanic on the optimal path.

## M3 Render foundation

- **Threading.** The Filament engine is created and used on the main thread. `Choreographer` drives frames; Filament's own driver thread does the GPU work. Asset bytes load on IO dispatchers and are uploaded on the main thread.
- **One surface, many stages.** A single `SurfaceView` (via `UiHelper`) sits behind the whole Compose tree. Screens switch `Stage`s (board, title beach, archipelago, journal model) in one shared `Scene`, so navigating never recreates the GL surface. The Activity declares every config change, so rotation, folding and resizing only resize the viewport.
- **Frame pacing.** Frame caps come from the Settings frame-rate mode (Battery 30 / Standard 60 / Smooth = display max, also passed to `SwapChain.setFrameRate`) and the quality tier. Stages that report nothing animating drop to 30 fps. Rendering stops in `onPause`.
- **Camera framing.** `CameraRig` binary-searches the closest distance at which the board, pieces included, fits the free viewport between HUD bars (or side columns) with an 8% margin. It then centres the board with `Camera.setShift` (NDC), so no part of the board hides under the HUD. Unit-tested for portrait phones and landscape tablets.
- **Animation.** `MoveTimeline` turns a `MoveResult` into poses: 60 ms head-turn anticipation, 85 ms per cell, ease-in on the first and ease-out on the last moving tick, a squash to 85% on stop, dives for saves, shrink-and-grow teleports, then a 0.3 s hazard phase (crab steps, shadow glide, egg wobble and hatch). The board dispatches events at the time they appear on screen, for audio, haptics and particles.
- **Zero per-frame allocation.** The board's update loop uses index loops, preallocated matrices and pose objects, and per-visual visibility flags (no boxed entity sets).
- **Input.** `SwipeDetector` fires during `ACTION_MOVE` after 24 dp within 35 degrees of an axis, once per gesture, and ignores gestures that start inside `WindowInsets.systemGestures`. `GameController` applies a swipe at once and buffers at most one more while the board animates. Arrow keys, WASD and the D-pad map to swipes; gamepad X/Y/L1 map to undo/restart/hint.
- **Frosted panels.** `RenderEffect` cannot sample a `SurfaceView`, so in-screen panels blur their own soft colour layer with `RenderEffect` on API 31+ (plain translucency below). Full-screen overlays (pause, results, tips) are dialog windows that use window blur-behind on API 31+ when the device enables cross-window blur, so the 3D scene really is frosted behind them.
- **Primitives.** Until the asset pipeline lands (M4), the board uses procedural meshes uploaded with `SurfaceOrientation` tangent frames.

Gate result: `./gradlew check` green; render unit tests (framing, dolly, timelines) and game input tests (swipe thresholds and angles, gesture insets, input buffer, undo, process-death JSON round trip) pass. The debug APK packages the matc-compiled materials. On-device rendering could not be exercised in this container (no emulator), so it is listed under Device verification.

## Device verification (to run on hardware or an emulator with GPU)

These gates need a device and are wired but not yet executed:

- Rendering smoke test (title and one level, no GL errors, screenshots).
- Instrumented UI and lifecycle tests (navigation, rotation/fold/resize mid-animation, process death).
- Macrobenchmark startup and frame timing, monkey run, PSS during world 5.
- Hint latency on a mid-range phone (target under 200 ms; JVM worst case 56 ms).
