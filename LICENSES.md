# Licences

HATCHSLIDE's code and its generated art, models, textures, skies, music and sound effects are original work created for this project.

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
| Pillow | MIT-CMU (HPND) | Image previews |
| trimesh | MIT | Model checks |
| pygltflib | MIT | glTF writing |
| soundfile | BSD-3-Clause | Audio (M6) |

## Runtime libraries

| Library | Licence |
| --- | --- |
| Filament, gltfio, filament-utils | Apache-2.0 |
| AndroidX, Jetpack Compose, Media3 | Apache-2.0 |
| kotlinx.coroutines, kotlinx.serialization | Apache-2.0 |
