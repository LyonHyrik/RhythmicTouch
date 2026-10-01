package com.lyon.rhythmictouch.systemui

import android.media.audiofx.Visualizer

class AudioCapturer private constructor() {

    private var visualizer: Visualizer? = null
    private var fftListener: ((ByteArray, Int) -> Unit)? = null

    @Volatile
    var samplingRate: Int = 0
        private set

    @Volatile
    var captureSize: Int = 0
        private set

    @Volatile
    private var currentSession = Int.MIN_VALUE
    @Volatile
    private var enabled = false

    fun setFftListener(listener: ((ByteArray, Int) -> Unit)?) {
        fftListener = listener
        visualizer?.let { setListenerOn(it) }
    }

    fun startDefault(): Boolean {
        log("🎵 startDefault() called")
        val result = attachToSession(0)
        log("🎵 startDefault() result=$result, enabled=$enabled")
        return result
    }

    fun attachToSession(sessionId: Int): Boolean {
        log("🎯 attachToSession($sessionId) called, currentSession=$currentSession, enabled=$enabled")
        
        if (sessionId == currentSession && enabled) return enabled
        
        val old = visualizer
        visualizer = null
        try {
            old?.release()
        } catch (_: Throwable) {
        }
        currentSession = sessionId
        enabled = false
        
        if (sessionId >= 0) {
            log("🔧 Attempting to attach visualizer for session=$sessionId...")
            val visualizerResult = tryAttachVisualizer(sessionId)
            
            if (!visualizerResult && sessionId == 0) {
                // ⚠️ AudioRecord fallback is intentionally disabled.
                // Opening any record stream from SystemUI (uid 10100) makes AudioPolicyManager
                // treat it as an active input and it lands in the STREAM_MUSIC output device
                // list as AUDIO_DEVICE_OUT_REMOTE_SUBMIX, which displaces bt_a2dp. Symptom:
                // with BT headphones connected while idle, volume keys control the speaker
                // instead of the headset. When playback is active we attach to a real
                // AudioTrack session and never reach this branch, which is why the bug only
                // showed up while idle.
                log("⚠️ Visualizer(0) failed — AudioRecord fallback disabled (protects BT routing)")
                return false
            }
            
            return visualizerResult
        }
        
        return false
    }

    private fun tryAttachVisualizer(sessionId: Int): Boolean {
        log("🎨 Creating Visualizer for session=$sessionId...")
        
        val v = try {
            val visualizer = Visualizer(sessionId)
            log("✅ Visualizer object created successfully")
            visualizer
        } catch (t: Throwable) {
            log("❌ Visualizer($sessionId) creation failed: ${t.message}")
            t.printStackTrace()
            currentSession = Int.MIN_VALUE
            return false
        }
        
        try {
            val maxSize = Visualizer.getCaptureSizeRange()[1]
            if (maxSize >= 512) {
                v.setCaptureSize(maxSize)
                log("📐 Capture size set to $maxSize")
            }
        } catch (_: Throwable) {
            log("⚠️ Failed to set custom capture size")
        }
        
        samplingRate = v.samplingRate / 1000  // Visualizer returns milliHertz
        captureSize = v.captureSize
        visualizer = v
        
        log("🔌 Setting up FFT listener...")
        setListenerOn(v)
        
        enabled = try {
            v.enabled = true
            val isEnabled = v.enabled
            log("🔋 Visualizer enable result: $isEnabled")
            isEnabled
        } catch (t: Throwable) {
            log("❌ Visualizer enable exception: ${t.message}")
            false
        }
        
        if (enabled) {
            log("✅✅ Visualizer SUCCESSFULLY attached to session=$sessionId samplingRate=$samplingRate captureSize=$captureSize")
        } else {
            log("❌❌ Visualizer FAILED to enable for session=$sessionId")
        }
        
        return enabled
    }

    private fun setListenerOn(visualizer: Visualizer) {
        try {
            visualizer.setDataCaptureListener(
                object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(visualizer: Visualizer?, waveform: ByteArray?, samplingRate: Int) = Unit

                    override fun onFftDataCapture(visualizer: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                        if (fft != null) fftListener?.invoke(fft, samplingRate / 1000)  // Convert milliHertz to Hertz
                    }
                },
                CAPTURE_PERIOD_MS,
                false,
                true,
            )
        } catch (_: Throwable) {
        }
    }

    fun getFftSnapshot(): ByteArray? {
        val v = visualizer ?: return null
        return try {
            val fft = ByteArray(captureSize)
            v.getFft(fft)
            fft
        } catch (t: Throwable) {
            null
        }
    }

    fun stop() {
        enabled = false
        try {
            visualizer?.enabled = false
        } catch (_: Throwable) {
        }
    }

    fun release() {
        stop()
        try {
            visualizer?.release()
        } catch (_: Throwable) {
        }
        visualizer = null
        currentSession = Int.MIN_VALUE
    }

    companion object {
        private const val CAPTURE_PERIOD_MS = 33

        fun create(): AudioCapturer = AudioCapturer()

        private fun log(msg: String) {
            RhythmicLog.x(TAG, msg)
        }

        private const val TAG = "RhythmicTouch"
    }
}