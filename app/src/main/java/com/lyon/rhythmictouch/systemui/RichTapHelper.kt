package com.lyon.rhythmictouch.systemui

import android.content.Context
import android.util.Log
import com.lyon.rhythmictouch.config.VibrationParams
import java.util.concurrent.atomic.AtomicBoolean

object RichTapHelper {

    private const val TAG = "RhythmicRichTap"

    private var initialized = AtomicBoolean(false)
    private var supported = false
    private var initAttempted = false

    // Cached reflection targets
    private var richTapUtilsClass: Class<*>? = null
    private var getInstanceMethod: java.lang.reflect.Method? = null
    private var isSupportedMethod: java.lang.reflect.Method? = null
    private var playHapticStringIntMethod: java.lang.reflect.Method? = null
    private var playHapticStringIntIntMethod: java.lang.reflect.Method? = null
    private var stopMethod: java.lang.reflect.Method? = null
    private var quitMethod: java.lang.reflect.Method? = null
    private var initMethod: java.lang.reflect.Method? = null
    private var enableDebugLogMethod: java.lang.reflect.Method? = null
    private var cachedInstance: Any? = null

    // Pre-built JSON templates (performance: avoid string alloc on hot path)
    // Intensity: 0-255, Frequency: 0-100
    private val jsonCache = object : ThreadLocal<MutableMap<Int, String>>() {
        override fun initialValue() = mutableMapOf<Int, String>()
    }

    fun isAvailable(): Boolean {
        if (initAttempted) return supported
        return false
    }

    @Synchronized
    fun init(context: Context) {
        if (initAttempted) return
        initAttempted = true

        try {
            richTapUtilsClass = Class.forName("com.apprichtap.haptic.RichTapUtils")
            Log.d(TAG, "RichTapUtils class found")

            getInstanceMethod = richTapUtilsClass!!.getMethod("getInstance")
            val instance = getInstanceMethod!!.invoke(null) ?: run {
                Log.e(TAG, "RichTapUtils.getInstance() returned null")
                return
            }
            cachedInstance = instance

            initMethod = richTapUtilsClass!!.getMethod("init", Context::class.java)
            initMethod!!.invoke(instance, context)
            Log.d(TAG, "RichTapUtils.init() called")

            isSupportedMethod = richTapUtilsClass!!.getMethod("getIsSupportedRichTap")
            supported = try {
                isSupportedMethod!!.invoke(instance) as? Boolean ?: false
            } catch (_: Throwable) {
                // Try property-style getter
                try {
                    val field = richTapUtilsClass!!.getField("isSupportedRichTap")
                    field.getBoolean(instance)
                } catch (_: Throwable) {
                    Log.w(TAG, "Cannot determine RichTap support, assuming false")
                    false
                }
            }

            if (supported) {
                // Cache playback methods
                playHapticStringIntMethod = richTapUtilsClass!!.methods.find {
                    it.name == "playHaptic" &&
                        it.parameterTypes.size == 2 &&
                        it.parameterTypes[0] == String::class.java &&
                        it.parameterTypes[1] == Int::class.javaPrimitiveType
                }
                playHapticStringIntIntMethod = richTapUtilsClass!!.methods.find {
                    it.name == "playHaptic" &&
                        it.parameterTypes.size == 3 &&
                        it.parameterTypes[0] == String::class.java &&
                        it.parameterTypes[1] == Int::class.javaPrimitiveType &&
                        it.parameterTypes[2] == Int::class.javaPrimitiveType
                }
                stopMethod = richTapUtilsClass!!.getMethod("stop")
                quitMethod = richTapUtilsClass!!.getMethod("quit")

                Log.d(TAG, "RichTap ready: playHaptic(json,int)=${playHapticStringIntMethod != null} playHaptic(json,int,int)=${playHapticStringIntIntMethod != null}")
                RhythmicLog.x(TAG, "RichTap HD haptics available")
            } else {
                Log.d(TAG, "RichTap not supported on this device")
                RhythmicLog.x(TAG, "RichTap not supported on this device")
            }

            initialized.set(true)
        } catch (t: Throwable) {
            Log.e(TAG, "RichTap init failed: ${t.message}", t)
            supported = false
        }
    }

    fun playTransient(modeKey: String, intensity: Float, sharpness: Float, delayMs: Long = 0): Boolean {
        if (!supported || cachedInstance == null) return false

        return try {
            val intensityScaled = (intensity.coerceIn(0.01f, 1.0f) * 255).toInt().coerceIn(1, 255)
            val frequencyScaled = (sharpness.coerceIn(0f, 1.0f) * 100).toInt().coerceIn(0, 100)

            val json = buildTransientJson(intensityScaled, frequencyScaled, delayMs.toInt().coerceIn(0, 5000))

            if (playHapticStringIntIntMethod != null) {
                playHapticStringIntIntMethod!!.invoke(cachedInstance, json, 0, intensityScaled)
            } else if (playHapticStringIntMethod != null) {
                playHapticStringIntMethod!!.invoke(cachedInstance, json, 0)
            } else {
                Log.w(TAG, "No playHaptic method available")
                return false
            }

            Log.d(TAG, "playTransient OK: mode=$modeKey intensity=$intensityScaled freq=$frequencyScaled delay=${delayMs}ms")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "playTransient FAILED: ${t.message}", t)
            false
        }
    }

    private fun buildTransientJson(intensity: Int, frequency: Int, delayMs: Int): String {
        // Use cached JSON for common delay=0 case
        if (delayMs == 0) {
            val key = (intensity shl 16) or (frequency shl 8)
            val cached = jsonCache.get()!![key]
            if (cached != null) return cached
        }

        val json = if (delayMs == 0) {
            "{\"Metadata\":{\"Version\":2},\"PatternList\":[{\"AbsoluteTime\":0,\"Pattern\":[{\"Event\":{\"Type\":\"transient\",\"RelativeTime\":0,\"Parameters\":{\"Intensity\":$intensity,\"Frequency\":$frequency},\"Index\":0}}]}]}"
        } else {
            "{\"Metadata\":{\"Version\":2},\"PatternList\":[{\"AbsoluteTime\":$delayMs,\"Pattern\":[{\"Event\":{\"Type\":\"transient\",\"RelativeTime\":0,\"Parameters\":{\"Intensity\":$intensity,\"Frequency\":$frequency},\"Index\":0}}]}]}"
        }

        if (delayMs == 0) {
            val key = (intensity shl 16) or (frequency shl 8)
            jsonCache.get()!![key] = json
        }

        return json
    }

    fun playContinuous(durationMs: Int, intensity: Float, sharpness: Float, delayMs: Long = 0): Boolean {
        if (!supported || cachedInstance == null) return false

        return try {
            val intensityScaled = (intensity.coerceIn(0.01f, 1.0f) * 255).toInt().coerceIn(1, 255)
            val frequencyScaled = (sharpness.coerceIn(0f, 1.0f) * 100).toInt().coerceIn(0, 100)
            val dur = durationMs.coerceIn(1, 10000)

            val json = "{\"Metadata\":{\"Version\":2},\"PatternList\":[{\"AbsoluteTime\":${delayMs.toInt().coerceIn(0, 5000)},\"Pattern\":[{\"Event\":{\"Type\":\"continuous\",\"RelativeTime\":0,\"Parameters\":{\"Intensity\":$intensityScaled,\"Frequency\":$frequencyScaled},\"Index\":0,\"Duration\":$dur}}]}]}"

            if (playHapticStringIntIntMethod != null) {
                playHapticStringIntIntMethod!!.invoke(cachedInstance, json, 0, intensityScaled)
            } else if (playHapticStringIntMethod != null) {
                playHapticStringIntMethod!!.invoke(cachedInstance, json, 0)
            } else {
                return false
            }

            Log.d(TAG, "playContinuous OK: dur=${dur}ms intensity=$intensityScaled freq=$frequencyScaled")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "playContinuous FAILED: ${t.message}", t)
            false
        }
    }

    fun stop() {
        if (!supported || cachedInstance == null) return
        try {
            stopMethod?.invoke(cachedInstance)
        } catch (t: Throwable) {
            Log.w(TAG, "stop() failed: ${t.message}")
        }
    }

    fun release() {
        if (!supported || cachedInstance == null) return
        try {
            stop()
            quitMethod?.invoke(cachedInstance)
        } catch (t: Throwable) {
            Log.w(TAG, "release() failed: ${t.message}")
        }
        cachedInstance = null
        supported = false
        initAttempted = false
        initialized.set(false)
    }

    fun getSharpnessForMode(modeKey: String): Float = when (modeKey) {
        VibrationParams.KEY_HEAVY_SHORT -> 0.8f
        VibrationParams.KEY_MEDIUM_HIT -> 0.7f
        VibrationParams.KEY_MID_TAP -> 0.6f
        VibrationParams.KEY_RISING_TAP -> 0.5f
        VibrationParams.KEY_SOFT_TICK -> 0.3f
        else -> 0.5f
    }

    fun getIntensityMultiplier(modeKey: String): Float = when (modeKey) {
        VibrationParams.KEY_SOFT_TICK -> 0.5f
        VibrationParams.KEY_RISING_TAP -> 0.8f
        else -> 1.0f
    }
}
