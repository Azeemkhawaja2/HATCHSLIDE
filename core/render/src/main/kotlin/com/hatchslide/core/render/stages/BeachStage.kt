package com.hatchslide.core.render.stages

import com.google.android.filament.Camera
import com.hatchslide.core.game.LevelCodec
import com.hatchslide.core.game.LevelFile
import com.hatchslide.core.render.Ocean
import com.hatchslide.core.render.RenderEngine
import com.hatchslide.core.render.Stage
import com.hatchslide.core.render.Surfaces
import com.hatchslide.core.render.WorldTheme
import com.hatchslide.core.render.assets.AssetCatalog.Models
import com.hatchslide.core.render.assets.ModelInstance
import com.hatchslide.core.render.board.EggRig
import com.hatchslide.core.render.board.GullRig
import com.hatchslide.core.render.board.HatchlingRig
import com.hatchslide.core.render.board.IslandBuilder
import com.hatchslide.core.render.board.Scenery
import com.hatchslide.core.render.camera.FreeViewport
import com.hatchslide.core.render.lookAt
import com.hatchslide.core.render.math.Transforms
import com.hatchslide.core.render.math.Vec3
import com.hatchslide.core.render.math.easeInOutCubic
import com.hatchslide.core.render.math.easeOutBack
import com.hatchslide.core.render.math.lerp
import com.hatchslide.core.render.math.smoothstep
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * The sunrise beach behind the title, the main menu and the menu screens (spec section 5). On the
 * title the camera glides in from over the sea while a hatchling breaks out of its egg; the menu
 * holds a gently swaying view; the Turtle Journal moves in close on the hatchling, which the player
 * can turn by dragging and which wears the selected species' skin.
 */
class BeachStage(private val host: RenderEngine) : Stage {
    enum class Mode { TITLE, MENU, JOURNAL }

    private val tm get() = host.engine.transformManager
    private val scratch = FloatArray(16)
    private val ocean = Ocean(host)
    private val scenery = Scenery(host)
    private val props = ArrayList<ModelInstance>()
    private var hero: ModelInstance? = null
    private var heroRig: HatchlingRig? = null
    private var egg: ModelInstance? = null
    private var eggRig: EggRig? = null
    private var gull: ModelInstance? = null
    private var gullRig: GullRig? = null
    private var built = false
    private var entered = false
    private var eggVisible = false

    var mode = Mode.TITLE
        private set
    private var modeTime = 0f
    private var clock = 0f
    private var heroYaw = 0.5f
    private var species = 0

    private val eye = Vec3()
    private val target = Vec3()
    private val fromEye = Vec3()
    private val fromTarget = Vec3()
    private var transition = 1f
    private var shiftX = 0f
    private var shiftY = 0f
    private var free = FreeViewport(1, 1)

    var reducedMotion = false

    override val isAnimating: Boolean get() = true

    private fun build() {
        if (built) return
        built = true
        val layout = LevelCodec.parse(LevelFile("beach", 0, 0, "Beach", GRID, par = 1, gullMoves = 1, seed = BEACH_SEED))
        scenery.build(IslandBuilder.build(layout.board, BEACH_SEED), withTiles = false)
        for (p in PROPS) {
            val instance = host.models.acquire(p.model, if (p.background) 1 else 0, host.surfaces)
            tm.setTransform(
                tm.getInstance(instance.root),
                Transforms.trs(scratch, p.x, Surfaces.ISLAND_TOP, p.z, p.yaw, p.scale, p.scale, p.scale),
            )
            props += instance
        }
        hero = host.models.acquire(Models.HATCHLING, 0, host.surfaces.hatchling(species)).also { heroRig = HatchlingRig(tm, it, 7) }
        egg = host.models.acquire(Models.EGG, 0, host.surfaces).also { eggRig = EggRig(tm, it) }
        gull = host.models.acquire(Models.SEAGULL, 1, host.surfaces).also { gullRig = GullRig(tm, it) }
    }

    private fun allRenderables(): Sequence<Int> = sequence {
        for (p in props) yieldAll(p.renderables.asSequence())
        hero?.let { yieldAll(it.renderables.asSequence()) }
        egg?.let { yieldAll(it.renderables.asSequence()) }
        gull?.let { yieldAll(it.renderables.asSequence()) }
    }

    /** Switches camera mode; the camera eases from wherever it is. */
    fun setMode(next: Mode) {
        if (next == mode && modeTime > 0f) return
        fromEye.set(eye)
        fromTarget.set(target)
        transition = if (modeTime == 0f && next == Mode.TITLE) 1f else 0f
        mode = next
        modeTime = 0f
        if (next == Mode.TITLE) {
            eggRig?.animate(0f)
            showEgg(true)
        }
    }

    /** Skin shown on the hatchling (Turtle Journal selection). */
    fun setSpecies(next: Int) {
        if (next == species) return
        species = next
        hero?.let { host.models.bind(it, host.surfaces.hatchling(next)) }
    }

    /** Turns the journal hatchling by [radians] (from a horizontal drag). */
    fun rotateHero(radians: Float) {
        heroYaw += radians
    }

    /** Free area between the UI panels; the camera shifts so the hatchling sits in its centre. */
    fun setFreeViewport(viewport: FreeViewport) {
        free = viewport
        if (viewport.width <= 1 || viewport.height <= 1) return
        val cx = (viewport.left + (viewport.width - viewport.right)) / 2f
        val cy = (viewport.top + (viewport.height - viewport.bottom)) / 2f
        shiftX = -(cx / viewport.width * 2f - 1f)
        shiftY = cy / viewport.height * 2f - 1f
    }

    override fun enter(host: RenderEngine) {
        build()
        entered = true
        host.environment.apply(WorldTheme.TITLE)
        ocean.show()
        scenery.attach()
        for (e in allRenderables()) host.scene.addEntity(e)
        eggVisible = true
        if (mode != Mode.TITLE || modeTime > TITLE_HATCH + 0.6f) showEgg(false)
    }

    private fun showEgg(visible: Boolean) {
        val e = egg ?: return
        if (!entered || visible == eggVisible) return
        eggVisible = visible
        for (r in e.renderables) if (visible) host.scene.addEntity(r) else host.scene.remove(r)
    }

    override fun exit(host: RenderEngine) {
        entered = false
        ocean.hide()
        scenery.detach()
        for (e in allRenderables()) host.scene.remove(e)
    }

    override fun onResize(host: RenderEngine, width: Int, height: Int) {
        free = free.copy(width = width, height = height)
        setFreeViewport(free)
    }

    override fun update(host: RenderEngine, dt: Float) {
        clock += dt
        modeTime += dt
        animateHero(dt)
        animateGull()
        animateCamera(dt)
        applyCamera(host.camera)
    }

    private fun animateHero(dt: Float) {
        val h = hero ?: return
        val rig = heroRig ?: return
        var scale = 1f
        if (mode == Mode.TITLE) {
            val t = modeTime - TITLE_HATCH
            eggRig?.animate(if (t < 0f) 0f else t / 0.45f)
            val wobble = if (t in -0.4f..0f && !reducedMotion) sin(t * 40f) * 0.1f else 0f
            egg?.let { e ->
                tm.setTransform(
                    tm.getInstance(e.root),
                    Transforms.trsEuler(scratch, HERO_X, Surfaces.ISLAND_TOP, HERO_Z, 0f, 0f, wobble, 1f),
                )
                if (t > 0.6f) showEgg(false)
            }
            scale = if (t < 0f) 0.001f else easeOutBack((t / 0.3f).coerceIn(0f, 1f))
        }
        val look = if (mode == Mode.JOURNAL || reducedMotion) 0f else sin(clock * 0.7f) * 0.5f
        val yaw = if (mode == Mode.JOURNAL) heroYaw else 0.5f + sin(clock * 0.23f) * 0.15f
        tm.setTransform(tm.getInstance(h.root), Transforms.trs(scratch, HERO_X, Surfaces.ISLAND_TOP, HERO_Z, yaw, scale, scale, scale))
        rig.animate(dt, 0f, 0f, look, clock, reducedMotion)
    }

    private fun animateGull() {
        val g = gull ?: return
        val angle = clock * 0.35f
        val x = cos(angle) * 4.5f
        val z = sin(angle) * 3f - 1f
        val yaw = kotlin.math.atan2(sin(angle) * 4.5f, -cos(angle) * 3f)
        tm.setTransform(tm.getInstance(g.root), Transforms.trsEuler(scratch, x, 3.6f, z, yaw, 0f, -0.3f, 1f))
        gullRig?.animate(clock)
    }

    private fun desired(outEye: Vec3, outTarget: Vec3) {
        val sway = if (reducedMotion) 0f else sin(clock * 0.31f) * 0.25f
        when (mode) {
            Mode.TITLE -> {
                val t = easeInOutCubic((modeTime / TITLE_GLIDE).coerceIn(0f, 1f))
                outEye.set(lerp(6f, 2.6f, t) + sway, lerp(7f, 2.3f, t), lerp(15f, 6.8f, t))
                outTarget.set(lerp(0f, -0.3f, t), lerp(-0.6f, -0.1f, t), lerp(-2f, 0.6f, t))
            }

            Mode.MENU -> {
                outEye.set(2.6f + sway, 2.3f, 6.8f)
                outTarget.set(-0.3f, -0.1f, 0.6f)
            }

            Mode.JOURNAL -> {
                outEye.set(HERO_X + 0.25f, Surfaces.ISLAND_TOP + 0.75f, HERO_Z + 1.9f)
                outTarget.set(HERO_X, Surfaces.ISLAND_TOP + 0.2f, HERO_Z)
            }
        }
    }

    private val goalEye = Vec3()
    private val goalTarget = Vec3()

    private fun animateCamera(dt: Float) {
        desired(goalEye, goalTarget)
        transition = (transition + dt / 0.9f).coerceAtMost(1f)
        val t = smoothstep(transition)
        eye.set(lerp(fromEye.x, goalEye.x, t), lerp(fromEye.y, goalEye.y, t), lerp(fromEye.z, goalEye.z, t))
        target.set(lerp(fromTarget.x, goalTarget.x, t), lerp(fromTarget.y, goalTarget.y, t), lerp(fromTarget.z, goalTarget.z, t))
        if (transition >= 1f) {
            fromEye.set(eye)
            fromTarget.set(target)
        }
    }

    private fun applyCamera(cam: Camera) {
        val aspect = host.width.toDouble() / host.height.coerceAtLeast(1)
        // Narrow windows widen the vertical field so the beach still fits sideways.
        val fov = if (aspect < 0.75) Math.toDegrees(2 * kotlin.math.atan(tan(Math.toRadians(FOV / 2)) * 0.75 / aspect)) else FOV
        cam.setProjection(fov, aspect, 0.1, 300.0, Camera.Fov.VERTICAL)
        cam.setShift(shiftX.toDouble() * 0.5, shiftY.toDouble() * 0.5)
        cam.lookAt(eye, target)
    }

    override fun destroy(host: RenderEngine) {
        if (entered) exit(host)
        scenery.release()
        props.forEach { host.models.release(it) }
        props.clear()
        hero?.let { host.models.release(it) }
        egg?.let { host.models.release(it) }
        gull?.let { host.models.release(it) }
        hero = null
        egg = null
        gull = null
        built = false
    }

    private class Prop(val model: String, val x: Float, val z: Float, val yaw: Float, val scale: Float, val background: Boolean = false)

    companion object {
        private const val FOV = 35.0
        private const val TITLE_GLIDE = 3.4f
        private const val TITLE_HATCH = 2.2f
        private const val HERO_X = -0.45f
        private const val HERO_Z = 0.7f
        private const val BEACH_SEED = 20261025L

        private val GRID = listOf(
            "~~~~~~~~~",
            ".........",
            ".........",
            "....T....",
            ".........",
            ".........",
        )

        private val PROPS = listOf(
            Prop(Models.NEST, 0.75f, 0.35f, 0.3f, 0.9f),
            Prop(Models.PALMS[0], -3.7f, -1.3f, 0.4f, 0.95f),
            Prop(Models.PALMS[1], 3.9f, -0.9f, 2.1f, 0.85f),
            Prop(Models.UMBRELLAS[0], 2.7f, 1.7f, 0.2f, 0.8f),
            Prop(Models.SANDCASTLE, -2.5f, 1.9f, -0.3f, 0.95f),
            Prop(Models.BUCKET, -1.6f, 2.5f, 0.8f, 0.8f),
            Prop(Models.DRIFTWOOD[0], 1.3f, -1.5f, 0.4f, 1f),
            Prop(Models.STARFISH, 2.0f, 2.7f, 1.2f, 0.9f),
            Prop(Models.PEBBLES, -3.1f, 3.0f, 0f, 1f, background = true),
            Prop(Models.SHELL_SCALLOP, 0.3f, 2.8f, 2.4f, 0.8f),
            Prop(Models.SHELL_CONCH, 3.3f, -1.7f, 0.9f, 0.8f, background = true),
            Prop(Models.CLIFFS[1], -5.0f, 0.5f, 0.7f, 0.5f, background = true),
        )
    }
}
