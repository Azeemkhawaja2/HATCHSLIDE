package com.hatchslide.app

import android.content.Context
import com.hatchslide.core.data.LevelRepository

/** Manual dependency injection: app-wide singletons, created once in [HatchslideApp]. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val levels = LevelRepository(appContext.assets)
}
