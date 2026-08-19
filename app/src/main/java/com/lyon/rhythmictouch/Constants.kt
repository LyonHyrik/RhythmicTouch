package com.lyon.rhythmictouch

import android.net.Uri

object RhythmicConstants {
    const val MODULE_PACKAGE = "com.lyon.rhythmictouch"
    const val SYSTEMUI_PACKAGE = "com.android.systemui"
    const val PROVIDER_AUTHORITY = "com.lyon.rhythmictouch.provider"
    const val PREF_NAME = "rhythmic_config"

    const val KEY_ENABLED = "enabled"
    const val KEY_INTENSITY = "intensity"
    const val KEY_WHITELIST_MODE = "whitelist_mode"
    const val KEY_EXCLUDED_APPS = "excluded_apps"
    const val KEY_LOG_MODE = "log_mode"
    const val KEY_MONET = "monet"
    const val KEY_VIBRATION_DELAY = "vibration_delay"
    const val KEY_AAUDIO_INTERVAL_MS = "aaudio_interval_ms"

    const val PREF_PROFILES = "rhythmic_profiles"
    const val KEY_PROFILES_JSON = "profiles_json"
    const val KEY_ACTIVE_PROFILE_ID = "active_profile_id"
    const val KEY_VIBRATION_PARAMS = "vibration_params"

    const val ACTION_REFRESH_CONFIG = "com.lyon.rhythmictouch.ACTION_REFRESH_CONFIG"
    const val ACTION_LIVE_STATUS = "com.lyon.rhythmictouch.ACTION_LIVE_STATUS"
    const val ACTION_OBSERVE_START = "com.lyon.rhythmictouch.ACTION_OBSERVE_START"
    const val ACTION_OBSERVE_STOP = "com.lyon.rhythmictouch.ACTION_OBSERVE_STOP"
    const val ACTION_TEST_VIBRATION = "com.lyon.rhythmictouch.ACTION_TEST_VIBRATION"
    const val ACTION_SYNC_AAUDIO_INTERVAL = "com.lyon.rhythmictouch.ACTION_SYNC_AAUDIO_INTERVAL"

    const val EXTRA_ACTIVE = "active"
    const val EXTRA_LEVEL = "level"
    const val EXTRA_BASS = "bass"
    const val EXTRA_MID = "mid"
    const val EXTRA_TREBLE = "treble"
    const val EXTRA_BEAT = "beat"
    const val EXTRA_ACTIVE_APP = "active_app"
    const val EXTRA_BLOCKED = "blocked"
    const val EXTRA_BANDS = "bands"
    const val EXTRA_PEAK_BAND_INDEX = "peak_band_index"
    const val EXTRA_VIBRATION_MODE = "vibration_mode"
    const val EXTRA_TEST_MODE_KEY = "test_mode_key"
    const val EXTRA_AAUDIO_INTERVAL_MS = "aaudio_interval_ms"

    const val KEY_MODULE_VERSION = "module_version_code"
    const val METHOD_SET_MODULE_VERSION = "set_module_version"
    const val METHOD_GET_MODULE_VERSION = "get_module_version"

    const val DEFAULT_ENABLED = true
    const val DEFAULT_INTENSITY = 70
    const val DEFAULT_WHITELIST_MODE = false
    const val LOG_MODE_ALL = 0
    const val LOG_MODE_VIBRATE = 1
    const val LOG_MODE_NONE = 2
    const val DEFAULT_LOG_MODE = LOG_MODE_NONE

    const val DEFAULT_MONET = true
    const val DEFAULT_VIBRATION_DELAY = 0
    const val DEFAULT_AAUDIO_INTERVAL_MS = 100
    const val KEY_SYNC_AAUDIO_WITH_AUDIOTRACK = "sync_aaudio_with_audiotrack"
    const val DEFAULT_SYNC_AAUDIO_WITH_AUDIOTRACK = true

    const val KEY_FLAT_DETECTION = "flat_detection"
    const val DEFAULT_FLAT_DETECTION = false

    const val ACTION_REQUEST_DETECTED_INTERVAL = "com.lyon.rhythmictouch.ACTION_REQUEST_DETECTED_INTERVAL"
    const val EXTRA_SYNC_ENABLED = "sync_enabled"

    const val ACTION_VIBRATOR_CALIBRATION_DONE = "com.lyon.rhythmictouch.ACTION_VIBRATOR_CALIBRATION_DONE"
    const val EXTRA_VIBRATOR_MIN_MS = "vibrator_min_ms"

    val PROVIDER_URI: Uri = Uri.parse("content://$PROVIDER_AUTHORITY")
}