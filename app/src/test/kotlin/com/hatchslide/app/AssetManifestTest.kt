package com.hatchslide.app

import com.hatchslide.core.designsystem.Licenses
import com.hatchslide.core.render.assets.AssetCatalog
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec section 8, size rules: every generated file in assets_manifest.json is referenced by the code,
 * and everything the code loads was generated. Run `python tools/assetgen/build_all.py` first.
 */
class AssetManifestTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }

    private class Entry(val path: String, val bytes: Long, val category: String, val referencedBy: String)

    private val entries: List<Entry> by lazy {
        val manifest = Json.parseToJsonElement(File(root, "assets_manifest.json").readText()).jsonObject
        manifest.getValue("files").jsonArray.map {
            val o = it.jsonObject
            Entry(
                o.getValue("path").jsonPrimitive.content,
                o.getValue("bytes").jsonPrimitive.long,
                o.getValue("category").jsonPrimitive.content,
                o.getValue("referencedBy").jsonPrimitive.content,
            )
        }
    }

    /** Asset path as the app opens it: generated-source prefixes and texture-format suffixes removed. */
    private fun assetPath(path: String): String? {
        val prefixes = listOf("app/src/main/assets-gen/", "hd_assets/src/main/assets/")
        val prefix = prefixes.firstOrNull { path.startsWith(it) } ?: return null
        return path.removePrefix(prefix).replace("#tcf_astc", "").replace("#tcf_etc2", "")
    }

    @Test
    fun manifestListsRealFilesWithTheirSizes() {
        assertTrue(entries.isNotEmpty())
        for (e in entries) {
            val file = File(root, e.path)
            assertTrue("${e.path} missing", file.exists())
            assertEquals("${e.path} size", file.length(), e.bytes)
            assertTrue("${e.path}: referencing file ${e.referencedBy} missing", File(root, e.referencedBy).exists())
        }
    }

    @Test
    fun everyGeneratedFileIsReferenced() {
        val catalog = AssetCatalog.referencedPaths()
        val licences = Licenses.FONTS.map { it.asset }.toSet()
        for (e in entries) {
            when (e.category) {
                "fonts" -> {
                    val name = File(e.path).nameWithoutExtension
                    val source = File(root, e.referencedBy).readText()
                    assertTrue("${e.path} not used in ${e.referencedBy}", "R.font.$name" in source)
                }

                "licenses" -> assertTrue("${e.path} not listed in Licenses", assetPath(e.path) in licences)

                else -> assertTrue("${e.path} is not in AssetCatalog", assetPath(e.path) in catalog)
            }
        }
    }

    @Test
    fun everyCatalogAssetWasGenerated() {
        val generated = entries.mapNotNull { assetPath(it.path) }.toSet()
        for (path in AssetCatalog.referencedPaths() + Licenses.FONTS.map { it.asset }) {
            assertTrue("$path is loaded but not generated", path in generated)
        }
        // Each texture exists in both compression formats, so texture targeting covers every device.
        for (texture in AssetCatalog.Textures.ALL) {
            for (format in listOf("astc", "etc2")) {
                assertTrue(entries.any { it.path == "hd_assets/src/main/assets/textures#tcf_$format/$texture.ktx" })
            }
        }
    }
}
