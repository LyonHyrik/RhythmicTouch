package com.lyon.rhythmictouch.config

import android.os.Bundle
import com.lyon.rhythmictouch.RhythmicConstants

data class RhythmicConfig(
    val enabled: Boolean = RhythmicConstants.DEFAULT_ENABLED,
    val intensity: Int = RhythmicConstants.DEFAULT_INTENSITY,
    val whitelistMode: Boolean = RhythmicConstants.DEFAULT_WHITELIST_MODE,
    val excludedApps: Set<String> = emptySet(),
    val logMode: Int = RhythmicConstants.DEFAULT_LOG_MODE,
    val monet: Boolean = RhythmicConstants.DEFAULT_MONET,
    val vibrationDelay: Int = RhythmicConstants.DEFAULT_VIBRATION_DELAY,
    val vibrationParams: VibrationParams = VibrationParams.defaults(),
    val profiles: List<VibrationProfile> = emptyList(),
    val activeProfileId: String = VibrationProfile.DEFAULT_ID,
    val deviceConfigs: List<DeviceVibrationConfig> = emptyList(),
    val aaudioIntervalMs: Int = RhythmicConstants.DEFAULT_AAUDIO_INTERVAL_MS,
    val syncAaudioWithAudioTrack: Boolean = RhythmicConstants.DEFAULT_SYNC_AAUDIO_WITH_AUDIOTRACK,
) {
    fun toBundle(): Bundle = Bundle().apply {
        putBoolean(RhythmicConstants.KEY_ENABLED, enabled)
        putInt(RhythmicConstants.KEY_INTENSITY, intensity)
        putBoolean(RhythmicConstants.KEY_WHITELIST_MODE, whitelistMode)
        putStringArrayList(RhythmicConstants.KEY_EXCLUDED_APPS, ArrayList(excludedApps))
        putInt(RhythmicConstants.KEY_LOG_MODE, logMode)
        putBoolean(RhythmicConstants.KEY_MONET, monet)
        putInt(RhythmicConstants.KEY_VIBRATION_DELAY, vibrationDelay)
        putString(RhythmicConstants.KEY_VIBRATION_PARAMS, vibrationParams.toJson())
        putStringArrayList("profiles_json", ArrayList(profiles.map { it.toJson() }))
        putString("active_profile_id", activeProfileId)
        putString("device_configs_json", DeviceVibrationConfig.toJson(deviceConfigs))
        putInt(RhythmicConstants.KEY_AAUDIO_INTERVAL_MS, aaudioIntervalMs)
        putBoolean(RhythmicConstants.KEY_SYNC_AAUDIO_WITH_AUDIOTRACK, syncAaudioWithAudioTrack)
    }

    companion object {
        fun fromBundle(bundle: Bundle?): RhythmicConfig {
            if (bundle == null) return RhythmicConfig()
            val profileJsons = bundle.getStringArrayList("profiles_json") ?: emptyList()
            return RhythmicConfig(
                enabled = bundle.getBoolean(RhythmicConstants.KEY_ENABLED, RhythmicConstants.DEFAULT_ENABLED),
                intensity = bundle.getInt(RhythmicConstants.KEY_INTENSITY, RhythmicConstants.DEFAULT_INTENSITY).coerceIn(0, 100),
                whitelistMode = bundle.getBoolean(RhythmicConstants.KEY_WHITELIST_MODE, RhythmicConstants.DEFAULT_WHITELIST_MODE),
                excludedApps = (bundle.getStringArrayList(RhythmicConstants.KEY_EXCLUDED_APPS) ?: emptyList()).toSet(),
                logMode = bundle.getInt(RhythmicConstants.KEY_LOG_MODE, RhythmicConstants.DEFAULT_LOG_MODE).coerceIn(0, 2),
                monet = bundle.getBoolean(RhythmicConstants.KEY_MONET, RhythmicConstants.DEFAULT_MONET),
                vibrationDelay = bundle.getInt(RhythmicConstants.KEY_VIBRATION_DELAY, RhythmicConstants.DEFAULT_VIBRATION_DELAY).coerceIn(0, 1000),
                vibrationParams = VibrationParams.fromJson(bundle.getString(RhythmicConstants.KEY_VIBRATION_PARAMS)),
                profiles = profileJsons.mapNotNull { VibrationProfile.fromJson(it) },
                activeProfileId = bundle.getString("active_profile_id", VibrationProfile.DEFAULT_ID) ?: VibrationProfile.DEFAULT_ID,
                deviceConfigs = DeviceVibrationConfig.fromJson(bundle.getString("device_configs_json")),
                aaudioIntervalMs = bundle.getInt(RhythmicConstants.KEY_AAUDIO_INTERVAL_MS, RhythmicConstants.DEFAULT_AAUDIO_INTERVAL_MS).coerceIn(33, 300),
                syncAaudioWithAudioTrack = bundle.getBoolean(RhythmicConstants.KEY_SYNC_AAUDIO_WITH_AUDIOTRACK, RhythmicConstants.DEFAULT_SYNC_AAUDIO_WITH_AUDIOTRACK),
            )
        }
    }
}