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

    @Volatile
    private var aaudioInterceptor: AAudioInterceptor? = null

    @Volatile
    private var nativeInterceptor: NativeAudioInterceptor? = null

    fun setAaudioInterceptor(interceptor: AAudioInterceptor) {
        aaudioInterceptor = interceptor
    }

    fun setNativeInterceptor(interceptor: NativeAudioInterceptor) {
        nativeInterceptor = interceptor
    }

    fun start(context: Context) {
        if (engine != null) return
        val ctx = context.applicationContext ?: context
        log("start(context=${ctx.packageName}, uid=${ctx.applicationInfo?.uid})")

        // Initialize RichTap before engine (priority haptic backend)
        try {
            RichTapHelper.init(ctx)
            log("RichTap init: supported=${RichTapHelper.isAvailable()}")
        } catch (t: Throwable) {
            log("RichTap init failed: ${t.message}")
        }

        val e = RhythmicEngine(ctx)
        log("engine created, starting...")
        e.start()
        engine = e
        log("engine started, engineActive=${LiveState.engineActive}")

        nativeInterceptor?.setAppContext(ctx)
        val config = ConfigBridge(ctx).refresh(force = true)
        aaudioInterceptor?.updateInterval(config.aaudioIntervalMs.toLong())
        nativeInterceptor?.setSyncEnabled(config.syncAaudioWithAudioTrack)

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
            addAction("com.lyon.rhythmictouch.ACTION_PHIRA_FFT_DATA")
            addAction(RhythmicConstants.ACTION_SYNC_AAUDIO_INTERVAL)
            addAction(RhythmicConstants.ACTION_REQUEST_DETECTED_INTERVAL)
        }
        val recv = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                log("broadcast received: ${intent?.action}")
                val e = engine ?: return
                when (intent?.action) {
                    RhythmicConstants.ACTION_REFRESH_CONFIG -> {
                        val config = e.refreshConfig()
                        aaudioInterceptor?.updateInterval(config.aaudioIntervalMs.toLong())
                        nativeInterceptor?.setSyncEnabled(config.syncAaudioWithAudioTrack)
                        if (config.syncAaudioWithAudioTrack) {
                            nativeInterceptor?.broadcastDetectedInterval()
                        } else {
                            val interval = intent.getIntExtra(RhythmicConstants.EXTRA_AAUDIO_INTERVAL_MS, -1)
                            if (interval >= 0) {
                                val syncIntent = Intent(RhythmicConstants.ACTION_SYNC_AAUDIO_INTERVAL).apply {
                                    putExtra(RhythmicConstants.EXTRA_AAUDIO_INTERVAL_MS, interval)
                                }
                                context!!.sendBroadcast(syncIntent)
                                log("📊 Forwarded manual interval to Phira: ${interval}ms")
                            }
                        }
                    }
                    RhythmicConstants.ACTION_OBSERVE_START -> e.setObserving(true)
                    RhythmicConstants.ACTION_OBSERVE_STOP -> e.setObserving(false)
                    RhythmicConstants.ACTION_TEST_VIBRATION -> {
                        val modeKey = intent.getStringExtra(RhythmicConstants.EXTRA_TEST_MODE_KEY)
                        if (modeKey != null) e.testVibration(modeKey)
                    }
                    "com.lyon.rhythmictouch.ACTION_PHIRA_FFT_DATA" -> {
                        val fftData = intent.getByteArrayExtra("fft_data")
                        val samplingRate = intent.getIntExtra("sampling_rate", 44100)
                        val sourceApp = intent.getStringExtra("source_app") ?: "unknown"
                        
                        if (fftData != null && fftData.isNotEmpty()) {
                            log("🎮📥 Received FFT data from $sourceApp: ${fftData.size} bytes, rate=$samplingRate")
                            onAaudioFftData(fftData, samplingRate)
                        }
                    }
                    RhythmicConstants.ACTION_SYNC_AAUDIO_INTERVAL -> {
                        val interval = intent.getIntExtra(RhythmicConstants.EXTRA_AAUDIO_INTERVAL_MS, 100)
                        aaudioInterceptor?.updateInterval(interval.toLong())
                        log("📊 Synced AAudio interval: ${interval}ms")
                    }
                    RhythmicConstants.ACTION_REQUEST_DETECTED_INTERVAL -> {
                        val cfg = ConfigBridge(context!!.applicationContext).refresh(force = true)
                        if (cfg.syncAaudioWithAudioTrack) {
                            nativeInterceptor?.broadcastDetectedInterval()
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
        }
    }
}