package com.hatchslide.core.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * A menu screen over the live 3D scene: a back button and title in the safe area, then the content
 * on a frosted panel no wider than a comfortable reading width, centred on large screens.
 */
@Composable
fun ScreenScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    maxWidth: Int = 640,
    actions: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            RoundButton(HatchIcons.Back, stringResource(R.string.back), onBack)
            Spacer(Modifier.width(12.dp))
            Text(
                title,
                style = MaterialTheme.typography.headlineMedium,
                color = Palette.Cream,
                modifier = Modifier.weight(1f).semantics { heading() },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            actions()
        }
        Spacer(Modifier.size(12.dp))
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = maxWidth.dp).fillMaxWidth(), content = content)
        }
    }
}

/** A section heading inside a panel. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        color = LocalHatchColors.current.onPanel,
        modifier = modifier.padding(top = 8.dp, bottom = 4.dp).semantics { heading() },
    )
}

/** A labelled on/off switch row; the whole row toggles, at least 56 dp tall. */
@Composable
fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, description: String? = null) {
    val colors = LocalHatchColors.current
    val sound = LocalUiSound.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(16.dp))
            .toggleable(checked, role = Role.Switch) {
                sound(UiSound.TOGGLE)
                onChange(it)
            }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleMedium, color = colors.onPanel)
            if (description != null) Text(description, style = MaterialTheme.typography.bodySmall, color = colors.onPanelSoft)
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(checkedTrackColor = colors.secondaryButton, checkedThumbColor = Color.White),
        )
    }
}

/** A labelled 0..1 slider with its percentage announced. */
@Composable
fun SliderRow(label: String, value: Float, onChange: (Float) -> Unit) {
    val colors = LocalHatchColors.current
    val percent = (value * 100).toInt()
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.titleMedium, color = colors.onPanel, modifier = Modifier.weight(1f))
            Text("$percent%", style = MaterialTheme.typography.labelMedium, color = colors.onPanelSoft)
        }
        Slider(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.semantics {
                contentDescription = label
                stateDescription = "$percent%"
            },
            colors = SliderDefaults.colors(thumbColor = colors.primaryButton, activeTrackColor = colors.primaryButton),
        )
    }
}

/** A row of mutually exclusive choices (graphics quality, frame rate). */
@Composable
fun <T> ChoiceRow(label: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    val colors = LocalHatchColors.current
    val sound = LocalUiSound.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = colors.onPanel)
        Spacer(Modifier.size(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((value, text) in options) {
                val isSelected = value == selected
                Box(
                    Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .selectable(isSelected, role = Role.RadioButton) {
                            sound(UiSound.TOGGLE)
                            onSelect(value)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(Modifier.matchParentSize()) {
                        drawRoundRect(
                            if (isSelected) colors.secondaryButton else Palette.Sand.copy(alpha = 0.6f),
                            cornerRadius = CornerRadius(14.dp.toPx()),
                        )
                    }
                    Text(
                        text,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isSelected) Color.White else colors.onPanel,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

/**
 * Shell-shaped level button (spec section 5, level select): a scallop with the level number,
 * its stars underneath and a lock when it cannot be played.
 */
@Composable
fun ShellButton(number: Int, stars: Int, locked: Boolean, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val sound = LocalUiSound.current
    val face = when {
        locked -> Palette.Sand.copy(alpha = 0.55f)
        stars == 3 -> Palette.Gold
        stars > 0 -> Palette.Coral
        else -> Palette.Cream
    }
    Column(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .clickable(enabled = !locked, role = Role.Button) {
                sound(UiSound.TAP)
                onClick()
            }
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1.05f), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val shell = Path().apply {
                    moveTo(w * 0.5f, h * 0.96f)
                    cubicTo(w * 0.05f, h * 0.7f, w * 0.0f, h * 0.25f, w * 0.18f, h * 0.12f)
                    cubicTo(w * 0.35f, h * 0.0f, w * 0.65f, h * 0.0f, w * 0.82f, h * 0.12f)
                    cubicTo(w * 1.0f, h * 0.25f, w * 0.95f, h * 0.7f, w * 0.5f, h * 0.96f)
                    close()
                }
                drawPath(shell, Palette.Shadow, alpha = 0.6f)
                translate(top = -h * 0.03f) {
                    drawPath(shell, face)
                    for (k in 1..4) {
                        val x = w * (0.18f + k * 0.128f)
                        drawLine(Palette.InkSoft.copy(alpha = 0.18f), Offset(w * 0.5f, h * 0.9f), Offset(x, h * 0.12f), 2.dp.toPx())
                    }
                    drawPath(shell, Color.White.copy(alpha = 0.55f), style = Stroke(2.dp.toPx()))
                }
            }
            if (locked) {
                Icon(HatchIcons.Lock, contentDescription = null, tint = Palette.InkSoft, modifier = Modifier.size(26.dp))
            } else {
                Text("$number", style = MaterialTheme.typography.titleLarge, color = Palette.Ink)
            }
        }
        StarRow(stars, starSize = 14.dp)
    }
}

/** A big number with a caption, for the stats grid. */
@Composable
fun StatTile(value: String, label: String, modifier: Modifier = Modifier) {
    val colors = LocalHatchColors.current
    Column(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .padding(10.dp)
            .semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, style = MaterialTheme.typography.headlineMedium, color = colors.onPanel, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelMedium, color = colors.onPanelSoft, textAlign = TextAlign.Center)
    }
}
