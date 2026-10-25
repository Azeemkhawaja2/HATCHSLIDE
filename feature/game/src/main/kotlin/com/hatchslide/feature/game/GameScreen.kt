package com.hatchslide.feature.game

import android.os.Build
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowSizeClass
import com.hatchslide.core.designsystem.ButtonTone
import com.hatchslide.core.designsystem.ChunkyButton
import com.hatchslide.core.designsystem.GullRing
import com.hatchslide.core.designsystem.HatchIcons
import com.hatchslide.core.designsystem.HatchlingTally
import com.hatchslide.core.designsystem.HudChip
import com.hatchslide.core.designsystem.LocalUiPreferences
import com.hatchslide.core.designsystem.Palette
import com.hatchslide.core.designsystem.RoundButton
import com.hatchslide.core.game.Direction
import com.hatchslide.core.game.GameStatus
import com.hatchslide.core.game.MoveEvent
import com.hatchslide.core.render.SceneDirector
import com.hatchslide.core.render.WorldTheme
import com.hatchslide.core.render.board.BoardCue
import com.hatchslide.core.render.board.BoardCueListener
import com.hatchslide.core.render.board.BoardEventListener
import com.hatchslide.core.render.camera.FreeViewport
import kotlinx.coroutines.delay

/** Gameplay options from Settings and progress. */
data class GameScreenConfig(
    val theme: WorldTheme,
    val skin: Int,
    val arrowPad: Boolean,
    val leftHanded: Boolean,
    val haptics: Boolean,
    /** Tip card to show before play (first time an intro level is played), as a string resource name. */
    val tip: String?,
    val tutorialHand: Boolean,
)

/** Things the game screen asks the app to do. */
interface GameNavigator {
    fun next(): Boolean

    fun levels()

    fun home()

    fun settings()

    fun tipSeen(tip: String)

    /** Counts successful tutorial moves; the hand disappears after three. */
    fun tutorialMove()
}

/** Reactions to play for the app's audio layer (spec section 7); haptics are handled here. */
interface GameFeedback {
    fun onBoardEvent(event: MoveEvent, worldX: Float)

    fun onCue(cue: BoardCue, worldX: Float, value: Float) {}

    fun onSwipe() {}

    fun onNoOp() {}

    fun onUndo() {}

    fun onRestart() {}

    fun onHint() {}

    fun onStar(index: Int) {}

    fun onUnlockCard(card: UnlockCard) {}

    fun onEnded(won: Boolean) {}

    /** Called when one move is left: the gull calls. */
    fun onLastMove() {}
}

/** Player actions wrapped with their feedback. */
private class GameActions(val undo: () -> Unit, val restart: () -> Unit, val hint: () -> Unit)

@Composable
fun GameScreen(
    viewModel: GameViewModel,
    director: SceneDirector,
    config: GameScreenConfig,
    navigator: GameNavigator,
    feedback: GameFeedback?,
    unlockCards: List<UnlockCard>,
    onStarShown: (Int) -> Unit,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val prefs = LocalUiPreferences.current
    val view = LocalView.current
    var paused by rememberSaveable { mutableStateOf(false) }
    var tipOpen by rememberSaveable(config.tip) { mutableStateOf(config.tip != null) }
    var rootSize by remember { mutableStateOf(IntSize.Zero) }
    var topBar by remember { mutableStateOf(Rect.Zero) }
    var bottomBar by remember { mutableStateOf(Rect.Zero) }
    var leftColumn by remember { mutableStateOf(Rect.Zero) }
    var rightColumn by remember { mutableStateOf(Rect.Zero) }
    val focus = remember { FocusRequester() }
    val sizeClass = currentWindowAdaptiveInfoV2().windowSizeClass
    val sideColumns = rootSize.width > rootSize.height && sizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)

    BindBoard(viewModel, director, config.theme, config.skin, prefs.reducedMotion)
    ImmersivePlay()
    LaunchedEffect(config.tutorialHand) { viewModel.tutorialActive = config.tutorialHand }
    val moveHaptics = remember(view) { MoveHaptics(view) }
    LaunchedEffect(feedback, config.haptics) {
        director.board.listener = BoardEventListener { event, worldX, _ ->
            if (config.haptics) moveHaptics.on(event)
            feedback?.onBoardEvent(event, worldX)
        }
        director.board.cueListener = BoardCueListener { cue, worldX, value -> feedback?.onCue(cue, worldX, value) }
    }
    val actions = remember(viewModel, feedback) {
        GameActions(
            undo = {
                feedback?.onUndo()
                viewModel.undo()
            },
            restart = {
                feedback?.onRestart()
                viewModel.restart()
            },
            hint = {
                feedback?.onHint()
                viewModel.hint()
            },
        )
    }
    LaunchedEffect(ui.ended, ui.state.status) {
        if (ui.ended && ui.state.status != GameStatus.PLAYING) feedback?.onEnded(ui.state.status == GameStatus.WON)
    }
    LaunchedEffect(ui.movesLeft) {
        if (ui.movesLeft == 1 && ui.state.status == GameStatus.PLAYING) feedback?.onLastMove()
    }
    BackHandler(enabled = !paused && !ui.ended) { paused = true }
    LaunchedEffect(Unit) { focus.requestFocus() }

    // Auto-frame the board between the HUD bars (or columns) with an 8% margin.
    LaunchedEffect(rootSize, topBar, bottomBar, leftColumn, rightColumn, sideColumns) {
        if (rootSize.width == 0) return@LaunchedEffect
        val viewport = if (sideColumns) {
            FreeViewport(
                rootSize.width,
                rootSize.height,
                left = leftColumn.right.toInt(),
                right = (rootSize.width - rightColumn.left).toInt().coerceAtLeast(0),
            )
        } else {
            FreeViewport(
                rootSize.width,
                rootSize.height,
                top = topBar.bottom.toInt(),
                bottom = (rootSize.height - bottomBar.top).toInt().coerceAtLeast(0),
            )
        }
        director.board.setFreeViewport(viewport)
    }

    val boardDescription = stringResource(R.string.game_board)
    val swipe: (Direction) -> Unit = { direction ->
        if (!paused && !tipOpen && ui.state.status == GameStatus.PLAYING) {
            if (config.haptics) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            feedback?.onSwipe()
            val before = viewModel.ui.value.state.movesUsed
            viewModel.swipe(direction)
            val after = viewModel.ui.value
            if (after.lastMove?.noOp == true) {
                if (config.haptics) rejectHaptic(view)
                feedback?.onNoOp()
            }
            if (config.tutorialHand && after.state.movesUsed > before) navigator.tutorialMove()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { rootSize = it }
            .focusRequester(focus)
            .focusable()
            .boardKeys(swipe, actions.undo, actions.restart, actions.hint)
            .boardSwipes(swipe)
            .semantics { contentDescription = boardDescription },
    ) {
        Announcer(ui)
        val hud = hudContent(ui, actions, onPause = { paused = true })
        if (sideColumns) {
            Row(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                val (first, second) = if (config.leftHanded) hud.controls to hud.status else hud.status to hud.controls
                Column(
                    Modifier.fillMaxHeight().width(200.dp).onGloballyPositioned {
                        leftColumn = it.boundsInRoot()
                    },
                    verticalArrangement = Arrangement.SpaceBetween,
                ) { first() }
                Column(
                    Modifier.fillMaxHeight().width(200.dp).onGloballyPositioned {
                        rightColumn = it.boundsInRoot()
                    },
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.End,
                ) { second() }
            }
        } else {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
                Column(Modifier.fillMaxWidth().onGloballyPositioned { topBar = it.boundsInRoot() }) { hud.top(config.leftHanded) }
                Spacer(Modifier.weight(1f))
                Column(Modifier.fillMaxWidth().onGloballyPositioned { bottomBar = it.boundsInRoot() }) { hud.bottom() }
            }
        }
        if (config.arrowPad) {
            ArrowPad(
                onSwipe = swipe,
                modifier = Modifier
                    .align(if (config.leftHanded) Alignment.BottomStart else Alignment.BottomEnd)
                    .safeDrawingPadding()
                    .padding(bottom = if (sideColumns) 16.dp else 150.dp, start = 16.dp, end = 16.dp),
            )
        }
        ui.tutorialDirection?.let { TutorialHand(it, Modifier.align(Alignment.Center)) }
        HintBanner(ui.hint, Modifier.align(Alignment.Center).padding(24.dp))
    }

    if (tipOpen && config.tip != null) {
        TipOverlay(config.tip) {
            tipOpen = false
            navigator.tipSeen(config.tip)
        }
    }
    if (paused) {
        PauseOverlay(
            onResume = { paused = false },
            onRestart = {
                paused = false
                actions.restart()
            },
            onLevels = navigator::levels,
            onSettings = navigator::settings,
            onHome = navigator::home,
        )
    }
    if (ui.ended && ui.state.status == GameStatus.WON) {
        CompleteOverlay(
            ui,
            unlockCards,
            onStarShown = {
                feedback?.onStar(it)
                onStarShown(it)
            },
            onCardShown = { feedback?.onUnlockCard(it) },
            onNext = { navigator.next() },
            onReplay = actions.restart,
            onLevels = navigator::levels,
        )
    } else if (ui.ended && ui.state.status.isLost) {
        // Let the gull swoop and land (spec section 2) before the panel covers the board.
        var showFailed by remember(ui.state.movesUsed) { mutableStateOf(prefs.reducedMotion) }
        LaunchedEffect(ui.state.movesUsed) {
            delay(FAILED_DELAY_MS)
            showFailed = true
        }
        if (showFailed) {
            FailedOverlay(ui.state.status, ui.canUndo, onUndo = actions.undo, onRetry = actions.restart, onLevels = navigator::levels)
        }
    }
}

private const val FAILED_DELAY_MS = 1900L

private class Hud(
    val top: @Composable (leftHanded: Boolean) -> Unit,
    val bottom: @Composable () -> Unit,
    val status: @Composable () -> Unit,
    val controls: @Composable () -> Unit,
)

@Composable
private fun hudContent(ui: GameUiState, actions: GameActions, onPause: () -> Unit): Hud {
    val title = if (ui.level.world == 0) {
        stringResource(R.string.game_daily_title, ui.level.name)
    } else {
        stringResource(R.string.game_level_title, ui.level.world, ui.level.index)
    }
    val movesLeftText = pluralStringResource(R.plurals.game_moves_left, ui.movesLeft, ui.movesLeft)
    val savedText = pluralStringResource(R.plurals.game_saved_tally, ui.totalHatchlings, ui.saved, ui.totalHatchlings)
    val pause: @Composable () -> Unit = {
        RoundButton(HatchIcons.Pause, stringResource(R.string.game_pause), onPause)
    }
    val gull: @Composable () -> Unit = {
        GullRing(ui.movesLeft, ui.level.gullMoves, Modifier.semantics { contentDescription = movesLeftText })
    }
    val titleChip: @Composable () -> Unit = {
        HudChip { Text(title, style = MaterialTheme.typography.titleMedium, color = Palette.Cream, textAlign = TextAlign.Center) }
    }
    val tally: @Composable () -> Unit = {
        HatchlingTally(ui.saved, ui.totalHatchlings, Modifier.semantics(mergeDescendants = true) { contentDescription = savedText })
    }
    val moves: @Composable () -> Unit = {
        HudChip {
            Text(
                stringResource(R.string.game_moves_par, ui.state.movesUsed, ui.level.par),
                style = MaterialTheme.typography.labelMedium,
                color = Palette.Cream,
            )
        }
    }
    val buttons: @Composable () -> Unit = {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ChunkyButton(
                stringResource(R.string.game_undo),
                actions.undo,
                icon = HatchIcons.Undo,
                tone = ButtonTone.Secondary,
                enabled = ui.canUndo,
                highlighted = ui.hint == HintUi.Unsolvable,
            )
            RoundButton(HatchIcons.Restart, stringResource(R.string.game_restart), actions.restart, tone = ButtonTone.Quiet)
            ChunkyButton(stringResource(R.string.game_hint), actions.hint, icon = HatchIcons.Hint, tone = ButtonTone.Accent)
        }
    }
    return Hud(
        top = { leftHanded ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (leftHanded) gull() else pause()
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { titleChip() }
                if (leftHanded) pause() else gull()
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { tally() }
        },
        bottom = {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { moves() }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { buttons() }
        },
        status = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    pause()
                    Spacer(Modifier.width(10.dp))
                    gull()
                }
                titleChip()
                tally()
            }
        },
        controls = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.End) {
                moves()
                ChunkyButton(
                    stringResource(R.string.game_undo),
                    actions.undo,
                    icon = HatchIcons.Undo,
                    tone = ButtonTone.Secondary,
                    enabled = ui.canUndo,
                    highlighted =
                        ui.hint == HintUi.Unsolvable,
                )
                RoundButton(HatchIcons.Restart, stringResource(R.string.game_restart), actions.restart)
                ChunkyButton(stringResource(R.string.game_hint), actions.hint, icon = HatchIcons.Hint, tone = ButtonTone.Accent)
            }
        },
    )
}

/** TalkBack: announces each move result as a polite live region. */
@Composable
private fun Announcer(ui: GameUiState) {
    val move = ui.lastMove ?: return
    val direction = stringResource(directionName(move.direction))
    val text = if (move.noOp) {
        stringResource(R.string.announce_swiped, direction) + " " + stringResource(R.string.announce_nothing_moved)
    } else {
        stringResource(R.string.announce_swiped, direction) + " " +
            pluralStringResource(R.plurals.announce_saved, move.savedTotal, move.savedTotal) + " " +
            pluralStringResource(R.plurals.announce_moves_left, move.movesLeft, move.movesLeft)
    }
    Box(
        Modifier.size(1.dp).semantics {
            liveRegion = LiveRegionMode.Polite
            contentDescription = text
        },
    )
}

fun directionName(direction: Direction): Int = when (direction) {
    Direction.UP -> R.string.direction_up
    Direction.DOWN -> R.string.direction_down
    Direction.LEFT -> R.string.direction_left
    Direction.RIGHT -> R.string.direction_right
}

@Composable
private fun HintBanner(hint: HintUi, modifier: Modifier) {
    val text = when (hint) {
        HintUi.Thinking -> stringResource(R.string.game_hint_thinking)
        HintUi.Unsolvable -> stringResource(R.string.game_hint_unsolvable)
        is HintUi.Arrow -> stringResource(R.string.game_hint_arrow, stringResource(directionName(hint.direction)))
        HintUi.None -> null
    }
    AnimatedVisibility(text != null, modifier, enter = fadeIn(), exit = fadeOut()) {
        HudChip(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
            Text(text.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = Palette.Cream, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun ArrowPad(onSwipe: (Direction) -> Unit, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        RoundButton(HatchIcons.Play, stringResource(R.string.arrow_up), { onSwipe(Direction.UP) }, Modifier.rotated(-90f), size = 52.dp)
        Row {
            RoundButton(HatchIcons.Play, stringResource(R.string.arrow_left), {
                onSwipe(Direction.LEFT)
            }, Modifier.rotated(180f), size = 52.dp)
            Spacer(Modifier.width(52.dp))
            RoundButton(HatchIcons.Play, stringResource(R.string.arrow_right), { onSwipe(Direction.RIGHT) }, size = 52.dp)
        }
        RoundButton(HatchIcons.Play, stringResource(R.string.arrow_down), { onSwipe(Direction.DOWN) }, Modifier.rotated(90f), size = 52.dp)
    }
}

private fun Modifier.rotated(degrees: Float): Modifier = graphicsLayer { rotationZ = degrees }

/**
 * Haptics without the VIBRATE permission (spec section 7): View.performHapticFeedback only. Every
 * stop gives GESTURE_END, but pieces stopping within [MERGE_MS] of each other share one pulse.
 */
internal class MoveHaptics(private val view: View) {
    private var lastStop = 0L

    fun on(event: MoveEvent) {
        val api30 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        val constant = when (event) {
            is MoveEvent.Saved -> if (api30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.KEYBOARD_TAP

            is MoveEvent.Stopped -> {
                val now = SystemClock.uptimeMillis()
                if (now - lastStop < MERGE_MS) return
                lastStop = now
                if (api30) HapticFeedbackConstants.GESTURE_END else HapticFeedbackConstants.CLOCK_TICK
            }

            else -> return
        }
        view.performHapticFeedback(constant)
    }

    private companion object {
        const val MERGE_MS = 60L
    }
}

/** No-op swipe: REJECT haptic. */
internal fun rejectHaptic(view: View) {
    view.performHapticFeedback(
        if (Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.R
        ) {
            HapticFeedbackConstants.REJECT
        } else {
            HapticFeedbackConstants.LONG_PRESS
        },
    )
}
