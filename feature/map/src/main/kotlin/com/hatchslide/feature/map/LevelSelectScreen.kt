package com.hatchslide.feature.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hatchslide.core.data.Progress
import com.hatchslide.core.data.ProgressRules
import com.hatchslide.core.designsystem.FrostedPanel
import com.hatchslide.core.designsystem.GameText
import com.hatchslide.core.designsystem.LocalHatchColors
import com.hatchslide.core.designsystem.R as DesignR
import com.hatchslide.core.designsystem.ScreenScaffold
import com.hatchslide.core.designsystem.ShellButton
import com.hatchslide.core.game.Level
import com.hatchslide.core.render.SceneDirector

/**
 * Level select (spec section 5): a 6x5 grid of shell buttons with stars, best moves and locks, over
 * the map camera resting on the island. The last five need 45 stars in the world.
 */
@Composable
fun LevelSelectScreen(
    director: SceneDirector,
    world: Int,
    progress: Progress,
    levels: List<Level>?,
    onLevel: (String) -> Unit,
    onBack: () -> Unit,
) {
    LaunchedEffect(world) {
        director.show(director.map)
        director.map.focus(world, animate = false)
    }
    val colors = LocalHatchColors.current
    val stars = ProgressRules.worldStars(progress, world)
    ScreenScaffold(stringResource(GameText.worldName(world)), onBack, maxWidth = 720) {
        FrostedPanel(Modifier.fillMaxWidth(), padding = 12.dp) {
            BoxWithConstraints {
                // Six columns on phones in portrait; up to ten when there is room, so it stays 30 shells.
                val columns = if (maxWidth > 520.dp) 10 else 6
                LazyVerticalGrid(GridCells.Fixed(columns), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column(Modifier.padding(bottom = 6.dp)) {
                            Text(
                                pluralStringResource(
                                    R.plurals.levels_stars,
                                    ProgressRules.STARS_PER_WORLD,
                                    stars,
                                    ProgressRules.STARS_PER_WORLD,
                                ),
                                style = MaterialTheme.typography.titleMedium,
                                color = colors.onPanel,
                            )
                            if (stars < ProgressRules.GATE_STARS) {
                                Text(
                                    pluralStringResource(R.plurals.levels_gate, ProgressRules.GATE_STARS, ProgressRules.GATE_STARS, stars),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.onPanelSoft,
                                    textAlign = TextAlign.Start,
                                )
                            }
                        }
                    }
                    items((1..ProgressRules.LEVELS_PER_WORLD).toList(), key = { it }) { index ->
                        val id = ProgressRules.levelId(world, index)
                        val record = progress.levels[id]
                        val lock = ProgressRules.lockOf(progress, world, index)
                        val name = levels?.getOrNull(index - 1)?.name.orEmpty()
                        val description = when (lock) {
                            null -> buildString {
                                append(stringResource(R.string.level_button, index, name))
                                if (record != null && record.stars > 0) {
                                    append(". ")
                                    append(pluralStringResource(DesignR.plurals.stars_count, record.stars, record.stars))
                                    append(". ")
                                    append(pluralStringResource(R.plurals.level_best, record.bestMoves, record.bestMoves))
                                }
                            }

                            ProgressRules.Lock.STARS -> pluralStringResource(
                                R.plurals.level_locked_stars,
                                ProgressRules.GATE_STARS,
                                index,
                                ProgressRules.GATE_STARS,
                            )

                            else -> stringResource(R.string.level_locked_previous, index)
                        }
                        ShellButton(
                            number = index,
                            stars = record?.stars ?: 0,
                            locked = lock != null || levels == null,
                            description = description,
                            onClick = { onLevel(id) },
                        )
                    }
                }
            }
        }
    }
}
