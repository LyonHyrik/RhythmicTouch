package com.lyon.rhythmictouch.systemui

import android.util.Log
import com.lyon.rhythmictouch.RhythmicConstants

object RhythmicLog {

    @Volatile
    var mode: Int = RhythmicConstants.DEFAULT_LOG_MODE

    @Volatile
    var vibrationFrame: Boolean = false

    private fun allowed(): Boolean = when (mode) {
        RhythmicConstants.LOG_MODE_NONE -> false
        RhythmicConstants.LOG_MODE_VIBRATE -> vibrationFrame
        else -> true
    }

    fun d(tag: String, msg: String) {
        if (allowed()) Log.d(tag, msg)
    }

    fun x(tag: String, msg: String) {
        if (allowed()) {
            try {
                de.robv.android.xposed.XposedBridge.log("[$tag] $msg")
            } catch (_: Throwable) {
                Log.d(tag, msg)
            }
        }
    }
}
