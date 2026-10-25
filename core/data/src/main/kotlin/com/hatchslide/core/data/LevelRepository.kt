package com.hatchslide.core.data

import android.content.res.AssetManager
import com.hatchslide.core.game.Level
import com.hatchslide.core.game.LevelCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Static facts about a world, shared by the map, level select and unlock rules. */
data class WorldInfo(val number: Int, val nameKey: String, val levelCount: Int = 30)

/**
 * Loads the bundled puzzles (`assets/levels/world_<n>.json`, `assets/levels/daily.json`) on the IO
 * dispatcher and caches them. Levels are validated by the solver at build time.
 */
class LevelRepository(private val assets: AssetManager) {
    private val mutex = Mutex()
    private val worlds = HashMap<Int, List<Level>>()
    private var daily: List<Level>? = null

    suspend fun world(number: Int): List<Level> = mutex.withLock {
        worlds[number] ?: withContext(Dispatchers.IO) {
            LevelCodec.parseWorld(assets.open("levels/world_$number.json").bufferedReader().use { it.readText() })
        }.also { worlds[number] = it }
    }

    suspend fun level(id: String): Level? {
        if (id.startsWith("daily_")) return daily().firstOrNull { it.id == id }
        val world = id.removePrefix("w").substringBefore('_').toIntOrNull() ?: return null
        return world(world).firstOrNull { it.id == id }
    }

    suspend fun daily(): List<Level> = mutex.withLock {
        daily ?: withContext(Dispatchers.IO) {
            LevelCodec.parseDaily(assets.open("levels/daily.json").bufferedReader().use { it.readText() })
        }.also { daily = it }
    }

    /** The Daily Tide puzzle for a 1-based day of the year (1..366). */
    suspend fun dailyFor(dayOfYear: Int): Level {
        val all = daily()
        return all[(dayOfYear - 1).coerceIn(0, all.size - 1)]
    }

    companion object {
        const val WORLD_COUNT = 5
        val worldInfo = listOf(
            WorldInfo(1, "world_1_name"),
            WorldInfo(2, "world_2_name"),
            WorldInfo(3, "world_3_name"),
            WorldInfo(4, "world_4_name"),
            WorldInfo(5, "world_5_name"),
        )
    }
}
