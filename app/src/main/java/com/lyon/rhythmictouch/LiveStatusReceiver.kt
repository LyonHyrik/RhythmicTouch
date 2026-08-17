package com.lyon.rhythmictouch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lyon.rhythmictouch.systemui.BeatAnalyzer
import com.lyon.rhythmictouch.systemui.SpectrumBand

class LiveStatusReceiver : BroadcastReceiver() {

    private companion object {
        val ANALYZER = BeatAnalyzer()
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != RhythmicConstants.ACTION_LIVE_STATUS) return
        LiveState.engineActive = intent.getBooleanExtra(RhythmicConstants.EXTRA_ACTIVE, LiveState.engineActive)
        LiveState.level = intent.getFloatExtra(RhythmicConstants.EXTRA_LEVEL, 0f)
        LiveState.bass = intent.getFloatExtra(RhythmicConstants.EXTRA_BASS, 0f)
        LiveState.mid = intent.getFloatExtra(RhythmicConstants.EXTRA_MID, 0f)
        LiveState.treble = intent.getFloatExtra(RhythmicConstants.EXTRA_TREBLE, 0f)
        LiveState.beat = intent.getBooleanExtra(RhythmicConstants.EXTRA_BEAT, false)
        LiveState.activeApp = intent.getStringExtra(RhythmicConstants.EXTRA_ACTIVE_APP)
        LiveState.blocked = intent.getBooleanExtra(RhythmicConstants.EXTRA_BLOCKED, false)
        
        val bandsArray = intent.getFloatArrayExtra(RhythmicConstants.EXTRA_BANDS)
        if (bandsArray != null) {
            val analyzer = ANALYZER
            LiveState.bands = bandsArray.mapIndexed { idx, value ->
                val (start, end) = analyzer.getBandFrequencyRange(idx)
                SpectrumBand(index = idx, value = value, freqStart = start, freqEnd = end)
            }
            LiveState.peakBandIndex = intent.getIntExtra(RhythmicConstants.EXTRA_PEAK_BAND_INDEX, 0)
        }
        
        LiveState.vibrationMode = intent.getStringExtra(RhythmicConstants.EXTRA_VIBRATION_MODE) ?: "安静"
        
        LiveState.markUpdated()
    }
}