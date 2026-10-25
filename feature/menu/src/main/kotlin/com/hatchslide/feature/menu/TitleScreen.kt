package com.hatchslide.feature.menu

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hatchslide.core.designsystem.LocalUiPreferences
import com.hatchslide.core.designsystem.Palette
import com.hatchslide.core.render.SceneDirector
import com.hatchslide.core.render.stages.BeachStage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Title (spec section 5): the camera glides in over the sunrise beach, a hatchling pops out of its
 * egg, then the logo drops in with a bounce. Any tap, key or gamepad button starts.
 */
@Composable
fun TitleScreen(director: SceneDirector, onStart: () -> Unit) {
    BeachBackdrop(director, BeachStage.Mode.TITLE)
    val reduced = LocalUiPreferences.current.reducedMotion
    val drop = remember { Animatable(if (reduced) 0f else -1f) }
    val alpha = remember { Animatable(0f) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        focus.requestFocus()
        delay(if (reduced) 300 else 2500)
        launch { alpha.animateTo(1f, tween(250)) }
        if (!reduced) drop.animateTo(0f, spring(dampingRatio = 0.35f, stiffness = 220f))
    }
    val tapText = stringResource(R.string.title_tap)
    Box(
        Modifier
            .fillMaxSize()
            .focusRequester(focus)
            .focusable()
            .onKeyEvent {
                if (it.type == KeyEventType.KeyUp) onStart()
                true
            }
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onStart)
            .semantics { contentDescription = tapText }
            .safeDrawingPadding(),
    ) {
        Text(
            stringResource(R.string.title_logo),
            style = TextStyle(
                fontFamily = MaterialTheme.typography.displayLarge.fontFamily,
                fontWeight = MaterialTheme.typography.displayLarge.fontWeight,
                fontSize = 56.sp,
                letterSpacing = 2.sp,
                color = Palette.Cream,
                shadow = Shadow(Palette.CoralDark, Offset(0f, 8f), 2f),
            ),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 72.dp)
                .graphicsLayer {
                    translationY = drop.value * 420.dp.toPx()
                    this.alpha = alpha.value
                },
        )
        PulsingHint(tapText, alpha.value, Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp))
    }
}

@Composable
private fun PulsingHint(text: String, visible: Float, modifier: Modifier) {
    val reduced = LocalUiPreferences.current.reducedMotion
    val pulse = if (reduced) {
        1f
    } else {
        rememberInfiniteTransition(
            label = "tap",
        ).animateFloat(0.55f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "tap").value
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text,
            style = MaterialTheme.typography.headlineSmall.copy(shadow = Shadow(Color.Black.copy(alpha = 0.35f), Offset(0f, 3f), 6f)),
            color = Palette.Cream,
            modifier = Modifier.graphicsLayer { alpha = pulse * visible },
        )
    }
}
