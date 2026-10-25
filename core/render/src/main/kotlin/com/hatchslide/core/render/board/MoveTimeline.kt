package com.hatchslide.core.render.board

import com.hatchslide.core.game.Board
import com.hatchslide.core.game.Direction
import com.hatchslide.core.game.MoveEvent
import com.hatchslide.core.game.MoveResult
import com.hatchslide.core.game.PieceKind
import com.hatchslide.core.game.StopCause
import com.hatchslide.core.render.math.easeOutBack
import com.hatchslide.core.render.math.smoothstep
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sin

/** Where a piece is and how it looks at a moment of a move animation. Cell coordinates are fractional. */
class PiecePose {
    var x = 0f
    var y = 0f

    /** Height above the tile (dives go below 0). */
    var lift = 0f

    /** Facing, radians around the vertical axis; 0 faces board-up (toward the ocean row). */
    var heading = 0f
    var scale = 1f

    /** Vertical squash factor (1 = rest, 0.85 = squashed on stop). */
    var squash = 1f
    var visible = true
    var moving = false

    /** 0..1 progress of the flipper paddle cycle; renderer converts to flipper angles. */
    var paddle = 0f
}

/**
 * Turns a [MoveResult] into a time line (spec section 6, hatchling animation): the head turns toward
 * the swipe for 60 ms, then every tick advances pieces one cell. Pieces ease in on their first moving
 * tick and ease out on their last; a stop squashes to 85% height and springs back. Saved hatchlings
 * dive below the surf. Hazards (crabs, gull shadows, eggs) animate after the slide.
 */
class MoveTimeline(
    val result: MoveResult,
    private val board: Board,
    val tickSeconds: Float = 0.085f,
    val anticipation: Float = 0.06f,
    /** Height a saved hatchling dives to (the sea surface, below the tiles). */
    val diveDepth: Float = -0.5f,
) {
    val slideStart: Float = anticipation
    val slideEnd: Float = anticipation + result.ticks * tickSeconds
    private val hasHazards = result.events.any {
        it is MoveEvent.CrabStepped || it is MoveEvent.GullShadowMoved || it is MoveEvent.Hatched || it is MoveEvent.EggWobble
    }
    val hazardStart: Float = slideEnd + 0.02f
    val hazardDuration: Float = 0.3f
    val diveDuration: Float = 0.45f
    private val lastSave = result.eventsOf<MoveEvent.Saved>().maxOfOrNull { it.tick }
    val duration: Float = maxOf(
        if (hasHazards) hazardStart + hazardDuration else slideEnd,
        lastSave?.let { anticipation + (it + 1) * tickSeconds + diveDuration } ?: 0f,
        slideEnd + SQUASH_SECONDS,
    )

    private val trackById = result.tracks.associateBy { it.pieceId }
    private val stopTick = HashMap<Int, Int>()
    private val stopCause = HashMap<Int, StopCause>()
    private val savedTick = HashMap<Int, Int>()
    private val removedTick = HashMap<Int, Int>()
    private val teleports = HashMap<Long, MoveEvent.Teleported>()

    init {
        for (event in result.events) {
            when (event) {
                is MoveEvent.Stopped -> if (event.pieceId !in stopTick) {
                    stopTick[event.pieceId] = event.tick
                    stopCause[event.pieceId] = event.cause
                }

                is MoveEvent.Saved -> savedTick[event.pieceId] = event.tick

                is MoveEvent.CoconutSank -> removedTick[event.pieceId] = event.tick

                is MoveEvent.FellInPit -> removedTick[event.pieceId] = event.tick

                is MoveEvent.PitFilled -> removedTick[event.pieceId] = event.tick

                is MoveEvent.Teleported -> teleports[key(event.pieceId, event.tick)] = event

                else -> Unit
            }
        }
    }

    private fun key(pieceId: Int, tick: Int) = (pieceId.toLong() shl 32) or tick.toLong()

    private fun cx(cell: Int) = (cell % board.width).toFloat()

    private fun cy(cell: Int) = (cell / board.width).toFloat()

    /** First and last tick in which the piece actually changes cell, or null if it never moves. */
    private fun movingRange(cells: List<Int>): IntRange? {
        var first = -1
        var last = -1
        for (t in 0 until cells.size - 1) {
            if (cells[t] != cells[t + 1]) {
                if (first < 0) first = t
                last = t
            }
        }
        return if (first < 0) null else first..last
    }

    /** Fills [out] with the pose of [pieceId] at time [time] (seconds since the swipe). */
    fun pose(pieceId: Int, time: Float, out: PiecePose): PiecePose {
        val track = trackById[pieceId] ?: run {
            out.visible = false
            return out
        }
        val cells = track.cells
        out.visible = true
        out.scale = 1f
        out.lift = 0f
        out.squash = 1f
        out.moving = false
        val swipeHeading = headingOf(result.direction)
        val range = movingRange(cells)

        // Anticipation: turn toward the swipe before moving.
        val firstMoveTime = range?.let { slideStart + it.first * tickSeconds } ?: slideStart
        out.heading = swipeHeading

        val t = (time - slideStart) / tickSeconds
        val tick = t.toInt().coerceIn(0, maxOf(0, cells.size - 2))
        if (range == null || time < firstMoveTime) {
            out.x = cx(cells[0])
            out.y = cy(cells[0])
        } else {
            val lastIndex = cells.size - 1
            if (t >= lastIndex) {
                out.x = cx(cells[lastIndex])
                out.y = cy(cells[lastIndex])
            } else {
                val from = cells[tick]
                val to = cells[tick + 1]
                var f = (t - tick).coerceIn(0f, 1f)
                if (tick == range.first && tick == range.last) {
                    f = smoothstep(f)
                } else if (tick == range.first) {
                    f = f * f * (2f - f) * 0.5f + f * 0.5f
                } else if (tick == range.last) {
                    f = 1f - (1f - f) * (1f - f)
                }
                val teleport = teleports[key(pieceId, tick)]
                if (teleport != null) {
                    // Slide into the entry pool and shrink, then grow out of the twin.
                    if (f < 0.5f) {
                        val g = f / 0.5f
                        out.x = cx(from) + (cx(teleport.fromCell) - cx(from)) * g
                        out.y = cy(from) + (cy(teleport.fromCell) - cy(from)) * g
                        out.scale = 1f - g
                    } else {
                        out.x = cx(to)
                        out.y = cy(to)
                        out.scale = (f - 0.5f) / 0.5f
                    }
                } else {
                    out.x = cx(from) + (cx(to) - cx(from)) * f
                    out.y = cy(from) + (cy(to) - cy(from)) * f
                    val dx = cx(to) - cx(from)
                    val dy = cy(to) - cy(from)
                    if (abs(dx) + abs(dy) > 0f) out.heading = atan2(dx, -dy)
                }
                out.moving = from != to
                out.paddle = (time * PADDLE_HZ) % 1f
            }
        }

        // Stop squash: 85% height, spring back.
        val stop = stopTick[pieceId]
        if (stop != null && (range != null || stopCause[pieceId] != StopCause.EDGE)) {
            val stopTime = slideStart + stop * tickSeconds
            val s = (time - stopTime) / SQUASH_SECONDS
            if (s in 0f..1f) out.squash = 1f - 0.15f * sin(s * Math.PI.toFloat()) * (1f - s * 0.5f)
        }

        // Saves dive below the surf; other removals sink in place.
        val saved = savedTick[pieceId]
        val removed = saved ?: removedTick[pieceId]
        if (removed != null) {
            val arrive = slideStart + (removed + 1) * tickSeconds
            val d = ((time - arrive) / diveDuration).coerceAtLeast(0f)
            if (time >= arrive) {
                if (saved != null) {
                    out.lift = diveDepth * smoothstep(d)
                    out.heading = swipeHeading
                    val push = 0.6f * smoothstep(d)
                    out.x += result.direction.dx * push
                    out.y += result.direction.dy * push
                } else {
                    out.lift = -0.6f * smoothstep(d)
                }
                if (d >= 1f) out.visible = false
            }
        }
        return out
    }

    /** Hazard progress 0..1 during the post-slide window (crab steps, shadow glide, egg pop). */
    fun hazardProgress(time: Float): Float = smoothstep((time - hazardStart) / hazardDuration)

    /** Pop-in scale for a hatchling that hatched from a late egg this move. */
    fun hatchScale(time: Float): Float {
        val p = (time - hazardStart - 0.08f) / 0.25f
        return if (p <= 0f) 0f else easeOutBack(p)
    }

    /** True if a piece slid at least three cells and was stopped by a blocker: worth a camera shake. */
    fun hardStops(): List<Pair<Int, Float>> = result.tracks.mapNotNull { track ->
        val range = movingRange(track.cells) ?: return@mapNotNull null
        val cause = stopCause[track.pieceId]
        val distance = range.last - range.first + 1
        if (distance >= 3 && (cause == StopCause.BLOCKER || cause == StopCause.CRAB || cause == StopCause.EGG)) {
            track.pieceId to slideStart + (range.last + 1) * tickSeconds
        } else {
            null
        }
    }

    /** Events scheduled in seconds for audio, haptics and particles. */
    fun schedule(): List<Pair<Float, MoveEvent>> = result.events.map { event ->
        val time = when (event) {
            is MoveEvent.Stopped -> slideStart + event.tick * tickSeconds

            is MoveEvent.Saved, is MoveEvent.CoconutSank, is MoveEvent.FellInPit, is MoveEvent.PitFilled ->
                slideStart + (event.tick + 1) * tickSeconds

            is MoveEvent.Redirected, is MoveEvent.Teleported -> slideStart + (event.tick + 1) * tickSeconds

            is MoveEvent.ShellCracked -> slideStart + event.tick * tickSeconds

            is MoveEvent.Hatched -> hazardStart + 0.08f

            else -> hazardStart
        }
        time to event
    }.sortedBy { it.first }

    fun kindOf(pieceId: Int): PieceKind? = trackById[pieceId]?.kind

    companion object {
        const val SQUASH_SECONDS = 0.22f
        const val PADDLE_HZ = 2.5f

        fun headingOf(direction: Direction): Float = when (direction) {
            Direction.UP -> 0f
            Direction.RIGHT -> (Math.PI / 2).toFloat()
            Direction.DOWN -> Math.PI.toFloat()
            Direction.LEFT -> (-Math.PI / 2).toFloat()
        }
    }
}
