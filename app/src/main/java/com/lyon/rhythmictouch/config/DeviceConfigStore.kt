package com.lyon.rhythmictouch.config

import android.content.Context
import android.content.SharedPreferences

class DeviceConfigStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("rhythmic_device_config", Context.MODE_PRIVATE)

    private companion object {
        const val KEY_DEVICES_JSON = "devices_json"
    }

    fun readDevices(): List<DeviceVibrationConfig> =
        DeviceVibrationConfig.fromJson(prefs.getString(KEY_DEVICES_JSON, null))

    fun writeDevices(devices: List<DeviceVibrationConfig>) {
        prefs.edit().putString(KEY_DEVICES_JSON, DeviceVibrationConfig.toJson(devices)).apply()
    }

    fun getEffectiveIntensity(deviceAddress: String, globalIntensity: Int): Int {
        val devices = readDevices()
        val match = devices.find { it.deviceAddress == deviceAddress && it.enabled }
        return match?.intensity ?: globalIntensity
    }

    fun getEffectiveDelay(deviceAddress: String, globalDelay: Int): Int {
        val devices = readDevices()
        val match = devices.find { it.deviceAddress == deviceAddress && it.enabled }
        return match?.vibrationDelay ?: globalDelay
    }
}
