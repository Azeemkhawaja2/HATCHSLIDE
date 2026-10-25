package com.hatchslide.core.render.board

import com.google.android.filament.Camera
import com.hatchslide.core.game.Direction
import com.hatchslide.core.game.GameState
import com.hatchslide.core.game.GameStatus
import com.hatchslide.core.game.Level
import com.hatchslide.core.game.MoveEvent
import com.hatchslide.core.game.MoveOutcome
import com.hatchslide.core.game.PieceKind
import com.hatchslide.core.game.TileType
import com.hatchslide.core.render.Ocean
import com.hatchslide.core.render.PerfLog
import com.hatchslide.core.render.QualityTier
import com.hatchslide.core.render.RenderEngine
import com.hatchslide.core.render.Stage
import com.hatchslide.core.render.Surfaces
import com.hatchslide.core.render.WorldTheme
import com.hatchslide.core.render.assets.AssetCatalog.Models
import com.hatchslide.core.render.camera.CameraAnimator
import com.hatchslide.core.render.camera.CameraRig
import com.hatchslide.core.render.camera.FreeViewport
import com.hatchslide.core.render.math.Transforms
import com.hatchslide.core.render.math.easeOutBack
import com.hatchslide.core.render.math.lerp
import com.hatchslide.core.render.math.smoothstep
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/** Receives move events at the moment they are shown on screen (for audio, haptics, particles). */
fun interface BoardEventListener {
    /** [worldX] and [worldZ] are the event's position in board units (x is used for stereo panning). */
    fun onEvent(event: MoveEvent, worldX: Float, worldZ: Float)
}

/** Moments of board animation that have a sound but no game event (intro, slides, the lose sequence). */
enum class BoardCue { SLIDE_START, SLIDE_END, EGG_CRACK, HATCH, SWIM, GULL_SWOOP, TUCK, GULL_LAND }

/** Receives [BoardCue]s; [value] carries the slide length in ticks for [BoardCue.SLIDE_START]. */
fun interface BoardCueListener {
    fun onCue(cue: BoardCue, worldX: Float, value: Float)
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
    private val ocean = Ocean(host)

    var level: Level? = null
        private set
    private var width = 1
    private var height = 1
    private var theme = WorldTheme.TITLE
    private var displayed: GameState? = null
    private var pending: GameState? = null
    private var skin = 0

    private val visuals = ArrayList<Visual>()
    private val shellByCell = HashMap<Int, Visual>()
    private val pitByCell = HashMap<Int, Visual>()
    private val pitFillByCell = HashMap<Int, Visual>()
    private var pieces = arrayOfNulls<Visual>(0)
    private var pieceIsHatchling = BooleanArray(0)
    private var bodyHeading = FloatArray(0)
    private var lean = FloatArray(0)
    private var lastX = FloatArray(0)
    private var lastY = FloatArray(0)
    private val crabs = ArrayList<Visual>()
    private val eggs = ArrayList<Visual>()
    private val shadows = ArrayList<Visual>()
    private val shadowNext = ArrayList<Visual>()
    private val gulls = ArrayList<Visual>()
    private var shadowX = FloatArray(0)
    private var shadowZ = FloatArray(0)
    private var hint: Visual? = null
    private var hintDirection: Direction? = null

    /** Colour-blind symbols on tide pools and wet sand, shown only while [colorBlind] is on. */
    private val symbols = ArrayList<Visual>()

    private val scenery = Scenery(host)
    private val effects = BoardEffects(host.particles.pool)

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
    private var frameDt = 0f
    private var lastSaveCell = -1

    /** Saved hatchlings swimming out to sea after their dive. */
    private class Swimmer(val visual: Visual, var x: Float, var z: Float, val heading: Float, var age: Float = 0f)

    private val swimmers = ArrayList<Swimmer>()
    private var flareX = 0f
    private var flareZ = 0f
    private var flareAge = 99f

    /** The seagull that circles at three moves left and, on a loss, swoops and lands on a rock. */
    private var alarmGull: Visual? = null
    private var loseTime = -1f
    private var duck = 0f
    private val swoopFrom = FloatArray(3)
    private val perch = FloatArray(3)

    /** Level-start intro: hatchlings break out of eggs 80 ms apart (spec section 6). */
    private val introEggs = ArrayList<Visual>()
    private var introPieces = IntArray(0)
    private var introTime = -1f

    private val rig = CameraRig()
    val camera = CameraAnimator()
    private var free = FreeViewport(1, 1)
    private var entered = false

    var listener: BoardEventListener? = null

    /** When the last swipe arrived (System.nanoTime), for the debug swipe-to-motion log; 0 when logged. */
    var inputNanos = 0L
    var cueListener: BoardCueListener? = null
    private var slideEnded = true
    private var introCracked = BooleanArray(0)
    private var loseCues = 0

    var reducedMotion: Boolean = false
        set(value) {
            field = value
            camera.reducedMotion = value
            host.surfaces.setWaveAmplitude(if (value) 0.25f else 1f)
        }

    override val isAnimating: Boolean get() = true

    /** Colour-blind tiles (spec section 5): symbols on tide-pool pairs and wet sand. */
    var colorBlind: Boolean = false
        set(value) {
            field = value
            for (i in 0 until symbols.size) if (value) show(symbols[i]) else hide(symbols[i])
        }

    /** Quality tier for model detail (Low uses LOD1 everywhere); applies from the next level load. */
    var tier: QualityTier
        get() = art.tier
        set(value) {
            art.tier = value
        }

    // ----- Public API -----

    /** Builds a level's island geometry; pure computation, safe on a background thread before [load]. */
    fun prepare(level: Level): IslandGeometry = IslandBuilder.build(level.board, level.seed)

    /** Builds [level] with this look; [island] from [prepare] skips rebuilding the geometry here. */
    fun load(level: Level, state: GameState, theme: WorldTheme, skin: Int, dolly: Boolean = true, island: IslandGeometry? = null) {
        val started = System.nanoTime()
        val worldChange = this.theme !== theme
        clear()
        this.level = level
        width = level.board.width
        height = level.board.height
        this.theme = theme
        this.skin = skin
        val slots = level.board.maxPieces.coerceAtLeast(1)
        pieces = arrayOfNulls(slots)
        pieceIsHatchling = BooleanArray(slots)
        bodyHeading = FloatArray(slots)
        lean = FloatArray(slots)
        lastX = FloatArray(slots)
        lastY = FloatArray(slots)
        host.environment.apply(theme)
        buildScenery(level, island ?: prepare(level))
        effects.begin(theme, level.board, tileHeights, slots)
        buildStatic(level, state)
        displayed = state
        sync(state)
        reframe()
        if (dolly && !reducedMotion) {
            camera.startDolly(height * 2.2f + 8f)
            startIntro(state)
        }
        host.textures.dropPrefetched()
        PerfLog.since(if (worldChange) "world switch load" else "level load", started)
    }

    /** True if [level] is already built with this look, so re-entering can just [snap]. */
    fun isShowing(level: Level, theme: WorldTheme, skin: Int): Boolean =
        this.level?.id == level.id && this.theme === theme && this.skin == skin

    /** Animates [outcome]; [finished] runs once the board shows [MoveOutcome.state]. */
    fun play(outcome: MoveOutcome, finished: () -> Unit) {
        val board = level?.board ?: return finished()
        finishIntro()
        completeTimeline()
        if (outcome.result.noOp) {
            inputNanos = 0L
            bump(outcome.result.direction)
            finished()
            return
        }
        val tl = MoveTimeline(outcome.result, board, diveDepth = Surfaces.WATER_LEVEL - 0.06f)
        pending = outcome.state
        timeline = tl
        timelineTime = 0f
        schedule = tl.schedule()
        nextEvent = 0
        hardStops = tl.hardStops()
        nextShake = 0
        onFinished = finished
        slideEnded = false
        cueListener?.onCue(BoardCue.SLIDE_START, 0f, outcome.result.ticks.toFloat())
        setHint(null)
    }

    /** Jumps to [state] without animation (undo, restart, process-death restore). */
    fun snap(state: GameState) {
        finishIntro()
        effects.resetTracks()
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

    /** Shell confetti over the board when the third star lands. */
    fun confetti() = effects.confetti()

    // ----- Stage -----

    override fun enter(host: RenderEngine) {
        entered = true
        for (i in 0 until visuals.size) {
            val v = visuals[i]
            if (v.shown) for (e in v.renderables) scene.addEntity(e)
        }
        ocean.show()
        scenery.attach()
        host.environment.apply(theme)
    }

    override fun exit(host: RenderEngine) {
        for (i in 0 until visuals.size) {
            val v = visuals[i]
            if (v.shown) for (e in v.renderables) scene.remove(e)
        }
        ocean.hide()
        scenery.detach()
        entered = false
    }

    override fun onResize(host: RenderEngine, width: Int, height: Int) {
        free = free.copy(width = width, height = height)
        reframe()
    }

    override fun update(host: RenderEngine, dt: Float) {
        clock += dt
        frameDt = dt
        val tl = timeline
        if (tl != null) {
            if (inputNanos != 0L) {
                PerfLog.since("swipe to first movement", inputNanos)
                inputNanos = 0L
            }
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
            if (!slideEnded && timelineTime >= tl.slideEnd) {
                slideEnded = true
                cueListener?.onCue(BoardCue.SLIDE_END, 0f, 0f)
            }
            if (timelineTime >= tl.duration) completeTimeline()
        } else {
            idle(dt)
        }
        updateIntro(dt)
        updateSwimmers(dt)
        updateHazardIdle()
        updateAlarm(dt)
        updateHint()
        effects.update()
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
        ocean.hide()
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

    private fun placePiece(id: Int, visual: Visual, cellX: Float, cellY: Float, lift: Float, scale: Float, squash: Float) {
        tm.setTransform(
            tm.getInstance(visual.root),
            Transforms.trsLean(scratch, worldX(cellX), lift, worldZ(cellY), -bodyHeading[id], lean[id], scale, scale * squash, scale),
        )
    }

    private fun track(visual: Visual): Visual {
        visuals += visual
        return visual
    }

    // ----- Building -----

    private fun buildScenery(level: Level, island: IslandGeometry) {
        scenery.build(island)
        if (entered) scenery.attach()
        placeDecor(level, island)
        tileHeights = island.tileHeights
    }

    private var tileHeights = FloatArray(0)

    private fun placeDecor(level: Level, island: IslandGeometry) {
        val (tall, small) = decorSets(theme.id)
        var placed = 0
        for ((i, spot) in island.decor.withIndex()) {
            if (placed >= MAX_DECOR) break
            val roll = ((level.seed ushr (i % 40)) xor (i * 2654435761L)).let { ((it xor (it ushr 17)) and 0xFFFF) / 65535f }
            if (roll > 0.42f) continue
            val allowTall = spot.side != DecorSpot.Side.NEAR
            val pool = if (allowTall && roll < 0.2f) tall else small
            val name = pool[((roll * 997).toInt() + i) % pool.size]
            if (name in Models.CLIFFS && spot.ring < 1.4f) continue
            val background = spot.ring > 1.3f
            val visual = track(art.decor(name, background))
            val scale = when {
                name in Models.CLIFFS -> 0.55f + 0.2f * roll
                name in Models.PALMS -> 0.7f + 0.3f * roll
                else -> 0.85f + 0.3f * roll
            }
            tm.setTransform(
                tm.getInstance(visual.root),
                Transforms.trs(scratch, spot.x, Surfaces.ISLAND_TOP - 0.01f, spot.z, roll * 40f, scale, scale, scale),
            )
            show(visual)
            placed++
        }
    }

    /** Tall and small island decoration per world (spec section 6, other models). */
    private fun decorSets(world: Int): Pair<List<String>, List<String>> = when (world) {
        2 -> (Models.PALMS + Models.UMBRELLAS[1] + Models.CORALS[0] + Models.CORALS[2]) to
            listOf(Models.CORALS[1], Models.STARFISH, Models.PEBBLES, Models.SHELL_SCALLOP)

        3 -> (Models.PALMS + Models.LANTERN + Models.LANTERN) to (Models.DRIFTWOOD + Models.PEBBLES + Models.STARFISH)

        4 -> (Models.CLIFFS + Models.PALMS[0]) to (Models.DRIFTWOOD + Models.PEBBLES + Models.BUCKET + Models.CORALS[1])

        5 -> Models.CLIFFS to listOf(Models.PEBBLES, Models.DRIFTWOOD[1], Models.ROCKS[2])

        else -> (Models.PALMS + Models.UMBRELLAS[0] + Models.SANDCASTLE) to
            (Models.DRIFTWOOD + Models.STARFISH + Models.PEBBLES + Models.BUCKET + Models.SHELL_CONCH)
    }

    private fun buildStatic(level: Level, state: GameState) {
        val board = level.board
        for (cell in 0 until board.cellCount) {
            val tile = board.tile(cell)
            if (tile == TileType.OCEAN) continue
            val variant = board.variant(cell, level.seed, 6)
            val y = tileHeights[cell]
            when (tile) {
                TileType.ROCK -> show(track(art.rock(variant)).also { place(it, cell, y, yaw = variant * 1.1f) })

                TileType.SHELL -> show(track(art.shell(variant % 3, cracked = false)).also { place(it, cell, y, yaw = variant * 0.9f) })

                TileType.CRACKED_SHELL -> shellByCell[cell] =
                    track(art.shell(variant % 3, cracked = true)).also { place(it, cell, y, yaw = variant * 0.9f) }

                TileType.CURRENT_UP, TileType.CURRENT_DOWN, TileType.CURRENT_LEFT, TileType.CURRENT_RIGHT ->
                    show(track(art.current(tile)).also { place(it, cell, y + 0.012f, yaw = currentYaw(tile)) })

                TileType.PIT -> {
                    val (open, filled) = art.pit()
                    pitByCell[cell] = track(open).also { place(it, cell, y) }
                    pitFillByCell[cell] = track(filled)
                }

                TileType.TIDE_POOL -> {
                    val pair = board.poolNumberOf(cell)
                    show(track(art.tidePool(pair)).also { place(it, cell, y) })
                    addSymbol(TileSymbol.forPool(pair), cell, 0f, 0f, y + 0.03f, 0.2f)
                }

                TileType.WET_SAND -> addSymbol(TileSymbol.DROPLET, cell, 0.3f, 0.3f, y + 0.015f, 0.1f)

                else -> Unit
            }
        }
        repeat(board.crabPaths.size) { crabs += track(art.crab()) }
        repeat(board.eggCells.size) { eggs += track(art.egg()) }
        shadowX = FloatArray(board.shadowPaths.size)
        shadowZ = FloatArray(board.shadowPaths.size)
        repeat(board.shadowPaths.size) {
            shadows += track(art.gullShadow(next = false))
            shadowNext += track(art.gullShadow(next = true))
            gulls += track(art.seagull()).also { show(it) }
        }
        for (piece in state.pieces) pieceVisual(piece.id, piece.kind)
    }

    private fun addSymbol(shape: TileSymbol, cell: Int, dx: Float, dz: Float, y: Float, size: Float) {
        val visual = track(art.symbol(shape))
        tm.setTransform(
            tm.getInstance(visual.root),
            Transforms.trs(scratch, worldX((cell % width).toFloat()) + dx, y, worldZ((cell / width).toFloat()) + dz, 0f, size, 1f, size),
        )
        symbols += visual
        if (colorBlind) show(visual)
    }

    private fun currentYaw(tile: TileType): Float = when (tile) {
        TileType.CURRENT_UP -> 0f
        TileType.CURRENT_LEFT -> (PI / 2).toFloat()
        TileType.CURRENT_DOWN -> PI.toFloat()
        else -> (-PI / 2).toFloat()
    }

    private fun pieceVisual(id: Int, kind: PieceKind): Visual {
        pieces[id]?.let { return it }
        val visual = track(if (kind == PieceKind.HATCHLING) art.hatchling(skin) else art.coconut())
        pieces[id] = visual
        pieceIsHatchling[id] = kind == PieceKind.HATCHLING
        bodyHeading[id] = 0f
        lean[id] = 0f
        return visual
    }

    /** Makes every visual match [state] exactly. */
    private fun sync(state: GameState) {
        val board = level?.board ?: return
        for ((cell, visual) in shellByCell) if (state.isShellBroken(board, cell)) hide(visual) else show(visual)
        for ((cell, visual) in pitByCell) {
            val filled = state.isPitFilled(board, cell)
            if (filled) hide(visual) else show(visual)
            pitFillByCell[cell]?.let { if (filled) show(it) else hide(it) }
        }
        val present = BooleanArray(pieces.size)
        for (piece in state.pieces) {
            present[piece.id] = true
            val visual = pieceVisual(piece.id, piece.kind)
            show(visual)
            bodyHeading[piece.id] = 0f
            lean[piece.id] = 0f
            placePiece(piece.id, visual, (piece.cell % width).toFloat(), (piece.cell / width).toFloat(), 0f, 1f, 1f)
        }
        for (id in pieces.indices) if (!present[id]) pieces[id]?.let { hide(it) }
        state.crabPhases.forEachIndexed { k, phase ->
            place(crabs[k], board.crabCell(k, phase))
            show(crabs[k])
        }
        state.eggTimers.forEachIndexed { e, timer ->
            if (timer > 0) {
                place(eggs[e], board.eggCells[e], tileHeights[board.eggCells[e]])
                eggs[e].egg?.animate(0f)
                show(eggs[e])
            } else {
                hide(eggs[e])
            }
        }
        state.shadowPhases.forEachIndexed { g, phase ->
            val path = board.shadowPaths[g]
            val cell = board.shadowCell(g, phase)
            val next = board.shadowCell(g, (phase + 1) % path.size)
            placeShadow(g, cell, next)
            show(shadows[g])
            if (path.size > 1) {
                place(shadowNext[g], next, yaw = shadowYaw(next, board.shadowCell(g, (phase + 2) % path.size)))
                show(shadowNext[g])
            }
        }
    }

    private fun shadowYaw(from: Int, to: Int): Float {
        val dx = (to % width - from % width).toFloat()
        val dz = (to / width - from / width).toFloat()
        return if (dx == 0f && dz == 0f) 0f else atan2(-dx, -dz)
    }

    private fun placeShadow(g: Int, cell: Int, next: Int) {
        shadowX[g] = worldX((cell % width).toFloat())
        shadowZ[g] = worldZ((cell / width).toFloat())
        place(shadows[g], cell, yaw = shadowYaw(cell, next))
    }

    // ----- Animation -----

    /** Turns the body toward [target] (radians), returning the head's lead angle. */
    private fun steer(id: Int, target: Float, dt: Float): Float {
        var delta = target - bodyHeading[id]
        while (delta > PI) delta -= (2 * PI).toFloat()
        while (delta < -PI) delta += (2 * PI).toFloat()
        val step = (1f - exp(-TURN_RATE * dt)) * delta
        bodyHeading[id] += step
        return -(delta - step).coerceIn(-0.9f, 0.9f)
    }

    private fun animate(tl: MoveTimeline, time: Float) {
        val dt = frameDt
        val tracks = tl.result.tracks
        for (i in 0 until tracks.size) {
            val id = tracks[i].pieceId
            val visual = pieces.getOrNull(id) ?: continue
            tl.pose(id, time, pose)
            if (!pose.visible) {
                effects.piece(id, 0f, 0f, 0f, 0f, sliding = false, hatchling = false)
                if (pieceIsHatchling[id] && lastSaved(tl, id)) startSwimmer(id, visual) else hide(visual)
                continue
            }
            lastX[id] = pose.x
            lastY[id] = pose.y
            val headYaw = steer(id, pose.heading, dt)
            val targetLean = if (pose.moving) -0.14f else 0f
            lean[id] += (targetLean - lean[id]) * (1f - exp(-12f * dt))
            val lift = pose.lift + if (pose.moving) 0.02f * sin(pose.paddle * 2f * PI.toFloat()) else 0f
            placePiece(id, visual, pose.x, pose.y, lift, pose.scale, pose.squash)
            // Trails and footprints only while sliding on the surface (not diving or teleporting).
            val sliding = pose.moving && pose.lift > -0.05f && pose.scale > 0.9f
            effects.piece(id, pose.x, pose.y, worldX(pose.x), worldZ(pose.y), sliding, pieceIsHatchling[id])
            visual.hatchling?.animate(dt, pose.paddle, if (pose.moving) 1f else 0f, headYaw, clock, reducedMotion)
        }
        if (time < tl.hazardStart) return
        val hazard = tl.hazardProgress(time)
        val events = tl.result.events
        for (i in 0 until events.size) {
            when (val event = events[i]) {
                is MoveEvent.CrabStepped -> {
                    glide(crabs[event.crab], event.from, event.to, hazard)
                    crabs[event.crab].crab?.animate(sin(hazard * PI.toFloat()), 0.5f + 0.5f * sin(hazard * 6f * PI.toFloat()))
                }

                is MoveEvent.GullShadowMoved -> {
                    glide(shadows[event.shadow], event.from, event.to, hazard, shadowYaw(event.from, event.to))
                    val fx = worldX((event.from % width).toFloat())
                    val fz = worldZ((event.from / width).toFloat())
                    shadowX[event.shadow] = fx + (worldX((event.to % width).toFloat()) - fx) * hazard
                    shadowZ[event.shadow] = fz + (worldZ((event.to / width).toFloat()) - fz) * hazard
                }

                is MoveEvent.Hatched -> {
                    val visual = pieces[event.pieceId] ?: pieceVisual(event.pieceId, PieceKind.HATCHLING)
                    show(visual)
                    eggs[event.egg].egg?.animate(hazard)
                    placePiece(
                        event.pieceId,
                        visual,
                        (event.cell % width).toFloat(),
                        (event.cell / width).toFloat(),
                        0f,
                        tl.hatchScale(time),
                        1f,
                    )
                }

                is MoveEvent.EggWobble -> {
                    val wobble = sin(hazard * 6f * PI.toFloat()) * 0.25f * (1f - hazard)
                    tm.setTransform(
                        tm.getInstance(eggs[event.egg].root),
                        Transforms.trsEuler(
                            scratch,
                            worldX((event.cell % width).toFloat()),
                            tileHeights[event.cell],
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

    private fun lastSaved(tl: MoveTimeline, id: Int): Boolean {
        val events = tl.result.events
        for (i in 0 until events.size) {
            val e = events[i]
            if (e is MoveEvent.Saved && e.pieceId == id) return true
        }
        return false
    }

    private fun glide(visual: Visual, from: Int, to: Int, t: Float, yaw: Float = 0f) {
        val fx = (from % width).toFloat()
        val fy = (from / width).toFloat()
        val x = fx + ((to % width) - fx) * t
        val y = fy + ((to / width) - fy) * t
        tm.setTransform(tm.getInstance(visual.root), Transforms.trs(scratch, worldX(x), 0f, worldZ(y), yaw, 1f, 1f, 1f))
    }

    /** Hands a saved hatchling over to the swimmers: it surfaces and paddles out to sea. */
    private fun startSwimmer(id: Int, visual: Visual) {
        pieces[id] = null
        swimmers += Swimmer(visual, worldX(lastX[id]), worldZ(lastY[id]), bodyHeading[id])
        flareX = worldX(lastX[id])
        flareZ = worldZ(lastY[id])
        flareAge = 0f
        cueListener?.onCue(BoardCue.SWIM, flareX, 0f)
    }

    private fun updateSwimmers(dt: Float) {
        var i = swimmers.size - 1
        while (i >= 0) {
            val s = swimmers[i--]
            s.age += dt
            // Out toward the open sea (-z), turning gently from the dive heading.
            val heading = s.heading * exp(-s.age * 1.5f)
            s.x += sin(heading) * SWIM_SPEED * dt
            s.z -= cos(heading) * SWIM_SPEED * dt
            val fade = 1f - smoothstep((s.age - SWIM_SECONDS + 0.8f) / 0.8f)
            val bob = 0.02f * sin(s.age * 5f)
            tm.setTransform(
                tm.getInstance(s.visual.root),
                Transforms.trsLean(scratch, s.x, Surfaces.WATER_LEVEL - 0.06f + bob, s.z, -heading, 0.1f, fade, fade, fade),
            )
            s.visual.hatchling?.animate(dt, (s.age * MoveTimeline.PADDLE_HZ) % 1f, 1f, 0f, clock, reducedMotion)
            if (s.age >= SWIM_SECONDS) {
                hide(s.visual)
                visuals.remove(s.visual)
                art.destroy(s.visual)
                swimmers.removeAt(i + 1)
            }
        }
        if (theme.glow > 0f) {
            flareAge += dt
            val strength = if (flareAge < 1.6f) 3f * (1f - flareAge / 1.6f) else 0f
            host.surfaces.setFlare(flareX, flareZ, strength, 0.9f)
        }
    }

    private fun dispatch(event: MoveEvent) {
        when (event) {
            is MoveEvent.ShellCracked -> shellByCell[event.cell]?.let { hide(it) }

            is MoveEvent.PitFilled -> {
                pitByCell[event.cell]?.let { hide(it) }
                pitFillByCell[event.cell]?.let { show(it) }
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
        val x = worldX((cell % width).toFloat())
        val z = worldZ((cell / width).toFloat())
        when (event) {
            // Pieces blocked from the first tick also report a stop; only real slides raise dust.
            is MoveEvent.Stopped -> if (timeline?.moved(event.pieceId) == true) effects.stop(x, z, cell)

            is MoveEvent.Redirected -> effects.stop(x, z, cell)

            is MoveEvent.Saved -> effects.save(x, z)

            is MoveEvent.CoconutSank -> effects.sink(x, z)

            is MoveEvent.FellInPit, is MoveEvent.PitFilled -> effects.pit(x, z, cell)

            is MoveEvent.ShellCracked -> effects.bits(x, z, cell, egg = false)

            is MoveEvent.Hatched -> effects.bits(x, z, cell, egg = true)

            is MoveEvent.Teleported ->
                effects.teleport(worldX((event.fromCell % width).toFloat()), worldZ((event.fromCell / width).toFloat()), x, z)

            else -> Unit
        }
        listener?.onEvent(event, x, z)
    }

    private fun completeTimeline() {
        val tl = timeline ?: return
        if (!slideEnded) {
            slideEnded = true
            cueListener?.onCue(BoardCue.SLIDE_END, 0f, 0f)
        }
        while (nextEvent < schedule.size) dispatch(schedule[nextEvent++].second)
        // Saved hatchlings still mid-dive go straight to swimming.
        for (track in tl.result.tracks) {
            val id = track.pieceId
            val visual = pieces.getOrNull(id) ?: continue
            if (pieceIsHatchling[id] && lastSaved(tl, id)) startSwimmer(id, visual)
        }
        timeline = null
        effects.resetTracks()
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
            if (introTime >= 0f && piece.id in introPieces) continue
            val wiggle = if (reducedMotion) 0f else sin(clock * 1.3f + piece.id * 1.7f) * 0.04f
            val headYaw = steer(piece.id, wiggle, dt)
            lean[piece.id] *= exp(-10f * dt)
            val x = (piece.cell % width) + (bump?.dx ?: 0) * nudge
            val y = (piece.cell / width) + (bump?.dy ?: 0) * nudge
            placePiece(piece.id, visual, x, y, 0f, 1f, 1f)
            visual.hatchling?.duck = duck
            visual.hatchling?.animate(dt, 0f, 0f, headYaw, clock + piece.id, reducedMotion)
        }
    }

    /** Crabs fidget and gulls circle over their shadows, whether or not a move is playing. */
    private fun updateHazardIdle() {
        val state = displayed ?: return
        val board = level?.board ?: return
        if (timeline == null) {
            for (k in 0 until crabs.size) {
                val cell = board.crabCell(k, state.crabPhases[k])
                val snip = if (reducedMotion) 0f else sin(clock * 3f + k) * 0.15f
                tm.setTransform(
                    tm.getInstance(crabs[k].root),
                    Transforms.trs(
                        scratch,
                        worldX((cell % width).toFloat()),
                        tileHeights[cell],
                        worldZ((cell / width).toFloat()),
                        snip,
                        1f,
                        1f,
                        1f,
                    ),
                )
                crabs[k].crab?.animate(0.5f + 0.5f * sin(clock * 2.3f + k), 0.5f + 0.5f * sin(clock * 5.1f + k * 2f))
            }
        }
        for (g in 0 until gulls.size) {
            val angle = clock * 0.55f + g * 2.1f
            val radius = 0.7f
            val x = shadowX[g] + cos(angle) * radius
            val z = shadowZ[g] + sin(angle) * radius
            // Flying counter-clockwise seen from above: the tangent heads along (-sin, cos).
            val yaw = atan2(sin(angle), -cos(angle))
            tm.setTransform(tm.getInstance(gulls[g].root), Transforms.trsEuler(scratch, x, GULL_HEIGHT, z, yaw, 0f, -0.25f, 1f))
            gulls[g].gull?.animate(clock + g * 1.3f)
        }
    }

    // ----- Level intro -----

    // ----- Seagull alarm and the gentle lose animation (spec section 2) -----

    private fun updateAlarm(dt: Float) {
        val state = displayed ?: return
        val lvl = level ?: return
        val lost = state.status.isLost && timeline == null
        val circling = !lost && state.status == GameStatus.PLAYING && lvl.gullMoves - state.movesUsed <= 3
        if (!lost) {
            loseTime = -1f
            duck = (duck - dt * 3f).coerceAtLeast(0f)
        }
        if (!lost && !circling) {
            alarmGull?.let { hide(it) }
            return
        }
        val gull = alarmGull ?: track(art.seagull()).also { alarmGull = it }
        val wasShown = gull.shown
        show(gull)
        if (circling) {
            val angle = clock * 0.5f
            val x = cos(angle) * ALARM_RADIUS
            val z = sin(angle) * ALARM_RADIUS * 0.7f
            tm.setTransform(
                tm.getInstance(gull.root),
                Transforms.trsEuler(scratch, x, ALARM_HEIGHT, z, atan2(sin(angle), -cos(angle) * 0.7f), 0f, -0.3f, 1f),
            )
            gull.gull?.animate(clock)
            return
        }
        if (loseTime < 0f) startLose(gull, wasShown)
        loseTime += dt
        val t = if (reducedMotion) LOSE_SECONDS else loseTime
        duck = smoothstep((t - 0.35f) / 0.3f)
        val x: Float
        val y: Float
        val z: Float
        var heading: Float
        if (t < 1f) {
            // Swoop: down from the sky to just above the board, then up toward the perch.
            val s = smoothstep(t)
            x = lerp(swoopFrom[0], 0f, s)
            z = lerp(swoopFrom[2], 0.4f, s)
            y = lerp(swoopFrom[1], 0.9f, s * (2f - s))
            heading = atan2(-(0f - swoopFrom[0]), -(0.4f - swoopFrom[2]))
        } else {
            val s = smoothstep((t - 1f) / 0.7f)
            x = lerp(0f, perch[0], s)
            z = lerp(0.4f, perch[2], s)
            y = lerp(0.9f, perch[1], s) + 0.35f * sin(s * PI.toFloat())
            heading = atan2(-(perch[0] - 0f), -(perch[2] - 0.4f))
        }
        val perched = t >= 1.7f
        if (perched) heading = atan2(perch[0], perch[2])
        if (loseCues == 0 && t >= 0.35f) {
            loseCues = 1
            cueListener?.onCue(BoardCue.TUCK, 0f, 0f)
        }
        if (loseCues == 1 && perched) {
            loseCues = 2
            cueListener?.onCue(BoardCue.GULL_LAND, perch[0], 0f)
        }
        tm.setTransform(tm.getInstance(gull.root), Transforms.trsEuler(scratch, x, y, z, heading, 0f, 0f, 0.8f))
        gull.gull?.animate(clock, perched)
    }

    private fun startLose(gull: Visual, circling: Boolean) {
        loseTime = 0f
        loseCues = 0
        cueListener?.onCue(BoardCue.GULL_SWOOP, 0f, 0f)
        tm.getTransform(tm.getInstance(gull.root), scratch)
        if (circling) {
            swoopFrom[0] = scratch[12]
            swoopFrom[1] = scratch[13]
            swoopFrom[2] = scratch[14]
        } else {
            swoopFrom[0] = -width.toFloat()
            swoopFrom[1] = 4.5f
            swoopFrom[2] = -height / 2f - 2f
        }
        // Land on the rock nearest the centre, or on the island beside the board.
        val board = level?.board
        var best = -1
        var bestDistance = Float.MAX_VALUE
        if (board != null) {
            for (cell in 0 until board.cellCount) {
                if (board.tile(cell) != TileType.ROCK) continue
                val d = worldX((cell % width).toFloat()).let { it * it } + worldZ((cell / width).toFloat()).let { it * it }
                if (d < bestDistance) {
                    bestDistance = d
                    best = cell
                }
            }
        }
        if (best >= 0) {
            perch[0] = worldX((best % width).toFloat())
            perch[1] = tileHeights[best] + 0.42f
            perch[2] = worldZ((best / width).toFloat())
        } else {
            perch[0] = width / 2f + 0.7f
            perch[1] = Surfaces.ISLAND_TOP
            perch[2] = 0f
        }
    }

    private fun startIntro(state: GameState) {
        val ids = state.pieces.filter { it.kind == PieceKind.HATCHLING }
        introPieces = IntArray(ids.size) { ids[it].id }
        introCracked = BooleanArray(ids.size)
        for ((i, piece) in ids.withIndex()) {
            val egg = track(art.egg())
            place(egg, piece.cell, tileHeights[piece.cell])
            egg.egg?.animate(0f)
            show(egg)
            introEggs += egg
            pieces[piece.id]?.let {
                placePiece(piece.id, it, (piece.cell % width).toFloat(), (piece.cell / width).toFloat(), 0f, 0.001f, 1f)
            }
            if (i == 0) introTime = 0f
        }
    }

    private fun updateIntro(dt: Float) {
        if (introTime < 0f) return
        introTime += dt
        val state = displayed ?: return
        for (i in introPieces.indices) {
            val id = introPieces[i]
            val egg = introEggs.getOrNull(i) ?: continue
            val visual = pieces.getOrNull(id) ?: continue
            val cell = cellOf(state, id)
            if (cell < 0) continue
            val crackAt = INTRO_START + i * INTRO_STAGGER
            val t = introTime - crackAt
            val cx = (cell % width).toFloat()
            val cy = (cell / width).toFloat()
            if (t < 0f) {
                val wobble = if (t > -0.3f) sin(t * 40f) * 0.12f * (1f + t / 0.3f) else 0f
                tm.setTransform(
                    tm.getInstance(egg.root),
                    Transforms.trsEuler(scratch, worldX(cx), tileHeights[cell], worldZ(cy), 0f, 0f, wobble, 1f),
                )
                placePiece(id, visual, cx, cy, 0f, 0.001f, 1f)
            } else {
                if (!introCracked[i]) {
                    introCracked[i] = true
                    cueListener?.onCue(BoardCue.EGG_CRACK, worldX(cx), 0f)
                    cueListener?.onCue(BoardCue.HATCH, worldX(cx), 0f)
                    effects.bits(worldX(cx), worldZ(cy), cell, egg = true)
                }
                egg.egg?.animate(t / 0.45f)
                val sink = smoothstep((t - 0.5f) / 0.35f)
                tm.setTransform(
                    tm.getInstance(egg.root),
                    Transforms.trs(
                        scratch,
                        worldX(cx),
                        tileHeights[cell] - 0.3f * sink,
                        worldZ(cy),
                        0f,
                        1f - sink * 0.6f,
                        1f - sink * 0.6f,
                        1f - sink * 0.6f,
                    ),
                )
                if (sink >= 1f) hide(egg)
                placePiece(id, visual, cx, cy, 0f, easeOutBack((t / 0.3f).coerceIn(0f, 1f)), 1f)
                visual.hatchling?.animate(dt, 0f, 0f, 0f, clock, reducedMotion)
            }
        }
        if (introTime > INTRO_START + introPieces.size * INTRO_STAGGER + 0.9f) finishIntro()
    }

    private fun cellOf(state: GameState, id: Int): Int {
        val list = state.pieces
        for (k in 0 until list.size) if (list[k].id == id) return list[k].cell
        return -1
    }

    private fun finishIntro() {
        if (introTime < 0f) return
        introTime = -1f
        for (egg in introEggs) {
            hide(egg)
            visuals.remove(egg)
            art.destroy(egg)
        }
        introEggs.clear()
        introPieces = IntArray(0)
        displayed?.let { state ->
            for (piece in state.pieces) {
                val visual = pieces.getOrNull(piece.id) ?: continue
                placePiece(piece.id, visual, (piece.cell % width).toFloat(), (piece.cell / width).toFloat(), 0f, 1f, 1f)
            }
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
        finishIntro()
        effects.end()
        timeline = null
        onFinished = null
        for (s in swimmers) {
            hide(s.visual)
            visuals.remove(s.visual)
            art.destroy(s.visual)
        }
        swimmers.clear()
        for (v in visuals) {
            if (v === hint) continue
            hide(v)
            art.destroy(v)
        }
        visuals.retainAll { it === hint }
        scenery.release()
        shellByCell.clear()
        symbols.clear()
        pitByCell.clear()
        pitFillByCell.clear()
        pieces = arrayOfNulls(0)
        crabs.clear()
        eggs.clear()
        shadows.clear()
        shadowNext.clear()
        gulls.clear()
        alarmGull = null
        loseTime = -1f
        duck = 0f
        level = null
        displayed = null
        lastSaveCell = -1
        flareAge = 99f
    }

    companion object {
        const val BUMP_SECONDS = 0.18f
        private const val TURN_RATE = 16f
        private const val SWIM_SPEED = 0.9f
        private const val SWIM_SECONDS = 3f
        private const val GULL_HEIGHT = 2.4f
        private const val MAX_DECOR = 16
        private const val INTRO_START = 0.75f
        private const val INTRO_STAGGER = 0.08f
        private const val ALARM_RADIUS = 2.8f
        private const val ALARM_HEIGHT = 3.2f
        private const val LOSE_SECONDS = 1.8f
    }
}
