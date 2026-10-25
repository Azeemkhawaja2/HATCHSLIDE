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

## M4 Assets

- **One command.** `python tools/assetgen/build_all.py` (Gradle `generateAssets`) runs four deterministic stages (fonts, textures, skies, models), skips outputs whose code and parameters are unchanged, and writes `assets_manifest.json` with every file, its size and the code that loads it. `AssetManifestTest` fails if a generated file is not referenced by `AssetCatalog` (or the font and licence lists), or if the code loads something that was not generated.
- **Textures.** Tileable spectral fBm and periodic Worley noise build five sand looks, two sand detail sets, rock, basalt, a shell atlas, seven species carapaces, the shared 4096 carapace detail normal (the only hero texture), turtle skin, two water normal maps, foam, caustics, a 4x4 props atlas and a 4x4 effects atlas. Colour mips are filtered in linear light; normal mips are renormalised.
- **No mipgen.** Filament publishes `matc` and `cmgen` on Maven Central, but not `mipgen` (it ships only in the GitHub release archives, which this build environment cannot download). `tools/assetgen/textures/ktx.py` therefore writes the mipmapped KTX1 files itself: ASTC through the ARM `astcenc` encoder (6x6 colour, 5x5 normals, 8x8 masks) and ETC2/EAC through `etcpak`. ETC2 copies of ORM, mask and hero-normal textures drop their top level, since only GLES 3.0 devices without ASTC use them. (M9 restored the full levels to reach the download floor.) The runtime still loads everything with filament-utils' `KTX1Loader`.
- **Tier texture limits.** `KtxTrim` drops KTX levels above the tier's limit before upload (Low 1024, Medium 2048, High 4096 for the hero normal and 2048 otherwise), so a Low-tier phone never allocates a 2048 level.
- **Texture targeting.** Textures and skyboxes live in `textures#tcf_astc` and `textures#tcf_etc2` in the `hd_assets` install-time pack; Play serves one folder as `textures/`. `TextureStore` probes `textures/`, then the ASTC folder when the GPU supports ASTC, then ETC2, so local debug APKs (which carry both folders through the debug source set) work too.
- **Skies.** Each environment's 4096x2048 HDR panorama (gradient, sun or moon disc with halo, layered procedural clouds, stars and Milky Way at night, volcanic haze) goes through `cmgen` for the IBL (specular cubemap with spherical harmonics in its metadata). The skybox is a 768-pixel sRGB cubemap compressed like the other textures, so it costs about a tenth of cmgen's float skybox.
- **Sun direction.** The key light keeps the azimuth of the sun painted in each sky but is raised to at least 38 degrees (28 on the title beach), so dawn and dusk shadows never hide a neighbouring cell; the painted sun disc stays at its low, golden-hour height.
- **Models.** Blender is not available, so models are built in Python (trimesh, numpy, pygltflib per spec section 8): 40 models, each with LOD0 and LOD1, with smooth seam-welded normals, real UVs (atlas cells or projections) and baked AO in vertex colour. The hatchling LOD0 has 9.2k triangles (limit 15k). Animated parts are separate named nodes: head, eyelids on hinge nodes, four flippers and tail; crab claws and pincers; gull wings; the egg's top half. A software preview renderer (`models/preview.py`) renders every model from three views for review; that is how inside-out faces, a malformed conch and a self-intersecting sea fan were caught and fixed.
- **Materials.** Custom `.mat` files compiled by matc 1.77.3: `terrain` (sand projected from above, triplanar rock on steep faces, board mask for wet sand and footprints, caustics and tint under the waterline), `water` (four Gerstner waves, two scrolling normal maps, shallow-to-deep colour and a lapping foam band from the shoreline texture, emissive bioluminescence and splash flares; transparent blending keeps Filament's Fresnel specular for sky reflections and sun glints), `textured`, `turtle_shell` (clear coat), `rock` (object-space triplanar), `current` (flowing chevrons), `glow`, `sprite` and `decal`. gltfio loads the models; every primitive is rebound to these instances by its glTF material name.
- **Diorama.** `IslandBuilder` builds each level's scenery in Kotlin: superellipse-bevelled tiles with tiny height noise (holes for pits and tide pools); an island chunk from a signed distance field of the land cells plus a two-cell margin, with rounded corners, a noisy coastline away from the board and sculpted cliffs dropping into the sea; a terrain mask (wet sand on wet tiles and near the surf); and a shoreline-distance texture for the foam. Ocean margins stay open so the surf row connects to the sea. Decoration (palms, umbrellas, lanterns, coral, driftwood, cliffs and more, per world) goes on free margin spots, tall pieces never on the near side where they would cover the board; spots more than 1.3 cells out use LOD1, and the Low tier uses LOD1 everywhere.
- **Hatchling animation.** The body turns toward the move with a lagging heading while the head leads it (the anticipation), flippers paddle at 2.5 Hz while sliding and idle gently at rest, the body leans into the slide and squashes on stops, eyelids blink every 2 to 5 s, and the tail wags. Saved hatchlings dive to the sea surface, then swim out to open water and fade (with a bioluminescent flare at night). At level start each hatchling breaks out of an egg, 80 ms apart. Crabs snip, pincers gape, gulls circle over their shadows with flap bursts between glides, and late eggs crack open when they hatch.
- **Gull shadows** are soft gull-silhouette sprites from the effects atlas, turned toward the cell they move to next.
- **Deferred to M7 (polish):** hatchling footprint decals (the terrain mask already reserves a channel for them) and all particles.

Gate result: every primitive is replaced (the only procedural meshes left are the island, tiles, ocean, the hint arrow and sprite quads); unit tests cover KTX trimming, island geometry (no tile over water, wet mask, shoreline border, decor off the board, exact distance transform) and the asset manifest. Asset totals: 4.1 MB models, 12.6 MB IBL, about 85 MB of textures and skyboxes per compression format; about 112 MB per device before audio. Visual checks were done with the software preview renderer; on-device rendering remains under Device verification.

## M5 UI

- **Navigation.** One `NavHost` with type-safe routes: Title, Main menu, World map, Level select, Gameplay (with Pause, Complete and Failed overlays), Daily Tide, Turtle Journal, Achievements and stats, Settings (with Credits and licences and Privacy policy pages); the first-run tutorial is the hand overlay on world 1 levels 1 to 3. The title pops itself, so back on the main menu leaves the app; gameplay back opens Pause; everything else pops one screen (navigation-compose's predictive back).
- **3D behind every screen.** The title, main menu and menu screens share `BeachStage`: the title glides in from the sea while a hatchling hatches; the menu holds a swaying view; the Journal moves in close on a hatchling wearing the selected species' skin, turned by drag or by the turn buttons. The map and level select share `ArchipelagoStage` (five island models, a dotted sea path of sprites, foam from its own shoreline texture). Panels report their bounds so cameras shift the subject into the free space.
- **Persistence.** DataStore with two kotlinx-serialization JSON documents: `progress.json` (level records, tips seen, tutorial moves, skin, achievements with dates, stats, Daily Tide history, the in-progress run) and `settings.json`. Corrupt files reset to defaults instead of crashing. The splash screen stays until both are read. The run is saved after every counted move, so a closed app resumes the board (the saved-state handle still covers process death).
- **Rules** live in `ProgressRules` (pure, unit-tested): levels open in order; levels 26 to 30 also need 45 stars in their world; world N+1 opens when level 30 of world N is beaten. Species: Green from the start, one per completed world, Flatback after 7 Daily Tides. The Daily Tide streak grows on consecutive local days and shows 0 once a day is missed. 24 achievements, each reachable (tested).
- **Unlock cards** after the stars come from `recordWin`: newly opened island, newly met species, newly earned achievements.
- **Daily Tide look.** Daily puzzles carry no world, so they use the look of the newest mechanic on the board (tide pools or gull shadows: Volcanic Isle; crabs or eggs: Crab Reef, and so on).
- **Lose animation (spec section 2).** At three moves left a seagull circles the board. On a loss it swoops low, the hatchlings tuck head, flippers and tail into their shells and close their eyes, and the gull lands on the nearest rock (or beside the board) with its wings folded. The Failed panel waits 1.9 s for this (immediately with reduced motion).
- **Settings applied now:** volumes and haptics are stored (audio arrives in M6), quality and frame rate go to the renderer, high contrast switches the UI palette, reduced motion and the left-handed HUD and arrow pad change gameplay. Auto quality uses Medium until the auto tier lands in M7, which also adds colour-blind tile symbols.
- **Privacy policy and credits** are in the app, so they read offline; font licences open from the bundled OFL texts.
- **Accessibility.** Every control has text or a content description; the map's island badges are real buttons, so keyboards, gamepads and TalkBack reach worlds without touching the 3D view; plurals cover every count.

Gate result: `./gradlew check assembleDebug` green with no lint warnings; new unit tests cover unlocks, the star gate, world unlocks, best results, the Daily Tide streak, species and achievements. Screen-by-screen checks on devices (portrait, landscape, tablet, foldable) are listed under Device verification.

## M6 Audio and haptics

- **All synthesised.** `tools/assetgen/audio/` is a small music system in NumPy and SciPy: modal and additive voices (marimba, glass and bass marimba, kalimba, steel drum, chimes, woodblock), Karplus-Strong plucks (nylon guitar, ukulele), FM brass swells, a soft pad and bass, and a drum kit (kick, low drum, shaker, conga, rim, clap). A sequencer renders notes with humanised timing and velocity, sends them to a block-vectorised Freeverb, and masters with gentle compression, BS.1770 loudness normalisation to -14 LUFS and a look-ahead limiter at -1 dBFS. Nothing comes from recordings, so there is nothing to license; `assets_src/audio/override/` can replace any output with a CC0 file.
- **Real structure.** Each track has a key, mode and tempo and is composed from motifs into intro, A, B, A' and outro sections, with its own chord rhythm, bass style and groove: Title D major 96 BPM (marimba, steel drum, nylon plucks, shaker), Sunrise Cove G major 88 BPM with a slow build and kalimba lead, Coral Coast A major 112 BPM (ukulele plucks, congas, clap), Moonlit Bay E-flat Lydian 66 BPM (glass marimba, pads, chimes), Crab Reef D Dorian 104 BPM with swing (woodblocks, bass marimba), Volcanic Isle D Dorian into F major 92 BPM (low drums, brass swells). Each is 2:40 to 2:47, 48 kHz stereo, Vorbis q6.
- **Seamless loops.** Tracks are rendered with a reverb tail that is folded back onto the start, so the loop point carries the same reverb as the rest of the track. Ambience (surf, wind, insects, rumble) is built from noise shaped in the frequency domain of a circular buffer, with envelopes of whole cycles, so it has no seam at all. Loudness targets: music -14 LUFS, ambience -22 to -26 LUFS.
- **Stingers in the base module.** The victory (3 s) and fail (2 s) stingers play through SoundPool, which needs a file descriptor on an uncompressed APK asset; they ship at `music/` in the base module (0.1 MB) while the six tracks stay in the `hd_assets` pack. AAPT stores `.ogg` uncompressed by default.
- **Chunked encoding.** libsndfile's Vorbis encoder crashed on single writes of whole tracks, so `write_ogg` writes one second at a time.
- **37 effects** cover the spec's list plus gameplay sounds it implies (pit fill, gull squawk, teleport pop, hint sparkle, undo, restart, tuck, overlay open, swim paddle). Each effect carries the world whose mechanic uses it; `AudioEngine.preloadWorld` keeps the shared ones loaded and swaps the rest when a level's world changes (all of them for the Daily Tide).
- **Playback.** SoundPool with 12 streams for effects; two Media3 players crossfade music over 1.5 s; two more loop the ambience bed (surf plus wind, insects or rumble per world). Effects are panned by where they happen on the board. The sand-slide loop's pitch rises with slide length; the save chime rises a major third for each extra hatchling saved in the same move. The music dips under the stingers.
- **Ducking ambience under swells.** Each track ships a 4 Hz loudness envelope (`.env`, one byte per 250 ms). The engine reads it at the player position and lowers ambience by up to 35% when the music swells, with no audio analysis on the device.
- **Focus and lifecycle.** The engine requests focus (`USAGE_GAME`) when the activity starts, ducks to 25% on transient loss, pauses on loss and resumes on regain, and pauses everything in the background, abandoning focus.
- **Asset-only players.** The players are built with an asset-only `ProgressiveMediaSource` and only the OGG extractor. R8 then strips Media3's HTTP data sources, `DefaultDataSource` and `DefaultMediaSourceFactory` from release builds. The only `java.net` type left in the release dex is `java.net.URL`, as the return type of `ClassLoader.getResource`.
- **Haptics** use `View.performHapticFeedback` only: `CLOCK_TICK` on swipe, `REJECT` on a no-op, `CONFIRM` on a save and `GESTURE_END` on every stop. Pieces that stop within 60 ms of each other share one pulse, so a board-wide stop is a single tap. Before API 30, `KEYBOARD_TAP`, `LONG_PRESS` and `CLOCK_TICK` stand in for the newer constants. The in-game toggle gates all of them.

Gate result: `./gradlew check assembleDebug` green with no lint warnings; `AssetManifestTest` covers the audio catalogue both ways (every generated sound is loaded, every loaded sound is generated) and every `Sfx` entry is played by the game. Audio sizes: music 16.4 MB (budget 30), effects and ambience 1.5 MB (budget 8). Listening checks on a device are listed under Device verification.

## M7 Polish

- **One pooled particle draw.** Filament's instancing carries only per-instance transforms, so the particle system writes its quads on the CPU into one renderable. That keeps a single draw call for every particle and decal, and per-particle colour, atlas cell, size and fade. The pool holds 1024 particles as fixed structure-of-arrays state; dead ones are swapped out. Each frame the live quads go into one of three preallocated vertex buffers in turn, so Filament is never reading a buffer being rewritten. The index buffer is static, and nothing is allocated per frame. The quads draw at priority 7, after the water.
- **One material for every effect.** `particle.mat` takes a premultiplied vertex colour. Alpha 0 makes a quad additive (sparkles, fireflies, at 2.5 times brightness); black with alpha only darkens what is underneath. Footprints use the latter, so they read correctly in every world's lighting. Unlit particles are scaled by a per-world `fxLight` (0.3 at night), and sand dust is tinted to each world's sand.
- **Effects** (`BoardEffects`):
  - a sand trail behind sliding pieces, or spray on currents;
  - a sand puff, wet clumps or spray on every stop; pieces that never moved raise none;
  - droplets, spray, a ripple ring and sparkles on a save, and a smaller splash when a coconut sinks;
  - dark dust in pits, shell and egg fragments (including the level intro and the title hatchling), and shimmer at both ends of a tide-pool teleport;
  - shell confetti when the third star lands;
  - 22 fireflies drifting and blinking at Moonlit Bay, and 48 flakes of ash drifting down on Volcanic Isle.
- **Footprints are decals.** They are ground quads from the atlas footprint cell, one every 0.42 cells along a hatchling's slide on dry or wet sand, fading linearly over 4 s. The terrain mask's spare channel (planned in M4) is 8 samples per cell, too coarse for the print shape, so `terrain.mat` no longer reads it.
- **Reduced motion** emits 20% of every burst and ambient target, rounded randomly so single particles still appear. Footprints stay because they do not move. Camera shake and idle sway stay off, waves stay calm, and screen transitions only fade.
- **Colour grading.** Each world gets a cached Filament `ColorGrading` with the `ACESLegacy` tone mapper. That is Filament's default operator, so the exposure and light levels tuned so far still hold. On top of it each world has a white balance, contrast, vibrance, saturation and shadow and highlight tints:
  - Sunrise Cove: peach;
  - Coral Coast: crisp and vivid;
  - Moonlit Bay: cool, slightly desaturated, with a little night adaptation;
  - Crab Reef: warm amber;
  - Volcanic Isle: an orange, contrasty dusk.
- **Transitions.** Screens fade and settle from a 4% zoom (fade only with reduced motion). One Filament view cannot cross-fade two scenes without extra render targets, so when the 3D stage changes (beach, archipelago, board) the new stage is revealed from deep ocean blue over 450 ms. The camera moves already in place cover the rest: the title glide, the map flights, the level-start dolly and the win follow.
- **Auto quality, at first launch.** The game reads `GL_RENDERER` from a throwaway 1x1 EGL pbuffer context and maps it to a tier:
  - Adreno 730+ and 640 to 699: High;
  - Adreno 700 to 729, 616 to 639 and 540 to 599: Medium;
  - Mali-G78, G7xx, Immortalis and Xclipse: High;
  - Mali-G57 to G77 and G5xx to G6xx: Medium;
  - older Mali, PowerVR Rogue and other entry GPUs: Low;
  - anything unknown: Medium.

  Memory caps the result: 3 GB phones and low-RAM devices get Low, 4 GB phones at most Medium. The tier is stored in settings. Android Game Mode shifts it one step on every launch (Performance up, Battery down), and the existing `game_mode_config` declares that the game handles both modes itself.
- **Auto quality, at run time.** While quality is Auto, each second's presented-frame intervals are reduced to a 95th percentile. Five seconds in a row more than 20% over the frame budget drops one tier, followed by an 8 s cooldown. The budget follows the frame-rate setting, the tier cap, idle menus and the display rate. Stage changes and hitches over half a second are ignored. A drop is saved for later launches, unless the device is thermally throttled (moderate or worse), in which case it lasts for the session only. M8's thermal stepping handles the rest.
- **Colour-blind tiles** add flat light-on-dark symbols. Tide-pool pairs get a circle, triangle, square and diamond, on top of their cowrie count, and wet sand gets a droplet in the tile corner. Pairs no longer rely on hue, and wet sand no longer relies on tone. The symbols toggle instantly.
- **Accessibility audit.** Every interactive control is at least 48 dp; the smallest are the 48 dp Journal buttons. TalkBack announces each move (since M5). Text scaling to 200% in menus is on the device checklist.

Gate result: `./gradlew check assembleDebug` green with no lint warnings. New unit tests cover:
- the particle pool: capacity, lifetimes, group counts, ground-first order, billboard orientation, linear footprint fade, additive encoding and density scaling;
- the board effects: footprints only for hatchlings on sand, the 4 s fade, the 80% cut, no dust on rock, fireflies and ash only in their worlds and kept topped up, confetti;
- auto quality: renderer strings, memory caps, Game Mode steps, and the frame monitor (steady play never drops, sustained overrun drops once and then cools down, good seconds reset it, hitches and holds are ignored).

## M8 Performance and adaptation

- **Nothing allocated per frame.** `FrameAllocationTest` runs the pure-Kotlin parts of the frame thousands of times and checks the thread's allocated bytes: move timelines, piece trails and effects, the particle pool and its quad writer, the camera animator and the frame monitor. It found two allocations, both now fixed. `MoveTimeline.pose` built an `IntRange` per piece per frame and boxed a `Long` teleport key; it now uses per-track primitive arrays. In the render loop, the frame-cap calculation built and filtered a list, and the overlay loop created an iterator. Frame observers take primitive arguments through a plain interface, so they do not box either.
- **Baseline profile.** `baselineprofile/` drives the real UI with UiAutomator: cold start, the title, the main menu, the map, Sunrise Cove's level select, level 1, swipes in every direction and back home. It holds:
  - the profile generator (`BaselineProfileRule`, with the startup profile);
  - `StartupBenchmark` (`StartupTimingMetric`, with and without the profile);
  - `GameplayBenchmark` (`FrameTimingMetric` while hatchlings slide).

  The app reports fully drawn when the title beach's first 3D frame is presented. Until the profile can be generated on a device, `app/src/main/baseline-prof.txt` seeds one with wildcard rules for the game's own code and Filament's Java bindings; libraries bring their own.
- **ADPF.** On API 31+ a `PerformanceHintManager` session covers the frame-loop thread and Filament's driver thread, found by its `FEngine::loop` name in `/proc/self/task`. Each frame's CPU work is reported against the current frame budget, which is retargeted when the budget changes.
- **Thermal stepping.** Thermal headroom (10 s forecast, API 30+) and thermal status are read every 2 s in the foreground, off the main thread. Headroom at 0.85 or above, or a moderate status, steps quality down one tier, at most once every 10 s and two tiers at most. A severe status goes straight to two steps. 30 cool seconds (headroom under 0.6) step back up one tier. This applies to every quality setting, not just Auto, because it is temporary and pre-empts the system's own throttling. The step-down and step-up rules are unit-tested.
- **World switches.** A new level reads its world's sky, IBL and terrain textures and builds its island geometry on a background thread first, while the previous screen's 3D stage stays visible. Only the GPU uploads then run on the main thread, after which the board appears through the stage curtain. If a load passes 300 ms, an animated tip panel fades in: a waddling hatchling, plus a game tip or a sea turtle fact. Input is ignored until the board is listening, since a command sent during the load would be lost. The level-start dolly now follows the board's rest pose every frame, so it holds when the first layout arrives after the load.
- **Debug timing.** Debuggable builds log swipe-to-first-movement and world-switch and level load times under `HatchslidePerf` (spec section 11 asks for these two logs). Release builds never format a message.
- **Input.**
  - Keyboard: arrows or WASD swipe, U undo, R restart, H hint, P pause, Escape back.
  - Gamepad: D-pad or stick swipe, X undo, Y restart, L1 hint, Start pause, B back, A select.
  - Mouse wheels and touchpads pan the world map; mouse drags swipe on the board.

  Android's generic key map already falls back A to D-pad centre and B and Escape to Back when the app does not handle them, and the view system turns unhandled stick and hat motion into D-pad keys. The game relies on both instead of translating input itself; that also avoids AndroidX's restricted `dispatchKeyEvent`. Key repeats from a held stick or key are ignored on the board, so one push is one swipe. Menus are ordinary focusable Compose buttons, so D-pads and keyboards move between them.
- **Large screens and windows.** The activity handles rotation, folding, resizing and density changes itself, so the Filament surface resizes, the game state stays in its ViewModel, and the board re-frames to the new free space. It is resizeable with no aspect-ratio limit (no letterboxing), and the HUD moves to side columns on wide windows (M5).

Gate result: `./gradlew check assembleDebug` and the benchmark module's build are green with no lint warnings. New unit tests cover zero per-frame allocation and the thermal stepper. Benchmark numbers (startup, frame timing on the Medium and Low tiers, PSS during world 5, world switch time) need a device or emulator and are listed under Device verification.

## M9 Release

- **Release gates in `./gradlew check`.**
  - `sizeReport` builds the full APK set from the release bundle with bundletool. It passes the SDK's aapt2, because the Maven artifact does not embed one. It then measures `get-size total` for five device profiles (`tools/verify/devices/`) and writes `SIZE_REPORT.md`. It fails outside 100 to 190 MB, where 1 MB = 10^6 bytes, as Play reports sizes. The profiles declare their OpenGL ES version, so bundletool picks the right texture format.
  - `verify16KbAlignment` reads every arm64-v8a and x86_64 library in the bundle and requires 16 KB `PT_LOAD` alignment. It also requires every such library in every generated APK to be stored uncompressed at a 16 KB offset, checked with its own zip reader and with the SDK's `zipalign -c -P 16`. The NDK's `llvm-readelf` is not installed here, so the ELF check is done directly.
- **Reaching the size floor with real content.** The first measurement was 75 MB on ETC2 devices and 97 MB on ASTC devices: Play deflates the textures and float IBL far more than the raw budget assumed. Padding is not allowed, so content was raised where the game was below the spec's own budget:
  - Skyboxes went from 768 to 1536 pixels per face. They fill the title, menu, map and Journal views, and were well below the screen's pixel density. Low still loads them, since they have no mips; the sky is only ever magnified.
  - Three new cliff and boulder rock sets (coral limestone, moonlit slate, crab-reef sandstone) complete "5 world sets" next to granite and basalt. Board rocks, cliffs and boulders now take the world's rock through one retextured material instance, so no shader samplers were added.
  - Music grew to 3:05 to 3:23 per track, inside the spec's 2:30 to 3:30, with one or two more sections each. Ambience loops grew from 30 to 40 s to 90 to 120 s, with seeded irregular wave, gust, chirp and boom timing.
  - The ETC2 copies keep the full-resolution top level of ORM maps, masks and the hero normal map. M4 dropped it to save download, on the grounds that only GLES 3.0 devices without ASTC use ETC2. With the other changes, ETC2 devices still came out at 91 MB, under the floor. The full levels give those devices the same detail as ASTC ones when they run Medium or High.

  Result (SIZE_REPORT.md): 122.0 MB on ASTC phones and tablets; 107.7 to 108.2 MB on ETC2 devices (arm64, armv7 and x86_64). All are inside 100 to 190 MB, and ASTC is close to the spec's 145 MB target.
- **Silent loop tails fixed.** Measuring the music found that three tracks declared more bars than their form filled. The title, Coral Coast and Volcanic Isle loops ended in 9 to 21 s of near silence (-41 to -54 dBFS). Each song's length now comes from its finished form, and the sequencer refuses a loop whose length was never set.
- **Signing.** `bundleRelease` signs with the upload key from `keystore.properties` or the `HATCHSLIDE_UPLOAD_*` environment variables. This was checked with a throwaway key kept outside the repository (`jarsigner -verify` on the bundle). The real key is created by `tools/release/create_upload_key.sh`, and both its files are git-ignored. Play App Signing holds the app signing key.
- **Instrumented tests** (`app/src/androidTest`, UiAutomator over the real game):
  - every menu screen and back;
  - solving level 1 with the solver's path to the complete screen;
  - losing level 1 to the gentle fail screen;
  - settings that survive a restart;
  - rotation in the middle of a slide, keeping the board and undo;
  - a saved run resuming after the app closes;
  - rendering and audio stopping in the background and resuming;
  - a rendering smoke test whose screenshots must show a real scene.

  Compose's idling is not used, because the 3D view renders continuously and the map polls island positions every frame. The tests reset progress through the app's repository. `tools/verify/monkey.sh` runs the 10,000-event monkey and fails on any crash or ANR.
- **Store folder.** `store/` has:
  - the listing text (name 30 characters, short description 65, full description 1,633);
  - `privacy_policy.md` and `.html`, the same text as in the app;
  - `console_answers.md` (data safety: no data collected or shared; IARC answers; target audience 13+ recommended, Families-ready);
  - `release_notes.md` and a `test_notes.md` plan and log for the 12-tester, 14-day closed test.

  `icon_512.png` is rendered from the launcher icon's own vector layers, converted to SVG and drawn by Chromium, centred slightly wider than the safe zone. Recommending 13+ follows spec section 10: it keeps review simplest, and the game also meets the Families policy if the developer includes younger players.
- **Screenshots and feature graphic need a device.** They must come from the real game, and this environment has no emulator. PhotoMode is the debug-only `StoreCaptureTest`, which runs only with `-e store <set>`:
  - it sizes the display with `wm size` and `wm density`;
  - it unlocks worlds 1 to 4 and opens real scenes through the UI;
  - it holds them still with the engine's `timeScale` (particles included) and saves screenshots;
  - for the feature graphic, it hides the UI.

  `tools/store/capture.sh` runs it for the phone, 7-inch, 10-inch and feature sets. `tools/store/compose_store_art.py` adds short captions to the 8 phone shots and the logo to the 1024x500 feature graphic. The only trace in release builds is an inert flag that nothing sets, plus the engine's `timeScale`.

Gate result: `./gradlew clean check bundleRelease` is green, signed with a throwaway upload key passed through the environment variables (`jarsigner -verify`: jar verified):
- 108 unit tests pass, and the solver validates all 516 puzzles;
- lint and ktlint report no issues in any module;
- the merged release manifest has targetSdk 37, minSdk 26, `appCategory="game"` and zero permissions;
- the no-network and no-TODO scans pass;
- the asset manifest matches the code both ways;
- the size report passes;
- all 40 arm64-v8a and x86_64 library entries pass the 16 KB alignment check.

The instrumented tests, benchmarks, monkey run and store capture compile and are ready to run on the device list in spec section 12; see Device verification.

## Device verification (to run on hardware or an emulator with GPU)

These gates need a device and are wired but not yet executed (`./gradlew :app:connectedDebugAndroidTest`, `tools/verify/monkey.sh`, the `baselineprofile` benchmarks and `tools/store/capture.sh`):

- Rendering smoke test (title and one level, no GL errors, screenshots), including water, the terrain mask and model material rebinding.
- Instrumented UI and lifecycle tests (navigation through all 14 screens, rotation/fold/resize mid-animation, process death, resuming a saved run).
- Macrobenchmark startup and frame timing (record the numbers here; generate and commit the baseline profile), monkey run, PSS during world 5, world-switch times from the `HatchslidePerf` log, the ADPF session being accepted, thermal stepping under sustained load, and gamepad sticks, hats and buttons on a real controller.
- Hint latency on a mid-range phone (target under 200 ms; JVM worst case 56 ms).
- Store art: run `tools/store/capture.sh` and review the screenshots and feature graphic before upload.
- Polish: particle and footprint look per world (premultiplied output, night tint, ash), colour grades, the stage curtain, colour-blind symbols over tide pools, menus at 200% text scale, and the auto tier's choice and drop on a Low, a Medium and a High reference phone.
- Audio: background and foreground pause and resume, ducking for a notification and a call, the 1.5 s crossfade between worlds, loop points, effect latency and panning, and haptics on a phone with and without API 30 constants.
