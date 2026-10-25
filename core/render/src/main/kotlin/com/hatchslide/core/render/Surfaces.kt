package com.hatchslide.core.render

import com.google.android.filament.Colors
import com.google.android.filament.MaterialInstance
import com.google.android.filament.Texture
import com.hatchslide.core.render.assets.AssetCatalog
import com.hatchslide.core.render.assets.AssetCatalog.Textures
import com.hatchslide.core.render.assets.ModelLibrary
import com.hatchslide.core.render.assets.TextureStore

/**
 * The game's material instances (spec section 6) and the mapping from glTF material names in the
 * generated models to them. World-dependent surfaces (sand look, cliffs, water colours, night glow)
 * follow [applyTheme]; animated ones follow [update]. Instances are created on first use so a scene
 * only loads the textures it shows.
 */
class Surfaces(private val host: RenderEngine, private val textures: TextureStore) : ModelLibrary.MaterialBinder {
    private val materials = host.materials
    private var theme: WorldTheme = WorldTheme.TITLE
    private var time = 0f

    /** Placeholder mask textures until a board provides its own. */
    private val blankMask: Texture = textures.rgba(1, 1, byteArrayOf(0, 0, 0, 0))
    private val farShore: Texture = textures.rgba(1, 1, byteArrayOf(-1, -1, -1, -1))

    private fun MaterialInstance.tex(name: String, texture: Texture, repeat: Boolean = true) =
        setParameter(name, texture, if (repeat) textures.repeat else textures.clamp)

    private fun MaterialInstance.srgb(name: String, c: FloatArray) = setParameter(name, Colors.RgbType.SRGB, c[0], c[1], c[2])

    private fun textured(base: String, tint: FloatArray = WHITE, uvScale: Float = 1f, normalStrength: Float = 1f) =
        materials.instance("textured").apply {
            tex("albedo", textures.get("${base}_albedo"))
            tex("normalMap", textures.get("${base}_normal"))
            tex("orm", textures.get("${base}_orm"))
            setParameter("tint", tint[0], tint[1], tint[2])
            setParameter("uvScale", uvScale)
            setParameter("normalStrength", normalStrength)
        }

    private fun rockSurface(base: String, tiling: Float) = materials.instance("rock").apply {
        tex("albedo", textures.get("${base}_albedo"))
        tex("normalMap", textures.get("${base}_normal"))
        tex("orm", textures.get("${base}_orm"))
        setParameter("tint", 1f, 1f, 1f)
        setParameter("tiling", tiling)
    }

    private fun terrainSurface(board: Boolean) = materials.instance("terrain").apply {
        setParameter("maskAmount", if (board) 1f else 0f)
        setParameter("slopeRock", if (board) 1f else 0f)
        setParameter("sandTiling", 0.45f)
        setParameter("rockTiling", 0.6f)
        setParameter("waterLevel", WATER_LEVEL)
        setParameter("sandTint", 1f, 1f, 1f)
        tex("caustics", textures.get("${Textures.CAUSTICS}_albedo"))
        setParameter("mask", blankMask, textures.linearClamp)
        setParameter("maskTransform", 0f, 0f, 1f, 1f)
        themeTerrain(this)
    }

    private fun themeTerrain(mi: MaterialInstance) {
        mi.tex("sandAlbedo", textures.get("${theme.sandLook}_albedo"))
        mi.tex("sandNormal", textures.get("${theme.sandDetail}_normal"))
        mi.tex("sandOrm", textures.get("${theme.sandDetail}_orm"))
        mi.tex("rockAlbedo", textures.get("${theme.cliff}_albedo"))
        mi.tex("rockNormal", textures.get("${theme.cliff}_normal"))
        mi.tex("rockOrm", textures.get("${theme.cliff}_orm"))
        mi.srgb("wetTint", theme.wetTint)
        mi.srgb("waterTint", theme.shallowWater)
    }

    private fun waterSurface(pool: Int) = materials.instance("water").apply {
        tex("normalA", textures.get("${Textures.WATER_A}_normal"))
        tex("normalB", textures.get("${Textures.WATER_B}_normal"))
        tex("foam", textures.get("${Textures.FOAM}_albedo"))
        setParameter("flare", 0f, 0f, 0f, 1f)
        if (pool < 0) {
            setParameter("shore", farShore, textures.linearClamp)
            setParameter("shoreTransform", 0f, 0f, 1f, 1f)
            setParameter("waveAmp", 1f)
            setParameter("foamAmount", 1f)
            setParameter("poolDepth", 1f)
            themeWater(this)
        } else {
            val c = POOL_COLORS[pool]
            setParameter("shore", farShore, textures.linearClamp)
            setParameter("shoreTransform", 0f, 0f, 1f, 1f)
            setParameter("waveAmp", 0f)
            setParameter("foamAmount", 0f)
            setParameter("poolDepth", 0.25f)
            srgb("shallow", c)
            srgb("deep", floatArrayOf(c[0] * 0.35f, c[1] * 0.45f, c[2] * 0.6f))
            srgb("glowColor", c)
            setParameter("glow", 0.9f)
        }
    }

    private fun themeWater(mi: MaterialInstance) {
        mi.srgb("shallow", theme.shallowWater)
        mi.srgb("deep", theme.deepWater)
        mi.srgb("glowColor", theme.glowColor)
        mi.setParameter("glow", theme.glow * 2.5f)
    }

    private fun glowSurface(color: FloatArray, intensity: Float) = materials.instance("glow").apply {
        srgb("color", color)
        setParameter("intensity", intensity)
    }

    private val terrainLazy = lazy { terrainSurface(board = true) }
    private val sandLazy = lazy { terrainSurface(board = false) }
    private val waterLazy = lazy { waterSurface(-1) }
    private val currentLazy = lazy {
        materials.instance("current").apply {
            srgb("color", floatArrayOf(0.2f, 0.62f, 0.68f))
            srgb("stripe", floatArrayOf(0.85f, 0.98f, 1f))
            setParameter("speed", 0.9f)
        }
    }

    /** Board sand and cliffs (uses the board mask). */
    val terrain: MaterialInstance by terrainLazy

    /** Sand parts of models: pit rims, the sandcastle, the nest mound, island beaches. */
    val sand: MaterialInstance by sandLazy
    val water: MaterialInstance by waterLazy
    private val pools = arrayOfNulls<MaterialInstance>(POOL_COLORS.size)
    val props: MaterialInstance by lazy { textured(Textures.PROPS) }
    val shells: MaterialInstance by lazy { textured(Textures.SHELLS) }
    val rock: MaterialInstance by lazy { rockSurface(Textures.ROCK, 1.6f) }
    val basalt: MaterialInstance by lazy { rockSurface(Textures.BASALT, 1.6f) }
    val eye: MaterialInstance by lazy { materials.litColor(0.03f, 0.025f, 0.03f, roughness = 0.12f) }
    val glint: MaterialInstance by lazy { glowSurface(WHITE, 3f) }
    val lantern: MaterialInstance by lazy { glowSurface(floatArrayOf(1f, 0.78f, 0.45f), 6f) }
    val lava: MaterialInstance by lazy { glowSurface(floatArrayOf(1f, 0.42f, 0.1f), 8f) }
    val pit: MaterialInstance by lazy { materials.litColor(0.16f, 0.11f, 0.07f, roughness = 1f) }
    val current: MaterialInstance by currentLazy
    private val turtleShells = arrayOfNulls<MaterialInstance>(AssetCatalog.SPECIES.size)
    private val turtleSkins = arrayOfNulls<MaterialInstance>(AssetCatalog.SPECIES.size)

    private fun sprite(cell: String, color: FloatArray, alpha: Float) = materials.instance("sprite").apply {
        val index = Textures.FX_CELLS.indexOf(cell)
        val inset = 0.004f
        setParameter("sprite", textures.get("${Textures.FX}_albedo"), textures.clamp)
        setParameter("rect", (index % 4) * 0.25f + inset, (index / 4) * 0.25f + inset, 0.25f - 2 * inset, 0.25f - 2 * inset)
        setParameter("color", Colors.RgbaType.SRGB, color[0], color[1], color[2], alpha)
    }

    /** Gull hazard shadow and the fainter preview of where it goes next. */
    val gullShadow: MaterialInstance by lazy { sprite("gull", floatArrayOf(0.04f, 0.05f, 0.1f), 0.5f) }
    val gullShadowNext: MaterialInstance by lazy { sprite("gull", floatArrayOf(0.04f, 0.05f, 0.1f), 0.2f) }

    /** Dots of the sea path on the world map. */
    val pathDot: MaterialInstance by lazy { sprite("glow", floatArrayOf(1f, 1f, 1f), 0.85f) }

    /** The ghost hint arrow. */
    val hint: MaterialInstance by lazy { materials.decal(1f, 1f, 1f, 0.55f) }

    fun pool(pair: Int): MaterialInstance {
        val index = (pair - 1).coerceIn(0, POOL_COLORS.size - 1)
        return pools[index] ?: waterSurface(index).also { pools[index] = it }
    }

    fun turtleShell(species: Int): MaterialInstance {
        val s = species.mod(AssetCatalog.SPECIES.size)
        return turtleShells[s] ?: materials.instance("turtle_shell").apply {
            tex("albedo", textures.get("${Textures.turtle(AssetCatalog.SPECIES[s])}_albedo"), repeat = false)
            tex("detailNormal", textures.get("${Textures.TURTLE_DETAIL}_normal"), repeat = false)
            tex("detailOrm", textures.get("${Textures.TURTLE_DETAIL}_orm"), repeat = false)
            setParameter("clearCoat", 0.55f)
        }.also { turtleShells[s] = it }
    }

    fun turtleSkin(species: Int): MaterialInstance {
        val s = species.mod(AssetCatalog.SPECIES.size)
        return turtleSkins[s] ?: textured(Textures.TURTLE_SKIN, SKIN_TINTS[s], uvScale = 3f).also { turtleSkins[s] = it }
    }

    override fun bind(model: String, materialName: String): MaterialInstance = when (materialName) {
        "turtle_shell" -> turtleShell(0)
        "turtle_skin" -> turtleSkin(0)
        "turtle_eye" -> eye
        "eye_glint" -> glint
        "rock" -> if (theme.cliff == Textures.BASALT && model.startsWith("cliff")) basalt else rock
        "basalt" -> basalt
        "shells" -> shells
        "props" -> props
        "sand" -> sand
        "pit" -> pit
        "current" -> current
        "pool_water" -> pool(1)
        "lantern_glow" -> lantern
        "lava" -> lava
        else -> error("model $model uses unknown material $materialName")
    }

    /** Binder for a hatchling of the given species skin. */
    fun hatchling(species: Int) = ModelLibrary.MaterialBinder { model, name ->
        when (name) {
            "turtle_shell" -> turtleShell(species)
            "turtle_skin" -> turtleSkin(species)
            else -> bind(model, name)
        }
    }

    /** Binder for a tide pool of the given pair number (1-based). */
    fun tidePool(pair: Int) = ModelLibrary.MaterialBinder { model, name ->
        if (name == "pool_water") pool(pair) else bind(model, name)
    }

    fun applyTheme(next: WorldTheme) {
        theme = next
        if (terrainLazy.isInitialized()) themeTerrain(terrain)
        if (sandLazy.isInitialized()) themeTerrain(sand)
        if (waterLazy.isInitialized()) themeWater(water)
    }

    /** Scenery mask textures; the owning stage calls [clearBoardTextures] before releasing them. */
    fun setBoardTextures(mask: Texture, maskTransform: FloatArray, shore: Texture, shoreTransform: FloatArray) {
        boundMask = mask
        terrain.setParameter("mask", mask, textures.linearClamp)
        terrain.setParameter("maskTransform", maskTransform[0], maskTransform[1], maskTransform[2], maskTransform[3])
        water.setParameter("shore", shore, textures.linearClamp)
        water.setParameter("shoreTransform", shoreTransform[0], shoreTransform[1], shoreTransform[2], shoreTransform[3])
    }

    private var boundMask: Texture? = null

    /** Unbinds [mask]'s scenery textures, if they are the ones bound, before they are destroyed. */
    fun clearBoardTextures(mask: Texture) {
        if (boundMask !== mask) return
        boundMask = null
        if (terrainLazy.isInitialized()) terrain.setParameter("mask", blankMask, textures.linearClamp)
        if (waterLazy.isInitialized()) water.setParameter("shore", farShore, textures.linearClamp)
    }

    /** Splash flare on the water (night glow where a hatchling enters the sea). */
    fun setFlare(x: Float, z: Float, strength: Float, radius: Float) {
        if (waterLazy.isInitialized()) water.setParameter("flare", x, z, strength, radius)
    }

    /** Wave height scale; 0 freezes the swell for reduced motion. */
    fun setWaveAmplitude(amplitude: Float) {
        water.setParameter("waveAmp", amplitude)
    }

    fun update(dt: Float) {
        time += dt
        // Keep the shader clock small so float precision holds over long sessions.
        if (time > 3600f) time -= 3600f
        if (waterLazy.isInitialized()) water.setParameter("time", time)
        if (terrainLazy.isInitialized()) terrain.setParameter("time", time)
        if (sandLazy.isInitialized()) sand.setParameter("time", time)
        if (currentLazy.isInitialized()) current.setParameter("time", time)
        for (pool in pools) pool?.setParameter("time", time)
    }

    companion object {
        /** Height of the sea surface; tiles top out at 0 and the island chunk at [ISLAND_TOP]. */
        const val WATER_LEVEL = -0.75f
        const val ISLAND_TOP = -0.3f

        private val WHITE = floatArrayOf(1f, 1f, 1f)

        /** Tide-pool pair colours (sRGB): cyan, pink, yellow, green. Pairs also differ by rim shells. */
        val POOL_COLORS = arrayOf(
            floatArrayOf(0.3f, 0.85f, 0.95f),
            floatArrayOf(0.95f, 0.55f, 0.85f),
            floatArrayOf(0.98f, 0.82f, 0.3f),
            floatArrayOf(0.5f, 0.95f, 0.5f),
        )

        /** Linear skin tints per species, multiplied into the shared skin albedo. */
        private val SKIN_TINTS = arrayOf(
            floatArrayOf(0.72f, 0.82f, 0.68f),
            floatArrayOf(0.86f, 0.7f, 0.56f),
            floatArrayOf(0.9f, 0.76f, 0.56f),
            floatArrayOf(0.5f, 0.54f, 0.6f),
            floatArrayOf(0.78f, 0.8f, 0.72f),
            floatArrayOf(0.76f, 0.8f, 0.64f),
            floatArrayOf(0.9f, 0.86f, 0.74f),
        )
    }
}
