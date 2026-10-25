package com.hatchslide.feature.menu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.hatchslide.core.data.FrameRateSetting
import com.hatchslide.core.data.GraphicsQuality
import com.hatchslide.core.data.Settings
import com.hatchslide.core.designsystem.ButtonTone
import com.hatchslide.core.designsystem.ChoiceRow
import com.hatchslide.core.designsystem.ChunkyButton
import com.hatchslide.core.designsystem.FrostedPanel
import com.hatchslide.core.designsystem.HatchIcons
import com.hatchslide.core.designsystem.ScreenScaffold
import com.hatchslide.core.designsystem.SectionTitle
import com.hatchslide.core.designsystem.SliderRow
import com.hatchslide.core.designsystem.ToggleRow
import com.hatchslide.core.render.SceneDirector
import com.hatchslide.core.render.stages.BeachStage

/** Settings (spec section 5): sound, graphics, accessibility, progress reset and about pages. */
@Composable
fun SettingsScreen(
    director: SceneDirector,
    settings: Settings,
    onChange: ((Settings) -> Settings) -> Unit,
    onResetProgress: () -> Unit,
    onPrivacy: () -> Unit,
    onCredits: () -> Unit,
    onBack: () -> Unit,
) {
    BeachBackdrop(director, BeachStage.Mode.MENU)
    var resetStep by rememberSaveable { mutableIntStateOf(0) }
    ScreenScaffold(stringResource(R.string.settings_title), onBack) {
        FrostedPanel(Modifier.fillMaxWidth(), padding = 14.dp) {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SectionTitle(stringResource(R.string.settings_audio))
                SliderRow(stringResource(R.string.settings_music), settings.musicVolume) { v -> onChange { it.copy(musicVolume = v) } }
                SliderRow(stringResource(R.string.settings_effects), settings.effectsVolume) { v ->
                    onChange { it.copy(effectsVolume = v) }
                }
                ToggleRow(stringResource(R.string.settings_haptics), settings.haptics, { v ->
                    onChange { it.copy(haptics = v) }
                }, stringResource(R.string.settings_haptics_body))

                SectionTitle(stringResource(R.string.settings_graphics))
                ChoiceRow(
                    stringResource(R.string.settings_quality),
                    listOf(
                        GraphicsQuality.AUTO to stringResource(R.string.quality_auto),
                        GraphicsQuality.LOW to stringResource(R.string.quality_low),
                        GraphicsQuality.MEDIUM to stringResource(R.string.quality_medium),
                        GraphicsQuality.HIGH to stringResource(R.string.quality_high),
                    ),
                    settings.quality,
                ) { q -> onChange { it.copy(quality = q) } }
                ChoiceRow(
                    stringResource(R.string.settings_frame_rate),
                    listOf(
                        FrameRateSetting.BATTERY to stringResource(R.string.frame_battery),
                        FrameRateSetting.STANDARD to stringResource(R.string.frame_standard),
                        FrameRateSetting.SMOOTH to stringResource(R.string.frame_smooth),
                    ),
                    settings.frameRate,
                ) { f -> onChange { it.copy(frameRate = f) } }

                SectionTitle(stringResource(R.string.settings_accessibility))
                ToggleRow(
                    stringResource(R.string.settings_color_blind),
                    settings.colorBlindTiles,
                    { v -> onChange { it.copy(colorBlindTiles = v) } },
                    stringResource(R.string.settings_color_blind_body),
                )
                ToggleRow(stringResource(R.string.settings_high_contrast), settings.highContrast, { v ->
                    onChange { it.copy(highContrast = v) }
                })
                ToggleRow(
                    stringResource(R.string.settings_reduced_motion),
                    settings.reducedMotion,
                    { v -> onChange { it.copy(reducedMotion = v) } },
                    stringResource(R.string.settings_reduced_motion_body),
                )
                ToggleRow(stringResource(R.string.settings_arrow_pad), settings.arrowPad, { v -> onChange { it.copy(arrowPad = v) } })
                ToggleRow(stringResource(R.string.settings_left_handed), settings.leftHanded, { v -> onChange { it.copy(leftHanded = v) } })

                SectionTitle(stringResource(R.string.settings_progress))
                ChunkyButton(stringResource(R.string.settings_reset), { resetStep = 1 }, Modifier.fillMaxWidth(), tone = ButtonTone.Quiet)

                SectionTitle(stringResource(R.string.settings_about))
                ChunkyButton(stringResource(R.string.settings_privacy), onPrivacy, Modifier.fillMaxWidth(), tone = ButtonTone.Quiet)
                ChunkyButton(
                    stringResource(R.string.settings_credits),
                    onCredits,
                    Modifier.fillMaxWidth(),
                    icon = HatchIcons.Journal,
                    tone = ButtonTone.Quiet,
                )
            }
        }
    }
    // Reset progress needs two confirmations.
    when (resetStep) {
        1 -> AlertDialog(
            onDismissRequest = { resetStep = 0 },
            title = { Text(stringResource(R.string.settings_reset_title)) },
            text = { Text(stringResource(R.string.settings_reset_body)) },
            confirmButton = { TextButton({ resetStep = 2 }) { Text(stringResource(R.string.settings_reset_continue)) } },
            dismissButton = { TextButton({ resetStep = 0 }) { Text(stringResource(R.string.cancel)) } },
        )

        2 -> AlertDialog(
            onDismissRequest = { resetStep = 0 },
            title = { Text(stringResource(R.string.settings_reset_final_title)) },
            text = { Text(stringResource(R.string.settings_reset_final_body)) },
            confirmButton = {
                TextButton({
                    resetStep = 0
                    onResetProgress()
                }) { Text(stringResource(R.string.settings_reset_confirm)) }
            },
            dismissButton = { TextButton({ resetStep = 0 }) { Text(stringResource(R.string.settings_reset_keep)) } },
        )
    }
}
