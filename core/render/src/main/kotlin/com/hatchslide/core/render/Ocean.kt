package com.hatchslide.core.render

/**
 * The sea around every scene (spec section 6, water): a dense grid near the island so the vertex
 * shader's Gerstner swell has vertices to move, ringed by an apron out to the horizon whose inner edge
 * shares the grid's border vertices, so the displaced surface never cracks.
 */
class Ocean(private val host: RenderEngine) {
    private var entity = 0
    var shown = false
        private set

    private fun ensure(): Int {
        if (entity == 0) {
            val mesh = host.meshes.cached("ocean") { mesh() }
            entity = host.meshes.renderable(mesh, host.surfaces.water, castShadows = false, receiveShadows = true)
        }
        return entity
    }

    fun show() {
        if (shown) return
        host.scene.addEntity(ensure())
        shown = true
    }

    fun hide() {
        if (!shown) return
        host.scene.remove(entity)
        shown = false
    }

    private fun mesh(): MeshData {
        val b = MeshBuilder(withUvs = false)
        val y = Surfaces.WATER_LEVEL
        val cells = GRID_CELLS
        val half = GRID_SIZE / 2f
        val step = GRID_SIZE / cells
        val grid = IntArray((cells + 1) * (cells + 1))
        for (j in 0..cells) {
            for (i in 0..cells) grid[j * (cells + 1) + i] = b.vertex(-half + i * step, y, -half + j * step, 0f, 1f, 0f)
        }
        for (j in 0 until cells) {
            for (i in 0 until cells) {
                val a = grid[j * (cells + 1) + i]
                val c = grid[j * (cells + 1) + i + 1]
                val d = grid[(j + 1) * (cells + 1) + i + 1]
                val e = grid[(j + 1) * (cells + 1) + i]
                b.quad(a, e, d, c)
            }
        }
        // Apron: fan each side of the grid border out to the far square.
        val far = APRON
        val corners = intArrayOf(
            b.vertex(-far, y, -far, 0f, 1f, 0f),
            b.vertex(far, y, -far, 0f, 1f, 0f),
            b.vertex(far, y, far, 0f, 1f, 0f),
            b.vertex(-far, y, far, 0f, 1f, 0f),
        )
        // The border walked so each fan triangle (border, outer corner, next border) faces up.
        val sides = arrayOf(
            IntArray(cells + 1) { grid[it * (cells + 1)] }, // x = -half, z rising
            IntArray(cells + 1) { grid[cells * (cells + 1) + it] }, // z = +half, x rising
            IntArray(cells + 1) { grid[(cells - it) * (cells + 1) + cells] }, // x = +half, z falling
            IntArray(cells + 1) { grid[cells - it] }, // z = -half, x falling
        )
        val outer = intArrayOf(corners[0], corners[3], corners[2], corners[1])
        for (s in 0 until 4) {
            val from = outer[s]
            val to = outer[(s + 1) % 4]
            val side = sides[s]
            val mid = cells / 2
            for (k in 0 until cells) {
                val outerVertex = if (k < mid) from else to
                b.triangle(side[k], outerVertex, side[k + 1])
            }
            b.triangle(side[mid], from, to)
        }
        return b.build()
    }

    companion object {
        const val GRID_SIZE = 56f
        const val GRID_CELLS = 112
        const val APRON = 400f
    }
}
