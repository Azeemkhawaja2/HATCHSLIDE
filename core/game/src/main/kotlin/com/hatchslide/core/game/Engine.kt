package com.hatchslide.core.game

/** Mutable, array-backed state used by the engine and the solver. Never escapes the game module. */
internal class SimState(val capacity: Int, crabs: Int, eggs: Int, shadows: Int) {
    var count = 0
    val cell = IntArray(capacity)
    val kind = ByteArray(capacity)
    val id = IntArray(capacity)
    var broken = 0L
    var filled = 0L
    val crabPhase = IntArray(crabs)
    val eggTimer = IntArray(eggs)
    val shadowPhase = IntArray(shadows)
    var moves = 0
    var saved = 0
    var nextId = 0
    var fell = false

    fun copyFrom(other: SimState) {
        count = other.count
        System.arraycopy(other.cell, 0, cell, 0, other.count)
        System.arraycopy(other.kind, 0, kind, 0, other.count)
        System.arraycopy(other.id, 0, id, 0, other.count)
        broken = other.broken
        filled = other.filled
        System.arraycopy(other.crabPhase, 0, crabPhase, 0, crabPhase.size)
        System.arraycopy(other.eggTimer, 0, eggTimer, 0, eggTimer.size)
        System.arraycopy(other.shadowPhase, 0, shadowPhase, 0, shadowPhase.size)
        moves = other.moves
        saved = other.saved
        nextId = other.nextId
        fell = other.fell
    }

    fun load(state: GameState) {
        count = state.pieces.size
        state.pieces.forEachIndexed { i, p ->
            cell[i] = p.cell
            kind[i] = if (p.kind == PieceKind.HATCHLING) HATCHLING else COCONUT
            id[i] = p.id
        }
        broken = state.brokenShells
        filled = state.filledPits
        state.crabPhases.forEachIndexed { i, v -> crabPhase[i] = v }
        state.eggTimers.forEachIndexed { i, v -> eggTimer[i] = v }
        state.shadowPhases.forEachIndexed { i, v -> shadowPhase[i] = v }
        moves = state.movesUsed
        saved = state.savedCount
        nextId = state.nextPieceId
        fell = state.status == GameStatus.LOST_PIT
    }

    fun toGameState(status: GameStatus): GameState {
        val pieces = ArrayList<Piece>(count)
        for (i in 0 until count) {
            pieces += Piece(id[i], if (kind[i] == HATCHLING) PieceKind.HATCHLING else PieceKind.COCONUT, cell[i])
        }
        pieces.sortBy { it.id }
        return GameState(
            pieces = pieces,
            brokenShells = broken,
            filledPits = filled,
            crabPhases = crabPhase.toList(),
            eggTimers = eggTimer.toList(),
            shadowPhases = shadowPhase.toList(),
            movesUsed = moves,
            savedCount = saved,
            status = status,
            nextPieceId = nextId,
        )
    }

    companion object {
        const val HATCHLING: Byte = 0
        const val COCONUT: Byte = 1

        fun forBoard(board: Board) = SimState(
            capacity = board.maxPieces.coerceAtLeast(1),
            crabs = board.crabPaths.size,
            eggs = board.eggCells.size,
            shadows = board.shadowPaths.size,
        )
    }
}

/** Status codes used inside the engine and solver (mirrors [GameStatus]). */
internal object Status {
    const val PLAYING = 0
    const val WON = 1
    const val LOST_MOVES = 2
    const val LOST_PIT = 3
    const val LOST_SHADOW = 4

    fun toGameStatus(code: Int): GameStatus = when (code) {
        PLAYING -> GameStatus.PLAYING
        WON -> GameStatus.WON
        LOST_MOVES -> GameStatus.LOST_OUT_OF_MOVES
        LOST_PIT -> GameStatus.LOST_PIT
        else -> GameStatus.LOST_GULL_SHADOW
    }
}

/**
 * The deterministic move resolver (spec section 2). One instance per thread: it owns scratch arrays
 * so a move allocates nothing unless a [Recorder] is attached.
 *
 * A move runs in ticks. Every tick each moving piece picks a destination one cell ahead (or the twin
 * of a tide pool it would enter). Pieces whose destination is outside the board, a blocker, a crab or
 * an unhatched egg stop. Then a fixed-point pass settles the rest: a piece stops if another moving
 * piece claims the same cell and is further forward along the swipe (ties: lower cell index), if its
 * destination holds a piece that is not moving, or if it would swap cells with another piece. Pieces
 * that may move all move together, so lines of pieces follow each other. Entered tiles then apply
 * their effect. Ocean and pit removals happen at the end of the tick, freeing the cell.
 */
internal class Engine(private val board: Board) {
    private val width = board.width
    private val cells = board.cellCount
    private val tiles = board.tiles
    private val capacity = board.maxPieces.coerceAtLeast(1)

    /** neighbour[cell * 4 + direction] or -1 off the board. */
    private val neighbour = IntArray(cells * 4).also { table ->
        for (c in 0 until cells) {
            for (d in Direction.ALL) table[c * 4 + d.ordinal] = board.step(c, d)
        }
    }

    private val occupant = IntArray(cells)
    private val entity = ByteArray(cells)
    private val moving = BooleanArray(capacity)
    private val alive = BooleanArray(capacity)
    private val removeAtTickEnd = BooleanArray(capacity)
    private val direction = IntArray(capacity)
    private val destination = IntArray(capacity)
    private val enteredPool = IntArray(capacity)
    private val seen = IntArray(capacity * cells * 4)
    private var stamp = 0
    private val tickCap = cells * 4

    private fun effectiveTile(s: SimState, c: Int): Byte {
        val t = tiles[c]
        if (t == CRACKED && (s.broken ushr board.crackedIndex[c]) and 1L == 1L) return SAND
        if (t == PIT && (s.filled ushr board.pitIndex[c]) and 1L == 1L) return SAND
        return t
    }

    private fun seenBefore(i: Int, c: Int, d: Int): Boolean {
        val slot = (i * cells + c) * 4 + d
        if (seen[slot] == stamp) return true
        seen[slot] = stamp
        return false
    }

    /** Projection of a cell on the swipe direction: larger is further forward. */
    private fun forwardness(c: Int, swipe: Int): Int = when (swipe) {
        UP -> -(c / width)
        DOWN -> c / width
        LEFT -> -(c % width)
        else -> c % width
    }

    private fun halt(s: SimState, i: Int, cause: StopCause, tick: Int, rec: Recorder?) {
        moving[i] = false
        rec?.stopped(tick, s.id[i], s.cell[i], cause, direction[i])
    }

    /**
     * Slides every piece in [swipe] until all have stopped. Returns true if anything changed (a piece
     * moved or a cracked shell broke); false means a no-op, in which case [s] is left unchanged.
     */
    fun slide(s: SimState, swipe: Int, rec: Recorder?): Boolean {
        val n = s.count
        if (++stamp == Int.MAX_VALUE) {
            seen.fill(0)
            stamp = 1
        }
        occupant.fill(-1)
        entity.fill(0)
        for (k in board.crabPaths.indices) entity[board.crabCell(k, s.crabPhase[k])] = ENTITY_CRAB
        for (e in board.eggCells.indices) if (s.eggTimer[e] > 0) entity[board.eggCells[e]] = ENTITY_EGG
        for (i in 0 until n) {
            occupant[s.cell[i]] = i
            moving[i] = true
            alive[i] = true
            removeAtTickEnd[i] = false
            direction[i] = swipe
            seenBefore(i, s.cell[i], swipe)
        }
        rec?.begin(s)

        var changed = false
        var active = n
        var tick = 0
        var saveOrder = 0
        while (active > 0) {
            if (tick >= tickCap) {
                for (i in 0 until n) if (moving[i]) halt(s, i, StopCause.TICK_CAP, tick, rec)
                break
            }
            var crackPending = 0L
            var fillPending = 0L

            // 1. Destination of every moving piece, stopping those that face a fixed obstacle.
            for (i in 0 until n) {
                if (!moving[i]) continue
                val c = s.cell[i]
                enteredPool[i] = -1
                val next = neighbour[c * 4 + direction[i]]
                if (next < 0) {
                    halt(s, i, StopCause.EDGE, tick, rec)
                    continue
                }
                when (effectiveTile(s, next)) {
                    ROCK, SHELL -> halt(s, i, StopCause.BLOCKER, tick, rec)

                    CRACKED -> {
                        halt(s, i, StopCause.BLOCKER, tick, rec)
                        val bit = 1L shl board.crackedIndex[next]
                        if (crackPending and bit == 0L) {
                            crackPending = crackPending or bit
                            rec?.event(MoveEvent.ShellCracked(tick, next, s.id[i]))
                        }
                    }

                    POOL -> {
                        val twin = board.poolTwin[next]
                        when {
                            entity[twin] == ENTITY_CRAB -> halt(s, i, StopCause.CRAB, tick, rec)

                            entity[twin] == ENTITY_EGG -> halt(s, i, StopCause.EGG, tick, rec)

                            twin == c -> halt(s, i, StopCause.BLOCKER, tick, rec)

                            else -> {
                                destination[i] = twin
                                enteredPool[i] = next
                            }
                        }
                    }

                    else -> when (entity[next]) {
                        ENTITY_CRAB -> halt(s, i, StopCause.CRAB, tick, rec)
                        ENTITY_EGG -> halt(s, i, StopCause.EGG, tick, rec)
                        else -> destination[i] = next
                    }
                }
            }

            // 2. Fixed point: conflicts, followers behind stopped pieces, and head-on swaps.
            do {
                var again = false
                for (i in 0 until n) {
                    if (!moving[i]) continue
                    val target = destination[i]
                    var outranked = false
                    for (j in 0 until n) {
                        if (j == i || !moving[j] || destination[j] != target) continue
                        val fj = forwardness(s.cell[j], swipe)
                        val fi = forwardness(s.cell[i], swipe)
                        if (fj > fi || (fj == fi && s.cell[j] < s.cell[i])) {
                            outranked = true
                            break
                        }
                    }
                    if (outranked) {
                        halt(s, i, StopCause.CONFLICT, tick, rec)
                        again = true
                        continue
                    }
                    val o = occupant[target]
                    if (o >= 0 && o != i) {
                        if (!moving[o]) {
                            halt(s, i, StopCause.PIECE, tick, rec)
                            again = true
                        } else if (destination[o] == s.cell[i]) {
                            halt(s, i, StopCause.PIECE, tick, rec)
                            halt(s, o, StopCause.PIECE, tick, rec)
                            again = true
                        }
                    }
                }
            } while (again)

            // 3. Everyone still moving moves at once.
            var movedThisTick = false
            for (i in 0 until n) if (moving[i] && occupant[s.cell[i]] == i) occupant[s.cell[i]] = -1
            for (i in 0 until n) {
                if (!moving[i]) continue
                s.cell[i] = destination[i]
                occupant[destination[i]] = i
                movedThisTick = true
            }
            if (movedThisTick) changed = true

            // 4. Tile effects for pieces that just entered a cell.
            for (i in 0 until n) {
                if (!moving[i]) continue
                val c = s.cell[i]
                if (enteredPool[i] >= 0) rec?.event(MoveEvent.Teleported(tick, s.id[i], enteredPool[i], c))
                when (val t = effectiveTile(s, c)) {
                    OCEAN -> {
                        moving[i] = false
                        removeAtTickEnd[i] = true
                        if (s.kind[i] == SimState.HATCHLING) {
                            s.saved++
                            rec?.event(MoveEvent.Saved(tick, s.id[i], c, saveOrder++))
                        } else {
                            rec?.event(MoveEvent.CoconutSank(tick, s.id[i], c))
                        }
                    }

                    WET -> halt(s, i, StopCause.WET_SAND, tick, rec)

                    CURRENT_UP, CURRENT_DOWN, CURRENT_LEFT, CURRENT_RIGHT -> {
                        val turned = t - CURRENT_UP
                        if (turned != direction[i]) {
                            rec?.event(MoveEvent.Redirected(tick, s.id[i], c, Direction.ALL[direction[i]], Direction.ALL[turned]))
                            direction[i] = turned
                        }
                    }

                    PIT -> {
                        moving[i] = false
                        removeAtTickEnd[i] = true
                        if (s.kind[i] == SimState.HATCHLING) {
                            s.fell = true
                            rec?.event(MoveEvent.FellInPit(tick, s.id[i], c))
                        } else {
                            fillPending = fillPending or (1L shl board.pitIndex[c])
                            rec?.event(MoveEvent.PitFilled(tick, s.id[i], c))
                        }
                    }

                    else -> Unit
                }
                if (moving[i] && seenBefore(i, c, direction[i])) halt(s, i, StopCause.LOOP, tick, rec)
            }

            // 5. End of tick: record positions, then remove saved/sunk/fallen pieces and apply tile changes.
            rec?.endTick(s, alive, n, movedThisTick)
            for (i in 0 until n) {
                if (!removeAtTickEnd[i]) continue
                removeAtTickEnd[i] = false
                alive[i] = false
                if (occupant[s.cell[i]] == i) occupant[s.cell[i]] = -1
            }
            if (crackPending != 0L) {
                s.broken = s.broken or crackPending
                changed = true
            }
            s.filled = s.filled or fillPending
            tick++
            active = 0
            for (i in 0 until n) if (moving[i]) active++
        }

        var k = 0
        for (i in 0 until n) {
            if (!alive[i]) continue
            if (k != i) {
                s.cell[k] = s.cell[i]
                s.kind[k] = s.kind[i]
                s.id[k] = s.id[i]
            }
            k++
        }
        s.count = k
        return changed
    }

    /** After a counted move: crabs step, gull shadows glide, egg timers tick, and the move counter rises. */
    fun advanceHazards(s: SimState, rec: Recorder?) {
        val tick = rec?.ticks ?: 0
        occupant.fill(-1)
        for (i in 0 until s.count) occupant[s.cell[i]] = i
        for (k in board.crabPaths.indices) {
            val path = board.crabPaths[k]
            if (path.size < 2) continue
            val phase = s.crabPhase[k]
            val from = board.crabCell(k, phase)
            val nextPhase = (phase + 1) % Board.pingPongPeriod(path.size)
            val to = board.crabCell(k, nextPhase)
            var blocked = occupant[to] >= 0
            if (!blocked) {
                for (e in board.eggCells.indices) if (s.eggTimer[e] > 0 && board.eggCells[e] == to) blocked = true
            }
            if (!blocked) {
                for (other in board.crabPaths.indices) {
                    if (other != k && board.crabCell(other, s.crabPhase[other]) == to) blocked = true
                }
            }
            if (blocked) {
                rec?.event(MoveEvent.CrabWaited(tick, k, from))
            } else {
                s.crabPhase[k] = nextPhase
                rec?.event(MoveEvent.CrabStepped(tick, k, from, to))
            }
        }
        for (g in board.shadowPaths.indices) {
            val length = board.shadowPaths[g].size
            if (length < 2) continue
            val from = board.shadowCell(g, s.shadowPhase[g])
            s.shadowPhase[g] = (s.shadowPhase[g] + 1) % length
            rec?.event(MoveEvent.GullShadowMoved(tick, g, from, board.shadowCell(g, s.shadowPhase[g])))
        }
        for (e in board.eggCells.indices) {
            if (s.eggTimer[e] <= 0) continue
            s.eggTimer[e]--
            val c = board.eggCells[e]
            if (s.eggTimer[e] == 0) {
                val i = s.count++
                s.cell[i] = c
                s.kind[i] = SimState.HATCHLING
                s.id[i] = s.nextId++
                rec?.event(MoveEvent.Hatched(tick, s.id[i], c, e))
            } else if (s.eggTimer[e] == 1) {
                rec?.event(MoveEvent.EggWobble(tick, c, e))
            }
        }
        s.moves++
    }

    /** Win and lose evaluation after hazards have advanced. */
    fun evaluate(s: SimState, gullMoves: Int): Int {
        if (s.fell) return Status.LOST_PIT
        var hatchlings = 0
        for (i in 0 until s.count) if (s.kind[i] == SimState.HATCHLING) hatchlings++
        var unhatched = 0
        for (t in s.eggTimer) if (t > 0) unhatched++
        if (hatchlings == 0 && unhatched == 0) return Status.WON
        for (g in board.shadowPaths.indices) {
            val shadow = board.shadowCell(g, s.shadowPhase[g])
            for (i in 0 until s.count) if (s.kind[i] == SimState.HATCHLING && s.cell[i] == shadow) return Status.LOST_SHADOW
        }
        if (s.moves >= gullMoves) return Status.LOST_MOVES
        return Status.PLAYING
    }

    /** One full counted move without recording: slide, hazards, evaluation. Returns -1 for a no-op. */
    fun step(s: SimState, swipe: Int, gullMoves: Int): Int {
        if (!slide(s, swipe, null)) return -1
        advanceHazards(s, null)
        return evaluate(s, gullMoves)
    }

    companion object {
        const val UP = 0
        const val DOWN = 1
        const val LEFT = 2

        private const val ENTITY_CRAB: Byte = 1
        private const val ENTITY_EGG: Byte = 2

        private val SAND = TileType.SAND.code
        private val ROCK = TileType.ROCK.code
        private val SHELL = TileType.SHELL.code
        private val OCEAN = TileType.OCEAN.code
        private val WET = TileType.WET_SAND.code
        private val CRACKED = TileType.CRACKED_SHELL.code
        private val CURRENT_UP = TileType.CURRENT_UP.code
        private val CURRENT_DOWN = TileType.CURRENT_DOWN.code
        private val CURRENT_LEFT = TileType.CURRENT_LEFT.code
        private val CURRENT_RIGHT = TileType.CURRENT_RIGHT.code
        private val PIT = TileType.PIT.code
        private val POOL = TileType.TIDE_POOL.code
    }
}

/** Collects tracks and events for the renderer. Only used on the interactive path. */
internal class Recorder {
    private val trackIds = ArrayList<Int>()
    private val trackKinds = ArrayList<PieceKind>()
    private val trackCells = ArrayList<MutableList<Int>>()
    private val trackIndex = HashMap<Int, Int>()
    val events = ArrayList<MoveEvent>()

    /** Ticks in which at least one piece moved; hazard events are stamped with this value. */
    var ticks = 0
        private set
    private var tickCount = 0

    fun begin(s: SimState) {
        for (i in 0 until s.count) {
            trackIndex[s.id[i]] = trackIds.size
            trackIds += s.id[i]
            trackKinds += if (s.kind[i] == SimState.HATCHLING) PieceKind.HATCHLING else PieceKind.COCONUT
            trackCells += mutableListOf(s.cell[i])
        }
    }

    fun stopped(tick: Int, pieceId: Int, cell: Int, cause: StopCause, direction: Int) {
        events += MoveEvent.Stopped(tick, pieceId, cell, cause, Direction.ALL[direction])
    }

    fun event(event: MoveEvent) {
        events += event
    }

    fun endTick(s: SimState, alive: BooleanArray, n: Int, moved: Boolean) {
        tickCount++
        if (moved) ticks = tickCount
        for (i in 0 until n) {
            if (!alive[i]) continue
            trackCells[trackIndex.getValue(s.id[i])] += s.cell[i]
        }
    }

    fun result(direction: Direction, noOp: Boolean, status: GameStatus): MoveResult {
        val tracks = trackIds.indices.map { t ->
            val cellsList = trackCells[t]
            // Drop trailing ticks after the last movement; a removed piece's track already ends early.
            val keep = minOf(cellsList.size, ticks + 1)
            PieceTrack(trackIds[t], trackKinds[t], cellsList.subList(0, keep).toList())
        }
        return MoveResult(direction, noOp, if (noOp) 0 else ticks, tracks, events.toList(), status)
    }
}
