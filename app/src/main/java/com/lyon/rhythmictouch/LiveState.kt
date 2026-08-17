package com.lyon.rhythmictouch

import android.os.SystemClock
import com.lyon.rhythmictouch.systemui.SpectrumBand

object LiveState {
    @Volatile var observing: Boolean = false
    @Volatile var engineActive: Boolean = false
    @Volatile var level: Float = 0f
    @Volatile var bass: Float = 0f
    @Volatile var mid: Float = 0f
    @Volatile var treble: Float = 0f
    @Volatile var beat: Boolean = false
    @Volatile var peakBandIndex: Int = 0
    @Volatile var bands: List<SpectrumBand> = emptyList()
    @Volatile var activeApp: String? = null
    @Volatile var blocked: Boolean = false
    @Volatile var vibrationMode: String = "安静"
    @Volatile private var lastUpdateMs: Long = 0L

    fun markUpdated() {
        lastUpdateMs = SystemClock.elapsedRealtime()
    }

    fun isFresh(withinMs: Long = 3000L): Boolean =
        SystemClock.elapsedRealtime() - lastUpdateMs < withinMs
}