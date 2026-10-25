package com.hatchslide.app

import com.hatchslide.core.audio.AudioCatalog.Ambience
import com.hatchslide.core.audio.AudioCatalog.Sfx
import com.hatchslide.core.audio.AudioCatalog.Stinger
import com.hatchslide.core.audio.AudioCatalog.Track
import com.hatchslide.core.audio.AudioEngine
import com.hatchslide.core.designsystem.UiSound
import com.hatchslide.core.game.Level
import com.hatchslide.core.game.MoveEvent
import com.hatchslide.core.game.StopCause
import com.hatchslide.core.game.TileType
import com.hatchslide.core.render.board.BoardCue
import com.hatchslide.feature.game.GameFeedback
import com.hatchslide.feature.game.UnlockCard
import kotlin.math.pow

/**
 * Sound for a level (spec section 7): every board event, animation cue and HUD action mapped to an
 * effect, panned by where it happens on the board. The sand-slide loop's pitch follows slide length.
 */
class GameAudio(private val audio: AudioEngine, private val level: Level) : GameFeedback {
    private var slideStream = 0
    private val hasCoconuts = level.board.coconutStarts.isNotEmpty()

    private fun pan(worldX: Float) = (worldX / 4.5f).coerceIn(-1f, 1f)

    override fun onBoardEvent(event: MoveEvent, worldX: Float) {
        val pan = pan(worldX)
        when (event) {
            is MoveEvent.Stopped -> when (event.cause) {
                StopCause.WET_SAND -> audio.play(Sfx.WET_SQUISH, pan)

                StopCause.BLOCKER -> {
                    val hit = level.board.step(event.cell, event.direction)
                    val tile = if (hit >= 0) level.board.tile(hit) else TileType.ROCK
                    if (tile == TileType.SHELL ||
                        tile == TileType.CRACKED_SHELL
                    ) {
                        audio.play(Sfx.SHELL_CLACK, pan)
                    } else {
                        audio.play(Sfx.STOP, pan)
                    }
                }

                StopCause.CRAB, StopCause.EGG -> audio.play(Sfx.STOP, pan)

                StopCause.PIECE, StopCause.EDGE, StopCause.CONFLICT -> audio.play(Sfx.STOP, pan, volume = 0.5f)

                StopCause.LOOP, StopCause.TICK_CAP -> Unit
            }

            is MoveEvent.Saved -> {
                audio.play(Sfx.SAVE_SPLASH, pan)
                // Each extra hatchling in the same move rings a major third higher.
                audio.play(Sfx.SAVE_CHIME, pan, rate = 2f.pow(event.order * 4f / 12f))
            }

            is MoveEvent.CoconutSank -> audio.play(Sfx.COCONUT_PLOP, pan)

            is MoveEvent.Redirected -> audio.play(Sfx.CURRENT, pan, volume = 0.7f)

            is MoveEvent.Teleported -> {
                audio.play(Sfx.POOL_SHIMMER, pan, volume = 0.7f)
                audio.play(Sfx.TELEPORT_POP, pan)
            }

            is MoveEvent.FellInPit -> audio.play(Sfx.PIT_FALL, pan)

            is MoveEvent.PitFilled -> audio.play(Sfx.PIT_FILL, pan)

            is MoveEvent.ShellCracked -> audio.play(Sfx.SHELL_CRACK, pan)

            is MoveEvent.Hatched -> {
                audio.play(Sfx.EGG_CRACK, pan)
                audio.play(Sfx.HATCH_CHIRP, pan, volume = 0.8f)
            }

            is MoveEvent.EggWobble -> audio.play(Sfx.EGG_WOBBLE, pan, volume = 0.7f)

            is MoveEvent.CrabStepped -> audio.play(Sfx.CRAB_CLICK, pan, volume = 0.7f)

            is MoveEvent.CrabWaited -> audio.play(Sfx.CRAB_CLICK, pan, volume = 0.35f)

            is MoveEvent.GullShadowMoved -> audio.play(Sfx.GULL_SWOOP, pan, volume = 0.35f)
        }
    }

    override fun onCue(cue: BoardCue, worldX: Float, value: Float) {
        val pan = pan(worldX)
        when (cue) {
            BoardCue.SLIDE_START -> {
                audio.stop(slideStream)
                slideStream = audio.play(Sfx.SLIDE_LOOP, volume = 0.35f, rate = 0.85f + 0.035f * value.coerceAtMost(10f), loop = true)
                if (hasCoconuts) audio.play(Sfx.COCONUT_ROLL, volume = 0.5f)
            }

            BoardCue.SLIDE_END -> {
                audio.stop(slideStream)
                slideStream = 0
            }

            BoardCue.EGG_CRACK -> audio.play(Sfx.EGG_CRACK, pan, volume = 0.7f)

            BoardCue.HATCH -> audio.play(Sfx.HATCH_CHIRP, pan, volume = 0.7f)

            BoardCue.SWIM -> audio.play(Sfx.SWIM_PADDLE, pan, volume = 0.6f)

            BoardCue.GULL_SWOOP -> audio.play(Sfx.GULL_SWOOP)

            BoardCue.TUCK -> audio.play(Sfx.TUCK)

            BoardCue.GULL_LAND -> audio.play(Sfx.GULL_SQUAWK, pan)
        }
    }

    override fun onSwipe() {
        audio.play(Sfx.SWIPE, volume = 0.55f)
    }

    override fun onNoOp() {
        audio.play(Sfx.BUMP)
    }

    override fun onUndo() {
        audio.play(Sfx.UNDO, volume = 0.7f)
    }

    override fun onRestart() {
        audio.play(Sfx.RESTART, volume = 0.7f)
    }

    override fun onHint() {
        audio.play(Sfx.HINT_SPARKLE)
    }

    override fun onStar(index: Int) {
        audio.play(listOf(Sfx.STAR_1, Sfx.STAR_2, Sfx.STAR_3)[(index - 1).coerceIn(0, 2)])
    }

    override fun onUnlockCard(card: UnlockCard) {
        audio.play(if (card.kind == UnlockCard.Kind.ACHIEVEMENT) Sfx.ACHIEVEMENT else Sfx.UNLOCK_FANFARE)
    }

    override fun onEnded(won: Boolean) {
        audio.stop(slideStream)
        audio.playStinger(if (won) Stinger.VICTORY else Stinger.FAIL)
    }

    override fun onLastMove() {
        audio.play(Sfx.GULL_CALL, volume = 0.8f)
    }
}

/** Interface sounds requested by the design-system components. */
fun AudioEngine.playUi(sound: UiSound) {
    when (sound) {
        UiSound.TAP -> play(Sfx.UI_TAP, volume = 0.5f)
        UiSound.TOGGLE -> play(Sfx.UI_TOGGLE, volume = 0.5f)
        UiSound.PAGE -> play(Sfx.PAGE_SWIPE, volume = 0.6f)
        UiSound.OVERLAY -> play(Sfx.OVERLAY_OPEN, volume = 0.5f)
    }
}

/** Music and ambience for a world's beach (0 = the title beach used by every menu screen). */
fun AudioEngine.enterWorld(world: Int) {
    playMusic(Track.forWorld(world))
    when (world) {
        3 -> setAmbience(Ambience.SURF, Ambience.INSECTS)
        5 -> setAmbience(Ambience.SURF, Ambience.RUMBLE)
        2 -> setAmbience(Ambience.SURF)
        else -> setAmbience(Ambience.SURF, Ambience.WIND)
    }
}
