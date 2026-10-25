package com.hatchslide.core.render.stages

import com.google.android.filament.Camera
import com.google.android.filament.Texture
import com.hatchslide.core.render.GpuMesh
import com.hatchslide.core.render.MeshBuilder
import com.hatchslide.core.render.MeshData
import com.hatchslide.core.render.Ocean
import com.hatchslide.core.render.QualityTier
import com.hatchslide.core.render.RenderEngine
import com.hatchslide.core.render.Stage
import com.hatchslide.core.render.Surfaces
import com.hatchslide.core.render.WorldTheme
import com.hatchslide.core.render.assets.AssetCatalog.Models
import com.hatchslide.core.render.assets.ModelInstance
import com.hatchslide.core.render.lookAt
import com.hatchslide.core.render.math.PerspectiveProjector
import com.hatchslide.core.render.math.Transforms
import com.hatchslide.core.render.math.Vec3
import com.hatchslide.core.render.math.lerp
import com.hatchslide.core.render.math.smoothstep
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The world map (spec section 5): five island models, one per world, joined by a dotted sea path.
 * Dragging pans along the chain; tapping an island flies the camera to it. Screen positions of the
 * islands are exposed for the Compose overlay (stars, locks) and for hit-testing taps.
 */
class ArchipelagoStage(private val host: RenderEngine) : Stage {
    private val tm get() = host.engine.transformManager
    private val scratch = FloatArray(16)
    private val ocean = Ocean(host)
    private val islands = ArrayList<ModelInstance>()
    private var path: GpuMesh? = null
    private var pathEntity = 0
    private var mask: Texture? = null
    private var shore: Texture? = null
    private var shoreTransform = FloatArray(4)
    private var built = false
    private var entered = false
    private var clock = 0f

    private val projector = PerspectiveProjector()
    private val eye = Vec3()
    private val target = Vec3()
    private val ndc = Vec3()

    /** Camera centre along the chain, its goal, and the zoom (0 overview, 1 close on an island). */
    private var centre = 0f
    private var goal = 0f
    private var zoom = 0f
    private var zoomGoal = 0f
    private var flyFrom = 0f
    private var flyTime = -1f
    private var arriving = -1

    /** Called when a fly-to finishes, with the world number. */
    var onArrived: ((Int) -> Unit)? = null
    var tier = QualityTier.MEDIUM

    override val isAnimating: Boolean get() = true

    private fun build() {
        if (built) return
        built = true
        for (i in 0 until ISLANDS.size) {
            val island = host.models.acquire(Models.ISLANDS[i], if (tier == QualityTier.LOW) 1 else 0, host.surfaces)
            val (x, z, yaw) = ISLANDS[i]
            tm.setTransform(tm.getInstance(island.root), Transforms.trs(scratch, x, ISLAND_Y, z, yaw, 1f, 1f, 1f))
            islands += island
        }
        path = host.meshes.upload(pathMesh())
        pathEntity = host.meshes.renderable(path!!, host.surfaces.pathDot, castShadows = false, receiveShadows = false)
        mask = host.textures.rgba(1, 1, byteArrayOf(0, 0, 0, 0))
        val (pixels, width, height) = shorePixels()
        shore = host.textures.rgba(width, height, pixels)
        shoreTransform = floatArrayOf(-REGION_X, -REGION_Z, 1f / (2 * REGION_X), 1f / (2 * REGION_Z))
    }

    /** Flies to world [world] (1-based); [animate] false jumps there. */
    fun focus(world: Int, animate: Boolean = true) {
        val i = (world - 1).coerceIn(0, ISLANDS.size - 1)
        goal = ISLANDS[i].first
        zoomGoal = 1f
        if (animate) {
            flyFrom = centre
            flyTime = 0f
            arriving = world
        } else {
            centre = goal
            zoom = zoomGoal
            flyTime = -1f
        }
    }

    /** Back to the overview around world [world]. */
    fun overview(world: Int) {
        goal = ISLANDS[(world - 1).coerceIn(0, ISLANDS.size - 1)].first
        zoomGoal = 0f
        flyTime = -1f
    }

    /** Pans by a drag of [dxPixels]; cancels any fly-to. */
    fun pan(dxPixels: Float) {
        flyTime = -1f
        arriving = -1
        zoomGoal = 0f
        val unitsPerPixel = 22f / host.width.coerceAtLeast(1)
        goal = (goal - dxPixels * unitsPerPixel).coerceIn(ISLANDS.first().first, ISLANDS.last().first)
        centre = goal
    }

    /** Screen position (pixels) of island [world]'s summit into [out]; false if off screen. */
    fun screenPosition(world: Int, out: FloatArray): Boolean {
        val (x, z, _) = ISLANDS[world - 1]
        if (!projector.project(x, ISLAND_Y + 1.2f, z, ndc)) return false
        out[0] = (ndc.x + 1f) / 2f * host.width
        out[1] = (1f - ndc.y) / 2f * host.height
        return abs(ndc.x) < 1.2f && abs(ndc.y) < 1.2f
    }

    private val hit = FloatArray(2)

    /** The world whose island is under the pixel ([px], [py]), or null. */
    fun islandAt(px: Float, py: Float): Int? {
        var best: Int? = null
        var bestDistance = host.height * 0.12f
        for (world in 1..ISLANDS.size) {
            if (!screenPosition(world, hit)) continue
            val d = sqrt((hit[0] - px) * (hit[0] - px) + (hit[1] - py) * (hit[1] - py))
            if (d < bestDistance) {
                bestDistance = d
                best = world
            }
        }
        return best
    }

    override fun enter(host: RenderEngine) {
        build()
        entered = true
        host.environment.apply(WorldTheme.CORAL_COAST)
        ocean.show()
        for (island in islands) for (e in island.renderables) host.scene.addEntity(e)
        host.scene.addEntity(pathEntity)
        host.surfaces.setBoardTextures(mask!!, floatArrayOf(0f, 0f, 1f, 1f), shore!!, shoreTransform)
    }

    override fun exit(host: RenderEngine) {
        entered = false
        ocean.hide()
        for (island in islands) for (e in island.renderables) host.scene.remove(e)
        host.scene.remove(pathEntity)
    }

    override fun onResize(host: RenderEngine, width: Int, height: Int) = Unit

    override fun update(host: RenderEngine, dt: Float) {
        clock += dt
        if (flyTime >= 0f) {
            flyTime += dt
            val t = smoothstep(flyTime / FLY_SECONDS)
            centre = lerp(flyFrom, goal, t)
            if (flyTime >= FLY_SECONDS) {
                flyTime = -1f
                val world = arriving
                arriving = -1
                if (world > 0) onArrived?.invoke(world)
            }
        } else {
            centre += (goal - centre) * (1f - kotlin.math.exp(-6f * dt))
        }
        zoom += (zoomGoal - zoom) * (1f - kotlin.math.exp(-4f * dt))
        val bob = sin(clock * 0.4f) * 0.15f
        val height = lerp(11f, 6.5f, zoom)
        val back = lerp(9.5f, 6f, zoom)
        val focusZ = nearestZ(centre) * zoom
        target.set(centre, Surfaces.WATER_LEVEL, focusZ)
        eye.set(centre, Surfaces.WATER_LEVEL + height + bob, focusZ + back)
        val aspect = host.width.toFloat() / host.height.coerceAtLeast(1)
        val fov = if (aspect < 0.8f) FOV * 1.35f else FOV
        projector.configure(eye, target, fov, aspect)
        val cam: Camera = host.camera
        cam.setProjection(fov.toDouble(), aspect.toDouble(), 0.1, 300.0, Camera.Fov.VERTICAL)
        cam.setShift(0.0, 0.0)
        cam.lookAt(eye, target)
    }

    private fun nearestZ(x: Float): Float {
        var best = ISLANDS[0]
        for (island in ISLANDS) if (abs(island.first - x) < abs(best.first - x)) best = island
        return best.second
    }

    override fun destroy(host: RenderEngine) {
        if (entered) exit(host)
        islands.forEach { host.models.release(it) }
        islands.clear()
        if (pathEntity != 0) host.meshes.destroyEntity(pathEntity)
        path?.let { host.meshes.release(it) }
        mask?.let {
            host.surfaces.clearBoardTextures(it)
            host.textures.release(it)
        }
        shore?.let { host.textures.release(it) }
        pathEntity = 0
        path = null
        mask = null
        shore = null
        built = false
    }

    /** Dotted path: small flat sprites along a Catmull-Rom curve through the islands, skipping their shores. */
    private fun pathMesh(): MeshData {
        val b = MeshBuilder()
        val y = Surfaces.WATER_LEVEL + 0.09f
        val half = 0.12f
        var carry = 0f
        var px = ISLANDS[0].first
        var pz = ISLANDS[0].second
        for (seg in 0 until ISLANDS.size - 1) {
            val p0 = ISLANDS[maxOf(seg - 1, 0)]
            val p1 = ISLANDS[seg]
            val p2 = ISLANDS[seg + 1]
            val p3 = ISLANDS[minOf(seg + 2, ISLANDS.size - 1)]
            val steps = 200
            for (s in 1..steps) {
                val t = s.toFloat() / steps
                val x = catmull(p0.first, p1.first, p2.first, p3.first, t)
                val z = catmull(p0.second, p1.second, p2.second, p3.second, t)
                carry += sqrt((x - px) * (x - px) + (z - pz) * (z - pz))
                px = x
                pz = z
                if (carry < DOT_SPACING) continue
                carry = 0f
                if (ISLANDS.any { (ix, iz, _) -> (x - ix) * (x - ix) + (z - iz) * (z - iz) < 3.1f * 3.1f }) continue
                val a = b.vertex(x - half, y, z - half, 0f, 1f, 0f, 0f, 0f)
                val c = b.vertex(x + half, y, z - half, 0f, 1f, 0f, 1f, 0f)
                val d = b.vertex(x + half, y, z + half, 0f, 1f, 0f, 1f, 1f)
                val e = b.vertex(x - half, y, z + half, 0f, 1f, 0f, 0f, 1f)
                b.quad(a, e, d, c)
            }
        }
        return b.build()
    }

    private fun catmull(a: Float, b: Float, c: Float, d: Float, t: Float): Float {
        val t2 = t * t
        val t3 = t2 * t
        return 0.5f * (2f * b + (-a + c) * t + (2f * a - 5f * b + 4f * c - d) * t2 + (-a + 3f * b - 3f * c + d) * t3)
    }

    /** Shoreline distance around the islands for the water's colour and foam (r = 0 on the coast). */
    private fun shorePixels(): Triple<ByteArray, Int, Int> {
        val w = (2 * REGION_X * SHORE_SAMPLES).toInt()
        val h = (2 * REGION_Z * SHORE_SAMPLES).toInt()
        val pixels = ByteArray(w * h * 4)
        for (j in 0 until h) {
            for (i in 0 until w) {
                val x = -REGION_X + (i + 0.5f) / SHORE_SAMPLES
                val z = -REGION_Z + (j + 0.5f) / SHORE_SAMPLES
                var d = 99f
                for ((ix, iz, _) in ISLANDS) d = minOf(d, sqrt((x - ix) * (x - ix) + (z - iz) * (z - iz)) - COAST_RADIUS)
                val border = i == 0 || j == 0 || i == w - 1 || j == h - 1
                val v = if (border) 255 else ((d / 3f).coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                val k = (j * w + i) * 4
                pixels[k] = v.toByte()
                pixels[k + 1] = v.toByte()
                pixels[k + 2] = v.toByte()
                pixels[k + 3] = -1
            }
        }
        return Triple(pixels, w, h)
    }

    companion object {
        private const val FOV = 35f
        private const val FLY_SECONDS = 0.9f
        private const val DOT_SPACING = 0.55f
        private const val COAST_RADIUS = 2.3f
        private const val REGION_X = 22f
        private const val REGION_Z = 10f
        private const val SHORE_SAMPLES = 4f
        private val ISLAND_Y = Surfaces.WATER_LEVEL + 0.12f

        /** Island centres (x, z) and yaw, world 1 to 5 from left to right. */
        val ISLANDS = listOf(
            Triple(-14f, 1.8f, 0.3f),
            Triple(-7f, -2.4f, 1.9f),
            Triple(0f, 2.0f, -0.6f),
            Triple(7f, -2.2f, 2.6f),
            Triple(14f, 1.6f, 0.9f),
        )
    }
}
