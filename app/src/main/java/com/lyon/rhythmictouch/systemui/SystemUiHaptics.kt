package com.lyon.rhythmictouch.systemui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.lyon.rhythmictouch.LiveState
import com.lyon.rhythmictouch.RhythmicConstants

object SystemUiHaptics {

    private const val TAG = "RhythmicTouch"

    private fun log(msg: String) {
        RhythmicLog.x(TAG, msg)
    }

    @Volatile
    private var engine: RhythmicEngine? = null

    @Volatile
    private var receiver: BroadcastReceiver? = null

    fun start(context: Context) {
        if (engine != null) return
        val ctx = context.applicationContext ?: context
        log("start(context=${ctx.packageName}, uid=${ctx.applicationInfo?.uid})")
        val e = RhythmicEngine(ctx)
        log("engine created, starting...")
        e.start()
        engine = e
        log("engine started, engineActive=${LiveState.engineActive}")
        registerReceiver(ctx)
    }

    fun onAaudioFftData(fft: ByteArray, samplingRate: Int) {
        engine?.onExternalFftData(fft, samplingRate)
    }

    private fun registerReceiver(context: Context) {
        val filter = IntentFilter().apply {
            addAction(RhythmicConstants.ACTION_REFRESH_CONFIG)
            addAction(RhythmicConstants.ACTION_OBSERVE_START)
            addAction(RhythmicConstants.ACTION_OBSERVE_STOP)
            addAction(RhythmicConstants.ACTION_TEST_VIBRATION)
            addAction("com.lyon.rhythmictouch.ACTION_PHIRA_FFT_DATA")  // Receive Phira FFT data!
        }
        val recv = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                log("broadcast received: ${intent?.action}")
                val e = engine ?: return
                when (intent?.action) {
                    RhythmicConstants.ACTION_REFRESH_CONFIG -> e.refreshConfig()
                    RhythmicConstants.ACTION_OBSERVE_START -> e.setObserving(true)
                    RhythmicConstants.ACTION_OBSERVE_STOP -> e.setObserving(false)
                    RhythmicConstants.ACTION_TEST_VIBRATION -> {
                        val modeKey = intent.getStringExtra(RhythmicConstants.EXTRA_TEST_MODE_KEY)
                        if (modeKey != null) e.testVibration(modeKey)
                    }
                    "com.lyon.rhythmictouch.ACTION_PHIRA_FFT_DATA" -> {
                        // Receive FFT data from Phira process!
                        val fftData = intent.getByteArrayExtra("fft_data")
                        val samplingRate = intent.getIntExtra("sampling_rate", 44100)
                        val sourceApp = intent.getStringExtra("source_app") ?: "unknown"
                        
                        if (fftData != null && fftData.isNotEmpty()) {
                            log("🎮📥 Received FFT data from $sourceApp: ${fftData.size} bytes, rate=$samplingRate, engine=${if (engine != null) "✅" else "❌ NULL"}")
                            
                            if (engine == null) {
                                log("⚠️ ENGINE IS NULL! Cannot process FFT data!")
                            } else {
                                onAaudioFftData(fftData, samplingRate)  // Feed into vibration engine!
                                log("✅ onAaudioFftData called successfully")
                            }
                        } else {
                            log("⚠️ Received empty FFT data from $sourceApp")
                        }
                    }
                }
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(recv, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(recv, filter)
            }
            receiver = recv
        } catch (t: Throwable) {
            // Observation and instant refresh will not work, but vibration still does.
        }
    }
}