package com.hatchslide.core.render.board

import com.google.android.filament.Camera
import com.hatchslide.core.game.Direction
import com.hatchslide.core.game.GameState
import com.hatchslide.core.game.Level
import com.hatchslide.core.game.MoveEvent
import com.hatchslide.core.game.MoveOutcome
import com.hatchslide.core.game.PieceKind
import com.hatchslide.core.game.TileType
import com.hatchslide.core.render.RenderEngine
import com.hatchslide.core.render.Stage
import com.hatchslide.core.render.WorldTheme
import com.hatchslide.core.render.camera.CameraAnimator
import com.hatchslide.core.render.camera.CameraRig
import com.hatchslide.core.render.camera.FreeViewport
import com.hatchslide.core.render.math.Transforms
import com.hatchslide.core.render.math.easeOutBack
import kotlin.math.PI
import kotlin.math.sin

/** Receives move events at the moment they are shown on screen (for audio, haptics, particles). */
fun interface BoardEventListener {
    /** [worldX] and [worldZ] are the event's position in board units (x is used for stereo panning). */
    fun onEvent(event: MoveEvent, worldX: Float, worldZ: Float)
}

/**
 * The gameplay board (spec sections 2 and 6). The board never changes game state: it shows the
 * [GameState] it is given and animates [MoveOutcome]s handed to [play]. When an animation finishes
 * it calls back so the game can run a buffered swipe. The per-frame path allocates nothing.
 */
open class BoardStage(protected val host: RenderEngine, protected val art: BoardArt = BoardArt(host)) : Stage {
    private val tm get() = host.engine.transformManager
    private val scene get() = host.scene
    private val scratch = FloatArray(16)
    private val pose = PiecePose()

    var level: Level? = null
        private set
    private var width = 1
    private var height = 1
    private var theme = WorldTheme.TITLE
    private var displayed: GameState? = null
    private var pending: GameState? = null
    private var skin = 0

    private val visuals = ArrayList<Visual>()
    private val staticVisuals = ArrayList<Visual>()
    private val shellByCell = HashMap<Int, Visual>()
    private val pitByCell = HashMap<Int, Visual>()
    private val pitCapByCell = HashMap<Int, Visual>()
    private var pieces = arrayOfNulls<Visual>(0)
    private var pieceIsHatchling = BooleanArray(0)
    private val crabs = ArrayList<Visual>()
    private val eggs = ArrayList<Visual>()
    private val shadows = ArrayList<Visual>()
    private val shadowNext = ArrayList<Visual>()
    private var hint: Visual? = null
    private var hintDirection: Direction? = null

    private var timeline: MoveTimeline? = null
    private var timelineTime = 0f
    private var schedule: List<Pair<Float, MoveEvent>> = emptyList()
    private var nextEvent = 0
    private var hardStops: List<Pair<Int, Float>> = emptyList()
    private var nextShake = 0
    private var onFinished: (() -> Unit)? = null
    private var bumpDirection: Direction? = null
    private var bumpTime = 0f
    private var clock = 0f
    private var lastSaveCell = -1

    private val rig = CameraRig()
    val camera = CameraAnimator()
    private var free = FreeViewport(1, 1)
    private var entered = false

    var listener: BoardEventListener? = null

    var reducedMotion: Boolean = false
        set(value) {
            field = value
            camera.reducedMotion = value
        }

    override val isAnimating: Boolean get() = true

    // ----- Public API -----

    fun load(level: Level, state: GameState, theme: WorldTheme, skin: Int, dolly: Boolean = true) {
        clear()
        this.level = level
        width = level.board.width
        height = level.board.height
        this.theme = theme
        this.skin = skin
        pieces = arrayOfNulls(level.board.maxPieces.coerceAtLeast(1))
        pieceIsHatchling = BooleanArray(pieces.size)
        art.applyTheme(theme)
        if (host.environment.theme != theme) host.environment.apply(theme)
        buildStatic(level, state)
        displayed = state
        sync(state)
        reframe()
        if (dolly && !reducedMotion) camera.startDolly(height * 2.2f + 8f)
    }

    /** Animates [outcome]; [finished] runs once the board shows [MoveOutcome.state]. */
    fun play(outcome: MoveOutcome, finished: () -> Unit) {
        val board = level?.board ?: return finished()
        completeTimeline()
        if (outcome.result.noOp) {
            bump(outcome.result.direction)
            finished()
            return
        }
        val tl = MoveTimeline(outcome.result, board)
        pending = outcome.state
        timeline = tl
        timelineTime = 0f
        schedule = tl.schedule()
        nextEvent = 0
        hardStops = tl.hardStops()
        nextShake = 0
        onFinished = finished
        setHint(null)
    }

    /** Jumps to [state] without animation (undo, restart, process-death restore). */
    fun snap(state: GameState) {
        timeline = null
        onFinished = null
        pending = null
        displayed = state
        sync(state)
    }

    /** Soft bump for a no-op swipe: everything nudges toward [direction] and settles back. */
    fun bump(direction: Direction) {
        bumpDirection = direction
        bumpTime = 0f
    }

    /** Shows the ghost hint arrow for [direction], or hides it for null. */
    fun setHint(direction: Direction?) {
        hintDirection = direction
        val h = hint ?: art.hintArrow().also {
            hint = it
            visuals += it
        }
        if (direction == null) hide(h) else show(h)
    }

    /** The space between HUD bars, in pixels; the board auto-frames to it with an 8% margin. */
    fun setFreeViewport(viewport: FreeViewport) {
        free = viewport
        reframe()
    }

    /** Win camera: follow the last saved hatchling into the surf, then tilt to the horizon. */
    fun celebrate() {
        if (reducedMotion) return
        val cell = lastSaveCell
        if (cell < 0) camera.follow(0f, worldZ(0f)) else camera.follow(worldX((cell % width).toFloat()), worldZ((cell / width).toFloat()))
    }

    // ----- Stage -----

    override fun enter(host: RenderEngine) {
        entered = true
        for (i in 0 until visuals.size) {
            val v = visuals[i]
            if (v.shown) for (e in v.renderables) scene.addEntity(e)
        }
        if (host.environment.theme != theme) host.environment.apply(theme)
    }

    override fun exit(host: RenderEngine) {
        for (i in 0 until visuals.size) {
            val v = visuals[i]
            if (v.shown) for (e in v.renderables) scene.remove(e)
        }
        entered = false
    }

    override fun onResize(host: RenderEngine, width: Int, height: Int) {
        free = free.copy(width = width, height = height)
        reframe()
    }

    override fun update(host: RenderEngine, dt: Float) {
        clock += dt
        val tl = timeline
        if (tl != null) {
            timelineTime += dt
            animate(tl, timelineTime)
            while (nextEvent < schedule.size && schedule[nextEvent].first <= timelineTime) {
                dispatch(schedule[nextEvent].second)
                nextEvent++
            }
            while (nextShake < hardStops.size && hardStops[nextShake].second <= timelineTime) {
                camera.shake()
                nextShake++
            }
            if (timelineTime >= tl.duration) completeTimeline()
        } else {
            idle(dt)
        }
        updateHint()
        camera.update(dt)
        applyCamera(host.camera)
    }

    override fun destroy(host: RenderEngine) {
        clear()
        hint?.let {
            hide(it)
            art.destroy(it)
        }
        hint = null
        visuals.clear()
    }

    // ----- Visibility and placement -----

    private fun show(visual: Visual) {
        if (visual.shown) return
        visual.shown = true
        if (entered) for (e in visual.renderables) scene.addEntity(e)
    }

    private fun hide(visual: Visual) {
        if (!visual.shown) return
        visual.shown = false
        if (entered) for (e in visual.renderables) scene.remove(e)
    }

    private fun worldX(cellX: Float) = cellX - (width - 1) / 2f

    private fun worldZ(cellY: Float) = cellY - (height - 1) / 2f

    private fun place(visual: Visual, cell: Int, y: Float = 0f, yaw: Float = 0f, scale: Float = 1f) {
        tm.setTransform(
            tm.getInstance(visual.root),
            Transforms.trs(scratch, worldX((cell % width).toFloat()), y, worldZ((cell / width).toFloat()), yaw, scale, scale, scale),
        )
    }

    private fun placeAt(visual: Visual, cellX: Float, cellY: Float, lift: Float, heading: Float, scale: Float, squash: Float) {
        tm.setTransform(
            tm.getInstance(visual.root),
            Transforms.trs(scratch, worldX(cellX), lift, worldZ(cellY), -heading, scale, scale * squash, scale),
        )
    }

    private fun track(visual: Visual): Visual {
        visuals += visual
        return visual
    }

    // ----- Building -----

    private fun buildStatic(level: Level, state: GameState) {
        val board = level.board
        for (cell in 0 until board.cellCount) {
            val tile = board.tile(cell)
            val variant = board.variant(cell, level.seed, 6)
            if (tile == TileType.OCEAN) {
                staticVisuals += track(art.waterTile(theme)).also { place(it, cell) }
                continue
            }
            val heightNoise = (board.variant(cell, level.seed xor 0x5A5A, 7) - 3) * 0.006f
            staticVisuals += track(art.sandTile(theme, wet = tile == TileType.WET_SAND)).also { place(it, cell, heightNoise) }
            when (tile) {
                TileType.ROCK -> staticVisuals += track(art.rock(variant)).also { place(it, cell, yaw = variant * 1.1f) }

                TileType.SHELL ->
                    staticVisuals +=
                        track(art.shell(variant % 3, cracked = false)).also { place(it, cell, yaw = variant * 0.9f) }

                TileType.CRACKED_SHELL -> shellByCell[cell] =
                    track(art.shell(variant % 3, cracked = true)).also { place(it, cell, yaw = variant * 0.9f) }

                TileType.CURRENT_UP, TileType.CURRENT_DOWN, TileType.CURRENT_LEFT, TileType.CURRENT_RIGHT ->
                    staticVisuals += track(art.currentArrow(tile)).also { place(it, cell) }

                TileType.PIT -> {
                    pitByCell[cell] = track(art.pit()).also { place(it, cell) }
                    pitCapByCell[cell] = track(art.sandTile(theme, wet = false)).also { place(it, cell, 0.002f) }
                }

                TileType.TIDE_POOL -> staticVisuals += track(art.tidePool(board.poolNumberOf(cell))).also { place(it, cell) }

                else -> Unit
            }
        }
        staticVisuals.forEach { show(it) }
        repeat(board.crabPaths.size) { crabs += track(art.crab()) }
        repeat(board.eggCells.size) { eggs += track(art.egg()) }
        repeat(board.shadowPaths.size) {
            shadows += track(art.gullShadow())
            shadowNext += track(art.gullShadowNext())
        }
        for (piece in state.pieces) pieceVisual(piece.id, piece.kind)
    }

    private fun pieceVisual(id: Int, kind: PieceKind): Visual {
        pieces[id]?.let { return it }
        val visual = track(if (kind == PieceKind.HATCHLING) art.hatchling(skin) else art.coconut())
        pieces[id] = visual
        pieceIsHatchling[id] = kind == PieceKind.HATCHLING
        return visual
    }

    /** Makes every visual match [state] exactly. */
    private fun sync(state: GameState) {
        val board = level?.board ?: return
        for ((cell, visual) in shellByCell) if (state.isShellBroken(board, cell)) hide(visual) else show(visual)
        for ((cell, visual) in pitByCell) {
            val filled = state.isPitFilled(board, cell)
            if (filled) hide(visual) else show(visual)
            pitCapByCell[cell]?.let { if (filled) show(it) else hide(it) }
        }
        val present = BooleanArray(pieces.size)
        for (piece in state.pieces) {
            present[piece.id] = true
            val visual = pieceVisual(piece.id, piece.kind)
            show(visual)
            placeAt(visual, (piece.cell % width).toFloat(), (piece.cell / width).toFloat(), 0f, 0f, 1f, 1f)
            animateFlippers(visual, 0f, 0f)
        }
        for (id in pieces.indices) if (!present[id]) pieces[id]?.let { hide(it) }
        state.crabPhases.forEachIndexed { k, phase ->
            place(crabs[k], board.crabCell(k, phase))
            show(crabs[k])
        }
        state.eggTimers.forEachIndexed { e, timer ->
            if (timer > 0) {
                place(eggs[e], board.eggCells[e])
                show(eggs[e])
            } else {
                hide(eggs[e])
            }
        }
        state.shadowPhases.forEachIndexed { g, phase ->
            place(shadows[g], board.shadowCell(g, phase))
            show(shadows[g])
            val path = board.shadowPaths[g]
            if (path.size > 1) {
                place(shadowNext[g], board.shadowCell(g, (phase + 1) % path.size))
                show(shadowNext[g])
            }
        }
    }

    private fun animateFlippers(visual: Visual, paddle: Float, amount: Float) {
        val f = visual.flippers
        if (f.size < 4) return
        val swing = sin(paddle * 2f * PI.toFloat()) * 0.6f * amount
        tm.setTransform(tm.getInstance(f[0]), Transforms.trsEuler(scratch, -0.3f, 0.1f, -0.16f, swing, 0f, 0f, 1f))
        tm.setTransform(tm.getInstance(f[1]), Transforms.trsEuler(scratch, 0.3f, 0.1f, -0.16f, -swing, 0f, 0f, 1f))
        tm.setTransform(tm.getInstance(f[2]), Transforms.trsEuler(scratch, -0.22f, 0.09f, 0.28f, -swing * 0.6f, 0f, 0f, 1f))
        tm.setTransform(tm.getInstance(f[3]), Transforms.trsEuler(scratch, 0.22f, 0.09f, 0.28f, swing * 0.6f, 0f, 0f, 1f))
    }

    // ----- Animation -----

    private fun animate(tl: MoveTimeline, time: Float) {
        val tracks = tl.result.tracks
        for (i in 0 until tracks.size) {
            val id = tracks[i].pieceId
            val visual = pieces.getOrNull(id) ?: continue
            tl.pose(id, time, pose)
            if (!pose.visible) {
                hide(visual)
                continue
            }
            val lift = pose.lift + if (pose.moving) 0.02f * sin(pose.paddle * 2f * PI.toFloat()) else 0f
            placeAt(visual, pose.x, pose.y, lift, pose.heading, pose.scale, pose.squash)
            if (pieceIsHatchling[id]) animateFlippers(visual, pose.paddle, if (pose.moving) 1f else 0f)
        }
        if (time < tl.hazardStart) return
        val hazard = tl.hazardProgress(time)
        val events = tl.result.events
        for (i in 0 until events.size) {
            when (val event = events[i]) {
                is MoveEvent.CrabStepped -> glide(crabs[event.crab], event.from, event.to, hazard)

                is MoveEvent.GullShadowMoved -> glide(shadows[event.shadow], event.from, event.to, hazard)

                is MoveEvent.Hatched -> {
                    val visual = pieces[event.pieceId] ?: pieceVisual(event.pieceId, PieceKind.HATCHLING)
                    show(visual)
                    hide(eggs[event.egg])
                    placeAt(visual, (event.cell % width).toFloat(), (event.cell / width).toFloat(), 0f, 0f, tl.hatchScale(time), 1f)
                }

                is MoveEvent.EggWobble -> {
                    val wobble = sin(hazard * 6f * PI.toFloat()) * 0.25f * (1f - hazard)
                    tm.setTransform(
                        tm.getInstance(eggs[event.egg].root),
                        Transforms.trsEuler(
                            scratch,
                            worldX((event.cell % width).toFloat()),
                            0f,
                            worldZ((event.cell / width).toFloat()),
                            0f,
                            0f,
                            wobble,
                            1f,
                        ),
                    )
                }

                else -> Unit
            }
        }
    }

    private fun glide(visual: Visual, from: Int, to: Int, t: Float) {
        val fx = (from % width).toFloat()
        val fy = (from / width).toFloat()
        val x = fx + ((to % width) - fx) * t
        val y = fy + ((to / width) - fy) * t
        tm.setTransform(tm.getInstance(visual.root), Transforms.trs(scratch, worldX(x), 0f, worldZ(y), 0f, 1f, 1f, 1f))
    }

    private fun dispatch(event: MoveEvent) {
        when (event) {
            is MoveEvent.ShellCracked -> shellByCell[event.cell]?.let { hide(it) }

            is MoveEvent.PitFilled -> {
                pitByCell[event.cell]?.let { hide(it) }
                pitCapByCell[event.cell]?.let { show(it) }
            }

            is MoveEvent.Saved -> lastSaveCell = event.cell

            else -> Unit
        }
        val cell = when (event) {
            is MoveEvent.Stopped -> event.cell
            is MoveEvent.Saved -> event.cell
            is MoveEvent.CoconutSank -> event.cell
            is MoveEvent.Redirected -> event.cell
            is MoveEvent.Teleported -> event.toCell
            is MoveEvent.FellInPit -> event.cell
            is MoveEvent.PitFilled -> event.cell
            is MoveEvent.ShellCracked -> event.cell
            is MoveEvent.Hatched -> event.cell
            is MoveEvent.EggWobble -> event.cell
            is MoveEvent.CrabStepped -> event.to
            is MoveEvent.CrabWaited -> event.cell
            is MoveEvent.GullShadowMoved -> event.to
        }
        listener?.onEvent(event, worldX((cell % width).toFloat()), worldZ((cell / width).toFloat()))
    }

    private fun completeTimeline() {
        timeline ?: return
        while (nextEvent < schedule.size) dispatch(schedule[nextEvent++].second)
        timeline = null
        val state = pending
        pending = null
        if (state != null) {
            displayed = state
            sync(state)
        }
        val done = onFinished
        onFinished = null
        done?.invoke()
    }

    private fun idle(dt: Float) {
        val state = displayed ?: return
        val board = level?.board ?: return
        bumpTime += dt
        val bump = bumpDirection
        val nudge = if (bump != null && bumpTime < BUMP_SECONDS) {
            sin(bumpTime / BUMP_SECONDS * PI.toFloat()) * 0.12f
        } else {
            bumpDirection = null
            0f
        }
        val list = state.pieces
        for (i in 0 until list.size) {
            val piece = list[i]
            val visual = pieces.getOrNull(piece.id) ?: continue
            val wiggle = if (reducedMotion) 0f else sin(clock * 1.3f + piece.id * 1.7f) * 0.04f
            val x = (piece.cell % width) + (bump?.dx ?: 0) * nudge
            val y = (piece.cell / width) + (bump?.dy ?: 0) * nudge
            placeAt(visual, x, y, 0f, wiggle, 1f, 1f)
        }
        for (k in 0 until crabs.size) {
            val cell = board.crabCell(k, state.crabPhases[k])
            val snip = if (reducedMotion) 0f else sin(clock * 3f + k) * 0.15f
            tm.setTransform(
                tm.getInstance(crabs[k].root),
                Transforms.trs(scratch, worldX((cell % width).toFloat()), 0f, worldZ((cell / width).toFloat()), snip, 1f, 1f, 1f),
            )
        }
    }

    private fun updateHint() {
        val h = hint ?: return
        val direction = hintDirection ?: return
        val pulse = 1f + 0.06f * sin(clock * 5f)
        val yaw = when (direction) {
            Direction.UP -> 0f
            Direction.LEFT -> (PI / 2).toFloat()
            Direction.DOWN -> PI.toFloat()
            Direction.RIGHT -> (-PI / 2).toFloat()
        }
        val offset = 0.3f * easeOutBack((clock % 1.2f) / 1.2f)
        tm.setTransform(
            tm.getInstance(h.root),
            Transforms.trs(scratch, direction.dx * offset, 0.6f, direction.dy * offset, yaw, pulse, 1f, pulse),
        )
    }

    // ----- Camera -----

    private fun reframe() {
        if (level == null || free.width <= 1) return
        camera.setRest(rig.frame(width / 2f + 0.3f, height / 2f + 0.3f, 0.6f, free))
    }

    private fun applyCamera(cam: Camera) {
        val aspect = host.width.toDouble() / host.height.coerceAtLeast(1)
        cam.setProjection(rig.fovDegrees.toDouble(), aspect, 0.1, 300.0, Camera.Fov.VERTICAL)
        cam.setShift(camera.shiftX.toDouble(), camera.shiftY.toDouble())
        cam.lookAt(
            camera.eye.x.toDouble(),
            camera.eye.y.toDouble(),
            camera.eye.z.toDouble(),
            camera.target.x.toDouble(),
            camera.target.y.toDouble(),
            camera.target.z.toDouble(),
            0.0,
            1.0,
            0.0,
        )
    }

    private fun clear() {
        timeline = null
        onFinished = null
        for (v in visuals) {
            if (v === hint) continue
            hide(v)
            art.destroy(v)
        }
        visuals.retainAll { it === hint }
        staticVisuals.clear()
        shellByCell.clear()
        pitByCell.clear()
        pitCapByCell.clear()
        pieces = arrayOfNulls(0)
        crabs.clear()
        eggs.clear()
        shadows.clear()
        shadowNext.clear()
        level = null
        displayed = null
        lastSaveCell = -1
    }

    companion object {
        const val BUMP_SECONDS = 0.18f
    }
}
