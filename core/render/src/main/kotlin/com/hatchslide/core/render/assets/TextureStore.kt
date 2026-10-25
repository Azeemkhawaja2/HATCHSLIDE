package com.hatchslide.core.render.assets

import android.content.res.AssetManager
import com.google.android.filament.Engine
import com.google.android.filament.Skybox
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.android.filament.utils.KTX1Loader
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

/**
 * Loads the compressed KTX textures of the HD asset pack through filament-utils' [KTX1Loader].
 *
 * Google Play delivers one texture folder per device (texture compression targeting) and strips its
 * suffix, so installed builds read `textures/`. Local builds carry both `textures#tcf_astc/` and
 * `textures#tcf_etc2/`; ASTC is used when the GPU supports it. Mip levels above the quality tier's
 * maximum size are dropped before upload ([KtxTrim]).
 */
class TextureStore(private val assets: AssetManager, private val engine: Engine) {
    private val textures = HashMap<String, Texture>()
    private val owned = ArrayList<Texture>()
    private val prefetched = ConcurrentHashMap<String, ByteArray>()
    private val loaded: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Largest level kept for ordinary textures and for hero textures ([AssetCatalog.Textures.HERO]). */
    var maxSize = 2048
        private set
    var heroMaxSize = 2048
        private set

    private val directoryLazy = lazy { resolveDirectory() }
    val directory: String by directoryLazy

    /** Samplers shared by every material: trilinear with anisotropy, wrapping for tiling textures. */
    val repeat = TextureSampler(
        TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR,
        TextureSampler.MagFilter.LINEAR,
        TextureSampler.WrapMode.REPEAT,
    )
        .apply { anisotropy = 4f }
    val clamp = TextureSampler(
        TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR,
        TextureSampler.MagFilter.LINEAR,
        TextureSampler.WrapMode.CLAMP_TO_EDGE,
    ).apply { anisotropy = 4f }
    val linearClamp =
        TextureSampler(TextureSampler.MinFilter.LINEAR, TextureSampler.MagFilter.LINEAR, TextureSampler.WrapMode.CLAMP_TO_EDGE)

    /** Applies a tier's limits; textures already loaded keep their size until [evictAll]. */
    fun setLimits(maxSize: Int, heroMaxSize: Int) {
        this.maxSize = maxSize
        this.heroMaxSize = heroMaxSize
    }

    private fun exists(path: String): Boolean = try {
        assets.open(path).close()
        true
    } catch (_: IOException) {
        false
    }

    private fun resolveDirectory(): String {
        val probe = AssetCatalog.Textures.ALL.first() + ".ktx"
        val astc = Texture.isTextureFormatSupported(engine, Texture.InternalFormat.SRGB8_ALPHA8_ASTC_6x6)
        val candidates = buildList {
            add("textures")
            if (astc) add("textures#tcf_astc")
            add("textures#tcf_etc2")
        }
        return candidates.firstOrNull { exists("$it/$probe") } ?: error("HD texture pack missing: no $probe in $candidates")
    }

    private fun read(name: String): ByteArray = assets.open("$directory/$name.ktx").use { it.readBytes() }

    private fun bytes(name: String): ByteArray = prefetched.remove(name) ?: read(name)

    /**
     * Reads textures ahead (a level's new look), so the main thread only uploads them. Safe from any
     * thread once the store has loaded its first texture, which picks the texture folder.
     */
    fun prefetch(names: Iterable<String>) {
        if (!directoryLazy.isInitialized()) return
        for (name in names) {
            if (name in loaded || prefetched.containsKey(name)) continue
            runCatching { read(name) }.onSuccess { prefetched[name] = it }
        }
    }

    /** Forgets prefetched textures that were not used (the player left before the level loaded). */
    fun dropPrefetched() = prefetched.clear()

    /** The texture `textures/<name>.ktx`, loaded once and owned by the store. */
    fun get(name: String): Texture = textures.getOrPut(name) {
        val limit = if (name in AssetCatalog.Textures.HERO) heroMaxSize else maxSize
        val buffer = KtxTrim.trim(bytes(name), limit)
        loaded += name
        KTX1Loader.createTexture(engine, buffer, KTX1Loader.Options()).also { owned += it }
    }

    /** Builds the compressed skybox cubemap for a sky; the caller owns both returned objects. */
    fun skybox(sky: String): Pair<Skybox, Texture> {
        val bundle = KTX1Loader.createSkybox(engine, KtxTrim.trim(bytes(AssetCatalog.Skies.skybox(sky)), maxSize), KTX1Loader.Options())
        return (bundle.skybox ?: error("skybox $sky")) to (bundle.cubemap ?: error("skybox $sky"))
    }

    /** An uncompressed RGBA8 texture filled from [pixels] (width * height * 4 bytes), owned by the store. */
    fun rgba(width: Int, height: Int, pixels: ByteArray): Texture {
        val texture = Texture.Builder()
            .width(width)
            .height(height)
            .levels(1)
            .sampler(Texture.Sampler.SAMPLER_2D)
            .format(Texture.InternalFormat.RGBA8)
            .build(engine)
        update(texture, width, height, pixels)
        owned += texture
        return texture
    }

    fun update(texture: Texture, width: Int, height: Int, pixels: ByteArray) {
        val buffer = ByteBuffer.allocateDirect(pixels.size).order(ByteOrder.nativeOrder()).put(pixels)
        buffer.flip()
        texture.setImage(engine, 0, Texture.PixelBufferDescriptor(buffer, Texture.Format.RGBA, Texture.Type.UBYTE))
    }

    fun release(texture: Texture) {
        if (owned.remove(texture)) {
            textures.entries.firstOrNull { it.value === texture }?.let { loaded -= it.key }
            textures.values.remove(texture)
            engine.destroyTexture(texture)
        }
    }

    fun evictAll() {
        owned.asReversed().forEach { engine.destroyTexture(it) }
        owned.clear()
        textures.clear()
        loaded.clear()
        prefetched.clear()
    }

    fun destroy() = evictAll()
}
