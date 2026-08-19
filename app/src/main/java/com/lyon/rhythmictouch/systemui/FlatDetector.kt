package com.lyon.rhythmictouch.systemui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.lyon.rhythmictouch.RhythmicConstants

class FlatDetector(context: Context, private val configBridge: ConfigBridge) : SensorEventListener {

    companion object {
        private const val TAG = "RhythmicFlat"

        private const val FLAT_THRESHOLD_XY = 1.5f
        private const val FLAT_THRESHOLD_Z_MIN = 8.0f
        private const val FLAT_CONFIRM_MS = 0L
        private const val PICKUP_CONFIRM_MS = 0L
        private const val SENSOR_DELAY_US = 200_000
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null

    @Volatile
    var isFlat = false
        private set

    @Volatile
    var isPaused = false
        private set

    private var flatSinceMs = 0L
    private var pickupSinceMs = 0L

    var onStateChanged: ((paused: Boolean) -> Unit)? = null

    private var hasStarted = false

    fun start() {
        if (accelerometer == null) {
            RhythmicLog.x(TAG, "No accelerometer available")
            return
        }
        if (!configBridge.config.flatDetection) {
            RhythmicLog.x(TAG, "Flat detection disabled in config")
            return
        }
        try {
            if (handlerThread?.isAlive != true) {
                handlerThread?.quitSafely()
                handlerThread = HandlerThread("flat-detector").apply { start() }
                handler = Handler(handlerThread!!.looper)
            }
            sensorManager.registerListener(this, accelerometer, SENSOR_DELAY_US, handler)
            hasStarted = true
            RhythmicLog.x(TAG, "Started (threshold: XY<${FLAT_THRESHOLD_XY}, Z>${FLAT_THRESHOLD_Z_MIN}, flatConfirm=${FLAT_CONFIRM_MS}ms, pickupConfirm=${PICKUP_CONFIRM_MS}ms)")
        } catch (e: Throwable) {
            RhythmicLog.x(TAG, "Failed to start: ${e.message}")
            hasStarted = false
        }
    }

    fun stop() {
        try {
            sensorManager.unregisterListener(this)
        } catch (_: Throwable) {}
        handlerThread?.quitSafely()
        handlerThread = null
        handler = null
        hasStarted = false
        isFlat = false
        isPaused = false
    }

    fun refreshEnabled() {
        val enabled = configBridge.config.flatDetection
        if (enabled && !hasStarted) {
            start()
        } else if (!enabled && hasStarted) {
            stop()
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_ACCELEROMETER) return
        if (!configBridge.config.flatDetection) return

        val ax = event.values[0]
        val ay = event.values[1]
        val az = event.values[2]

        val now = SystemClock.elapsedRealtime()
        val flatNow = kotlin.math.abs(ax) < FLAT_THRESHOLD_XY &&
            kotlin.math.abs(ay) < FLAT_THRESHOLD_XY &&
            az > FLAT_THRESHOLD_Z_MIN

        if (flatNow) {
            if (!isFlat) {
                isFlat = true
                flatSinceMs = now
                pickupSinceMs = 0L
                RhythmicLog.d(TAG, "📱 Device went flat: ax=${"%.1f".format(ax)} ay=${"%.1f".format(ay)} az=${"%.1f".format(az)}")
            } else if (!isPaused && now - flatSinceMs >= FLAT_CONFIRM_MS) {
                isPaused = true
                RhythmicLog.x(TAG, "⏸️ Flat confirmed, pausing vibration after ${FLAT_CONFIRM_MS}ms")
                onStateChanged?.invoke(true)
            }
        } else {
            if (isFlat) {
                isFlat = false
                pickupSinceMs = now
                flatSinceMs = 0L
                RhythmicLog.d(TAG, "📱 Device picked up: ax=${"%.1f".format(ax)} ay=${"%.1f".format(ay)} az=${"%.1f".format(az)}")
            } else if (isPaused && pickupSinceMs > 0L && now - pickupSinceMs >= PICKUP_CONFIRM_MS) {
                isPaused = false
                pickupSinceMs = 0L
                RhythmicLog.x(TAG, "▶️ Pick-up confirmed, resuming vibration after ${PICKUP_CONFIRM_MS}ms")
                onStateChanged?.invoke(false)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
