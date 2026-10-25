package com.hatchslide.core.game

/** Builds a level from grid rows for tests; coordinates are (x, y). */
internal fun testLevel(
    vararg grid: String,
    gullMoves: Int = 40,
    crabs: List<List<Pair<Int, Int>>> = emptyList(),
    eggs: List<Triple<Int, Int, Int>> = emptyList(),
    shadows: List<List<Pair<Int, Int>>> = emptyList(),
): Level = LevelCodec.parse(
    LevelFile(
        id = "test",
        world = 1,
        index = 1,
        name = "Test",
        grid = grid.toList(),
        crabs = crabs.map { path -> CrabSpec(path.map { listOf(it.first, it.second) }) },
        eggs = eggs.map { EggSpec(listOf(it.first, it.second), it.third) },
        gullShadows = shadows.map { path -> ShadowSpec(path.map { listOf(it.first, it.second) }) },
        par = 1,
        gullMoves = gullMoves,
        seed = 1L,
    ),
)

internal fun Level.cell(x: Int, y: Int): Int = board.index(x, y)

internal fun GameState.cellsOf(kind: PieceKind): List<Int> = pieces.filter { it.kind == kind }.map { it.cell }.sorted()

internal fun MoveOutcome.hatchlingCells(): List<Int> = state.cellsOf(PieceKind.HATCHLING)
