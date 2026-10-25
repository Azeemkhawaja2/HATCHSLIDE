package com.hatchslide.core.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.AssetDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.ogg.OggExtractor
import com.hatchslide.core.audio.AudioCatalog.Ambience
import com.hatchslide.core.audio.AudioCatalog.Sfx
import com.hatchslide.core.audio.AudioCatalog.Stinger
import com.hatchslide.core.audio.AudioCatalog.Track
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Game audio (spec section 7). Effects and stingers play through a 12-stream [SoundPool], preloaded
 * per world; music plays through two Media3 players that crossfade over 1.5 s; ambience loops play
 * under the music and dip when it swells. The engine holds audio focus (ducking on transient loss,
 * pausing on loss) and goes silent in the background. Everything runs on the main thread.
 *
 * Opts in to Media3's unstable API for the asset-only media source and the OGG extractor.
 */
@OptIn(UnstableApi::class)
class AudioEngine(context: Context) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val gameAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_GAME)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
    private val pool = SoundPool.Builder().setMaxStreams(MAX_STREAMS).setAudioAttributes(gameAttributes).build()
    private val soundIds = HashMap<Sfx, Int>()
    private val stingerIds = HashMap<Stinger, Int>()
    private val loaded = HashSet<Int>()

    /**
     * Players read only bundled OGG assets. Building them with an asset-only source (instead of the
     * default factory) leaves Media3's HTTP data sources unreferenced, so R8 strips them from the app.
     */
    private val assetSources = ProgressiveMediaSource.Factory(
        { AssetDataSource(appContext) },
        ExtractorsFactory { arrayOf(OggExtractor()) },
    )

    private val musicPlayers = arrayOf(newPlayer(C.AUDIO_CONTENT_TYPE_MUSIC), newPlayer(C.AUDIO_CONTENT_TYPE_MUSIC))
    private var activeMusic = 0
    private var track: Track? = null
    private var envelope = ByteArray(0)
    private val musicLevel = FloatArray(2)
    private var fadeStart = 0L

    private val ambiencePlayers = arrayOf(newPlayer(C.AUDIO_CONTENT_TYPE_SONIFICATION), newPlayer(C.AUDIO_CONTENT_TYPE_SONIFICATION))
    private val ambienceLayers = arrayOfNulls<Ambience>(2)

    var musicVolume = 0.7f
        private set
    var effectsVolume = 0.9f
        private set
    private var duck = 1f
    private var stingerDuckUntil = 0L
    private var foreground = true
    private var focusLost = false
    private var released = false

    private fun newPlayer(contentType: Int): ExoPlayer = ExoPlayer.Builder(appContext, assetSources).build().apply {
        setAudioAttributes(
            androidx.media3.common.AudioAttributes.Builder().setUsage(C.USAGE_GAME).setContentType(contentType).build(),
            false,
        )
        repeatMode = Player.REPEAT_MODE_ONE
        volume = 0f
    }

    private fun load(path: String): Int = appContext.assets.openFd(path).use { pool.load(it, 1) }

    /** Loads the effects every world uses plus those of [world]'s mechanics; 0 loads everything (Daily Tide). */
    fun preloadWorld(world: Int) {
        for (sfx in Sfx.entries) {
            val needed = world == 0 || sfx.world == 0 || sfx.world == world
            val id = soundIds[sfx]
            if (needed && id == null) {
                soundIds[sfx] = load(AudioCatalog.sfxPath(sfx))
            } else if (!needed && id != null) {
                pool.unload(id)
                loaded -= id
                soundIds.remove(sfx)
            }
        }
    }

    fun setVolumes(music: Float, effects: Float) {
        musicVolume = music.coerceIn(0f, 1f)
        effectsVolume = effects.coerceIn(0f, 1f)
    }

    private fun audible(): Boolean = foreground && !focusLost && !released

    /**
     * Plays an effect panned by [pan] (-1 left to 1 right, from the board position); [rate] shifts
     * pitch (0.5..2). Returns the stream id, or 0 if it could not play.
     */
    fun play(sfx: Sfx, pan: Float = 0f, volume: Float = 1f, rate: Float = 1f, loop: Boolean = false): Int {
        if (!audible() || effectsVolume <= 0f) return 0
        val id = soundIds[sfx] ?: return 0
        if (id !in loaded) return 0
        val angle = (pan.coerceIn(-1f, 1f) + 1f) * (PI / 4).toFloat()
        val v = volume * effectsVolume * duck
        return pool.play(id, v * cos(angle) * 1.41f, v * sin(angle) * 1.41f, 1, if (loop) -1 else 0, rate.coerceIn(0.5f, 2f))
    }

    fun setRate(stream: Int, rate: Float) {
        if (stream != 0) pool.setRate(stream, rate.coerceIn(0.5f, 2f))
    }

    fun stop(stream: Int) {
        if (stream != 0) pool.stop(stream)
    }

    /** Win or lose stinger; the music dips under it. */
    fun playStinger(stinger: Stinger) {
        if (!audible()) return
        val id = stingerIds[stinger] ?: return
        if (id !in loaded) return
        val v = effectsVolume.coerceAtLeast(musicVolume) * duck
        pool.play(id, v, v, 2, 0, 1f)
        stingerDuckUntil = System.currentTimeMillis() + if (stinger == Stinger.VICTORY) 3200 else 2200
    }

    /** Crossfades to [next] over 1.5 s; null fades the music out. */
    fun playMusic(next: Track?) {
        if (next == track) return
        track = next
        activeMusic = 1 - activeMusic
        fadeStart = System.currentTimeMillis()
        val player = musicPlayers[activeMusic]
        if (next == null) {
            player.stop()
            envelope = ByteArray(0)
            return
        }
        envelope = runCatching { appContext.assets.open(AudioCatalog.envelopePath(next)).use { it.readBytes() } }.getOrDefault(ByteArray(0))
        player.setMediaItem(MediaItem.fromUri("asset:///${AudioCatalog.trackPath(next)}"))
        player.volume = 0f
        player.prepare()
        if (audible()) player.play()
    }

    /** Ambience bed: up to two loops (surf plus one of wind, insects or rumble). */
    fun setAmbience(vararg layers: Ambience) {
        for (i in ambiencePlayers.indices) {
            val layer = layers.getOrNull(i)
            if (ambienceLayers[i] == layer) continue
            ambienceLayers[i] = layer
            val player = ambiencePlayers[i]
            if (layer == null) {
                player.stop()
            } else {
                player.setMediaItem(MediaItem.fromUri("asset:///${AudioCatalog.ambiencePath(layer)}"))
                player.prepare()
                if (audible()) player.play()
            }
        }
    }

    /** Volume and fade bookkeeping at 20 Hz: crossfades, stinger dips and ambience ducking. */
    private val ticker = object : Runnable {
        override fun run() {
            if (released) return
            val now = System.currentTimeMillis()
            val fade = min(1f, (now - fadeStart) / CROSSFADE_MS.toFloat())
            val dip = if (now < stingerDuckUntil) 0.3f else 1f
            for (i in musicPlayers.indices) {
                val target = if (i == activeMusic && track != null) 1f else 0f
                musicLevel[i] = if (target > 0f) fade.coerceAtLeast(musicLevel[i]) else min(musicLevel[i], 1f - fade)
                musicPlayers[i].volume = musicLevel[i] * musicVolume * duck * dip
                if (target == 0f && musicLevel[i] <= 0f && musicPlayers[i].isPlaying) musicPlayers[i].pause()
            }
            // Ambience dips by up to a third when the music swells.
            val swell = envelopeAt(musicPlayers[activeMusic].currentPosition) * (if (track != null) musicVolume else 0f)
            for ((i, player) in ambiencePlayers.withIndex()) {
                val base = if (i == 0) 0.55f else 0.4f
                player.volume = base * effectsVolume * duck * (1f - 0.35f * swell)
            }
            handler.postDelayed(this, 50)
        }
    }

    private fun envelopeAt(positionMs: Long): Float {
        if (envelope.isEmpty()) return 0f
        val index = ((positionMs / AudioCatalog.ENVELOPE_STEP_MS) % envelope.size).toInt()
        return (envelope[index].toInt() and 255) / 255f
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                focusLost = true
                pauseAll()
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> duck = DUCKED

            AudioManager.AUDIOFOCUS_GAIN -> {
                duck = 1f
                if (focusLost) {
                    focusLost = false
                    resumeAll()
                }
            }
        }
    }

    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(gameAttributes)
        .setOnAudioFocusChangeListener(focusListener, handler)
        .setWillPauseWhenDucked(false)
        .build()

    init {
        pool.setOnLoadCompleteListener { _, sampleId, status -> if (status == 0) loaded += sampleId }
        for (stinger in Stinger.entries) stingerIds[stinger] = load(AudioCatalog.stingerPath(stinger))
        preloadWorld(0)
        handler.post(ticker)
    }

    /** The app came to the foreground: take audio focus and resume. */
    fun onForeground() {
        foreground = true
        if (audioManager?.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) focusLost = false
        resumeAll()
    }

    /** The app went to the background: everything stops (spec section 7). */
    fun onBackground() {
        foreground = false
        pauseAll()
        audioManager?.abandonAudioFocusRequest(focusRequest)
    }

    private fun pauseAll() {
        pool.autoPause()
        musicPlayers.forEach { it.pause() }
        ambiencePlayers.forEach { it.pause() }
    }

    private fun resumeAll() {
        if (!audible()) return
        pool.autoResume()
        if (track != null) musicPlayers[activeMusic].play()
        for (i in ambiencePlayers.indices) if (ambienceLayers[i] != null) ambiencePlayers[i].play()
    }

    fun release() {
        released = true
        handler.removeCallbacks(ticker)
        audioManager?.abandonAudioFocusRequest(focusRequest)
        musicPlayers.forEach { it.release() }
        ambiencePlayers.forEach { it.release() }
        pool.release()
    }

    companion object {
        const val MAX_STREAMS = 12
        const val CROSSFADE_MS = 1500L
        private const val DUCKED = 0.25f
    }
}
