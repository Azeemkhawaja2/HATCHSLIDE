package com.hatchslide.core.designsystem

import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider

/**
 * Full-screen overlay (pause, level complete, level failed, tips). On API 31+ the overlay window
 * blurs everything behind it, including the 3D SurfaceView, when the device supports cross-window
 * blur; otherwise a translucent ocean scrim is used. Back calls [onBack].
 */
@Composable
fun Overlay(onBack: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    Dialog(
        onDismissRequest = onBack,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnClickOutside = false),
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        var blurred = false
        if (window != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val wm = window.context.getSystemService(WindowManager::class.java)
            blurred = wm?.isCrossWindowBlurEnabled == true
        }
        DisposableEffect(window, blurred) {
            if (window != null) {
                window.setDimAmount(if (blurred) 0.15f else 0.35f)
                if (blurred && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                    window.attributes = window.attributes.apply { blurBehindRadius = 56 }
                }
            }
            onDispose { }
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(if (blurred) Palette.Ocean.copy(alpha = 0.12f) else Palette.Ocean.copy(alpha = 0.45f))
                .safeDrawingPadding(),
            contentAlignment = Alignment.Center,
            content = content,
        )
    }
}
