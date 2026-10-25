# Licences

HATCHSLIDE's code and its generated art, models, textures, skies, music, ambience and sound effects are original work created for this project. Every sound is synthesised by `tools/assetgen/audio/`; no recordings or samples are used, and `assets_src/audio/override/` is empty.

## Fonts (bundled)

| Font | Licence | Source |
| --- | --- | --- |
| Fredoka (variable) | SIL Open Font License 1.1 | Google Fonts repository, `ofl/fredoka` |
| Nunito (variable) | SIL Open Font License 1.1 | Google Fonts repository, `ofl/nunito` |

The licence texts ship in the app as `assets/licenses/OFL-Fredoka.txt` and `OFL-Nunito.txt`.

## Build-time tools (not shipped in the app)

| Tool | Licence | Use |
| --- | --- | --- |
| Filament `matc`, `cmgen` 1.77.3 | Apache-2.0 | Material compilation, IBL generation |
| ktlint 1.8.0 | MIT | Code style gate |
| bundletool 1.18.3 | Apache-2.0 | Size report |
| ARM astc-encoder (via astc-encoder-py 0.1.12, MIT) | Apache-2.0 | ASTC texture compression |
| etcpak (via the etcpak 0.9.15 Python binding, MIT) | BSD-3-Clause | ETC2/EAC texture compression |
| NumPy, SciPy | BSD-3-Clause | Procedural textures, skies and models |
| Pillow | MIT-CMU (HPND) | Image previews, store screenshot captions and the feature graphic |
| trimesh | MIT | Model checks |
| pygltflib | MIT | glTF writing |
| soundfile 0.14.0 | BSD-3-Clause | OGG Vorbis encoding and reading |
| libsndfile (bundled with soundfile) | LGPL-2.1-or-later | Audio file I/O |
| libvorbis, libogg (bundled with libsndfile) | BSD-3-Clause | Vorbis encoder |
| Playwright with Chromium (pre-installed) | Apache-2.0, BSD-3-Clause | Rendering the 512 px store icon from the launcher icon vectors |

## Runtime libraries

| Library | Licence |
| --- | --- |
| Filament, gltfio, filament-utils | Apache-2.0 |
| AndroidX, Jetpack Compose, Media3 | Apache-2.0 |
| kotlinx.coroutines, kotlinx.serialization | Apache-2.0 |

## Test-only libraries (not shipped)

| Library | Licence |
| --- | --- |
| JUnit 4 | EPL-1.0 |
| AndroidX Test, UiAutomator, Compose UI Test, Benchmark (Macrobenchmark) | Apache-2.0 |
