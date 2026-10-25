package com.hatchslide.feature.journal

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hatchslide.core.data.Progress
import com.hatchslide.core.data.ProgressRules
import com.hatchslide.core.designsystem.ButtonTone
import com.hatchslide.core.designsystem.ChunkyButton
import com.hatchslide.core.designsystem.FrostedPanel
import com.hatchslide.core.designsystem.GameText
import com.hatchslide.core.designsystem.HatchIcons
import com.hatchslide.core.designsystem.HudChip
import com.hatchslide.core.designsystem.LocalHatchColors
import com.hatchslide.core.designsystem.LocalUiSound
import com.hatchslide.core.designsystem.Palette
import com.hatchslide.core.designsystem.R as DesignR
import com.hatchslide.core.designsystem.RoundButton
import com.hatchslide.core.designsystem.ScreenScaffold
import com.hatchslide.core.designsystem.UiSound
import com.hatchslide.core.render.SceneDirector
import com.hatchslide.core.render.camera.FreeViewport
import com.hatchslide.core.render.stages.BeachStage

/**
 * Turtle Journal (spec section 5): the seven species, each unlocked by progress, with a rotatable 3D
 * hatchling wearing the species' shell and three short facts. Unlocked species can be chosen as the
 * in-game skin.
 */
@Composable
fun JournalScreen(director: SceneDirector, progress: Progress, onSelectSkin: (Int) -> Unit, onBack: () -> Unit) {
    var page by rememberSaveable { mutableIntStateOf(progress.skin) }
    val unlocked = ProgressRules.isSpeciesUnlocked(progress, page)
    LaunchedEffect(Unit) {
        director.show(director.beach)
        director.beach.setMode(BeachStage.Mode.JOURNAL)
    }
    LaunchedEffect(page, unlocked) { director.beach.setSpecies(if (unlocked) page else progress.skin) }
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val sideBySide = maxWidth > maxHeight
        val width = with(density) { maxWidth.roundToPx() }
        val height = with(density) { maxHeight.roundToPx() }
        // Drag anywhere outside the card to turn the hatchling.
        Box(
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectHorizontalDragGestures { change, dx ->
                    change.consume()
                    director.beach.rotateHero(dx * 0.012f)
                }
            },
        )
        ScreenScaffold(stringResource(R.string.journal_title), onBack, maxWidth = 2000) {
            Box(Modifier.fillMaxSize(), contentAlignment = if (sideBySide) Alignment.CenterEnd else Alignment.BottomCenter) {
                Column(
                    Modifier
                        .widthIn(max = 460.dp)
                        .fillMaxWidth(if (sideBySide) 0.5f else 1f)
                        .then(if (sideBySide) Modifier.fillMaxHeight() else Modifier)
                        .onGloballyPositioned {
                            val panel = it.boundsInRoot()
                            director.beach.setFreeViewport(
                                if (sideBySide) {
                                    FreeViewport(width, height, right = (width - panel.left).toInt().coerceAtLeast(0))
                                } else {
                                    FreeViewport(width, height, bottom = (height - panel.top).toInt().coerceAtLeast(0))
                                },
                            )
                        },
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    TurnButtons(director)
                    Spacer(Modifier.size(8.dp))
                    val sound = LocalUiSound.current
                    SpeciesCard(page, progress, onSelectSkin, onPrevious = {
                        sound(UiSound.PAGE)
                        page = (page + 6) % 7
                    }, onNext = {
                        sound(UiSound.PAGE)
                        page = (page + 1) % 7
                    })
                }
            }
        }
    }
}

@Composable
private fun TurnButtons(director: SceneDirector) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        RoundButton(HatchIcons.Restart, stringResource(R.string.journal_turn_left), { director.beach.rotateHero(-0.6f) }, size = 48.dp)
        Spacer(Modifier.size(10.dp))
        HudChip { Text(stringResource(R.string.journal_drag_hint), style = MaterialTheme.typography.labelMedium, color = Palette.Cream) }
        Spacer(Modifier.size(10.dp))
        RoundButton(
            HatchIcons.Restart,
            stringResource(R.string.journal_turn_right),
            { director.beach.rotateHero(0.6f) },
            Modifier.graphicsLayer { scaleX = -1f },
            size = 48.dp,
        )
    }
}

@Composable
private fun SpeciesCard(species: Int, progress: Progress, onSelectSkin: (Int) -> Unit, onPrevious: () -> Unit, onNext: () -> Unit) {
    val colors = LocalHatchColors.current
    val unlocked = ProgressRules.isSpeciesUnlocked(progress, species)
    val name = stringResource(GameText.speciesName(species))
    FrostedPanel(Modifier.fillMaxWidth(), padding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RoundButton(HatchIcons.Back, stringResource(R.string.journal_previous), onPrevious, size = 48.dp)
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    name,
                    style = MaterialTheme.typography.headlineMedium,
                    color = colors.onPanel,
                    modifier = Modifier.semantics {
                        heading()
                        liveRegion = LiveRegionMode.Polite
                    },
                )
                Text(
                    pluralStringResource(
                        R.plurals.journal_count,
                        ProgressRules.SPECIES,
                        ProgressRules.unlockedSpecies(progress).size,
                        ProgressRules.SPECIES,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onPanelSoft,
                )
            }
            RoundButton(
                HatchIcons.Back,
                stringResource(R.string.journal_next),
                onNext,
                Modifier.graphicsLayer {
                    scaleX = -1f
                },
                size = 48.dp,
            )
        }
        Spacer(Modifier.size(10.dp))
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (unlocked) {
                for (fact in GameText.speciesFacts(species)) {
                    Row {
                        Icon(
                            HatchIcons.Shell,
                            contentDescription = null,
                            tint = Palette.Coral,
                            modifier = Modifier.size(18.dp).padding(top = 2.dp),
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(fact), style = MaterialTheme.typography.bodyMedium, color = colors.onPanel)
                    }
                }
                val inUse = progress.skin == species
                ChunkyButton(
                    stringResource(if (inUse) R.string.journal_in_use else R.string.journal_use_skin),
                    { onSelectSkin(species) },
                    Modifier.fillMaxWidth(),
                    icon = if (inUse) HatchIcons.Check else HatchIcons.Hatchling,
                    tone = if (inUse) ButtonTone.Quiet else ButtonTone.Secondary,
                    enabled = !inUse,
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(HatchIcons.Lock, contentDescription = null, tint = colors.onPanelSoft, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(unlockText(species), style = MaterialTheme.typography.bodyLarge, color = colors.onPanelSoft)
                }
            }
        }
    }
}

@Composable
private fun unlockText(species: Int): String = when (species) {
    0 -> stringResource(DesignR.string.species_unlock_start)
    in 1..ProgressRules.WORLDS -> stringResource(DesignR.string.species_unlock_world, stringResource(GameText.worldName(species)))
    else -> pluralStringResource(GameText.speciesUnlockDaily, ProgressRules.FLATBACK_DAILIES, ProgressRules.FLATBACK_DAILIES)
}
