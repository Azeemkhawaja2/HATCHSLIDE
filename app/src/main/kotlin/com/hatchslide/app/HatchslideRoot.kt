package com.hatchslide.app

import android.view.SurfaceView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.hatchslide.core.designsystem.HatchslideTheme
import com.hatchslide.core.game.Level
import com.hatchslide.core.render.SceneDirector
import com.hatchslide.core.render.WorldTheme
import com.hatchslide.feature.game.GameNavigator
import com.hatchslide.feature.game.GameScreen
import com.hatchslide.feature.game.GameScreenConfig
import com.hatchslide.feature.game.GameViewModel
import kotlinx.serialization.Serializable

@Serializable
data class GameRoute(val levelId: String)

/** The Filament surface sits behind every screen; Compose draws the UI on top. */
@Composable
fun HatchslideRoot(container: AppContainer, director: SceneDirector) {
    HatchslideTheme {
        Box(Modifier.fillMaxSize()) {
            AndroidView(
                factory = { context -> SurfaceView(context).also { director.engine.attach(it) } },
                modifier = Modifier.fillMaxSize(),
            )
            val nav = rememberNavController()
            NavHost(nav, startDestination = GameRoute("w1_l01")) {
                composable<GameRoute> { entry ->
                    val route = entry.toRoute<GameRoute>()
                    val level by produceState<Level?>(null, route.levelId) { value = container.levels.level(route.levelId) }
                    level?.let { loaded ->
                        val viewModel: GameViewModel = viewModel(
                            key = loaded.id,
                            factory = viewModelFactory { initializer { GameViewModel(loaded, createSavedStateHandle(), null) } },
                        )
                        GameScreen(
                            viewModel = viewModel,
                            director = director,
                            config = GameScreenConfig(
                                WorldTheme.forWorld(loaded.world),
                                skin = 0,
                                arrowPad = false,
                                leftHanded = false,
                                haptics = true,
                                tip = loaded.tip,
                                tutorialHand =
                                    loaded.index <= 3 && loaded.world == 1,
                            ),
                            navigator = object : GameNavigator {
                                override fun next(): Boolean {
                                    val next = "w${loaded.world}_l%02d".format(loaded.index + 1)
                                    if (loaded.index >= 30) return false
                                    nav.navigate(GameRoute(next)) { popUpTo<GameRoute> { inclusive = true } }
                                    return true
                                }

                                override fun levels() = Unit

                                override fun home() = Unit

                                override fun settings() = Unit

                                override fun tipSeen(tip: String) = Unit

                                override fun tutorialMove() = Unit
                            },
                            feedback = null,
                            unlockCards = emptyList(),
                            onStarShown = {},
                        )
                    }
                }
            }
        }
    }
}
