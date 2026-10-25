package com.hatchslide.core.render

import com.google.android.filament.EntityManager
import com.google.android.filament.IndirectLight
import com.google.android.filament.LightManager
import com.google.android.filament.Skybox
import com.google.android.filament.Texture
import com.google.android.filament.View
import com.google.android.filament.utils.KTX1Loader

/** Look of one environment (spec section 6): sky, sun, ambient light, fog and palette tints. */
data class WorldTheme(
    val id: Int,
    val name: String,
    /** Asset base name for the cmgen output: `skies/<sky>_ibl.ktx` and `skies/<sky>_skybox.ktx`. */
    val sky: String,
    val sunDirection: FloatArray,
    val sunColor: FloatArray,
    val sunLux: Float,
    val iblIntensity: Float,
    val zenith: FloatArray,
    val horizon: FloatArray,
    val fog: FloatArray,
    val fogDensity: Float,
    val sand: FloatArray,
    val wetSand: FloatArray,
    val shallowWater: FloatArray,
    val deepWater: FloatArray,
    val nightGlow: Boolean = false,
    val ash: Boolean = false,
) {
    companion object {
        private fun rgb(hex: Int) = floatArrayOf(((hex shr 16) and 255) / 255f, ((hex shr 8) and 255) / 255f, (hex and 255) / 255f)

        val TITLE = WorldTheme(
            0, "Sunrise", "title", floatArrayOf(-0.35f, -0.45f, -0.82f), rgb(0xFFD2A8), 70_000f, 26_000f,
            rgb(0x8EC5E8), rgb(0xFFC9A0), rgb(0xF6D3B8), 0.012f, rgb(0xF2D4A2), rgb(0xC9A777), rgb(0x52D1C8), rgb(0x1B5F8C),
        )
        val SUNRISE_COVE = WorldTheme(
            1, "Sunrise Cove", "world1", floatArrayOf(-0.4f, -0.5f, -0.77f), rgb(0xFFD6AE), 80_000f, 28_000f,
            rgb(0x9CCBEA), rgb(0xFFCBA4), rgb(0xF7D6BC), 0.010f, rgb(0xF3D3A0), rgb(0xC7A271), rgb(0x55D3C9), rgb(0x1C6290),
        )
        val CORAL_COAST = WorldTheme(
            2, "Coral Coast", "world2", floatArrayOf(-0.15f, -0.92f, -0.36f), rgb(0xFFF6E8), 110_000f, 32_000f,
            rgb(0x5FB3EE), rgb(0xC9EBFF), rgb(0xD8EEF9), 0.007f, rgb(0xFBF1E1), rgb(0xD9CAB2), rgb(0x48E0D3), rgb(0x1774B5),
        )
        val MOONLIT_BAY = WorldTheme(
            3, "Moonlit Bay", "world3", floatArrayOf(0.25f, -0.6f, -0.76f), rgb(0xB9CCFF), 9_000f, 9_000f,
            rgb(0x0B1636), rgb(0x2B3F74), rgb(0x1A2850), 0.014f, rgb(0xB6B9C9), rgb(0x8A8FA6), rgb(0x2A8C9E), rgb(0x0A2346),
            nightGlow = true,
        )
        val CRAB_REEF = WorldTheme(
            4, "Crab Reef", "world4", floatArrayOf(0.55f, -0.35f, -0.76f), rgb(0xFFC48A), 75_000f, 24_000f,
            rgb(0x7DA9D6), rgb(0xF6B987), rgb(0xE9C2A0), 0.011f, rgb(0xE8C38E), rgb(0xB89466), rgb(0x46BDB4), rgb(0x174F78),
        )
        val VOLCANIC_ISLE = WorldTheme(
            5, "Volcanic Isle", "world5", floatArrayOf(0.6f, -0.22f, -0.77f), rgb(0xFF8A4C), 55_000f, 18_000f,
            rgb(0x3A2D4F), rgb(0xF47B45), rgb(0xB3685A), 0.018f, rgb(0x4A4448), rgb(0x2F2B30), rgb(0x3A9C98), rgb(0x123A55),
            ash = true,
        )

        fun forWorld(world: Int): WorldTheme = when (world) {
            1 -> SUNRISE_COVE
            2 -> CORAL_COAST
            3 -> MOONLIT_BAY
            4 -> CRAB_REEF
            5 -> VOLCANIC_ISLE
            else -> TITLE
        }

        val all = listOf(TITLE, SUNRISE_COVE, CORAL_COAST, MOONLIT_BAY, CRAB_REEF, VOLCANIC_ISLE)
    }
}

/**
 * Sun, image-based lighting and sky for the current [WorldTheme]. Uses the cmgen-built KTX IBL and
 * skybox from the HD asset pack when present; otherwise a gradient-derived spherical-harmonics
 * ambient and a flat sky colour so the scene still reads correctly.
 */
class Environment(private val host: RenderEngine) {
    private val engine = host.engine
    val sun: Int = EntityManager.get().create()
    private var indirectLight: IndirectLight? = null
    private var skybox: Skybox? = null
    private var iblTexture: Texture? = null
    private var skyTexture: Texture? = null
    var theme: WorldTheme = WorldTheme.TITLE
        private set
    var shadowMapSize = 2048
        private set

    init {
        buildSun(theme, shadowMapSize)
        host.scene.addEntity(sun)
        apply(WorldTheme.TITLE)
    }

    private fun buildSun(theme: WorldTheme, mapSize: Int) {
        LightManager.Builder(LightManager.Type.SUN)
            .color(theme.sunColor[0], theme.sunColor[1], theme.sunColor[2])
            .intensity(theme.sunLux)
            .direction(theme.sunDirection[0], theme.sunDirection[1], theme.sunDirection[2])
            .sunAngularRadius(1.6f)
            .sunHaloSize(8f)
            .sunHaloFalloff(60f)
            .castShadows(true)
            .shadowOptions(
                LightManager.ShadowOptions().apply {
                    this.mapSize = mapSize
                    shadowCascades = 1
                    stable = true
                    normalBias = 0.6f
                    shadowFar = 40f
                },
            )
            .build(engine, sun)
    }

    /** Rebuilds the sun when the quality tier changes its shadow map size. */
    fun setShadowMapSize(size: Int) {
        if (size == shadowMapSize) return
        shadowMapSize = size
        engine.lightManager.destroy(sun)
        buildSun(theme, size)
    }

    fun apply(next: WorldTheme) {
        theme = next
        val lm = engine.lightManager
        val light = lm.getInstance(sun)
        lm.setColor(light, next.sunColor[0], next.sunColor[1], next.sunColor[2])
        lm.setIntensity(light, next.sunLux)
        lm.setDirection(light, next.sunDirection[0], next.sunDirection[1], next.sunDirection[2])
        releaseSky()
        val loaded = loadKtx(next)
        if (!loaded) {
            indirectLight = IndirectLight.Builder()
                .irradiance(3, gradientHarmonics(next))
                .intensity(next.iblIntensity)
                .build(engine)
            skybox = Skybox.Builder().color(next.horizon[0], next.horizon[1], next.horizon[2], 1f).build(engine)
        }
        host.scene.indirectLight = indirectLight
        host.scene.skybox = skybox
        host.view.fogOptions = host.view.fogOptions.apply {
            enabled = true
            distance = 18f
            density = next.fogDensity
            height = 0f
            heightFalloff = 0.6f
            color = floatArrayOf(next.fog[0], next.fog[1], next.fog[2])
            fogColorFromIbl = loaded
            maximumOpacity = 0.85f
        }
    }

    private fun loadKtx(theme: WorldTheme): Boolean {
        val iblPath = "skies/${theme.sky}_ibl.ktx"
        val skyPath = "skies/${theme.sky}_skybox.ktx"
        val assets = host.assets
        val available = try {
            assets.open(iblPath).close()
            assets.open(skyPath).close()
            true
        } catch (_: java.io.IOException) {
            false
        }
        if (!available) return false
        val ibl = KTX1Loader.createIndirectLight(engine, assets.readDirect(iblPath), KTX1Loader.Options())
        indirectLight = ibl.indirectLight?.also { it.intensity = theme.iblIntensity }
        iblTexture = ibl.cubemap
        val sky = KTX1Loader.createSkybox(engine, assets.readDirect(skyPath), KTX1Loader.Options())
        skybox = sky.skybox
        skyTexture = sky.cubemap
        return true
    }

    /** Three-band SH approximating a sky gradient: bright from above, warm toward the horizon. */
    private fun gradientHarmonics(theme: WorldTheme): FloatArray {
        val sh = FloatArray(27)
        val avg = FloatArray(3) { (theme.zenith[it] + theme.horizon[it]) * 0.5f }
        for (c in 0 until 3) {
            sh[c] = avg[c] * 0.9f
            // L1,-1 (the y, up, term): sky above brighter than the ground below.
            sh[3 + c] = (theme.zenith[c] - theme.sand[c] * 0.4f) * 0.35f
        }
        return sh
    }

    private fun releaseSky() {
        host.scene.indirectLight = null
        host.scene.skybox = null
        indirectLight?.let { engine.destroyIndirectLight(it) }
        skybox?.let { engine.destroySkybox(it) }
        iblTexture?.let { engine.destroyTexture(it) }
        skyTexture?.let { engine.destroyTexture(it) }
        indirectLight = null
        skybox = null
        iblTexture = null
        skyTexture = null
    }

    fun destroy() {
        releaseSky()
        host.scene.remove(sun)
        engine.lightManager.destroy(sun)
        EntityManager.get().destroy(sun)
    }

    /** Applies a quality tier's post-processing and shadow settings to the main view. */
    fun applyQuality(view: View, tier: QualityTier) {
        view.antiAliasing = if (tier == QualityTier.HIGH) View.AntiAliasing.NONE else View.AntiAliasing.FXAA
        view.temporalAntiAliasingOptions = view.temporalAntiAliasingOptions.apply { enabled = tier == QualityTier.HIGH }
        view.ambientOcclusionOptions = view.ambientOcclusionOptions.apply {
            enabled = tier == QualityTier.HIGH
            radius = 0.35f
            intensity = 0.9f
        }
        view.bloomOptions = view.bloomOptions.apply {
            enabled = tier != QualityTier.LOW
            strength = 0.08f
            levels = 5
        }
        view.vignetteOptions = view.vignetteOptions.apply {
            enabled = true
            midPoint = 0.55f
            roundness = 0.6f
            feather = 0.6f
        }
        view.shadowType = when (tier) {
            QualityTier.LOW -> View.ShadowType.PCF
            QualityTier.MEDIUM -> View.ShadowType.DPCF
            QualityTier.HIGH -> View.ShadowType.PCSS
        }
        view.dynamicResolutionOptions = view.dynamicResolutionOptions.apply {
            enabled = true
            maxScale = tier.renderScale
            minScale = (tier.renderScale * 0.7f).coerceAtLeast(0.5f)
        }
        setShadowMapSize(tier.shadowMapSize)
    }
}

/** Quality tiers (spec section 6). */
enum class QualityTier(val renderScale: Float, val shadowMapSize: Int, val maxTexture: Int, val frameCap: Float) {
    LOW(0.7f, 1024, 1024, 30f),
    MEDIUM(0.85f, 2048, 2048, 60f),
    HIGH(1.0f, 2048, 4096, 0f),
}
