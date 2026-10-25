package com.hatchslide.feature.menu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.hatchslide.core.data.Progress
import com.hatchslide.core.data.ProgressRules
import com.hatchslide.core.designsystem.ButtonTone
import com.hatchslide.core.designsystem.ChunkyButton
import com.hatchslide.core.designsystem.FrostedPanel
import com.hatchslide.core.designsystem.HatchIcons
import com.hatchslide.core.designsystem.LocalHatchColors
import com.hatchslide.core.designsystem.Palette
import com.hatchslide.core.render.SceneDirector
import com.hatchslide.core.render.stages.BeachStage

/** Main menu (spec section 5): Play, Daily Tide, Turtle Journal, Achievements, Settings over the live beach. */
@Composable
fun MainMenuScreen(
    director: SceneDirector,
    progress: Progress,
    today: Long,
    onPlay: () -> Unit,
    onDaily: () -> Unit,
    onJournal: () -> Unit,
    onAchievements: () -> Unit,
    onSettings: () -> Unit,
) {
    BeachBackdrop(director, BeachStage.Mode.MENU)
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val sideBySide = maxWidth > maxHeight && maxWidth >= 600.dp
        val width = with(density) { maxWidth.roundToPx() }
        val height = with(density) { maxHeight.roundToPx() }
        Box(
            Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp),
            contentAlignment = if (sideBySide) Alignment.CenterEnd else Alignment.BottomCenter,
        ) {
            FrostedPanel(
                Modifier
                    .widthIn(max = 420.dp)
                    .fillMaxWidth(if (sideBySide) 0.42f else 1f)
                    .onGloballyPositioned { director.avoidPanel(width, height, it.boundsInRoot(), sideBySide) },
            ) {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val stars = ProgressRules.totalStars(progress)
                    Text(
                        pluralStringResource(R.plurals.menu_stars, TOTAL_STARS, stars, TOTAL_STARS),
                        style = MaterialTheme.typography.labelMedium,
                        color = LocalHatchColors.current.onPanelSoft,
                    )
                    ChunkyButton(stringResource(R.string.menu_play), onPlay, Modifier.fillMaxWidth(), icon = HatchIcons.Play)
                    DailyButton(progress, today, onDaily)
                    ChunkyButton(
                        stringResource(R.string.menu_journal),
                        onJournal,
                        Modifier.fillMaxWidth(),
                        icon = HatchIcons.Journal,
                        tone = ButtonTone.Secondary,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ChunkyButton(
                            stringResource(R.string.menu_achievements),
                            onAchievements,
                            Modifier.weight(1f),
                            icon = HatchIcons.Trophy,
                            tone = ButtonTone.Quiet,
                        )
                        ChunkyButton(
                            stringResource(R.string.menu_settings),
                            onSettings,
                            Modifier.weight(1f),
                            icon = HatchIcons.Settings,
                            tone = ButtonTone.Quiet,
                        )
                    }
                }
            }
        }
    }
}

private const val TOTAL_STARS = ProgressRules.STARS_PER_WORLD * ProgressRules.WORLDS

@Composable
private fun DailyButton(progress: Progress, today: Long, onDaily: () -> Unit) {
    val done = today in progress.daily.completedDays
    val streak = ProgressRules.currentStreak(progress, today)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        ChunkyButton(
            stringResource(R.string.menu_daily),
            onDaily,
            Modifier.fillMaxWidth(),
            icon = HatchIcons.Calendar,
            tone = ButtonTone.Accent,
        )
        if (done || streak > 0) {
            Spacer(Modifier.size(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (done) {
                    Icon(HatchIcons.Check, contentDescription = null, tint = Palette.SeaGlassDark, modifier = Modifier.size(18.dp))
                    Text(
                        stringResource(R.string.menu_daily_done),
                        style = MaterialTheme.typography.labelMedium,
                        color = Palette.SeaGlassDark,
                    )
                    Spacer(Modifier.size(10.dp))
                }
                if (streak > 0) {
                    Text(
                        pluralStringResource(R.plurals.menu_streak, streak, streak),
                        style = MaterialTheme.typography.labelMedium,
                        color = Palette.SunsetDark,
                    )
                }
            }
        }
    }
}
