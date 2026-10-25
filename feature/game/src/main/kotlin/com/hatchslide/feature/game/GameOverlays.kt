package com.hatchslide.feature.game

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hatchslide.core.designsystem.ButtonTone
import com.hatchslide.core.designsystem.ChunkyButton
import com.hatchslide.core.designsystem.FrostedPanel
import com.hatchslide.core.designsystem.HatchIcons
import com.hatchslide.core.designsystem.LocalHatchColors
import com.hatchslide.core.designsystem.LocalUiPreferences
import com.hatchslide.core.designsystem.Overlay
import com.hatchslide.core.designsystem.Palette
import com.hatchslide.core.game.Direction
import com.hatchslide.core.game.GameStatus
import kotlinx.coroutines.delay

/** A card queued after the stars on the level-complete screen. */
data class UnlockCard(val title: String, val body: String, val icon: ImageVector)

@Composable
private fun OverlayPanel(content: @Composable () -> Unit) {
    FrostedPanel(Modifier.padding(24.dp).widthIn(max = 460.dp)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) { content() }
    }
}

@Composable
private fun OverlayTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.headlineMedium,
        color = LocalHatchColors.current.onPanel,
        textAlign = TextAlign.Center,
        modifier = Modifier.semantics { heading() },
    )
}

@Composable
fun PauseOverlay(onResume: () -> Unit, onRestart: () -> Unit, onLevels: () -> Unit, onSettings: () -> Unit, onHome: () -> Unit) {
    Overlay(onBack = onResume) {
        OverlayPanel {
            OverlayTitle(stringResource(R.string.pause_title))
            ChunkyButton(stringResource(R.string.pause_resume), onResume, Modifier.fillMaxWidth(), icon = HatchIcons.Play)
            ChunkyButton(
                stringResource(R.string.pause_restart),
                onRestart,
                Modifier.fillMaxWidth(),
                icon = HatchIcons.Restart,
                tone = ButtonTone.Secondary,
            )
            ChunkyButton(
                stringResource(R.string.pause_levels),
                onLevels,
                Modifier.fillMaxWidth(),
                icon = HatchIcons.Map,
                tone = ButtonTone.Quiet,
            )
            ChunkyButton(
                stringResource(R.string.pause_settings),
                onSettings,
                Modifier.fillMaxWidth(),
                icon = HatchIcons.Settings,
                tone = ButtonTone.Quiet,
            )
            ChunkyButton(
                stringResource(R.string.pause_home),
                onHome,
                Modifier.fillMaxWidth(),
                icon = HatchIcons.Home,
                tone = ButtonTone.Quiet,
            )
        }
    }
}

/** Level complete: stars fly in one by one, then unlock cards, then the buttons (spec section 5). */
@Composable
fun CompleteOverlay(
    ui: GameUiState,
    unlockCards: List<UnlockCard>,
    onStarShown: (Int) -> Unit,
    onNext: () -> Unit,
    onReplay: () -> Unit,
    onLevels: () -> Unit,
) {
    val reduced = LocalUiPreferences.current.reducedMotion
    var shown by rememberSaveable(ui.level.id, ui.state.movesUsed) { mutableIntStateOf(0) }
    var card by rememberSaveable(ui.level.id, ui.state.movesUsed) { mutableIntStateOf(0) }
    LaunchedEffect(ui.level.id, ui.state.movesUsed) {
        // Let the hatchlings swim away first.
        if (shown == 0) delay(if (reduced) 200 else 1400)
        while (shown < ui.stars) {
            delay(if (reduced) 120 else 420)
            shown++
            onStarShown(shown)
        }
    }
    Overlay(onBack = onLevels) {
        OverlayPanel {
            OverlayTitle(stringResource(R.string.complete_title))
            val starsText = pluralStringResource(R.plurals.complete_stars, ui.stars, ui.stars)
            Row(Modifier.semantics(mergeDescendants = true) { }, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(3) { i -> FlyInStar(filled = i < ui.stars, visible = i < shown || i >= ui.stars, reduced = reduced) }
            }
            Text(starsText, style = MaterialTheme.typography.labelMedium, color = LocalHatchColors.current.onPanelSoft)
            Text(
                stringResource(R.string.complete_moves, ui.state.movesUsed, ui.level.par),
                style = MaterialTheme.typography.titleMedium,
                color = LocalHatchColors.current.onPanel,
            )
            val starsDone = shown >= ui.stars
            if (starsDone && card < unlockCards.size) {
                val c = unlockCards[card]
                FrostedPanel(Modifier.fillMaxWidth(), padding = 16.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(c.icon, contentDescription = null, tint = Palette.SunsetDark, modifier = Modifier.size(40.dp))
                        Spacer(Modifier.size(12.dp))
                        Column {
                            Text(c.title, style = MaterialTheme.typography.titleMedium, color = LocalHatchColors.current.onPanel)
                            Text(c.body, style = MaterialTheme.typography.bodyMedium, color = LocalHatchColors.current.onPanelSoft)
                        }
                    }
                }
                ChunkyButton(stringResource(R.string.tip_got_it), { card++ }, Modifier.fillMaxWidth(), tone = ButtonTone.Accent)
            } else if (starsDone) {
                ChunkyButton(stringResource(R.string.complete_next), onNext, Modifier.fillMaxWidth(), icon = HatchIcons.Play)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ChunkyButton(stringResource(R.string.complete_replay), onReplay, icon = HatchIcons.Restart, tone = ButtonTone.Secondary)
                    ChunkyButton(stringResource(R.string.complete_levels), onLevels, icon = HatchIcons.Map, tone = ButtonTone.Quiet)
                }
            } else {
                Spacer(Modifier.height(112.dp))
            }
        }
    }
}

@Composable
private fun FlyInStar(filled: Boolean, visible: Boolean, reduced: Boolean) {
    val scale = remember { Animatable(if (visible || reduced) 1f else 0f) }
    LaunchedEffect(visible) {
        if (visible) scale.animateTo(1f, if (reduced) tween(80) else spring(dampingRatio = 0.38f, stiffness = 300f))
    }
    Icon(
        HatchIcons.Star,
        contentDescription = null,
        tint = if (filled) Palette.Gold else Palette.InkSoft.copy(alpha = 0.2f),
        modifier = Modifier
            .size(56.dp)
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                rotationZ = (1f - scale.value) * -40f
            },
    )
}

@Composable
fun FailedOverlay(status: GameStatus, canUndo: Boolean, onUndo: () -> Unit, onRetry: () -> Unit, onLevels: () -> Unit) {
    Overlay(onBack = onLevels) {
        OverlayPanel {
            val title = when (status) {
                GameStatus.LOST_PIT -> R.string.failed_title_pit
                GameStatus.LOST_GULL_SHADOW -> R.string.failed_title_shadow
                else -> R.string.failed_title_moves
            }
            Icon(HatchIcons.Seagull, contentDescription = null, tint = Palette.Ink, modifier = Modifier.size(56.dp))
            OverlayTitle(stringResource(title))
            Text(
                stringResource(R.string.failed_body),
                style = MaterialTheme.typography.bodyLarge,
                color = LocalHatchColors.current.onPanelSoft,
                textAlign = TextAlign.Center,
            )
            ChunkyButton(
                stringResource(R.string.failed_undo),
                onUndo,
                Modifier.fillMaxWidth(),
                icon = HatchIcons.Undo,
                tone = ButtonTone.Secondary,
                enabled = canUndo,
            )
            ChunkyButton(stringResource(R.string.failed_retry), onRetry, Modifier.fillMaxWidth(), icon = HatchIcons.Restart)
            ChunkyButton(
                stringResource(R.string.failed_levels),
                onLevels,
                Modifier.fillMaxWidth(),
                icon = HatchIcons.Map,
                tone = ButtonTone.Quiet,
            )
        }
    }
}

/** Maps a level's tip key to its text; unknown keys show nothing. */
fun tipText(key: String): Int? = when (key) {
    "tip_swipe" -> R.string.tip_swipe
    "tip_all_move" -> R.string.tip_all_move
    "tip_blocking" -> R.string.tip_blocking
    "tip_rock" -> R.string.tip_rock
    "tip_shell" -> R.string.tip_shell
    "tip_gull" -> R.string.tip_gull
    "tip_wet_sand" -> R.string.tip_wet_sand
    "tip_cracked_shell" -> R.string.tip_cracked_shell
    "tip_current" -> R.string.tip_current
    "tip_coconut" -> R.string.tip_coconut
    "tip_pit" -> R.string.tip_pit
    "tip_crab" -> R.string.tip_crab
    "tip_late_egg" -> R.string.tip_late_egg
    "tip_tide_pool" -> R.string.tip_tide_pool
    "tip_gull_shadow" -> R.string.tip_gull_shadow
    else -> null
}

/** One-card tip shown the first time a new cell type appears. */
@Composable
fun TipOverlay(tip: String, onDone: () -> Unit) {
    val text = tipText(tip) ?: return onDone()
    Overlay(onBack = onDone) {
        OverlayPanel {
            Icon(HatchIcons.Hint, contentDescription = null, tint = Palette.SunsetDark, modifier = Modifier.size(48.dp))
            Text(
                stringResource(text),
                style = MaterialTheme.typography.titleLarge,
                color = LocalHatchColors.current.onPanel,
                textAlign = TextAlign.Center,
            )
            ChunkyButton(stringResource(R.string.tip_got_it), onDone, Modifier.fillMaxWidth())
        }
    }
}

/** The first-run tutorial hand: a fingertip that demonstrates the next swipe (levels 1-3). */
@Composable
fun TutorialHand(direction: Direction, modifier: Modifier = Modifier) {
    val reduced = LocalUiPreferences.current.reducedMotion
    val transition = rememberInfiniteTransition(label = "hand")
    val t by transition.animateFloat(
        0f,
        1f,
        infiniteRepeatable(tween(1300, easing = FastOutSlowInEasing), RepeatMode.Restart),
        label = "hand",
    )
    Canvas(modifier.size(220.dp)) {
        val travel = size.minDimension * 0.32f
        val p = if (reduced) 0.5f else t
        val dx = direction.dx * travel * (p * 2 - 1)
        val dy = direction.dy * travel * (p * 2 - 1)
        val c = Offset(center.x + dx, center.y + dy)
        val alpha = if (reduced) 0.9f else (1f - kotlin.math.abs(p * 2 - 1) * 0.6f)
        for (k in 1..5) {
            val back = k * 0.06f * travel
            drawCircle(
                Palette.Cream.copy(alpha = alpha * (0.3f - k * 0.05f)),
                18.dp.toPx() - k,
                Offset(
                    c.x - direction.dx * back,
                    c.y - direction.dy * back,
                ),
            )
        }
        drawCircle(Palette.Ocean.copy(alpha = 0.25f * alpha), 24.dp.toPx(), Offset(c.x + 2.dp.toPx(), c.y + 3.dp.toPx()))
        drawCircle(Palette.Cream.copy(alpha = alpha), 22.dp.toPx(), c)
        drawCircle(Palette.Coral.copy(alpha = alpha), 10.dp.toPx(), c)
    }
}
