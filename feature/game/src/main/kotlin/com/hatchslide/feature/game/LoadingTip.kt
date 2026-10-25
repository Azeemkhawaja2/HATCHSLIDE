package com.hatchslide.feature.game

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hatchslide.core.designsystem.FrostedPanel
import com.hatchslide.core.designsystem.HatchIcons
import com.hatchslide.core.designsystem.LocalHatchColors
import com.hatchslide.core.designsystem.LocalUiPreferences
import com.hatchslide.core.designsystem.Palette
import kotlin.random.Random

/**
 * Shown while a level loads, but only once loading has taken 300 ms (spec section 11): a hatchling
 * waddling on the spot and a tip about the game or about real sea turtles.
 */
@Composable
fun LoadingTip(visible: Boolean) {
    val tips = stringArrayResource(R.array.loading_tips)
    val tip = remember { tips[Random.nextInt(tips.size)] }
    val motion = if (LocalUiPreferences.current.reducedMotion) 0f else 1f
    AnimatedVisibility(visible, enter = fadeIn(tween(200, delayMillis = 300)), exit = fadeOut(tween(200))) {
        val waddle by rememberInfiniteTransition(label = "waddle").animateFloat(
            initialValue = -1f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(420), RepeatMode.Reverse),
            label = "waddle",
        )
        val colors = LocalHatchColors.current
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            FrostedPanel(
                Modifier.padding(24.dp).widthIn(max = 420.dp).semantics(mergeDescendants = true) {
                    liveRegion =
                        LiveRegionMode.Polite
                },
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(
                        HatchIcons.Hatchling,
                        contentDescription = null,
                        tint = Palette.SeaGlassDark,
                        modifier = Modifier.size(52.dp).graphicsLayer {
                            rotationZ = waddle * 10f * motion
                            translationY = -(1f - waddle * waddle) * 6.dp.toPx() * motion
                        },
                    )
                    Text(stringResource(R.string.loading_title), style = MaterialTheme.typography.titleMedium, color = colors.onPanel)
                    Text(tip, style = MaterialTheme.typography.bodyMedium, color = colors.onPanelSoft, textAlign = TextAlign.Center)
                }
            }
        }
    }
}
