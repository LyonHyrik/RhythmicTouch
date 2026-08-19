package com.lyon.rhythmictouch.config

import android.content.Context
import com.lyon.rhythmictouch.RhythmicConstants

class ConfigStore(context: Context) {
    private val prefs = context.getSharedPreferences(RhythmicConstants.PREF_NAME, Context.MODE_PRIVATE)

    fun read(): RhythmicConfig = RhythmicConfig(
        enabled = prefs.getBoolean(RhythmicConstants.KEY_ENABLED, RhythmicConstants.DEFAULT_ENABLED),
        intensity = prefs.getInt(RhythmicConstants.KEY_INTENSITY, RhythmicConstants.DEFAULT_INTENSITY).coerceIn(0, 100),
        whitelistMode = prefs.getBoolean(RhythmicConstants.KEY_WHITELIST_MODE, RhythmicConstants.DEFAULT_WHITELIST_MODE),
        excludedApps = prefs.getStringSet(RhythmicConstants.KEY_EXCLUDED_APPS, emptySet()) ?: emptySet(),
        logMode = prefs.getInt(RhythmicConstants.KEY_LOG_MODE, RhythmicConstants.DEFAULT_LOG_MODE).coerceIn(0, 2),
        monet = prefs.getBoolean(RhythmicConstants.KEY_MONET, RhythmicConstants.DEFAULT_MONET),
        vibrationDelay = prefs.getInt(RhythmicConstants.KEY_VIBRATION_DELAY, RhythmicConstants.DEFAULT_VIBRATION_DELAY).coerceIn(0, 1000),
        aaudioIntervalMs = prefs.getInt(RhythmicConstants.KEY_AAUDIO_INTERVAL_MS, RhythmicConstants.DEFAULT_AAUDIO_INTERVAL_MS).coerceIn(33, 300),
        syncAaudioWithAudioTrack = prefs.getBoolean(RhythmicConstants.KEY_SYNC_AAUDIO_WITH_AUDIOTRACK, RhythmicConstants.DEFAULT_SYNC_AAUDIO_WITH_AUDIOTRACK),
        quietPeriods = QuietPeriod.fromJsonList(prefs.getString("quiet_periods_json", null)),
        flatDetection = prefs.getBoolean(RhythmicConstants.KEY_FLAT_DETECTION, RhythmicConstants.DEFAULT_FLAT_DETECTION),
    )

    fun write(config: RhythmicConfig) {
        prefs.edit()
            .putBoolean(RhythmicConstants.KEY_ENABLED, config.enabled)
            .putInt(RhythmicConstants.KEY_INTENSITY, config.intensity)
            .putBoolean(RhythmicConstants.KEY_WHITELIST_MODE, config.whitelistMode)
            .putStringSet(RhythmicConstants.KEY_EXCLUDED_APPS, config.excludedApps)
            .putInt(RhythmicConstants.KEY_LOG_MODE, config.logMode)
            .putBoolean(RhythmicConstants.KEY_MONET, config.monet)
            .putInt(RhythmicConstants.KEY_VIBRATION_DELAY, config.vibrationDelay)
            .putInt(RhythmicConstants.KEY_AAUDIO_INTERVAL_MS, config.aaudioIntervalMs)
            .putBoolean(RhythmicConstants.KEY_SYNC_AAUDIO_WITH_AUDIOTRACK, config.syncAaudioWithAudioTrack)
            .putString("quiet_periods_json", QuietPeriod.toJsonList(config.quietPeriods))
            .putBoolean(RhythmicConstants.KEY_FLAT_DETECTION, config.flatDetection)
            .apply()
    }
}