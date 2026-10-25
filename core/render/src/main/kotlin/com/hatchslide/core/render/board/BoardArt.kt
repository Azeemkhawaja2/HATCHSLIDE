package com.hatchslide.core.render.board

import com.google.android.filament.Colors
import com.google.android.filament.MaterialInstance
import com.hatchslide.core.game.TileType
import com.hatchslide.core.render.GpuMesh
import com.hatchslide.core.render.Primitives
import com.hatchslide.core.render.RenderEngine
import com.hatchslide.core.render.WorldTheme
import com.hatchslide.core.render.math.Transforms

/**
 * A renderable group placed by the board: a root transform plus renderable children. [flippers]
 * holds the four flipper entities of a hatchling (front-left, front-right, back-left, back-right).
 */
class Visual(val root: Int, val renderables: IntArray, val flippers: IntArray = IntArray(0), val claws: IntArray = IntArray(0)) {
    /** Whether the renderables are currently part of the scene. */
    var shown = false
}

/**
 * Builds the board's visuals. Every shape is described by a mesh and a material slot so the art can
 * be swapped (procedural shapes here; models from the asset pipeline in [ModelArt]) without touching
 * the board logic.
 */
open class BoardArt(protected val host: RenderEngine) {
    protected val meshes = host.meshes
    protected val tm get() = host.engine.transformManager
    private val scratch = FloatArray(16)

    private val instances = HashMap<String, MaterialInstance>()

    protected fun mi(key: String, r: Float, g: Float, b: Float, roughness: Float = 0.8f): MaterialInstance =
        instances.getOrPut(key) { host.materials.litColor(r, g, b, roughness) }

    protected fun decalMi(key: String, r: Float, g: Float, b: Float, a: Float): MaterialInstance =
        instances.getOrPut(key) { host.materials.decal(r, g, b, a) }

    /** Recolours the shared surface instances for a world's palette. */
    open fun applyTheme(theme: WorldTheme) {
        instances["sand"]?.setParameter("baseColor", Colors.RgbaType.SRGB, theme.sand[0], theme.sand[1], theme.sand[2], 1f)
        instances["wet"]?.setParameter("baseColor", Colors.RgbaType.SRGB, theme.wetSand[0], theme.wetSand[1], theme.wetSand[2], 1f)
        instances["water"]?.setParameter(
            "baseColor",
            Colors.RgbaType.SRGB,
            theme.shallowWater[0],
            theme.shallowWater[1],
            theme.shallowWater[2],
            1f,
        )
    }

    protected fun part(
        mesh: GpuMesh,
        material: MaterialInstance,
        parent: Int,
        x: Float,
        y: Float,
        z: Float,
        castShadows: Boolean = true,
    ): Int {
        val e = meshes.renderable(mesh, material, castShadows = castShadows)
        tm.setParent(tm.getInstance(e), tm.getInstance(parent))
        tm.setTransform(tm.getInstance(e), Transforms.trs(scratch, x, y, z, 0f, 1f, 1f, 1f))
        return e
    }

    private fun single(mesh: GpuMesh, material: MaterialInstance, y: Float, castShadows: Boolean = true): Visual {
        val root = meshes.group()
        val e = part(mesh, material, root, 0f, y, 0f, castShadows)
        return Visual(root, intArrayOf(e))
    }

    // ----- Tiles -----

    open fun sandTile(theme: WorldTheme, wet: Boolean): Visual {
        val mesh = meshes.cached("tile") { Primitives.box(0.97f, 0.3f, 0.97f) }
        val material = if (wet) {
            mi("wet", theme.wetSand[0], theme.wetSand[1], theme.wetSand[2], 0.35f)
        } else {
            mi("sand", theme.sand[0], theme.sand[1], theme.sand[2], 0.9f)
        }
        return single(mesh, material, -0.15f, castShadows = false)
    }

    open fun waterTile(theme: WorldTheme): Visual {
        val mesh = meshes.cached("water_tile") { Primitives.plane(1f, 1f) }
        return single(
            mesh,
            mi("water", theme.shallowWater[0], theme.shallowWater[1], theme.shallowWater[2], 0.08f),
            -0.12f,
            castShadows = false,
        )
    }

    open fun rock(variant: Int): Visual {
        val sizes = floatArrayOf(0.62f, 0.7f, 0.66f, 0.74f, 0.6f, 0.68f)
        val s = sizes[variant % sizes.size]
        val mesh = meshes.cached("rock_$variant") { Primitives.ellipsoid(s * 0.5f, s * 0.42f, s * 0.48f, 10, 6) }
        return single(mesh, mi("rock", 0.45f, 0.43f, 0.41f, 0.85f), s * 0.25f)
    }

    open fun shell(variant: Int, cracked: Boolean): Visual {
        val mesh = meshes.cached("shell_$variant") { Primitives.ellipsoid(0.34f, 0.2f + variant * 0.04f, 0.3f) }
        val material = if (cracked) {
            mi("shell_cracked", 0.78f, 0.7f, 0.62f, 0.5f)
        } else {
            when (variant) {
                0 -> mi("shell_scallop", 0.98f, 0.8f, 0.72f, 0.4f)
                1 -> mi("shell_conch", 0.96f, 0.64f, 0.56f, 0.35f)
                else -> mi("shell_cowrie", 0.9f, 0.84f, 0.7f, 0.25f)
            }
        }
        return single(mesh, material, 0.12f)
    }

    open fun currentArrow(type: TileType): Visual {
        val mesh = meshes.cached("arrow") { Primitives.arrow(0.7f, 0.42f) }
        val v = single(mesh, mi("current", 0.32f, 0.8f, 0.86f, 0.3f), 0.02f, castShadows = false)
        val yaw = when (type) {
            TileType.CURRENT_UP -> 0f
            TileType.CURRENT_LEFT -> (Math.PI / 2).toFloat()
            TileType.CURRENT_DOWN -> Math.PI.toFloat()
            else -> (-Math.PI / 2).toFloat()
        }
        tm.setTransform(tm.getInstance(v.renderables[0]), Transforms.trs(scratch, 0f, 0.02f, 0f, yaw, 1f, 1f, 1f))
        return v
    }

    open fun pit(): Visual {
        val root = meshes.group()
        val hole =
            part(meshes.cached("pit") { Primitives.cylinder(0.38f, 0.04f) }, mi("pit", 0.22f, 0.16f, 0.1f, 1f), root, 0f, -0.02f, 0f, false)
        val rim =
            part(
                meshes.cached("pit_rim") {
                    Primitives.cylinder(0.46f, 0.03f)
                },
                mi("pit_rim", 0.7f, 0.56f, 0.36f, 1f),
                root,
                0f,
                -0.03f,
                0f,
                false,
            )
        return Visual(root, intArrayOf(hole, rim))
    }

    /** Tide pool pair marker; [pair] selects a rim shape so pairs read without colour. */
    open fun tidePool(pair: Int): Visual {
        val colors =
            arrayOf(
                floatArrayOf(0.3f, 0.85f, 0.95f),
                floatArrayOf(0.95f, 0.55f, 0.85f),
                floatArrayOf(0.98f, 0.82f, 0.3f),
                floatArrayOf(0.5f, 0.95f, 0.5f),
            )
        val c = colors[(pair - 1).coerceIn(0, 3)]
        val root = meshes.group()
        val water =
            part(
                meshes.cached("pool") {
                    Primitives.cylinder(0.4f, 0.02f)
                },
                mi("pool_$pair", c[0], c[1], c[2], 0.1f),
                root,
                0f,
                0.0f,
                0f,
                false,
            )
        val markers = (0 until pair).map { k ->
            val angle = (k * 2 * Math.PI / pair).toFloat()
            part(
                meshes.cached("pool_marker") { Primitives.ellipsoid(0.06f, 0.05f, 0.06f, 8, 5) },
                mi("pool_marker", 0.95f, 0.95f, 0.95f, 0.3f),
                root,
                kotlin.math.cos(angle) * 0.42f,
                0.03f,
                kotlin.math.sin(angle) * 0.42f,
                false,
            )
        }
        return Visual(root, (listOf(water) + markers).toIntArray())
    }

    // ----- Pieces and hazards -----

    open fun hatchling(skin: Int): Visual {
        val root = meshes.group()
        val shellColor = TURTLE_SHELLS[skin % TURTLE_SHELLS.size]
        val shell =
            part(
                meshes.cached("h_shell") {
                    Primitives.ellipsoid(0.3f, 0.14f, 0.36f)
                },
                mi("h_shell_$skin", shellColor[0], shellColor[1], shellColor[2], 0.45f),
                root,
                0f,
                0.18f,
                0.03f,
            )
        val skinMi = mi("h_skin", 0.56f, 0.72f, 0.5f, 0.6f)
        val body = part(meshes.cached("h_body") { Primitives.ellipsoid(0.27f, 0.08f, 0.32f) }, skinMi, root, 0f, 0.1f, 0.03f)
        val head = part(meshes.cached("h_head") { Primitives.ellipsoid(0.13f, 0.12f, 0.14f) }, skinMi, root, 0f, 0.2f, -0.36f)
        val eyeMesh = meshes.cached("h_eye") { Primitives.ellipsoid(0.045f, 0.05f, 0.04f, 10, 6) }
        val eyeMi = mi("h_eye", 0.08f, 0.09f, 0.12f, 0.2f)
        val eyeL = part(eyeMesh, eyeMi, root, -0.075f, 0.25f, -0.44f)
        val eyeR = part(eyeMesh, eyeMi, root, 0.075f, 0.25f, -0.44f)
        val flipper = meshes.cached("h_flipper") { Primitives.ellipsoid(0.13f, 0.03f, 0.06f, 10, 5) }
        val fl = part(flipper, skinMi, root, -0.3f, 0.1f, -0.16f)
        val fr = part(flipper, skinMi, root, 0.3f, 0.1f, -0.16f)
        val bl = part(flipper, skinMi, root, -0.22f, 0.09f, 0.28f)
        val br = part(flipper, skinMi, root, 0.22f, 0.09f, 0.28f)
        return Visual(root, intArrayOf(shell, body, head, eyeL, eyeR, fl, fr, bl, br), flippers = intArrayOf(fl, fr, bl, br))
    }

    open fun coconut(): Visual {
        val mesh = meshes.cached("coconut") { Primitives.ellipsoid(0.27f, 0.25f, 0.27f) }
        return single(mesh, mi("coconut", 0.42f, 0.27f, 0.15f, 0.9f), 0.25f)
    }

    open fun crab(): Visual {
        val root = meshes.group()
        val red = mi("crab", 0.93f, 0.36f, 0.24f, 0.5f)
        val body = part(meshes.cached("crab_body") { Primitives.ellipsoid(0.3f, 0.14f, 0.22f) }, red, root, 0f, 0.16f, 0f)
        val claw = meshes.cached("crab_claw") { Primitives.ellipsoid(0.1f, 0.08f, 0.12f, 10, 6) }
        val cl = part(claw, red, root, -0.3f, 0.18f, -0.2f)
        val cr = part(claw, red, root, 0.3f, 0.18f, -0.2f)
        return Visual(root, intArrayOf(body, cl, cr), claws = intArrayOf(cl, cr))
    }

    open fun egg(): Visual {
        val mesh = meshes.cached("egg") { Primitives.ellipsoid(0.2f, 0.26f, 0.2f) }
        return single(mesh, mi("egg", 0.98f, 0.95f, 0.88f, 0.4f), 0.26f)
    }

    open fun gullShadow(): Visual {
        val mesh = meshes.cached("gull_shadow") { Primitives.cylinder(0.44f, 0.005f) }
        return single(mesh, decalMi("gull_shadow", 0.05f, 0.06f, 0.12f, 0.42f), 0.03f, castShadows = false)
    }

    open fun gullShadowNext(): Visual {
        val mesh = meshes.cached("gull_next") { Primitives.cylinder(0.3f, 0.004f) }
        return single(mesh, decalMi("gull_next", 0.05f, 0.06f, 0.12f, 0.18f), 0.025f, castShadows = false)
    }

    open fun hintArrow(): Visual {
        val mesh = meshes.cached("hint_arrow") { Primitives.arrow(1.6f, 1.0f) }
        return single(mesh, decalMi("hint", 1f, 1f, 1f, 0.55f), 0.5f, castShadows = false)
    }

    fun destroy(visual: Visual) {
        visual.renderables.forEach { meshes.destroyEntity(it) }
        meshes.destroyEntity(visual.root)
    }

    companion object {
        /** Shell base colours of the seven species skins (sRGB). */
        val TURTLE_SHELLS = arrayOf(
            floatArrayOf(0.36f, 0.5f, 0.3f), // green
            floatArrayOf(0.55f, 0.36f, 0.22f), // loggerhead
            floatArrayOf(0.62f, 0.45f, 0.22f), // hawksbill
            floatArrayOf(0.2f, 0.22f, 0.26f), // leatherback
            floatArrayOf(0.48f, 0.5f, 0.42f), // kemp's ridley
            floatArrayOf(0.42f, 0.46f, 0.3f), // olive ridley
            floatArrayOf(0.66f, 0.62f, 0.5f), // flatback
        )
    }
}
