package com.lyon.rhythmictouch.systemui

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import com.lyon.rhythmictouch.RhythmicConstants
import com.lyon.rhythmictouch.config.RhythmicConfig
import com.lyon.rhythmictouch.config.VibrationParams
import com.lyon.rhythmictouch.config.VibrationProfile

class ConfigBridge(context: Context) {
    private val resolver = context.contentResolver
    private val cachePrefs = context.getSharedPreferences("rt_systemui_cache", Context.MODE_PRIVATE)

    @Volatile
    var config: RhythmicConfig = readCached()
        private set

    private var lastRefreshMs = 0L

    fun refresh(force: Boolean = false): RhythmicConfig {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastRefreshMs < 1000) return config
        lastRefreshMs = now
        val bundle: Bundle? = try {
            resolver.call(RhythmicConstants.PROVIDER_URI, "get_config", null, null)
        } catch (t: Throwable) {
            null
        }
        if (bundle != null) {
            config = RhythmicConfig.fromBundle(bundle)
            writeCache(config)
        }
        return config
    }

    private fun readCached(): RhythmicConfig = RhythmicConfig(
        enabled = cachePrefs.getBoolean(RhythmicConstants.KEY_ENABLED, RhythmicConstants.DEFAULT_ENABLED),
        intensity = cachePrefs.getInt(RhythmicConstants.KEY_INTENSITY, RhythmicConstants.DEFAULT_INTENSITY).coerceIn(0, 100),
        whitelistMode = cachePrefs.getBoolean(RhythmicConstants.KEY_WHITELIST_MODE, RhythmicConstants.DEFAULT_WHITELIST_MODE),
        excludedApps = cachePrefs.getStringSet(RhythmicConstants.KEY_EXCLUDED_APPS, emptySet())?.toSet() ?: emptySet(),
        logMode = cachePrefs.getInt(RhythmicConstants.KEY_LOG_MODE, RhythmicConstants.DEFAULT_LOG_MODE).coerceIn(0, 2),
        monet = cachePrefs.getBoolean(RhythmicConstants.KEY_MONET, RhythmicConstants.DEFAULT_MONET),
        vibrationDelay = cachePrefs.getInt(RhythmicConstants.KEY_VIBRATION_DELAY, RhythmicConstants.DEFAULT_VIBRATION_DELAY).coerceIn(0, 1000),
        vibrationParams = VibrationParams.fromJson(cachePrefs.getString(RhythmicConstants.KEY_VIBRATION_PARAMS, null)),
        profiles = cachePrefs.getStringSet("profiles_json", emptySet())?.mapNotNull { VibrationProfile.fromJson(it) } ?: emptyList(),
        activeProfileId = cachePrefs.getString("active_profile_id", VibrationProfile.DEFAULT_ID) ?: VibrationProfile.DEFAULT_ID,
    )

    private fun writeCache(config: RhythmicConfig) {
        try {
            cachePrefs.edit()
                .putBoolean(RhythmicConstants.KEY_ENABLED, config.enabled)
                .putInt(RhythmicConstants.KEY_INTENSITY, config.intensity)
                .putBoolean(RhythmicConstants.KEY_WHITELIST_MODE, config.whitelistMode)
                .putStringSet(RhythmicConstants.KEY_EXCLUDED_APPS, config.excludedApps)
                .putInt(RhythmicConstants.KEY_LOG_MODE, config.logMode)
                .putBoolean(RhythmicConstants.KEY_MONET, config.monet)
                .putInt(RhythmicConstants.KEY_VIBRATION_DELAY, config.vibrationDelay)
                .putString(RhythmicConstants.KEY_VIBRATION_PARAMS, config.vibrationParams.toJson())
                .putStringSet("profiles_json", config.profiles.map { it.toJson() }.toSet())
                .putString("active_profile_id", config.activeProfileId)
                .apply()
        } catch (_: Throwable) {}
    }
}
