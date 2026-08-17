package com.lyon.rhythmictouch.config

import org.json.JSONArray
import org.json.JSONObject

private val DEFAULT_MODES_MAP: Map<String, ModeVibration> = linkedMapOf(
    "heavyLong" to ModeVibration(85, 150, bandStart = 0, bandEnd = 7),
    "heavyShort" to ModeVibration(85, 75, bandStart = 8, bandEnd = 18),
    "midTap" to ModeVibration(55, 60, bandStart = 8, bandEnd = 18),
    "mediumHit" to ModeVibration(65, 55, bandStart = 0, bandEnd = 31),
    "risingTap" to ModeVibration(40, 42, bandStart = 0, bandEnd = 31),
    "longPulse" to ModeVibration(65, 150, bandStart = 0, bandEnd = 7),
    "emotionPulse" to ModeVibration(48, 90, bandStart = 0, bandEnd = 31),
    "softTick" to ModeVibration(28, 25, bandStart = 0, bandEnd = 31),
)

fun defaultModes(): Map<String, ModeVibration> = DEFAULT_MODES_MAP

data class ModeVibration(
    val ampPercent: Int,
    val durMs: Int,
    val bandStart: Int = 0,
    val bandEnd: Int = 31,
    val activeBands: List<Int>? = null,
)

data class VibrationParams(
    val modes: Map<String, ModeVibration> = defaultModes(),
) {
    fun ampOf(key: String): Int = modes[key]?.ampPercent ?: DEFAULT_MODES[key]!!.ampPercent
    fun durOf(key: String): Int = modes[key]?.durMs ?: DEFAULT_MODES[key]!!.durMs

    fun withAmp(key: String, amp: Int): VibrationParams =
        copy(modes = modes + (key to (modes[key] ?: DEFAULT_MODES[key]!!).copy(ampPercent = amp)))

    fun withDur(key: String, dur: Int): VibrationParams =
        copy(modes = modes + (key to (modes[key] ?: DEFAULT_MODES[key]!!).copy(durMs = dur)))

    fun bandStartOf(key: String): Int = modes[key]?.bandStart ?: 0
    fun bandEndOf(key: String): Int = modes[key]?.bandEnd ?: 31
    fun activeBandsOf(key: String): List<Int>? = modes[key]?.activeBands

    fun withBandRange(key: String, start: Int, end: Int): VibrationParams =
        copy(modes = modes + (key to (modes[key] ?: DEFAULT_MODES[key]!!).copy(bandStart = start.coerceIn(0, 31), bandEnd = end.coerceIn(0, 31))))

    fun withActiveBands(key: String, bands: List<Int>?): VibrationParams =
        copy(modes = modes + (key to (modes[key] ?: DEFAULT_MODES[key]!!).copy(activeBands = bands)))

    fun toJson(): String {
        val obj = JSONObject()
        for ((key, mv) in modes) {
            val modeObj = JSONObject()
                .put("amp", mv.ampPercent)
                .put("dur", mv.durMs)
                .put("bandStart", mv.bandStart)
                .put("bandEnd", mv.bandEnd)
            if (mv.activeBands != null) {
                modeObj.put("activeBands", JSONArray(mv.activeBands))
            }
            obj.put(key, modeObj)
        }
        return obj.toString()
    }

    companion object {
        const val KEY_HEAVY_LONG = "heavyLong"
        const val KEY_HEAVY_SHORT = "heavyShort"
        const val KEY_MID_TAP = "midTap"
        const val KEY_MEDIUM_HIT = "mediumHit"
        const val KEY_RISING_TAP = "risingTap"
        const val KEY_LONG_PULSE = "longPulse"
        const val KEY_EMOTION_PULSE = "emotionPulse"
        const val KEY_SOFT_TICK = "softTick"

        val MODE_LABELS: Map<String, String> = linkedMapOf(
            KEY_HEAVY_LONG to "重长振",
            KEY_HEAVY_SHORT to "重短振",
            KEY_MID_TAP to "中敲击",
            KEY_MEDIUM_HIT to "中等击打",
            KEY_RISING_TAP to "上升轻击",
            KEY_LONG_PULSE to "长脉动",
            KEY_EMOTION_PULSE to "情感脉动",
            KEY_SOFT_TICK to "柔和细节",
        )

        val DEFAULT_MODES: Map<String, ModeVibration> = DEFAULT_MODES_MAP

        fun defaults(): VibrationParams = VibrationParams(DEFAULT_MODES)

        fun fromJson(json: String?): VibrationParams {
            if (json.isNullOrBlank()) return defaults()
            return try {
                val obj = JSONObject(json)
                val modes = DEFAULT_MODES.keys.associateWith { key ->
                    val mv = obj.optJSONObject(key)
                    if (mv != null) {
                        val activeArr = mv.optJSONArray("activeBands")
                        val activeBands = if (activeArr != null) {
                            (0 until activeArr.length()).mapNotNull { activeArr.optInt(it).takeIf { v -> v in 0..31 } }
                        } else null
                        ModeVibration(
                            ampPercent = mv.optInt("amp", DEFAULT_MODES[key]!!.ampPercent).coerceIn(0, 100),
                            durMs = mv.optInt("dur", DEFAULT_MODES[key]!!.durMs).coerceIn(10, 500),
                            bandStart = mv.optInt("bandStart", 0).coerceIn(0, 31),
                            bandEnd = mv.optInt("bandEnd", 31).coerceIn(0, 31),
                            activeBands = activeBands,
                        )
                    } else {
                        DEFAULT_MODES[key]!!
                    }
                }
                VibrationParams(modes)
            } catch (t: Throwable) {
                defaults()
            }
        }
    }
}

data class VibrationProfile(
    val id: String,
    val name: String,
    val params: VibrationParams = VibrationParams.defaults(),
    val scopeApps: List<String> = emptyList(),
) {
    val isDefault: Boolean get() = id == DEFAULT_ID
    val isGlobal: Boolean get() = scopeApps.isEmpty()

    fun toJson(): String = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("params", JSONObject(params.toJson()))
        put("scopeApps", JSONArray(scopeApps))
    }.toString()

    companion object {
        const val DEFAULT_ID = "default"
        const val DEFAULT_NAME = "Default"

        fun defaultProfile(): VibrationProfile = VibrationProfile(DEFAULT_ID, DEFAULT_NAME, VibrationParams.defaults())

        fun fromJson(json: String?): VibrationProfile? {
            if (json.isNullOrBlank()) return null
            return try {
                val obj = JSONObject(json)
                val arr = obj.optJSONArray("scopeApps")
                val apps = mutableListOf<String>()
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        arr.optString(i)?.let { apps.add(it) }
                    }
                }
                VibrationProfile(
                    id = obj.optString("id", ""),
                    name = obj.optString("name", ""),
                    params = VibrationParams.fromJson(obj.optString("params", "")),
                    scopeApps = apps,
                )
            } catch (t: Throwable) {
                null
            }
        }

        fun fromJsonList(json: String?): List<VibrationProfile> {
            if (json.isNullOrBlank()) return emptyList()
            return try {
                val arr = JSONArray(json)
                (0 until arr.length()).mapNotNull { VibrationProfile.fromJson(arr.optString(it)) }
            } catch (t: Throwable) {
                emptyList()
            }
        }
    }
}
