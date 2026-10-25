package com.hatchslide.core.data

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** DataStore serializer for a kotlinx-serializable document stored as JSON. */
internal class JsonDocument<T>(private val serializer: KSerializer<T>, override val defaultValue: T) : Serializer<T> {
    override suspend fun readFrom(input: InputStream): T = try {
        json.decodeFromString(serializer, input.readBytes().decodeToString())
    } catch (e: SerializationException) {
        throw CorruptionException("unreadable save file", e)
    } catch (e: IllegalArgumentException) {
        throw CorruptionException("unreadable save file", e)
    }

    override suspend fun writeTo(t: T, output: OutputStream) {
        output.write(json.encodeToString(serializer, t).encodeToByteArray())
    }

    companion object {
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }
    }
}

/** What a win changed, for the unlock cards on the level-complete screen. */
data class WinOutcome(val achievements: List<Achievement>, val species: List<Int>, val worldUnlocked: Int?)

/**
 * The player's saved data (spec section 9: DataStore): progress, stats, achievements, Daily Tide
 * streak and the in-progress run in `progress.json`; preferences in `settings.json`. The app never
 * sends them anywhere; Android's own Auto Backup copies them (see res/xml/data_extraction_rules.xml).
 */
class PlayerRepository(context: Context, private val scope: CoroutineScope) {
    private val appContext = context.applicationContext

    private val progressStore: DataStore<Progress> = DataStoreFactory.create(
        serializer = JsonDocument(Progress.serializer(), Progress()),
        corruptionHandler = ReplaceFileCorruptionHandler { Progress() },
        scope = scope,
        produceFile = { appContext.dataStoreFile("progress.json") },
    )
    private val settingsStore: DataStore<Settings> = DataStoreFactory.create(
        serializer = JsonDocument(Settings.serializer(), Settings()),
        corruptionHandler = ReplaceFileCorruptionHandler { Settings() },
        scope = scope,
        produceFile = { appContext.dataStoreFile("settings.json") },
    )

    val progress: StateFlow<Progress> = progressStore.data.stateIn(scope, SharingStarted.Eagerly, Progress())
    val settings: StateFlow<Settings> = settingsStore.data.stateIn(scope, SharingStarted.Eagerly, Settings())

    private val _loaded = MutableStateFlow(false)

    /** True once both documents have been read (the splash screen waits for it). */
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    init {
        scope.launch {
            progressStore.data.first()
            settingsStore.data.first()
            _loaded.value = true
        }
    }

    fun updateProgress(transform: (Progress) -> Progress) {
        scope.launch { progressStore.updateData(transform) }
    }

    fun updateSettings(transform: (Settings) -> Settings) {
        scope.launch { settingsStore.updateData(transform) }
    }

    fun recordMove(move: MoveStats) = updateProgress { ProgressRules.recordMove(it, move) }

    fun recordHint() = updateProgress(ProgressRules::recordHint)

    fun addPlaytime(seconds: Long) {
        if (seconds > 0) updateProgress { it.copy(stats = it.stats.copy(playSeconds = it.stats.playSeconds + seconds)) }
    }

    fun saveRun(levelId: String, snapshot: String) = updateProgress { it.copy(run = RunSave(levelId, snapshot)) }

    fun clearRun(levelId: String) = updateProgress { if (it.run?.levelId == levelId) it.copy(run = null) else it }

    fun markTipSeen(tip: String) = updateProgress { it.copy(tipsSeen = it.tipsSeen + tip) }

    fun countTutorialMove() = updateProgress { it.copy(tutorialMoves = it.tutorialMoves + 1) }

    fun selectSkin(species: Int) = updateProgress { if (ProgressRules.isSpeciesUnlocked(it, species)) it.copy(skin = species) else it }

    fun resetProgress() = updateProgress { Progress() }

    /** Records a win and stamps new achievements; [today] is the epoch day for a Daily Tide win. */
    suspend fun recordWin(win: WinReport, today: Long?, now: Long): WinOutcome {
        var outcome = WinOutcome(emptyList(), emptyList(), null)
        progressStore.updateData { before ->
            val won = ProgressRules.recordWin(before, win, today)
            val (withAchievements, achievements) = ProgressRules.awardAchievements(won, now)
            val (after, species) = ProgressRules.announceSpecies(withAchievements)
            val world = (2..ProgressRules.WORLDS).firstOrNull {
                !ProgressRules.isWorldUnlocked(before, it) && ProgressRules.isWorldUnlocked(after, it)
            }
            outcome = WinOutcome(achievements, species, world)
            after
        }
        return outcome
    }

    /** Stamps achievements earned outside a win (moves, stats). */
    fun awardAchievements(now: Long) = updateProgress { ProgressRules.awardAchievements(it, now).first }
}
