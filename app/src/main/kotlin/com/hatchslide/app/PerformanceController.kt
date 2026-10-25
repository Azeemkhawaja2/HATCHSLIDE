package com.hatchslide.app

import android.content.Context
import android.os.Build
import android.os.PerformanceHintManager
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import androidx.annotation.RequiresApi
import com.hatchslide.core.render.FrameObserver
import com.hatchslide.core.render.RenderEngine
import com.hatchslide.core.render.quality.ThermalStepper
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Android Dynamic Performance Framework (spec section 11), with no permission needed.
 * - A PerformanceHintManager session (API 31+) for the frame-loop thread and Filament's driver
 *   thread reports every frame's work against the frame budget, so the system raises clocks before
 *   a frame runs late and lowers them when there is slack.
 * - While in the foreground, thermal headroom (API 30+, forecast 10 s ahead) and thermal status are
 *   read every 2 s; [thermalSteps] lowers the quality tier before the device throttles and raises
 *   it again once it has cooled ([ThermalStepper]).
 */
class PerformanceController(private val context: Context, private val scope: CoroutineScope) : FrameObserver {
    private var engine: RenderEngine? = null
    private val hints: Hints? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Hints(context) else null
    private val stepper = ThermalStepper()
    private val steps = MutableStateFlow(0)
    private var polling: Job? = null

    /** Tiers to step down for heat (0 to 2). */
    val thermalSteps: StateFlow<Int> = steps.asStateFlow()

    fun attach(engine: RenderEngine) {
        this.engine = engine
        engine.frameObservers += this
    }

    fun detach() {
        engine?.frameObservers?.remove(this)
        engine = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) hints?.close()
    }

    fun onForeground() {
        if (polling?.isActive == true) return
        val power = context.getSystemService(PowerManager::class.java) ?: return
        polling = scope.launch(Dispatchers.Default) {
            while (isActive) {
                val headroom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) power.getThermalHeadroom(FORECAST_SECONDS) else Float.NaN
                val status = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) power.currentThermalStatus else 0
                steps.value = stepper.onSample(headroom, status, SystemClock.elapsedRealtime())
                delay(POLL_MS)
            }
        }
    }

    fun onBackground() {
        polling?.cancel()
        polling = null
    }

    override fun onFrame(intervalNanos: Long, workNanos: Long) {
        val e = engine ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) hints?.report(workNanos, e.frameBudgetNanos())
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private class Hints(context: Context) {
        private val manager = context.getSystemService(PerformanceHintManager::class.java)
        private var session: PerformanceHintManager.Session? = null
        private var target = 0L
        private var failed = false

        fun report(workNanos: Long, budgetNanos: Long) {
            if (failed || workNanos <= 0L) return
            val s = session ?: open(budgetNanos) ?: return
            if (budgetNanos != target) {
                target = budgetNanos
                s.updateTargetWorkDuration(budgetNanos)
            }
            s.reportActualWorkDuration(workNanos)
        }

        /** Created on the first frame, from the frame-loop thread, once Filament's threads exist. */
        private fun open(budgetNanos: Long): PerformanceHintManager.Session? {
            val tids = (intArrayOf(Process.myTid()) + filamentThreads()).distinct().toIntArray()
            val created = runCatching { manager?.createHintSession(tids, budgetNanos) }.getOrNull()
                ?: runCatching { manager?.createHintSession(intArrayOf(Process.myTid()), budgetNanos) }.getOrNull()
            if (created == null) failed = true
            session = created
            target = budgetNanos
            return created
        }

        fun close() {
            session?.close()
            session = null
        }

        /** Filament's driver thread, found by name among this process's threads. */
        private fun filamentThreads(): IntArray {
            val tasks = File("/proc/self/task").listFiles() ?: return IntArray(0)
            return tasks.mapNotNull { task ->
                val name = runCatching { File(task, "comm").readText().trim() }.getOrDefault("")
                if (name.startsWith("FEngine::loop")) task.name.toIntOrNull() else null
            }.toIntArray()
        }
    }

    private companion object {
        const val FORECAST_SECONDS = 10
        const val POLL_MS = 2_000L
    }
}
