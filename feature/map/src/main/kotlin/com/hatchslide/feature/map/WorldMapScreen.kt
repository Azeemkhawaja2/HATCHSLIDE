package com.hatchslide.feature.map

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.hatchslide.core.data.Progress
import com.hatchslide.core.data.ProgressRules
import com.hatchslide.core.designsystem.GameText
import com.hatchslide.core.designsystem.HatchIcons
import com.hatchslide.core.designsystem.HudChip
import com.hatchslide.core.designsystem.Palette
import com.hatchslide.core.designsystem.ScreenScaffold
import com.hatchslide.core.designsystem.StarRow
import com.hatchslide.core.render.SceneDirector
import kotlin.math.roundToInt

/**
 * World map (spec section 5): the 3D archipelago with a badge over each island showing stars / 90
 * or a lock. Drag pans the chain; tapping an island (or its badge, which also serves keyboard,
 * gamepad and TalkBack users) flies the camera there and opens its level select.
 */
@Composable
fun WorldMapScreen(director: SceneDirector, progress: Progress, focusWorld: Int, onWorld: (Int) -> Unit, onBack: () -> Unit) {
    val stage = director.map
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        director.show(stage)
        stage.overview(focusWorld)
        stage.onArrived = onWorld
    }
    // Island summits on screen, refreshed every frame while the camera moves.
    var positions by remember { mutableStateOf<List<Offset?>>(emptyList()) }
    LaunchedEffect(Unit) {
        val scratch = FloatArray(2)
        while (true) {
            withFrameNanos { }
            val next = (1..ProgressRules.WORLDS).map { w -> if (stage.screenPosition(w, scratch)) Offset(scratch[0], scratch[1]) else null }
            if (next != positions) positions = next
        }
    }
    val lockedText = (1..ProgressRules.WORLDS).map { w ->
        if (w == 1) "" else stringResource(R.string.map_locked_message, stringResource(GameText.worldName(w - 1)))
    }
    fun open(world: Int) {
        if (ProgressRules.isWorldUnlocked(progress, world)) {
            message = null
            stage.focus(world)
        } else {
            message = lockedText[world - 1]
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(progress) {
                detectTapGestures { offset -> stage.islandAt(offset.x, offset.y)?.let { open(it) } }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    change.consume()
                    stage.pan(drag.x)
                }
            },
    ) {
        val density = LocalDensity.current
        positions.forEachIndexed { i, p ->
            if (p == null) return@forEachIndexed
            val world = i + 1
            IslandBadge(
                world,
                progress,
                onClick = { open(world) },
                modifier = Modifier.offset {
                    IntOffset((p.x - with(density) { 70.dp.toPx() }).roundToInt(), (p.y - with(density) { 90.dp.toPx() }).roundToInt())
                },
            )
        }
        ScreenScaffold(stringResource(R.string.map_title), onBack) {}
        Column(Modifier.align(Alignment.BottomCenter).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            HudChip {
                Text(message ?: stringResource(R.string.map_hint), style = MaterialTheme.typography.bodyMedium, color = Palette.Cream)
            }
        }
    }
}

@Composable
private fun IslandBadge(world: Int, progress: Progress, onClick: () -> Unit, modifier: Modifier) {
    val unlocked = ProgressRules.isWorldUnlocked(progress, world)
    val stars = ProgressRules.worldStars(progress, world)
    val name = stringResource(GameText.worldName(world))
    val description = if (unlocked) {
        pluralStringResource(R.plurals.map_island, ProgressRules.STARS_PER_WORLD, name, world, stars, ProgressRules.STARS_PER_WORLD)
    } else {
        stringResource(R.string.map_island_locked, name, world, stringResource(GameText.worldName(world - 1)))
    }
    Column(
        modifier
            .size(width = 140.dp, height = 72.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        HudChip {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(name, style = MaterialTheme.typography.titleSmall, color = Palette.Cream, maxLines = 1)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (unlocked) {
                        StarRow(
                            if (stars >= ProgressRules.STARS_PER_WORLD) {
                                3
                            } else if (stars > 0) {
                                1
                            } else {
                                0
                            },
                            starSize = 12.dp,
                        )
                        Spacer(Modifier.size(4.dp))
                        Text(
                            "$stars / ${ProgressRules.STARS_PER_WORLD}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Palette.Cream,
                        )
                    } else {
                        Icon(HatchIcons.Lock, contentDescription = null, tint = Palette.Cream, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}
