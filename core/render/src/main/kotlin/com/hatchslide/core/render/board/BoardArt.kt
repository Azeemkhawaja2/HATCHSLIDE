package com.hatchslide.core.render.board

import com.google.android.filament.MaterialInstance
import com.hatchslide.core.game.TileType
import com.hatchslide.core.render.GpuMesh
import com.hatchslide.core.render.MeshBuilder
import com.hatchslide.core.render.MeshData
import com.hatchslide.core.render.QualityTier
import com.hatchslide.core.render.RenderEngine
import com.hatchslide.core.render.assets.AssetCatalog.Models
import com.hatchslide.core.render.assets.ModelInstance
import com.hatchslide.core.render.assets.ModelLibrary
import com.hatchslide.core.render.math.Transforms

/**
 * A renderable group placed by the board: a root transform, the renderables to add to the scene,
 * the model instance it came from (returned to its pool on release) or, for decals, entities it owns,
 * and an optional animation rig. A visual with neither shows nodes of another visual's model.
 */
class Visual(
    val root: Int,
    val renderables: IntArray,
    internal val model: ModelInstance? = null,
    internal val ownsEntities: Boolean = false,
    val hatchling: HatchlingRig? = null,
    val crab: CrabRig? = null,
    val gull: GullRig? = null,
    val egg: EggRig? = null,
) {
    /** Whether the renderables are currently part of the scene. */
    var shown = false
}

/**
 * Builds the board's visuals from the generated models (spec section 6) and a few flat decals.
 * Models come from [ModelLibrary] pools; LOD1 is used on the Low tier and for background decor.
 */
open class BoardArt(protected val host: RenderEngine) {
    private val models = host.models
    private val surfaces = host.surfaces
    private val meshes = host.meshes
    private val tm get() = host.engine.transformManager
    private val scratch = FloatArray(16)

    /** Low tier swaps every model for its LOD1. */
    var tier: QualityTier = QualityTier.MEDIUM

    private fun lod(background: Boolean) = if (background || tier == QualityTier.LOW) 1 else 0

    private fun model(
        name: String,
        background: Boolean = false,
        binder: ModelLibrary.MaterialBinder = surfaces,
    ): Pair<ModelInstance, IntArray> {
        val instance = models.acquire(name, lod(background), binder)
        return instance to instance.renderables
    }

    private fun simple(name: String, background: Boolean = false, binder: ModelLibrary.MaterialBinder = surfaces): Visual {
        val (instance, renderables) = model(name, background, binder)
        return Visual(instance.root, renderables, instance)
    }

    // ----- Tiles -----

    open fun rock(variant: Int): Visual = simple(Models.ROCKS[variant % Models.ROCKS.size])

    open fun shell(variant: Int, cracked: Boolean): Visual =
        simple(if (cracked) Models.SHELL_CRACKED else Models.SHELLS[variant % Models.SHELLS.size])

    /** Current channel: the model's chevrons flow toward -Z, so it is turned to the push direction. */
    open fun current(type: TileType): Visual = simple(Models.CURRENT)

    /** A pit as two visuals over one model: rim and open hole, and the sand mound shown once it is filled. */
    open fun pit(): Pair<Visual, Visual> {
        val (instance, renderables) = model(Models.PIT)
        val fill = instance.node("fill")
        val open = Visual(instance.root, renderables.filter { it != fill }.toIntArray(), instance)
        val filled = Visual(instance.root, intArrayOf(fill))
        return open to filled
    }

    /** Tide pool tinted by [pair], with [pair] tiny cowries on the rim so pairs read without colour. */
    open fun tidePool(pair: Int): Visual {
        val (instance, renderables) = model(Models.TIDE_POOL, binder = surfaces.tidePool(pair))
        val markers = ArrayList<ModelInstance>()
        for (k in 0 until pair) {
            val marker = models.acquire(Models.SHELL_COWRIE, 1, surfaces)
            val angle = (k * 2 * Math.PI / pair + Math.PI * 0.25).toFloat()
            tm.setParent(tm.getInstance(marker.root), tm.getInstance(instance.root))
            tm.setTransform(
                tm.getInstance(marker.root),
                Transforms.trs(scratch, kotlin.math.cos(angle) * 0.4f, 0.03f, kotlin.math.sin(angle) * 0.4f, angle, 0.35f, 0.35f, 0.35f),
            )
            markers += marker
        }
        val all = renderables + markers.flatMap { it.renderables.toList() }
        return Visual(instance.root, all, instance).also { attached[it] = markers }
    }

    /** Child instances attached to a visual's root (tide-pool markers), released with it. */
    private val attached = HashMap<Visual, List<ModelInstance>>()

    // ----- Pieces and hazards -----

    open fun hatchling(skin: Int): Visual {
        val (instance, renderables) = model(Models.HATCHLING, binder = surfaces.hatchling(skin))
        return Visual(instance.root, renderables, instance, hatchling = HatchlingRig(tm, instance, instance.root))
    }

    open fun coconut(): Visual = simple(Models.COCONUT)

    open fun crab(): Visual {
        val (instance, renderables) = model(Models.CRAB)
        return Visual(instance.root, renderables, instance, crab = CrabRig(tm, instance))
    }

    open fun egg(): Visual {
        val (instance, renderables) = model(Models.EGG)
        return Visual(instance.root, renderables, instance, egg = EggRig(tm, instance))
    }

    open fun seagull(): Visual {
        val (instance, renderables) = model(Models.SEAGULL)
        return Visual(instance.root, renderables, instance, gull = GullRig(tm, instance))
    }

    open fun gullShadow(next: Boolean): Visual {
        val mesh = meshes.cached("sprite_quad") { quad(1.15f) }
        val material = if (next) surfaces.gullShadowNext else surfaces.gullShadow
        return decal(mesh, material, 0.025f)
    }

    open fun hintArrow(): Visual = decal(meshes.cached("hint_arrow") { arrow(1.6f, 1.0f) }, surfaces.hint, 0.5f)

    /** Island decoration by model name; [background] picks LOD1. */
    open fun decor(name: String, background: Boolean): Visual = simple(name, background)

    private fun decal(mesh: GpuMesh, material: MaterialInstance, y: Float): Visual {
        val root = meshes.group()
        val e = meshes.renderable(mesh, material, castShadows = false, receiveShadows = false)
        tm.setParent(tm.getInstance(e), tm.getInstance(root))
        tm.setTransform(tm.getInstance(e), Transforms.trs(scratch, 0f, y, 0f, 0f, 1f, 1f, 1f))
        return Visual(root, intArrayOf(e), ownsEntities = true)
    }

    fun destroy(visual: Visual) {
        visual.model?.let { model ->
            attached.remove(visual)?.forEach { marker ->
                tm.setParent(tm.getInstance(marker.root), 0)
                models.release(marker)
            }
            models.release(model)
        }
        if (visual.ownsEntities) {
            visual.renderables.forEach { meshes.destroyEntity(it) }
            meshes.destroyEntity(visual.root)
        }
    }

    // ----- Flat decal meshes -----

    /** Horizontal textured quad of side [size], UVs with v = 0 at the far (-z) edge. */
    private fun quad(size: Float): MeshData {
        val b = MeshBuilder()
        val h = size / 2
        val a = b.vertex(-h, 0f, -h, 0f, 1f, 0f, 0f, 0f)
        val c = b.vertex(h, 0f, -h, 0f, 1f, 0f, 1f, 0f)
        val d = b.vertex(h, 0f, h, 0f, 1f, 0f, 1f, 1f)
        val e = b.vertex(-h, 0f, h, 0f, 1f, 0f, 0f, 1f)
        b.quad(a, e, d, c)
        return b.build()
    }

    /** Flat arrow pointing toward -Z (board-up), lying on y = 0, for the hint. */
    private fun arrow(length: Float, width: Float): MeshData {
        val b = MeshBuilder()
        val shaft = width * 0.38f
        val headStart = -length * 0.05f
        val points = arrayOf(
            floatArrayOf(-shaft, length * 0.5f),
            floatArrayOf(shaft, length * 0.5f),
            floatArrayOf(shaft, headStart),
            floatArrayOf(width / 2, headStart),
            floatArrayOf(0f, -length * 0.5f),
            floatArrayOf(-width / 2, headStart),
            floatArrayOf(-shaft, headStart),
        )
        val idx = points.map { (x, z) -> b.vertex(x, 0f, z, 0f, 1f, 0f, x / width + 0.5f, z / length + 0.5f) }
        b.triangle(idx[0], idx[2], idx[1])
        b.triangle(idx[0], idx[6], idx[2])
        b.triangle(idx[3], idx[5], idx[4])
        b.triangle(idx[6], idx[5], idx[3])
        b.triangle(idx[6], idx[3], idx[2])
        return b.build()
    }
}
