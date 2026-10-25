package com.hatchslide.feature.game

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.hatchslide.core.render.SceneDirector
import com.hatchslide.core.render.WorldTheme

/**
 * Connects a [GameViewModel] to the shared board stage: loads the level, forwards board commands,
 * and reports animation completion so a buffered swipe can run.
 */
@Composable
fun BindBoard(viewModel: GameViewModel, director: SceneDirector, theme: WorldTheme, skin: Int, reducedMotion: Boolean) {
    LaunchedEffect(viewModel) {
        val board = director.board
        board.reducedMotion = reducedMotion
        director.showBoard()
        val level = viewModel.ui.value.level
        // Coming back from Settings keeps the built board; a new level (or skin) rebuilds it.
        if (board.isShowing(level, theme, skin)) board.snap(viewModel.boardState) else board.load(level, viewModel.boardState, theme, skin)
        viewModel.commands.collect { command ->
            when (command) {
                is BoardCommand.Play -> board.play(command.outcome) { viewModel.animationFinished() }
                is BoardCommand.Snap -> board.snap(command.state)
                is BoardCommand.ShowHint -> board.setHint(command.direction)
                BoardCommand.Celebrate -> board.celebrate()
            }
        }
    }
    LaunchedEffect(reducedMotion) { director.board.reducedMotion = reducedMotion }
}

/** Keeps the screen awake (FLAG_KEEP_SCREEN_ON, no permission) and hides system bars during play. */
@Composable
fun ImmersivePlay() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            view.keepScreenOn = false
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}
