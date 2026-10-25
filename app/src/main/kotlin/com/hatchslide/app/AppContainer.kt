package com.hatchslide.app

import android.content.Context
import com.hatchslide.core.data.LevelRepository
import com.hatchslide.core.data.PlayerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Manual dependency injection: app-wide singletons, created once in [HatchslideApp]. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    /** Outlives screens so saves finish even while navigating away. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val levels = LevelRepository(appContext.assets)
    val player = PlayerRepository(appContext, scope)
}
