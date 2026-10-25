package com.hatchslide.core.render.board

import com.hatchslide.core.game.Board
import com.hatchslide.core.game.TileType
import com.hatchslide.core.render.Surfaces
import com.hatchslide.core.render.WorldTheme
import com.hatchslide.core.render.fx.ParticlePool
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Board particles and decals (spec section 6): a sand trail while sliding, a sand puff on every stop,
 * splash, spray and sparkle on a save, shell confetti on three stars, egg and shell bits, footprints
 * that fade over 4 s, fireflies at night and drifting ash on the volcano. Positions are world units
 * (one cell per unit, board centred on the origin). Everything goes through the pooled
 * [ParticlePool] that the renderer's particle system draws; nothing here allocates per frame.
 */
class BoardEffects(private val pool: ParticlePool) {
    private var theme = WorldTheme.TITLE
    private var board: Board? = null
    private var heights = FloatArray(0)
    private var halfWidth = 0.5f
    private var halfHeight = 0.5f
    private var trail = FloatArray(0)
    private var prints = FloatArray(0)
    private var prevX = FloatArray(0)
    private var prevZ = FloatArray(0)
    private var tracking = BooleanArray(0)

    /** Starts a level: clears every particle and remembers its board and look. */
    fun begin(theme: WorldTheme, board: Board, tileHeights: FloatArray, slots: Int) {
        pool.clear()
        this.theme = theme
        this.board = board
        heights = tileHeights
        halfWidth = board.width / 2f
        halfHeight = board.height / 2f
        trail = FloatArray(slots)
        prints = FloatArray(slots)
        prevX = FloatArray(slots)
        prevZ = FloatArray(slots)
        tracking = BooleanArray(slots)
    }

    fun end() {
        pool.clear()
        board = null
    }

    /** Forgets every piece's last position (after a snap, undo or restart). */
    fun resetTracks() = tracking.fill(false)

    /**
     * Follows piece [id] at cell coordinates ([cellX], [cellY]) and world ([worldX], [worldZ]):
     * trails sand while it slides over sand and, for hatchlings, leaves footprints.
     */
    fun piece(id: Int, cellX: Float, cellY: Float, worldX: Float, worldZ: Float, sliding: Boolean, hatchling: Boolean) {
        if (id >= tracking.size) return
        if (!sliding) {
            tracking[id] = false
            return
        }
        if (!tracking[id]) {
            tracking[id] = true
            prevX[id] = worldX
            prevZ[id] = worldZ
            return
        }
        val dx = worldX - prevX[id]
        val dz = worldZ - prevZ[id]
        prevX[id] = worldX
        prevZ[id] = worldZ
        val distance = sqrt(dx * dx + dz * dz)
        // Teleports jump; the next frame starts a fresh track.
        if (distance < 1e-4f || distance > 1.2f) return
        val cell = cellAt(cellX, cellY)
        if (cell < 0) return
        val tile = board?.tile(cell) ?: return
        val nx = dx / distance
        val nz = dz / distance
        val ground = heights.getOrElse(cell) { 0f }
        if (tile.currentDirection != null) {
            trail[id] += distance
            while (trail[id] >= TRAIL_STEP * 2f) {
                trail[id] -= TRAIL_STEP * 2f
                if (pool.scaled(1) > 0) spray(worldX - nx * 0.25f, ground + 0.05f, worldZ - nz * 0.25f, 0.6f)
            }
            return
        }
        if (tile != TileType.SAND && tile != TileType.WET_SAND) return
        val wet = tile == TileType.WET_SAND
        trail[id] += distance
        while (trail[id] >= TRAIL_STEP) {
            trail[id] -= TRAIL_STEP
            repeat(pool.scaled(2)) { grain(worldX - nx * 0.24f, ground + 0.04f, worldZ - nz * 0.24f, nx, nz, wet) }
        }
        if (!hatchling) return
        prints[id] += distance
        while (prints[id] >= PRINT_STEP) {
            prints[id] -= PRINT_STEP
            footprint(worldX - nx * 0.12f, ground + 0.012f, worldZ - nz * 0.12f, atan2(nx, -nz), wet)
        }
    }

    /** Sand puff (or wet clumps, or spray on water tiles) where a piece stops or a current turns it. */
    fun stop(worldX: Float, worldZ: Float, cell: Int) {
        val tile = board?.tile(cell) ?: return
        val ground = heights.getOrElse(cell) { 0f }
        when {
            tile == TileType.SAND || tile == TileType.WET_SAND -> puff(worldX, ground, worldZ, 10, tile == TileType.WET_SAND)

            tile.currentDirection != null || tile == TileType.TIDE_POOL -> repeat(pool.scaled(4)) {
                spray(worldX, ground + 0.05f, worldZ, 0.8f)
            }

            else -> Unit
        }
    }

    /** Splash, spray, ripple and sparkle where a hatchling dives into the sea. */
    fun save(worldX: Float, worldZ: Float) = splash(worldX, worldZ, 1f, sparkle = true)

    /** Smaller splash where a coconut drops into the sea. */
    fun sink(worldX: Float, worldZ: Float) = splash(worldX, worldZ, 0.55f, sparkle = false)

    /** A darker puff where something falls into a pit or fills it. */
    fun pit(worldX: Float, worldZ: Float, cell: Int) = puff(worldX, heights.getOrElse(cell) { 0f } - 0.05f, worldZ, 12, wet = true)

    /** Fragments of a cracked shell or egg on board cell [cell]. */
    fun bits(worldX: Float, worldZ: Float, cell: Int, egg: Boolean) = shellBits(worldX, heights.getOrElse(cell) { 0f }, worldZ, egg)

    /** Fragments of a cracked shell or egg standing on the ground at height [ground]. */
    fun shellBits(worldX: Float, ground: Float, worldZ: Float, egg: Boolean) {
        val light = theme.fxLight
        // Cream eggshell or pale pink seashell.
        val r = 0.96f
        val g = if (egg) 0.91f else 0.86f
        val b = if (egg) 0.82f else 0.8f
        repeat(pool.scaled(9)) {
            val angle = pool.random() * TAU
            val speed = pool.range(0.5f, 1.1f)
            pool.spawn(
                CELL_SPRAY,
                worldX,
                ground + 0.25f,
                worldZ,
                cos(angle) * speed,
                pool.range(1.0f, 1.8f),
                sin(angle) * speed,
                lifetime = pool.range(0.45f, 0.7f),
                size = pool.range(0.05f, 0.08f),
                r = r * light,
                g = g * light,
                b = b * light,
                opacity = 0.95f,
                fall = 6f,
                shrink = 0.25f,
            )
        }
    }

    /** Shimmer at both ends of a tide-pool teleport. */
    fun teleport(fromX: Float, fromZ: Float, toX: Float, toZ: Float) {
        sparkles(fromX, 0.15f, fromZ, 4)
        sparkles(toX, 0.15f, toZ, 5)
    }

    /** Shell confetti over the board when the third star lands. */
    fun confetti() {
        val light = theme.fxLight.coerceAtLeast(0.55f)
        repeat(pool.scaled(48)) { k ->
            val cell = CELL_CONFETTI[k % CELL_CONFETTI.size]
            pool.spawn(
                cell,
                pool.range(-halfWidth, halfWidth),
                pool.range(2.6f, 3.6f),
                pool.range(-halfHeight - 0.5f, halfHeight),
                pool.range(-0.6f, 0.6f),
                pool.range(0.4f, 1.4f),
                pool.range(-0.4f, 0.4f),
                lifetime = pool.range(2.6f, 3.8f),
                size = pool.range(0.16f, 0.24f),
                r = light,
                g = light,
                b = light,
                opacity = 1f,
                flag = ParticlePool.FLUTTER,
                fall = 1.6f,
                damping = 1.5f,
                angle = pool.random() * TAU,
                turn = pool.range(-6f, 6f),
                grow = 0.02f,
                shrink = 0.2f,
                sway = pool.range(0.3f, 0.7f),
            )
        }
    }

    /** Keeps the night fireflies and the volcanic ash topped up. */
    fun update() {
        if (board == null) return
        if (theme.glow > 0f) {
            val target = pool.scaled(FIREFLIES)
            while (pool.countOf(ParticlePool.GROUP_FIREFLY) < target && pool.count < pool.capacity) firefly()
        }
        if (theme.ash) {
            val target = pool.scaled(ASH)
            while (pool.countOf(ParticlePool.GROUP_ASH) < target && pool.count < pool.capacity) ash()
        }
    }

    // ----- Emitters -----

    private fun grain(x: Float, y: Float, z: Float, nx: Float, nz: Float, wet: Boolean) {
        val light = theme.fxLight * if (wet) 0.7f else 1f
        val d = theme.dust
        pool.spawn(
            CELL_SAND,
            x + pool.range(-0.08f, 0.08f),
            y,
            z + pool.range(-0.08f, 0.08f),
            -nx * pool.range(0.2f, 0.5f) + pool.range(-0.2f, 0.2f),
            pool.range(0.4f, if (wet) 0.6f else 0.9f),
            -nz * pool.range(0.2f, 0.5f) + pool.range(-0.2f, 0.2f),
            lifetime = pool.range(0.35f, 0.55f),
            size = pool.range(0.07f, 0.12f),
            endSize = 0.04f,
            r = d[0] * light,
            g = d[1] * light,
            b = d[2] * light,
            opacity = if (wet) 0.9f else 0.75f,
            fall = 3.5f,
            angle = pool.random() * TAU,
        )
    }

    private fun puff(x: Float, y: Float, z: Float, n: Int, wet: Boolean) {
        val light = theme.fxLight * if (wet) 0.65f else 1f
        val d = theme.dust
        repeat(pool.scaled(n)) {
            val angle = pool.random() * TAU
            val speed = pool.range(0.5f, 1.1f)
            pool.spawn(
                CELL_SAND,
                x + cos(angle) * 0.15f,
                y + 0.05f,
                z + sin(angle) * 0.15f,
                cos(angle) * speed,
                pool.range(0.2f, 0.6f),
                sin(angle) * speed,
                lifetime = pool.range(0.45f, 0.7f),
                size = pool.range(0.14f, 0.2f),
                endSize = pool.range(0.3f, 0.42f),
                r = d[0] * light,
                g = d[1] * light,
                b = d[2] * light,
                opacity = if (wet) 0.55f else 0.6f,
                fall = 0.6f,
                damping = 4f,
                angle = pool.random() * TAU,
                turn = pool.range(-1.5f, 1.5f),
                grow = 0.08f,
                shrink = 0.6f,
            )
        }
    }

    private fun footprint(x: Float, y: Float, z: Float, heading: Float, wet: Boolean) {
        pool.spawn(
            CELL_FOOTPRINT,
            x,
            y,
            z,
            lifetime = PRINT_SECONDS,
            size = 0.36f,
            r = 0f,
            g = 0f,
            b = 0f,
            opacity = if (wet) 0.5f else 0.38f,
            flag = ParticlePool.GROUND,
            angle = heading,
            grow = 0.02f,
            shrink = 1f,
        )
    }

    private fun splash(x: Float, z: Float, strength: Float, sparkle: Boolean) {
        val water = Surfaces.WATER_LEVEL + 0.04f
        val light = theme.fxLight
        repeat(pool.scaled((14 * strength).toInt())) {
            val angle = pool.random() * TAU
            val speed = pool.range(0.4f, 1.0f) * strength
            pool.spawn(
                CELL_DROPLET,
                x,
                water,
                z,
                cos(angle) * speed,
                pool.range(1.6f, 2.6f) * strength,
                sin(angle) * speed,
                lifetime = pool.range(0.55f, 0.85f),
                size = pool.range(0.08f, 0.13f),
                r = 0.85f * light,
                g = 0.97f * light,
                b = light,
                opacity = 0.9f,
                fall = 6.5f,
                shrink = 0.2f,
            )
        }
        repeat(pool.scaled((5 * strength).toInt() + 1)) { spray(x, water + 0.1f, z, strength) }
        pool.spawn(
            CELL_RIPPLE,
            x,
            water + 0.03f,
            z,
            lifetime = 1.1f,
            size = 0.3f,
            endSize = 1.5f * strength,
            r = light,
            g = light,
            b = light,
            opacity = 0.6f,
            flag = ParticlePool.GROUND,
            grow = 0.05f,
            shrink = 0.8f,
        )
        if (sparkle) sparkles(x, water + 0.3f, z, 6)
    }

    private fun spray(x: Float, y: Float, z: Float, strength: Float) {
        val light = theme.fxLight
        pool.spawn(
            CELL_SPRAY,
            x + pool.range(-0.12f, 0.12f),
            y,
            z + pool.range(-0.12f, 0.12f),
            pool.range(-0.3f, 0.3f),
            pool.range(0.4f, 0.9f) * strength,
            pool.range(-0.3f, 0.3f),
            lifetime = pool.range(0.6f, 0.9f),
            size = 0.18f,
            endSize = 0.5f * strength + 0.1f,
            r = light,
            g = light,
            b = light,
            opacity = 0.45f,
            fall = 0.4f,
            damping = 2.5f,
            angle = pool.random() * TAU,
            grow = 0.1f,
            shrink = 0.7f,
        )
    }

    private fun sparkles(x: Float, y: Float, z: Float, n: Int) {
        val glow = theme.glowColor
        val night = theme.glow > 0f
        repeat(pool.scaled(n)) {
            pool.spawn(
                CELL_SPARKLE,
                x + pool.range(-0.3f, 0.3f),
                y + pool.range(0f, 0.3f),
                z + pool.range(-0.3f, 0.3f),
                velocityY = pool.range(0.2f, 0.6f),
                lifetime = pool.range(0.5f, 0.8f),
                size = pool.range(0.12f, 0.2f),
                endSize = 0.05f,
                r = if (night) glow[0] else 1f,
                g = if (night) glow[1] else 0.97f,
                b = if (night) glow[2] else 0.8f,
                opacity = 0f,
                angle = pool.random() * TAU,
                turn = pool.range(-3f, 3f),
                grow = 0.15f,
                shrink = 0.5f,
            )
        }
    }

    private fun firefly() {
        val x = pool.range(-halfWidth - 2.5f, halfWidth + 2.5f)
        val z = pool.range(-halfHeight - 2.5f, halfHeight + 2f)
        val overBoard = x > -halfWidth && x < halfWidth && z > -halfHeight && z < halfHeight
        pool.spawn(
            CELL_GLOW,
            x,
            if (overBoard) pool.range(1.1f, 1.8f) else pool.range(0.2f, 1.3f),
            z,
            pool.range(-0.15f, 0.15f),
            pool.range(-0.05f, 0.05f),
            pool.range(-0.15f, 0.15f),
            lifetime = pool.range(5f, 8f),
            size = pool.range(0.12f, 0.18f),
            r = 0.75f,
            g = 1f,
            b = 0.45f,
            opacity = 0f,
            flag = ParticlePool.BLINK or ParticlePool.WANDER,
            damping = 0.8f,
            grow = 0.2f,
            shrink = 0.3f,
            sway = 0.6f,
            tag = ParticlePool.GROUP_FIREFLY,
        )
    }

    private fun ash() {
        val light = theme.fxLight
        pool.spawn(
            CELL_ASH,
            pool.range(-halfWidth - 3f, halfWidth + 2f),
            pool.range(0.4f, 4.2f),
            pool.range(-halfHeight - 3f, halfHeight + 2f),
            pool.range(0.08f, 0.2f),
            pool.range(-0.3f, -0.12f),
            pool.range(-0.03f, 0.06f),
            lifetime = pool.range(7f, 11f),
            size = pool.range(0.07f, 0.12f),
            r = light * 1.2f,
            g = light * 1.15f,
            b = light * 1.15f,
            opacity = 0.8f,
            flag = ParticlePool.FLUTTER,
            angle = pool.random() * TAU,
            turn = pool.range(-1.5f, 1.5f),
            grow = 0.15f,
            shrink = 0.25f,
            sway = pool.range(0.05f, 0.15f),
            tag = ParticlePool.GROUP_ASH,
        )
    }

    private fun cellAt(cellX: Float, cellY: Float): Int {
        val b = board ?: return -1
        val cx = (cellX + 0.5f).toInt()
        val cy = (cellY + 0.5f).toInt()
        if (cellX < -0.5f || cellY < -0.5f || cx >= b.width || cy >= b.height) return -1
        return cy * b.width + cx
    }

    companion object {
        private const val TAU = (2 * PI).toFloat()
        private const val TRAIL_STEP = 0.14f
        private const val PRINT_STEP = 0.42f
        const val PRINT_SECONDS = 4f
        private const val FIREFLIES = 22
        private const val ASH = 48
        private val CELL_SAND = ParticlePool.cellOf("sand_puff")
        private val CELL_DROPLET = ParticlePool.cellOf("droplet")
        private val CELL_SPRAY = ParticlePool.cellOf("spray")
        private val CELL_SPARKLE = ParticlePool.cellOf("sparkle")
        private val CELL_GLOW = ParticlePool.cellOf("glow")
        private val CELL_ASH = ParticlePool.cellOf("ash")
        private val CELL_FOOTPRINT = ParticlePool.cellOf("footprint")
        private val CELL_RIPPLE = ParticlePool.cellOf("ripple")
        private val CELL_CONFETTI = intArrayOf(
            ParticlePool.cellOf("confetti_pink"),
            ParticlePool.cellOf("confetti_teal"),
            ParticlePool.cellOf("confetti_yellow"),
        )
    }
}
