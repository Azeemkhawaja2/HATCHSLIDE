package com.hatchslide.core.render

import android.content.Context
import android.view.Choreographer
import android.view.Surface
import android.view.SurfaceView
import com.google.android.filament.Camera
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.Filament
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.SwapChain
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.google.android.filament.android.DisplayHelper
import com.google.android.filament.android.UiHelper
import com.google.android.filament.gltfio.Gltfio
import com.google.android.filament.utils.Utils
import com.hatchslide.core.render.assets.AssetCatalog
import com.hatchslide.core.render.assets.ModelLibrary
import com.hatchslide.core.render.assets.TextureStore
import com.hatchslide.core.render.fx.ParticleSystem
import com.hatchslide.core.render.math.Vec3

/** Points a camera from [eye] at [target] with world-up Y. */
fun Camera.lookAt(eye: Vec3, target: Vec3) = lookAt(
    eye.x.toDouble(),
    eye.y.toDouble(),
    eye.z.toDouble(),
    target.x.toDouble(),
    target.y.toDouble(),
    target.z.toDouble(),
    0.0,
    1.0,
    0.0,
)

/** Loads Filament's native libraries once per process. */
object FilamentRuntime {
    @Volatile
    private var initialized = false

    fun ensureInitialized() {
        if (initialized) return
        synchronized(this) {
            if (!initialized) {
                Filament.init()
                Gltfio.init()
                Utils.init()
                initialized = true
            }
        }
    }
}

/** Something drawn into the shared Filament scene: the title beach, the archipelago, a board. */
interface Stage {
    /** Adds the stage's entities to the scene. */
    fun enter(host: RenderEngine)

    /** Removes the stage's entities from the scene (they stay alive until [destroy]). */
    fun exit(host: RenderEngine)

    fun update(host: RenderEngine, dt: Float)

    fun onResize(host: RenderEngine, width: Int, height: Int)

    /** False when nothing on screen moves, so the loop can drop to 30 fps in menus. */
    val isAnimating: Boolean

    fun destroy(host: RenderEngine)
}

/** Told about every presented frame, for the auto quality tier and (M8) performance hints. */
interface FrameObserver {
    /** [intervalNanos] since the previous presented frame (0 for the first after a resume); [workNanos] of CPU work. */
    fun onFrame(intervalNanos: Long, workNanos: Long)

    /** The drawn stage changed; loading hitches follow. */
    fun onStageChanged() {}
}

/** Frame-rate setting (spec section 5): Battery 30, Standard 60, Smooth = display maximum. */
enum class FrameRateMode(val fps: Float) {
    BATTERY(30f),
    STANDARD(60f),
    SMOOTH(0f),
}

/**
 * Owns the Filament [Engine] and everything created from it (spec section 6). The engine uses the
 * OpenGL ES 3.0 backend and renders into a [SurfaceView] through [UiHelper]. Frames are driven by
 * [Choreographer]; rendering stops in [pause] and every Filament object is destroyed in reverse
 * creation order in [destroy]. All calls happen on the main thread, which owns the engine.
 */
class RenderEngine(context: Context) {
    private val appContext = context.applicationContext

    val engine: Engine
    val renderer: Renderer
    val scene: Scene
    val view: View
    private val cameraEntity: Int
    val camera: Camera
    val materials: MaterialLibrary
    val meshes: MeshFactory
    val textures: TextureStore
    val models: ModelLibrary
    val surfaces: Surfaces
    val environment: Environment
    val particles: ParticleSystem
    val assets get() = appContext.assets

    private val uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)
    private val displayHelper = DisplayHelper(appContext)
    private var swapChain: SwapChain? = null
    private var surfaceView: SurfaceView? = null
    private val choreographer = Choreographer.getInstance()
    private var running = false
    private var lastFrameNanos = 0L
    private var lastRenderNanos = 0L
    private var lastPresentNanos = 0L
    private var destroyed = false

    var width = 0
        private set
    var height = 0
        private set

    var stage: Stage? = null
        private set

    var frameRateMode = FrameRateMode.STANDARD
        set(value) {
            field = value
            applyFrameRate()
        }

    /** Extra frame cap from the quality tier (Low renders at 30 fps). */
    var tierFrameCap = 0f

    /** Frames presented since the engine started (startup reports fully drawn after the first). */
    var presentedFrames = 0L
        private set

    /** Refresh rate of the display the surface is on. */
    var displayFps = 60f
        private set

    /** Told about every presented frame (auto quality, performance hints). */
    val frameObservers = ArrayList<FrameObserver>()

    /** Extra views drawn after the main view, e.g. the Turtle Journal's model card. */
    val overlayViews = mutableListOf<View>()

    init {
        FilamentRuntime.ensureInitialized()
        engine = Engine.Builder().backend(Engine.Backend.OPENGL).build()
        renderer = engine.createRenderer()
        scene = engine.createScene()
        view = engine.createView()
        cameraEntity = EntityManager.get().create()
        camera = engine.createCamera(cameraEntity)
        view.scene = scene
        view.camera = camera
        renderer.clearOptions = renderer.clearOptions.apply {
            clear = true
            setClearColor(0.05, 0.16, 0.25, 1.0)
        }
        materials = MaterialLibrary(appContext.assets, engine)
        meshes = MeshFactory(engine)
        textures = TextureStore(appContext.assets, engine)
        models = ModelLibrary(appContext.assets, engine)
        surfaces = Surfaces(this, textures)
        environment = Environment(this)
        particles = ParticleSystem(engine, scene, surfaces.particle)
        setQuality(QualityTier.MEDIUM)
        camera.setExposure(16f, 1f / 125f, 100f)
        uiHelper.renderCallback = SurfaceCallback()
    }

    private inner class SurfaceCallback : UiHelper.RendererCallback {
        override fun onNativeWindowChanged(surface: Surface) {
            swapChain?.let { engine.destroySwapChain(it) }
            swapChain = engine.createSwapChain(surface, uiHelper.swapChainFlags)
            surfaceView?.display?.let {
                displayHelper.attach(renderer, it)
                displayFps = it.refreshRate.takeIf { rate -> rate > 1f } ?: 60f
            }
            applyFrameRate()
        }

        override fun onDetachedFromSurface() {
            displayHelper.detach()
            swapChain?.let {
                engine.destroySwapChain(it)
                engine.flushAndWait()
            }
            swapChain = null
        }

        override fun onResized(width: Int, height: Int) {
            this@RenderEngine.width = width
            this@RenderEngine.height = height
            view.viewport = Viewport(0, 0, width, height)
            stage?.onResize(this@RenderEngine, width, height)
        }
    }

    fun attach(surfaceView: SurfaceView) {
        if (this.surfaceView === surfaceView) return
        detachSurface()
        this.surfaceView = surfaceView
        uiHelper.attachTo(surfaceView)
    }

    fun detachSurface() {
        if (surfaceView == null) return
        uiHelper.detach()
        surfaceView = null
    }

    /** Switches the stage drawn into the scene. The previous stage keeps its GPU objects. */
    fun show(next: Stage) {
        if (stage === next) return
        stage?.exit(this)
        particles.clear()
        stage = next
        next.enter(this)
        for (i in 0 until frameObservers.size) frameObservers[i].onStageChanged()
        if (width > 0) next.onResize(this, width, height)
    }

    fun resume() {
        if (running || destroyed) return
        running = true
        lastFrameNanos = 0L
        lastPresentNanos = 0L
        choreographer.postFrameCallback(frameCallback)
    }

    fun pause() {
        running = false
        choreographer.removeFrameCallback(frameCallback)
    }

    /**
     * Reads a world's sky and terrain textures ahead, off the main thread, so switching to it only
     * uploads to the GPU (spec section 11: world switch under 800 ms).
     */
    fun prefetch(theme: WorldTheme) {
        environment.prefetch(theme)
        textures.prefetch(Surfaces.themeTextures(theme) + AssetCatalog.Skies.skybox(theme.sky))
    }

    /** Applies a quality tier: post-processing, shadows, frame cap and texture limits for later loads. */
    fun setQuality(tier: QualityTier) {
        environment.applyQuality(view, tier)
        textures.setLimits(tier.maxTexture, tier.heroTexture)
        tierFrameCap = tier.frameCap
    }

    private fun applyFrameRate() {
        val chain = swapChain ?: return
        val fps = frameRateMode.fps
        if (fps > 0f && chain.isFrameRateChangeSupported) chain.setFrameRate(fps)
    }

    /** Time one frame may take at the current cap (frame-rate setting, tier, idle menus, display). */
    fun frameBudgetNanos(): Long {
        val cap = frameCapFps()
        val fps = if (cap > 0f) minOf(cap, displayFps) else displayFps
        return (1_000_000_000.0 / fps).toLong()
    }

    private fun frameCapFps(): Float {
        val setting = frameRateMode.fps
        val cap = when {
            setting > 0f && tierFrameCap > 0f -> minOf(setting, tierFrameCap)
            setting > 0f -> setting
            else -> tierFrameCap
        }
        // Menus with nothing animating render on demand at 30 fps.
        val idle = stage?.isAnimating == false
        return if (idle) (if (cap > 0f) minOf(cap, 30f) else 30f) else cap
    }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            choreographer.postFrameCallback(this)
            val cap = frameCapFps()
            if (cap > 0f && lastRenderNanos != 0L) {
                val interval = (1_000_000_000.0 / cap).toLong()
                // Allow 25% early so a 30 fps cap lands on every other 60 Hz vsync.
                if (frameTimeNanos - lastRenderNanos < interval * 3 / 4) return
            }
            val dt = if (lastFrameNanos == 0L) 1f / 60f else ((frameTimeNanos - lastFrameNanos) / 1e9f).coerceIn(0f, 0.1f)
            lastFrameNanos = frameTimeNanos
            lastRenderNanos = frameTimeNanos
            val start = System.nanoTime()
            surfaces.update(dt)
            stage?.update(this@RenderEngine, dt)
            particles.update(dt, camera)
            val chain = swapChain ?: return
            if (!uiHelper.isReadyToRender) return
            if (renderer.beginFrame(chain, frameTimeNanos)) {
                renderer.render(view)
                for (i in 0 until overlayViews.size) renderer.render(overlayViews[i])
                renderer.endFrame()
                val interval = if (lastPresentNanos == 0L) 0L else frameTimeNanos - lastPresentNanos
                lastPresentNanos = frameTimeNanos
                presentedFrames++
                val work = System.nanoTime() - start
                for (i in 0 until frameObservers.size) frameObservers[i].onFrame(interval, work)
            }
        }
    }

    /** Destroys every Filament object, in reverse order of creation. */
    fun destroy(stages: List<Stage>) {
        if (destroyed) return
        pause()
        stage?.exit(this)
        stage = null
        stages.forEach { it.destroy(this) }
        detachSurface()
        particles.destroy()
        models.destroy()
        environment.destroy()
        meshes.destroy()
        materials.destroy()
        textures.destroy()
        engine.destroyCameraComponent(cameraEntity)
        EntityManager.get().destroy(cameraEntity)
        engine.destroyView(view)
        engine.destroyScene(scene)
        engine.destroyRenderer(renderer)
        engine.destroy()
        destroyed = true
    }
}
