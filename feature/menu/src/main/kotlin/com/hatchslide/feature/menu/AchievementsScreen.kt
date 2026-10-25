package com.hatchslide.feature.menu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hatchslide.core.data.Achievement
import com.hatchslide.core.data.Progress
import com.hatchslide.core.data.ProgressRules
import com.hatchslide.core.designsystem.FrostedPanel
import com.hatchslide.core.designsystem.GameText
import com.hatchslide.core.designsystem.HatchIcons
import com.hatchslide.core.designsystem.LocalHatchColors
import com.hatchslide.core.designsystem.Palette
import com.hatchslide.core.designsystem.ScreenScaffold
import com.hatchslide.core.designsystem.SectionTitle
import com.hatchslide.core.designsystem.StatTile
import com.hatchslide.core.render.SceneDirector
import com.hatchslide.core.render.stages.BeachStage
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Achievements and stats (spec section 5): the 24 local achievements plus lifetime totals. */
@Composable
fun AchievementsScreen(director: SceneDirector, progress: Progress, onBack: () -> Unit) {
    BeachBackdrop(director, BeachStage.Mode.MENU)
    val numbers = NumberFormat.getIntegerInstance()
    val stats = progress.stats
    ScreenScaffold(stringResource(R.string.achievements_title), onBack, maxWidth = 720) {
        FrostedPanel(Modifier.fillMaxWidth(), padding = 12.dp) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { SectionTitle(stringResource(R.string.achievements_stats)) }
                item {
                    Row(Modifier.fillMaxWidth()) {
                        StatTile(numbers.format(stats.saved), stringResource(R.string.stat_saved), Modifier.weight(1f))
                        StatTile(numbers.format(stats.moves), stringResource(R.string.stat_moves), Modifier.weight(1f))
                        StatTile(
                            "${ProgressRules.totalStars(progress)}",
                            stringResource(R.string.stat_stars),
                            Modifier.weight(1f),
                        )
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth()) {
                        val hours = (stats.playSeconds / 3600).toInt()
                        val minutes = ((stats.playSeconds / 60) % 60).toInt()
                        StatTile(
                            stringResource(R.string.playtime_format, hours, minutes),
                            stringResource(R.string.stat_playtime),
                            Modifier.weight(1f),
                        )
                        StatTile(
                            "${progress.levels.count { it.key.startsWith("w") && it.value.stars > 0 }}",
                            stringResource(R.string.stat_levels),
                            Modifier.weight(1f),
                        )
                        StatTile("${stats.dailiesCompleted}", stringResource(R.string.stat_dailies), Modifier.weight(1f))
                    }
                }
                item {
                    SectionTitle(stringResource(R.string.achievements_list, progress.achievements.size, Achievement.entries.size))
                }
                items(Achievement.entries, key = { it.id }) { achievement ->
                    AchievementRow(achievement, progress.achievements[achievement.id])
                }
            }
        }
    }
}

@Composable
private fun AchievementRow(achievement: Achievement, earnedAt: Long?) {
    val colors = LocalHatchColors.current
    val (title, body) = GameText.achievement(achievement.id)
    val earned = earnedAt != null
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (earned) HatchIcons.Trophy else HatchIcons.Lock,
            contentDescription = null,
            tint = if (earned) Palette.Gold else Palette.InkSoft.copy(alpha = 0.4f),
            modifier = Modifier.size(36.dp),
        )
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(title),
                style = MaterialTheme.typography.titleMedium,
                color = if (earned) colors.onPanel else colors.onPanelSoft,
            )
            Text(stringResource(body), style = MaterialTheme.typography.bodySmall, color = colors.onPanelSoft)
            val status = if (earnedAt != null) {
                val date = Instant.ofEpochMilli(earnedAt).atZone(ZoneId.systemDefault()).toLocalDate()
                stringResource(R.string.achievement_unlocked_on, date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)))
            } else {
                stringResource(R.string.achievement_locked)
            }
            Text(status, style = MaterialTheme.typography.labelSmall, color = if (earned) Palette.SeaGlassDark else colors.onPanelSoft)
        }
    }
}
