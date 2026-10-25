package com.hatchslide.core.render

import android.util.Log

/**
 * Timing log for debug builds (spec section 11: swipe to first movement, world switch load). The app
 * turns it on only when it is debuggable; release builds never format or write a message.
 */
object PerfLog {
    @Volatile
    var enabled = false

    fun since(label: String, startNanos: Long) {
        if (enabled) Log.d("HatchslidePerf", "$label: ${(System.nanoTime() - startNanos) / 1_000_000} ms")
    }
}
