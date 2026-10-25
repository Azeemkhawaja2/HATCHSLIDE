package com.hatchslide.feature.menu

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hatchslide.core.data.Progress
import com.hatchslide.core.data.ProgressRules
import com.hatchslide.core.designsystem.ButtonTone
import com.hatchslide.core.designsystem.ChunkyButton
import com.hatchslide.core.designsystem.FrostedPanel
import com.hatchslide.core.designsystem.HatchIcons
import com.hatchslide.core.designsystem.LocalHatchColors
import com.hatchslide.core.designsystem.Palette
import com.hatchslide.core.designsystem.ScreenScaffold
import com.hatchslide.core.designsystem.StatTile
import com.hatchslide.core.game.Level
import com.hatchslide.core.render.SceneDirector
import com.hatchslide.core.render.stages.BeachStage
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle

/** Daily Tide (spec section 5): today's puzzle, current and best streak, and a 7-day strip. */
@Composable
fun DailyScreen(director: SceneDirector, progress: Progress, today: Long, puzzle: Level?, onPlay: () -> Unit, onBack: () -> Unit) {
    BeachBackdrop(director, BeachStage.Mode.MENU)
    val colors = LocalHatchColors.current
    val date = LocalDate.ofEpochDay(today)
    val done = today in progress.daily.completedDays
    ScreenScaffold(stringResource(R.string.daily_title), onBack) {
        FrostedPanel(Modifier.fillMaxWidth()) {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onPanelSoft,
                )
                Text(stringResource(R.string.daily_today), style = MaterialTheme.typography.titleMedium, color = colors.onPanelSoft)
                if (puzzle != null) {
                    Text(puzzle.name, style = MaterialTheme.typography.headlineMedium, color = colors.onPanel)
                    Text(
                        pluralStringResource(R.plurals.daily_par, puzzle.gullMoves, puzzle.par, puzzle.gullMoves),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onPanel,
                    )
                }
                if (done) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(HatchIcons.Check, contentDescription = null, tint = Palette.SeaGlassDark, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.daily_done), style = MaterialTheme.typography.bodyMedium, color = colors.onPanel)
                    }
                }
                ChunkyButton(
                    stringResource(if (done) R.string.daily_play_again else R.string.daily_play),
                    onPlay,
                    Modifier.fillMaxWidth(),
                    icon = HatchIcons.Play,
                    tone = if (done) ButtonTone.Secondary else ButtonTone.Accent,
                    enabled = puzzle != null,
                )
                Row(Modifier.fillMaxWidth()) {
                    StatTile(
                        ProgressRules.currentStreak(progress, today).toString(),
                        stringResource(R.string.daily_streak),
                        Modifier.weight(1f),
                    )
                    StatTile(progress.daily.bestStreak.toString(), stringResource(R.string.daily_best), Modifier.weight(1f))
                }
                Text(stringResource(R.string.daily_week), style = MaterialTheme.typography.titleMedium, color = colors.onPanel)
                WeekStrip(progress, today)
            }
        }
    }
}

@Composable
private fun WeekStrip(progress: Progress, today: Long) {
    val colors = LocalHatchColors.current
    val locale = LocalLocale.current.platformLocale
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        for (offset in 6 downTo 0) {
            val day = today - offset
            val date = LocalDate.ofEpochDay(day)
            val name = date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
            val solved = day in progress.daily.completedDays
            val description = stringResource(if (solved) R.string.daily_day_done else R.string.daily_day_missed, name)
            Column(
                Modifier.semantics(mergeDescendants = true) { contentDescription = description },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(name, style = MaterialTheme.typography.labelSmall, color = colors.onPanelSoft, textAlign = TextAlign.Center)
                Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.size(32.dp)) {
                        if (solved) {
                            drawCircle(Palette.SeaGlass)
                        } else {
                            drawCircle(Palette.Sand, style = Stroke(3.dp.toPx()))
                        }
                        if (offset ==
                            0
                        ) {
                            drawCircle(Palette.Sunset, radius = size.minDimension / 2 + 3.dp.toPx(), style = Stroke(2.dp.toPx()))
                        }
                    }
                    if (solved) Icon(HatchIcons.Check, contentDescription = null, tint = Palette.Cream, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}
