package com.hatchslide.app

import android.view.SurfaceView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.hatchslide.core.data.ProgressRules
import com.hatchslide.core.designsystem.HatchslideTheme
import com.hatchslide.core.designsystem.UiPreferences
import com.hatchslide.core.game.Level
import com.hatchslide.core.render.QualityTier
import com.hatchslide.core.render.SceneDirector
import com.hatchslide.core.render.WorldTheme
import com.hatchslide.feature.game.GameNavigator
import com.hatchslide.feature.game.GameScreen
import com.hatchslide.feature.game.GameScreenConfig
import com.hatchslide.feature.game.GameViewModel
import com.hatchslide.feature.game.UnlockCard
import com.hatchslide.feature.journal.JournalScreen
import com.hatchslide.feature.map.LevelSelectScreen
import com.hatchslide.feature.map.WorldMapScreen
import com.hatchslide.feature.menu.AchievementsScreen
import com.hatchslide.feature.menu.CreditsScreen
import com.hatchslide.feature.menu.DailyScreen
import com.hatchslide.feature.menu.MainMenuScreen
import com.hatchslide.feature.menu.PrivacyScreen
import com.hatchslide.feature.menu.SettingsScreen
import com.hatchslide.feature.menu.TitleScreen
import java.time.LocalDate

/**
 * The Filament surface sits behind every screen; Compose draws the UI on top (spec section 5,
 * navigation flow). Back pops one screen; on the main menu it leaves the app; in gameplay it pauses.
 */
@Composable
fun HatchslideRoot(container: AppContainer, director: SceneDirector, autoTier: QualityTier) {
    val settings by container.player.settings.collectAsStateWithLifecycle()
    val progress by container.player.progress.collectAsStateWithLifecycle()
    val preferences = UiPreferences(
        reducedMotion = settings.reducedMotion,
        highContrast = settings.highContrast,
        leftHanded = settings.leftHanded,
        colorBlindTiles = settings.colorBlindTiles,
        haptics = settings.haptics,
    )
    LaunchedEffect(settings.quality, settings.frameRate, settings.reducedMotion) {
        director.applySettings(qualityTier(settings.quality, autoTier), frameRateMode(settings.frameRate), settings.reducedMotion)
    }
    HatchslideTheme(preferences) {
        Box(Modifier.fillMaxSize()) {
            AndroidView(
                factory = { context -> SurfaceView(context).also { director.engine.attach(it) } },
                modifier = Modifier.fillMaxSize(),
            )
            val nav = rememberNavController()
            NavHost(nav, startDestination = TitleRoute) {
                composable<TitleRoute> {
                    TitleScreen(director) { nav.navigate(MenuRoute) { popUpTo<TitleRoute> { inclusive = true } } }
                }
                composable<MenuRoute> {
                    MainMenuScreen(
                        director,
                        progress,
                        today(),
                        onPlay = { nav.navigate(MapRoute()) },
                        onDaily = { nav.navigate(DailyRoute) },
                        onJournal = { nav.navigate(JournalRoute) },
                        onAchievements = { nav.navigate(AchievementsRoute) },
                        onSettings = { nav.navigate(SettingsRoute) },
                    )
                }
                composable<MapRoute> { entry ->
                    val route = entry.toRoute<MapRoute>()
                    val focus = if (route.world >
                        0
                    ) {
                        route.world
                    } else {
                        (1..ProgressRules.WORLDS).last { ProgressRules.isWorldUnlocked(progress, it) }
                    }
                    WorldMapScreen(director, progress, focus, onWorld = { nav.navigate(LevelsRoute(it)) }, onBack = { nav.popBackStack() })
                }
                composable<LevelsRoute> { entry ->
                    val world = entry.toRoute<LevelsRoute>().world
                    val levels by produceState<List<Level>?>(null, world) { value = container.levels.world(world) }
                    LevelSelectScreen(
                        director,
                        world,
                        progress,
                        levels,
                        onLevel = { nav.navigate(GameRoute(it)) },
                        onBack = {
                            if (!nav.popBackStack()) nav.navigate(MapRoute(world))
                        },
                    )
                }
                composable<GameRoute> { entry -> GameDestination(entry.toRoute(), container, director, nav, settings, progress) }
                composable<DailyRoute> {
                    val day = today()
                    val puzzle by produceState<Level?>(null, day) { value = container.levels.dailyForEpochDay(day) }
                    DailyScreen(director, progress, day, puzzle, onPlay = {
                        puzzle?.let { nav.navigate(GameRoute(it.id, day)) }
                    }, onBack = { nav.popBackStack() })
                }
                composable<JournalRoute> {
                    JournalScreen(director, progress, onSelectSkin = container.player::selectSkin, onBack = { nav.popBackStack() })
                }
                composable<AchievementsRoute> { AchievementsScreen(director, progress) { nav.popBackStack() } }
                composable<SettingsRoute> {
                    SettingsScreen(
                        director,
                        settings,
                        onChange = container.player::updateSettings,
                        onResetProgress = container.player::resetProgress,
                        onPrivacy = { nav.navigate(PrivacyRoute) },
                        onCredits = { nav.navigate(CreditsRoute) },
                        onBack = { nav.popBackStack() },
                    )
                }
                composable<CreditsRoute> { CreditsScreen(director) { nav.popBackStack() } }
                composable<PrivacyRoute> { PrivacyScreen(director) { nav.popBackStack() } }
            }
        }
    }
}

private fun today(): Long = LocalDate.now().toEpochDay()

@Composable
private fun GameDestination(
    route: GameRoute,
    container: AppContainer,
    director: SceneDirector,
    nav: NavHostController,
    settings: com.hatchslide.core.data.Settings,
    progress: com.hatchslide.core.data.Progress,
) {
    val level by produceState<Level?>(null, route.levelId) { value = container.levels.level(route.levelId) }
    val loaded = level ?: return
    val context = LocalContext.current
    var unlockCards by remember(loaded.id) { mutableStateOf<List<UnlockCard>>(emptyList()) }
    val daily = route.dailyDay != NO_DAY
    val sink = remember(loaded.id, route.dailyDay) {
        ProgressSink(context, container.player, container.scope, route.dailyDay.takeIf { daily }) { unlockCards = it }
    }
    // The stored run is read once, when the level opens.
    val storedRun = remember(loaded.id) { progress.run?.takeIf { it.levelId == loaded.id }?.snapshot }
    val viewModel: GameViewModel = viewModel(
        key = "${loaded.id}@${route.dailyDay}",
        factory = viewModelFactory { initializer { GameViewModel(loaded, createSavedStateHandle(), sink, storedRun) } },
    )
    fun backToLevels() {
        if (daily) {
            nav.popBackStack(DailyRoute, inclusive = false)
        } else if (!nav.popBackStack(LevelsRoute(loaded.world), inclusive = false)) {
            nav.navigate(LevelsRoute(loaded.world)) { popUpTo<GameRoute> { inclusive = true } }
        }
    }
    GameScreen(
        viewModel = viewModel,
        director = director,
        config = GameScreenConfig(
            WorldTheme.forWorld(themeWorld(loaded)),
            skin = progress.skin,
            arrowPad = settings.arrowPad,
            leftHanded = settings.leftHanded,
            haptics = settings.haptics,
            tip = loaded.tip?.takeIf { it !in progress.tipsSeen },
            tutorialHand = loaded.world == 1 && loaded.index <= 3 && progress.tutorialMoves < 3,
        ),
        navigator = object : GameNavigator {
            override fun next(): Boolean {
                val next = if (daily) null else nextLevelId(loaded, progress)
                if (next == null) {
                    backToLevels()
                    return false
                }
                nav.navigate(GameRoute(next)) { popUpTo<GameRoute> { inclusive = true } }
                return true
            }

            override fun levels() = backToLevels()

            override fun home() {
                nav.popBackStack(MenuRoute, inclusive = false)
            }

            override fun settings() = nav.navigate(SettingsRoute)

            override fun tipSeen(tip: String) = container.player.markTipSeen(tip)

            override fun tutorialMove() = container.player.countTutorialMove()
        },
        feedback = null,
        unlockCards = unlockCards,
        onStarShown = {},
    )
}
