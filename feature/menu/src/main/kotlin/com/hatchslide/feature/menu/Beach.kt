package com.hatchslide.feature.menu

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.geometry.Rect
import com.hatchslide.core.render.SceneDirector
import com.hatchslide.core.render.camera.FreeViewport
import com.hatchslide.core.render.stages.BeachStage

/** Shows the 3D beach behind a menu screen in the given camera mode. */
@Composable
fun BeachBackdrop(director: SceneDirector, mode: BeachStage.Mode) {
    LaunchedEffect(mode) {
        director.show(director.beach)
        director.beach.setMode(mode)
    }
}

/** Tells the beach camera which part of the screen a panel covers, so the hatchling stays in view. */
fun SceneDirector.avoidPanel(width: Int, height: Int, panel: Rect, sideBySide: Boolean) {
    if (width <= 0 || height <= 0) return
    beach.setFreeViewport(
        if (sideBySide) {
            FreeViewport(width, height, right = (width - panel.left).toInt().coerceAtLeast(0))
        } else {
            FreeViewport(width, height, bottom = (height - panel.top).toInt().coerceAtLeast(0))
        },
    )
}
