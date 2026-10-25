package com.hatchslide.core.designsystem

import android.os.Build
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class ButtonTone { Primary, Secondary, Accent, Quiet }

private data class ToneColors(val face: Color, val lip: Color, val content: Color)

@Composable
private fun toneColors(tone: ButtonTone): ToneColors {
    val c = LocalHatchColors.current
    return when (tone) {
        ButtonTone.Primary -> ToneColors(c.primaryButton, c.primaryButtonDark, c.onButton)
        ButtonTone.Secondary -> ToneColors(c.secondaryButton, c.secondaryButtonDark, c.onButton)
        ButtonTone.Accent -> ToneColors(Palette.Sunset, Palette.SunsetDark, c.onButton)
        ButtonTone.Quiet -> ToneColors(Palette.Cream, Palette.Sand, c.onPanel)
    }
}

/**
 * Chunky, tactile button (spec section 5): rounded, with a darker lip and a soft shadow. On press
 * it squashes to 94% with a spring and the face drops onto the lip. At least 48 dp in both directions.
 */
@Composable
fun ChunkyButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tone: ButtonTone = ButtonTone.Primary,
    enabled: Boolean = true,
    highlighted: Boolean = false,
    contentDescription: String? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val reduced = LocalUiPreferences.current.reducedMotion
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.94f else 1f,
        animationSpec = if (reduced) tween(60) else spring(dampingRatio = 0.42f, stiffness = Spring.StiffnessMediumLow),
        label = "press",
    )
    val colors = toneColors(tone)
    val sound = LocalUiSound.current
    val alpha = if (enabled) 1f else 0.45f
    val lip = 5.dp
    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 56.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
            }
            .drawBehind {
                val r = CornerRadius(22.dp.toPx())
                val lipPx = lip.toPx()
                val drop = if (pressed) lipPx * 0.7f else 0f
                drawRoundRect(Palette.Shadow, topLeft = Offset(0f, lipPx * 1.6f), size = size, cornerRadius = r)
                drawRoundRect(colors.lip, topLeft = Offset(0f, lipPx), size = Size(size.width, size.height - lipPx), cornerRadius = r)
                drawRoundRect(colors.face, topLeft = Offset(0f, drop), size = Size(size.width, size.height - lipPx), cornerRadius = r)
                drawRoundRect(
                    Brush.verticalGradient(
                        listOf(Color.White.copy(alpha = 0.35f), Color.Transparent),
                        startY = drop,
                        endY =
                            drop + size.height * 0.5f,
                    ),
                    topLeft = Offset(0f, drop),
                    size = Size(size.width, (size.height - lipPx) * 0.55f),
                    cornerRadius = r,
                )
                if (highlighted) {
                    drawRoundRect(
                        Palette.Gold,
                        topLeft = Offset(-3.dp.toPx(), -3.dp.toPx() + drop),
                        size = Size(
                            size.width + 6.dp.toPx(),
                            size.height - lipPx + 6.dp.toPx(),
                        ),
                        cornerRadius = r,
                        style = Stroke(3.dp.toPx()),
                    )
                }
            }
            .clip(shape)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button) {
                sound(UiSound.TAP)
                onClick()
            }
            .then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier)
            .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 10.dp + lip),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = colors.content, modifier = Modifier.size(24.dp))
                if (text.isNotEmpty()) Spacer(Modifier.width(10.dp))
            }
            if (text.isNotEmpty()) {
                Text(text, style = MaterialTheme.typography.labelLarge, color = colors.content, textAlign = TextAlign.Center)
            }
        }
    }
}

/** Round icon-only variant (56 dp) for HUD buttons; [contentDescription] is mandatory. */
@Composable
fun RoundButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: ButtonTone = ButtonTone.Quiet,
    enabled: Boolean = true,
    highlighted: Boolean = false,
    size: Dp = 56.dp,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val reduced = LocalUiPreferences.current.reducedMotion
    val scale by animateFloatAsState(
        if (pressed && enabled) 0.94f else 1f,
        if (reduced) tween(60) else spring(dampingRatio = 0.42f, stiffness = Spring.StiffnessMediumLow),
        label = "press",
    )
    val colors = toneColors(tone)
    val sound = LocalUiSound.current
    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (enabled) 1f else 0.45f
            }
            .drawBehind {
                val lip = 4.dp.toPx()
                val drop = if (pressed) lip * 0.7f else 0f
                val radius = this.size.minDimension / 2f
                drawCircle(Palette.Shadow, radius, center = Offset(center.x, center.y + lip * 1.5f))
                drawCircle(colors.lip, radius - lip / 2, center = Offset(center.x, center.y + lip / 2))
                drawCircle(colors.face, radius - lip / 2, center = Offset(center.x, center.y - lip / 2 + drop))
                if (highlighted) drawCircle(Palette.Gold, radius + 2.dp.toPx(), style = Stroke(3.dp.toPx()))
            }
            .clip(CircleShape)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button) {
                sound(UiSound.TAP)
                onClick()
            }
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = colors.content, modifier = Modifier.size(size * 0.46f))
    }
}

/**
 * Frosted translucent panel. On API 31+ a [BlurEffect] (RenderEffect) softens a layer of drifting
 * colour behind the content; below that the panel is plain translucency. The 3D scene itself is a
 * SurfaceView, which RenderEffect cannot sample, so overlays that must blur the scene use window
 * blur-behind instead (see [Overlay]).
 */
@Composable
fun FrostedPanel(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(28.dp),
    padding: Dp = 20.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalHatchColors.current
    Box(
        modifier.shadow(
            14.dp,
            shape,
            ambientColor = Palette.Ocean.copy(alpha = 0.3f),
            spotColor = Palette.Ocean.copy(alpha = 0.3f),
        ).clip(shape),
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !LocalUiPreferences.current.highContrast) {
            Canvas(
                Modifier.matchParentSize().graphicsLayer {
                    renderEffect = BlurEffect(36f, 36f, TileMode.Clamp)
                },
            ) {
                drawRect(colors.panel)
                drawCircle(Palette.SeaGlass.copy(alpha = 0.22f), size.minDimension * 0.55f, Offset(size.width * 0.15f, size.height * 0.1f))
                drawCircle(Palette.Sunset.copy(alpha = 0.16f), size.minDimension * 0.5f, Offset(size.width * 0.9f, size.height * 0.95f))
            }
        } else {
            Box(Modifier.matchParentSize().background(colors.panel))
        }
        Column(
            Modifier
                .border(1.5.dp, colors.panelBorder, shape)
                .padding(padding),
            content = content,
        )
    }
}

/** Three stars; [filled] of them are gold. */
@Composable
fun StarRow(filled: Int, modifier: Modifier = Modifier, starSize: Dp = 28.dp) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(3) { i ->
            Icon(
                HatchIcons.Star,
                contentDescription = null,
                tint = if (i < filled) Palette.Gold else Palette.InkSoft.copy(alpha = 0.25f),
                modifier = Modifier.size(starSize),
            )
        }
    }
}

/**
 * The seagull counter (spec section 2): a ring that empties as moves run out, turning from teal to
 * sunset to coral. At one move left it pulses (unless reduced motion is on).
 */
@Composable
fun GullRing(movesLeft: Int, totalMoves: Int, modifier: Modifier = Modifier, size: Dp = 64.dp) {
    val reduced = LocalUiPreferences.current.reducedMotion
    val fraction = if (totalMoves <= 0) 0f else (movesLeft.toFloat() / totalMoves).coerceIn(0f, 1f)
    val animated by animateFloatAsState(fraction, tween(if (reduced) 0 else 350), label = "gull")
    val pulse = if (movesLeft <= 1 && !reduced) {
        val transition = rememberInfiniteTransition(label = "pulse")
        transition.animateFloat(1f, 1.12f, infiniteRepeatable(tween(420), RepeatMode.Reverse), label = "pulse").value
    } else {
        1f
    }
    val ringColor = when {
        movesLeft <= 1 -> Palette.Coral
        movesLeft <= 3 -> Palette.Sunset
        else -> Palette.SeaGlass
    }
    Box(
        modifier.size(size).graphicsLayer {
            scaleX = pulse
            scaleY = pulse
        },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 6.dp.toPx()
            drawCircle(Palette.Cream.copy(alpha = 0.92f), this.size.minDimension / 2f)
            drawArc(
                Palette.Ink.copy(alpha = 0.12f),
                0f,
                360f,
                false,
                style = Stroke(stroke),
                topLeft = Offset(stroke / 2, stroke / 2),
                size = Size(this.size.width - stroke, this.size.height - stroke),
            )
            drawArc(
                lerp(ringColor, Palette.Coral, 1f - animated),
                -90f,
                360f * animated,
                false,
                style = Stroke(stroke, cap = StrokeCap.Round),
                topLeft = Offset(stroke / 2, stroke / 2),
                size = Size(this.size.width - stroke, this.size.height - stroke),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(HatchIcons.Seagull, contentDescription = null, tint = Palette.Ink, modifier = Modifier.size(size * 0.3f))
            Text("$movesLeft", style = MaterialTheme.typography.titleMedium, color = Palette.Ink)
        }
    }
}

/** Small hatchling icons that fill in as each one reaches the sea. */
@Composable
fun HatchlingTally(saved: Int, total: Int, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(total.coerceAtMost(8)) { i ->
            Icon(
                HatchIcons.Hatchling,
                contentDescription = null,
                tint = if (i < saved) Palette.SeaGlassDark else Palette.Cream.copy(alpha = 0.7f),
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(6.dp))
        Text("$saved / $total", style = MaterialTheme.typography.labelMedium, color = Palette.Cream)
    }
}

/** A compact rounded chip for HUD text (level title, moves). */
@Composable
fun HudChip(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Palette.Ocean.copy(alpha = 0.55f))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
        content = content,
    )
}
