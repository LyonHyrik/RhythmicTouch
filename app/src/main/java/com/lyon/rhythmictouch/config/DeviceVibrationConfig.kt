package com.lyon.rhythmictouch.config

import org.json.JSONArray
import org.json.JSONObject

data class DeviceVibrationConfig(
    val deviceAddress: String,
    val deviceName: String,
    val deviceType: Int,
    val enabled: Boolean = false,
    val intensity: Int = 70,
    val vibrationDelay: Int = 0,
) {
    companion object {
        const val TYPE_SPEAKER = 0
        const val TYPE_HEADPHONE = 1
        const val TYPE_BLUETOOTH = 2

        fun toJson(devices: List<DeviceVibrationConfig>): String {
            val arr = JSONArray()
            for (d in devices) {
                arr.put(JSONObject().apply {
                    put("address", d.deviceAddress)
                    put("name", d.deviceName)
                    put("type", d.deviceType)
                    put("enabled", d.enabled)
                    put("intensity", d.intensity)
                    put("delay", d.vibrationDelay)
                })
            }
            return arr.toString()
        }

        fun fromJson(json: String?): List<DeviceVibrationConfig> {
            if (json.isNullOrBlank()) return emptyList()
            return try {
                val arr = JSONArray(json)
                (0 until arr.length()).mapNotNull { i ->
                    val obj = arr.getJSONObject(i)
                    DeviceVibrationConfig(
                        deviceAddress = obj.getString("address"),
                        deviceName = obj.getString("name"),
                        deviceType = obj.optInt("type", TYPE_BLUETOOTH),
                        enabled = obj.optBoolean("enabled", false),
                        intensity = obj.optInt("intensity", 70).coerceIn(0, 100),
                        vibrationDelay = obj.optInt("delay", 0).coerceIn(0, 1000),
                    )
                }
            } catch (_: Throwable) {
                emptyList()
            }
        }
    }
}
