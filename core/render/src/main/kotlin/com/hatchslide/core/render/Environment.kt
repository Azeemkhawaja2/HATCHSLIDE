package com.hatchslide.core.render

import com.google.android.filament.EntityManager
import com.google.android.filament.IndirectLight
import com.google.android.filament.LightManager
import com.google.android.filament.Skybox
import com.google.android.filament.Texture
import com.google.android.filament.View
import com.google.android.filament.utils.KTX1Loader
import com.hatchslide.core.render.assets.AssetCatalog
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** Look of one environment (spec section 6): sky, sun, surfaces, water and fog. Colours are sRGB. */
class WorldTheme(
    val id: Int,
    val name: String,
    /** Sky asset name: `skies/<sky>_ibl.ktx` and the `sky_<sky>` skybox texture. */
    val sky: String,
    /** Direction toward the sun or moon disc painted into the sky (tools/assetgen/skies). */
    skySun: FloatArray,
    /** Lowest key-light elevation in degrees, so long dawn and dusk shadows never hide a cell. */
    minSunElevation: Float,
    val sunColor: FloatArray,
    val sunLux: Float,
    val iblIntensity: Float,
    val fog: FloatArray,
    val fogDensity: Float,
    /** Sand albedo texture (AssetCatalog.Textures.SAND_LOOKS) and its detail set (fine or coarse). */
    val sandLook: String,
    val sandDetail: String,
    /** Cliff texture set: rock or basalt. */
    val cliff: String,
    val wetTint: FloatArray,
    val shallowWater: FloatArray,
    val deepWater: FloatArray,
    /** Bioluminescent foam strength (0 by day). */
    val glow: Float,
    val glowColor: FloatArray,
    val ash: Boolean = false,
) {
    /** Light travel direction of the sun: the sky's sun azimuth, raised to [minSunElevation]. */
    val sunDirection: FloatArray = run {
        val len = sqrt(skySun[0] * skySun[0] + skySun[1] * skySun[1] + skySun[2] * skySun[2])
        val azimuth = atan2(skySun[0], skySun[2])
        val elevation = max(asin(skySun[1] / len), Math.toRadians(minSunElevation.toDouble()).toFloat())
        floatArrayOf(-cos(elevation) * sin(azimuth), -sin(elevation), -cos(elevation) * cos(azimuth))
    }

    companion object {
        private fun rgb(hex: Int) = floatArrayOf(((hex shr 16) and 255) / 255f, ((hex shr 8) and 255) / 255f, (hex and 255) / 255f)

        private val NO_GLOW = rgb(0x000000)

        val TITLE = WorldTheme(
            0, "Sunrise", "title", floatArrayOf(-0.45f, 0.16f, -0.88f), 28f, rgb(0xFFD2A8), 70_000f, 26_000f,
            rgb(0xF6D3B8), 0.012f, "sand_golden", "sand_fine", "rock", rgb(0xB59A80), rgb(0x52D1C8), rgb(0x1B5F8C), 0f, NO_GLOW,
        )
        val SUNRISE_COVE = WorldTheme(
            1, "Sunrise Cove", "world1", floatArrayOf(-0.7f, 0.32f, -0.64f), 38f, rgb(0xFFD6AE), 80_000f, 28_000f,
            rgb(0xF7D6BC), 0.010f, "sand_golden", "sand_fine", "rock", rgb(0xB0957A), rgb(0x55D3C9), rgb(0x1C6290), 0f, NO_GLOW,
        )
        val CORAL_COAST = WorldTheme(
            2, "Coral Coast", "world2", floatArrayOf(0.2f, 0.92f, 0.33f), 38f, rgb(0xFFF6E8), 110_000f, 32_000f,
            rgb(0xD8EEF9), 0.007f, "sand_coral", "sand_fine", "rock", rgb(0xB8AC9C), rgb(0x48E0D3), rgb(0x1774B5), 0f, NO_GLOW,
        )
        val MOONLIT_BAY = WorldTheme(
            3, "Moonlit Bay", "world3", floatArrayOf(0.35f, 0.55f, -0.76f), 38f, rgb(0xB9CCFF), 9_000f, 9_000f,
            rgb(0x1A2850), 0.014f, "sand_silver", "sand_fine", "rock", rgb(0x8A8FA6), rgb(0x2A8C9E), rgb(0x0A2346), 1f, rgb(0x5CF2D6),
        )
        val CRAB_REEF = WorldTheme(
            4, "Crab Reef", "world4", floatArrayOf(0.72f, 0.3f, -0.62f), 38f, rgb(0xFFC48A), 75_000f, 24_000f,
            rgb(0xE9C2A0), 0.011f, "sand_amber", "sand_coarse", "rock", rgb(0xA8875E), rgb(0x46BDB4), rgb(0x174F78), 0f, NO_GLOW,
        )
        val VOLCANIC_ISLE = WorldTheme(
            5, "Volcanic Isle", "world5", floatArrayOf(0.6f, 0.12f, -0.79f), 38f, rgb(0xFF8A4C), 55_000f, 18_000f,
            rgb(0xB3685A), 0.018f, "sand_volcanic", "sand_coarse", "basalt", rgb(0x4A4448), rgb(0x3A9C98), rgb(0x123A55), 0f, NO_GLOW,
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
 * Sun, image-based lighting and sky for the current [WorldTheme], from the cmgen-built IBL in
 * `skies/` and the compressed skybox cubemap in the texture pack.
 */
class Environment(private val host: RenderEngine) {
    private val engine = host.engine
    val sun: Int = EntityManager.get().create()
    private var indirectLight: IndirectLight? = null
    private var skybox: Skybox? = null
    private var iblTexture: Texture? = null
    private var skyTexture: Texture? = null
    var theme: WorldTheme? = null
        private set
    var shadowMapSize = 2048
        private set

    init {
        buildSun(WorldTheme.TITLE, shadowMapSize)
        host.scene.addEntity(sun)
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
        buildSun(theme ?: WorldTheme.TITLE, size)
    }

    fun apply(next: WorldTheme) {
        if (theme === next) return
        theme = next
        val lm = engine.lightManager
        val light = lm.getInstance(sun)
        lm.setColor(light, next.sunColor[0], next.sunColor[1], next.sunColor[2])
        lm.setIntensity(light, next.sunLux)
        lm.setDirection(light, next.sunDirection[0], next.sunDirection[1], next.sunDirection[2])
        releaseSky()
        val ibl = KTX1Loader.createIndirectLight(engine, host.assets.readDirect(AssetCatalog.Skies.ibl(next.sky)), KTX1Loader.Options())
        indirectLight = ibl.indirectLight?.also { it.intensity = next.iblIntensity }
        iblTexture = ibl.cubemap
        val (box, cubemap) = host.textures.skybox(next.sky)
        skybox = box
        skyTexture = cubemap
        host.scene.indirectLight = indirectLight
        host.scene.skybox = skybox
        host.surfaces.applyTheme(next)
        host.view.fogOptions = host.view.fogOptions.apply {
            enabled = true
            distance = 18f
            density = next.fogDensity
            height = 0f
            heightFalloff = 0.6f
            color = floatArrayOf(next.fog[0], next.fog[1], next.fog[2])
            fogColorFromIbl = true
            maximumOpacity = 0.85f
        }
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
enum class QualityTier(val renderScale: Float, val shadowMapSize: Int, val maxTexture: Int, val heroTexture: Int, val frameCap: Float) {
    LOW(0.7f, 1024, 1024, 1024, 30f),
    MEDIUM(0.85f, 2048, 2048, 2048, 60f),
    HIGH(1.0f, 2048, 2048, 4096, 0f),
}
