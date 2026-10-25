package com.hatchslide.core.game

/**
 * Packs a [SimState] into a fixed number of 64-bit words. Hatchlings are interchangeable, so their
 * cells are sorted (and coconuts likewise): two states that differ only in which hatchling stands
 * where share one key.
 */
internal class StateCodec(private val board: Board) {
    private val hatchSlots = board.totalHatchlings
    private val coconutSlots = board.coconutStarts.size
    private val empty = board.cellCount
    private val cellBits = bitsFor(board.cellCount + 1)
    private val crackBits = board.crackedCells.size
    private val pitBits = board.pitCells.size
    private val crabBits = board.crabPaths.map { bitsFor(Board.pingPongPeriod(it.size)) }
    private val eggBits = 4
    private val shadowBits = board.shadowPaths.map { bitsFor(it.size) }
    private val hatchScratch = IntArray(hatchSlots.coerceAtLeast(1))
    private val coconutScratch = IntArray(coconutSlots.coerceAtLeast(1))

    val words: Int = run {
        val bits = (hatchSlots + coconutSlots) * cellBits + crackBits + pitBits + crabBits.sum() +
            board.eggCells.size * eggBits + shadowBits.sum()
        ((bits + 63) / 64).coerceAtLeast(1)
    }

    private var bitPos = 0

    private fun write(out: LongArray, base: Int, value: Long, bits: Int) {
        if (bits == 0) return
        val word = bitPos ushr 6
        val offset = bitPos and 63
        out[base + word] = out[base + word] or (value shl offset)
        if (offset + bits > 64) out[base + word + 1] = out[base + word + 1] or (value ushr (64 - offset))
        bitPos += bits
    }

    private fun read(src: LongArray, base: Int, bits: Int): Long {
        if (bits == 0) return 0L
        val word = bitPos ushr 6
        val offset = bitPos and 63
        var value = src[base + word] ushr offset
        if (offset + bits > 64) value = value or (src[base + word + 1] shl (64 - offset))
        bitPos += bits
        return if (bits == 64) value else value and ((1L shl bits) - 1)
    }

    fun encode(s: SimState, out: LongArray, base: Int = 0) {
        for (w in 0 until words) out[base + w] = 0L
        var h = 0
        var c = 0
        for (i in 0 until s.count) {
            if (s.kind[i] == SimState.HATCHLING) hatchScratch[h++] = s.cell[i] else coconutScratch[c++] = s.cell[i]
        }
        sortPrefix(hatchScratch, h)
        sortPrefix(coconutScratch, c)
        bitPos = 0
        for (k in 0 until hatchSlots) write(out, base, (if (k < h) hatchScratch[k] else empty).toLong(), cellBits)
        for (k in 0 until coconutSlots) write(out, base, (if (k < c) coconutScratch[k] else empty).toLong(), cellBits)
        write(out, base, s.broken, crackBits)
        write(out, base, s.filled, pitBits)
        for (k in crabBits.indices) write(out, base, s.crabPhase[k].toLong(), crabBits[k])
        for (e in board.eggCells.indices) write(out, base, s.eggTimer[e].toLong(), eggBits)
        for (g in shadowBits.indices) write(out, base, s.shadowPhase[g].toLong(), shadowBits[g])
    }

    fun decode(src: LongArray, base: Int, s: SimState) {
        bitPos = 0
        var n = 0
        for (k in 0 until hatchSlots) {
            val cell = read(src, base, cellBits).toInt()
            if (cell != empty) {
                s.cell[n] = cell
                s.kind[n] = SimState.HATCHLING
                s.id[n] = n
                n++
            }
        }
        for (k in 0 until coconutSlots) {
            val cell = read(src, base, cellBits).toInt()
            if (cell != empty) {
                s.cell[n] = cell
                s.kind[n] = SimState.COCONUT
                s.id[n] = n
                n++
            }
        }
        s.count = n
        s.broken = read(src, base, crackBits)
        s.filled = read(src, base, pitBits)
        for (k in crabBits.indices) s.crabPhase[k] = read(src, base, crabBits[k]).toInt()
        var unhatched = 0
        for (e in board.eggCells.indices) {
            s.eggTimer[e] = read(src, base, eggBits).toInt()
            if (s.eggTimer[e] > 0) unhatched++
        }
        for (g in shadowBits.indices) s.shadowPhase[g] = read(src, base, shadowBits[g]).toInt()
        var hatchlingsOnBoard = 0
        for (i in 0 until n) if (s.kind[i] == SimState.HATCHLING) hatchlingsOnBoard++
        s.saved = hatchSlots - hatchlingsOnBoard - unhatched
        s.nextId = n + board.eggCells.size
        s.fell = false
    }

    companion object {
        fun bitsFor(values: Int): Int {
            var bits = 0
            while ((1 shl bits) < values) bits++
            return bits
        }

        private fun sortPrefix(a: IntArray, n: Int) {
            for (i in 1 until n) {
                val v = a[i]
                var j = i - 1
                while (j >= 0 && a[j] > v) {
                    a[j + 1] = a[j]
                    j--
                }
                a[j + 1] = v
            }
        }
    }
}

/** Open-addressing hash set of fixed-width LongArray keys. Index order is insertion (BFS) order. */
internal class KeyStore(private val words: Int, expected: Int = 1 shl 12) {
    var keys = LongArray(words * expected)
        private set
    var size = 0
        private set
    private var table = IntArray(tableSizeFor(expected)) { -1 }
    private var mask = table.size - 1

    private fun hash(src: LongArray, base: Int): Int {
        var h = -0x61c8864680b583ebL
        for (w in 0 until words) {
            h = (h xor src[base + w]) * -0x4b47d5b1a6a16d7dL
            h = h xor (h ushr 31)
        }
        return (h xor (h ushr 32)).toInt()
    }

    private fun equalsAt(index: Int, key: LongArray): Boolean {
        val base = index * words
        for (w in 0 until words) if (keys[base + w] != key[w]) return false
        return true
    }

    /** Returns the index of [key], inserting it if new. Inserted keys get index `size - 1`; check [size]. */
    fun findOrAdd(key: LongArray): Int {
        var slot = hash(key, 0) and mask
        while (true) {
            val index = table[slot]
            if (index < 0) break
            if (equalsAt(index, key)) return index
            slot = (slot + 1) and mask
        }
        if ((size + 1) * 2 > table.size) {
            rehash(table.size * 2)
            return findOrAdd(key)
        }
        if ((size + 1) * words > keys.size) keys = keys.copyOf(keys.size * 2)
        System.arraycopy(key, 0, keys, size * words, words)
        table[slot] = size
        return size++
    }

    private fun rehash(newSize: Int) {
        table = IntArray(newSize) { -1 }
        mask = newSize - 1
        for (index in 0 until size) {
            var slot = hash(keys, index * words) and mask
            while (table[slot] >= 0) slot = (slot + 1) and mask
            table[slot] = index
        }
    }

    companion object {
        private fun tableSizeFor(expected: Int): Int {
            var n = 16
            while (n < expected * 2) n = n shl 1
            return n
        }
    }
}

sealed interface SolveResult {
    val statesExplored: Int

    /**
     * [moves] is the optimal number of moves from the given state; [path] is one optimal solution;
     * [optimalFirstMoves] lists every first move that starts some optimal solution; [optimalSolutions]
     * counts distinct optimal move sequences (saturating at Long.MAX_VALUE).
     */
    data class Solved(
        val moves: Int,
        val path: List<Direction>,
        val optimalFirstMoves: Set<Direction>,
        val optimalSolutions: Long,
        override val statesExplored: Int,
    ) : SolveResult

    /** No solution within the remaining gull moves. */
    data class Unsolvable(override val statesExplored: Int) : SolveResult

    /** The search exceeded its state budget before deciding. */
    data class TooLarge(override val statesExplored: Int) : SolveResult
}

/** Full exploration of a level up to its gull limit, used for difficulty scoring. */
data class Analysis(
    val solve: SolveResult,
    val states: Int,
    /** States from which no win is reachable within the limit. */
    val deadEnds: Int,
    /** Mean count of distinct successors per expanded state (no-ops and losses excluded). */
    val branching: Double,
    /** Moves from expanded states that lose immediately (pit, gull shadow). */
    val losingMoves: Int,
)

/**
 * Breadth-first solver (spec section 4). Depth limit is the gull counter. Thread-confined; create
 * one per thread. Searches allocate only when the key store grows.
 */
class Solver(private val level: Level) {
    private val board = level.board
    private val engine = Engine(board)
    private val codec = StateCodec(board)
    private val words = codec.words
    private val current = SimState.forBoard(board)
    private val next = SimState.forBoard(board)
    private val key = LongArray(words)

    private class Nodes(capacity: Int) {
        var parent = IntArray(capacity)
        var move = ByteArray(capacity)
        var depth = ShortArray(capacity)
        var firstMoves = ByteArray(capacity)
        var paths = LongArray(capacity)

        fun ensure(n: Int) {
            if (n <= parent.size) return
            val size = maxOf(n, parent.size * 2)
            parent = parent.copyOf(size)
            move = move.copyOf(size)
            depth = depth.copyOf(size)
            firstMoves = firstMoves.copyOf(size)
            paths = paths.copyOf(size)
        }
    }

    /** Optimal solution from [state] within its remaining gull moves. */
    fun solve(state: GameState = GameState.initial(level), maxStates: Int = DEFAULT_MAX_STATES): SolveResult =
        search(state, maxStates, exhaustive = false).first

    /** Explores every state reachable within the gull limit from the start; also returns the optimal solve. */
    fun analyze(maxStates: Int = DEFAULT_MAX_STATES): Analysis {
        val (result, extra) = search(GameState.initial(level), maxStates, exhaustive = true)
        return extra ?: Analysis(result, result.statesExplored, 0, 0.0, 0)
    }

    private fun search(state: GameState, maxStates: Int, exhaustive: Boolean): Pair<SolveResult, Analysis?> {
        if (state.status == GameStatus.WON) return SolveResult.Solved(0, emptyList(), emptySet(), 1, 0) to null
        if (state.status != GameStatus.PLAYING) return SolveResult.Unsolvable(0) to null
        val limit = level.gullMoves - state.movesUsed
        if (limit <= 0) return SolveResult.Unsolvable(0) to null

        val store = KeyStore(words)
        val nodes = Nodes(1 shl 12)
        current.load(state)
        codec.encode(current, key)
        store.findOrAdd(key)
        nodes.parent[0] = -1
        nodes.depth[0] = 0
        nodes.paths[0] = 1
        val startMoves = state.movesUsed

        // children[4 * node + d]: >= 0 node index, -1 no-op, -2 immediate loss, -3 win. Exhaustive mode only.
        var children = if (exhaustive) IntArray(4 shl 12) else IntArray(0)
        var bestDepth = -1
        var winParent = -1
        var winMove = -1
        var winFirstMoves = 0
        var winPaths = 0L
        var expanded = 0
        var successorSum = 0L
        var losingMoves = 0
        var head = 0

        while (head < store.size) {
            val node = head++
            val depth = nodes.depth[node].toInt()
            if (!exhaustive && bestDepth >= 0 && depth >= bestDepth) break
            if (depth >= limit) break
            codec.decode(store.keys, node * words, current)
            current.moves = startMoves + depth
            expanded++
            var successors = 0
            for (d in 0 until 4) {
                next.copyFrom(current)
                val status = engine.step(next, d, level.gullMoves)
                var child: Int
                when (status) {
                    -1 -> child = -1

                    Status.WON -> {
                        child = -3
                        val first = if (depth == 0) 1 shl d else nodes.firstMoves[node].toInt()
                        if (bestDepth < 0) {
                            bestDepth = depth + 1
                            winParent = node
                            winMove = d
                        }
                        if (depth + 1 == bestDepth) {
                            winFirstMoves = winFirstMoves or first
                            winPaths = saturatingAdd(winPaths, nodes.paths[node])
                        }
                    }

                    Status.PLAYING -> {
                        if (!exhaustive && bestDepth >= 0) {
                            child = -1
                        } else {
                            codec.encode(next, key)
                            val before = store.size
                            child = store.findOrAdd(key)
                            val first = if (depth == 0) 1 shl d else nodes.firstMoves[node].toInt()
                            if (store.size > before) {
                                nodes.ensure(store.size)
                                nodes.parent[child] = node
                                nodes.move[child] = d.toByte()
                                nodes.depth[child] = (depth + 1).toShort()
                                nodes.firstMoves[child] = first.toByte()
                                nodes.paths[child] = nodes.paths[node]
                                if (store.size > maxStates) return SolveResult.TooLarge(store.size) to null
                            } else if (nodes.depth[child].toInt() == depth + 1) {
                                nodes.firstMoves[child] = (nodes.firstMoves[child].toInt() or first).toByte()
                                nodes.paths[child] = saturatingAdd(nodes.paths[child], nodes.paths[node])
                            }
                            successors++
                        }
                    }

                    else -> {
                        child = -2
                        if (status != Status.LOST_MOVES) losingMoves++
                    }
                }
                if (exhaustive) {
                    if (4 * node + d >= children.size) children = children.copyOf(children.size * 2)
                    children[4 * node + d] = child
                }
            }
            successorSum += successors
        }

        val result = if (bestDepth < 0) {
            SolveResult.Unsolvable(store.size)
        } else {
            val path = ArrayList<Direction>(bestDepth)
            path += Direction.ALL[winMove]
            var n = winParent
            while (n > 0) {
                path += Direction.ALL[nodes.move[n].toInt()]
                n = nodes.parent[n]
            }
            path.reverse()
            val firsts = Direction.entries.filter { winFirstMoves and (1 shl it.ordinal) != 0 }.toSet()
            SolveResult.Solved(bestDepth, path, firsts, winPaths, store.size)
        }
        if (!exhaustive) return result to null

        // Dead ends: walk nodes in reverse BFS order so children are settled before parents.
        val canWin = BooleanArray(store.size)
        for (node in store.size - 1 downTo 0) {
            if (node >= expanded) continue
            for (d in 0 until 4) {
                val child = children[4 * node + d]
                if (child == -3 || (child >= 0 && canWin[child])) {
                    canWin[node] = true
                    break
                }
            }
        }
        val deadEnds = canWin.count { !it }
        val branching = if (expanded == 0) 0.0 else successorSum.toDouble() / expanded
        return result to Analysis(result, store.size, deadEnds, branching, losingMoves)
    }

    companion object {
        /** Budget that keeps an in-game hint well under 200 ms on a mid-range phone. */
        const val DEFAULT_MAX_STATES = 400_000

        private fun saturatingAdd(a: Long, b: Long): Long = if (a > Long.MAX_VALUE - b) Long.MAX_VALUE else a + b
    }
}

/** In-game hint (spec section 2): the next optimal move from the current state, or why there is none. */
sealed interface Hint {
    data class Move(val direction: Direction, val movesToWin: Int) : Hint

    /** The current state cannot be won within the moves left; the UI suggests Undo. */
    data object Unsolvable : Hint

    /** The search ran out of budget (never expected for shipped levels). */
    data object Unknown : Hint
}

object Hints {
    fun next(level: Level, state: GameState, maxStates: Int = Solver.DEFAULT_MAX_STATES): Hint =
        when (val result = Solver(level).solve(state, maxStates)) {
            is SolveResult.Solved -> if (result.path.isEmpty()) Hint.Unknown else Hint.Move(result.path.first(), result.moves)
            is SolveResult.Unsolvable -> Hint.Unsolvable
            is SolveResult.TooLarge -> Hint.Unknown
        }
}
