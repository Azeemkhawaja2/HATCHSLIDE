package com.hatchslide.feature.game

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.hatchslide.core.render.SceneDirector
import com.hatchslide.core.render.WorldTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Connects a [GameViewModel] to the shared board stage: loads the level, forwards board commands,
 * and reports animation completion so a buffered swipe can run. Returns whether a load is running.
 *
 * A new level reads its world's textures and builds its island off the main thread first, while the
 * previous stage stays on screen; only the GPU uploads then run on the main thread (spec section 11).
 */
@Composable
fun bindBoard(viewModel: GameViewModel, director: SceneDirector, theme: WorldTheme, skin: Int, reducedMotion: Boolean): State<Boolean> {
    val loading = remember { mutableStateOf(false) }
    LaunchedEffect(viewModel) {
        val board = director.board
        board.reducedMotion = reducedMotion
        val level = viewModel.ui.value.level
        // Coming back from Settings keeps the built board; a new level (or skin) rebuilds it.
        if (board.isShowing(level, theme, skin)) {
            director.showBoard()
            board.snap(viewModel.boardState)
        } else {
            loading.value = true
            val island = withContext(Dispatchers.IO) {
                director.engine.prefetch(theme)
                board.prepare(level)
            }
            // Let the loading tip draw before the uploads hold the main thread.
            withFrameNanos { }
            board.load(level, viewModel.boardState, theme, skin, island = island)
            director.showBoard()
            loading.value = false
        }
        viewModel.boardReady = true
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
    DisposableEffect(viewModel) { onDispose { viewModel.boardReady = false } }
    return loading
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
