package com.hatchslide.feature.menu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.hatchslide.core.designsystem.ButtonTone
import com.hatchslide.core.designsystem.ChunkyButton
import com.hatchslide.core.designsystem.FrostedPanel
import com.hatchslide.core.designsystem.Licenses
import com.hatchslide.core.designsystem.LocalHatchColors
import com.hatchslide.core.designsystem.ScreenScaffold
import com.hatchslide.core.designsystem.SectionTitle
import com.hatchslide.core.render.SceneDirector
import com.hatchslide.core.render.stages.BeachStage

/** Privacy policy, bundled so it reads offline (the same text is published in store/privacy_policy.md). */
@Composable
fun PrivacyScreen(director: SceneDirector, onBack: () -> Unit) {
    BeachBackdrop(director, BeachStage.Mode.MENU)
    ScreenScaffold(stringResource(R.string.privacy_title), onBack) {
        FrostedPanel(Modifier.fillMaxWidth()) {
            Text(
                stringResource(R.string.privacy_body),
                style = MaterialTheme.typography.bodyLarge,
                color = LocalHatchColors.current.onPanel,
                modifier = Modifier.verticalScroll(rememberScrollState()),
            )
        }
    }
}

/** Open-source software used at run time, with licences (LICENSES.md lists build tools too). */
private val LIBRARIES = listOf(
    "Filament, gltfio, filament-utils" to "Apache License 2.0",
    "AndroidX and Jetpack Compose" to "Apache License 2.0",
    "Media3 ExoPlayer" to "Apache License 2.0",
    "Kotlin, kotlinx.coroutines, kotlinx.serialization" to "Apache License 2.0",
)

/** Credits and licences, including the bundled OFL font licence texts. */
@Composable
fun CreditsScreen(director: SceneDirector, onBack: () -> Unit) {
    BeachBackdrop(director, BeachStage.Mode.MENU)
    val context = LocalContext.current
    val colors = LocalHatchColors.current
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    ScreenScaffold(stringResource(R.string.credits_title), onBack) {
        FrostedPanel(Modifier.fillMaxWidth()) {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.credits_game), style = MaterialTheme.typography.bodyLarge, color = colors.onPanel)
                SectionTitle(stringResource(R.string.credits_fonts))
                for (font in Licenses.FONTS) {
                    Text(
                        stringResource(R.string.credits_library_line, font.name, font.licence),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onPanel,
                    )
                    ChunkyButton(stringResource(R.string.credits_view_licence), { open = font.asset }, tone = ButtonTone.Quiet)
                }
                SectionTitle(stringResource(R.string.credits_libraries))
                for ((name, licence) in LIBRARIES) {
                    Text(
                        stringResource(R.string.credits_library_line, name, licence),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onPanel,
                    )
                }
            }
        }
    }
    open?.let { asset ->
        val text = context.assets.open(asset).bufferedReader().use { it.readText() }
        AlertDialog(
            onDismissRequest = { open = null },
            confirmButton = { TextButton({ open = null }) { Text(stringResource(com.hatchslide.core.designsystem.R.string.close)) } },
            text = {
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                )
            },
        )
    }
}
