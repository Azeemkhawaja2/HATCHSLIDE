package com.hatchslide.app

import androidx.compose.runtime.mutableStateOf

/**
 * Hooks for capturing store art (spec section 13). Nothing in the app changes them: only the
 * debug-only capture test (src/androidTest, StoreCaptureTest) does, so in play they keep their defaults.
 */
internal object CaptureHooks {
    /** Hides every screen's Compose UI over the 3D view (feature graphic). */
    val uiHidden = mutableStateOf(false)
}
