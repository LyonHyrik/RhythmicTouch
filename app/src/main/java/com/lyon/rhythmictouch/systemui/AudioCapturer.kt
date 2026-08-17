package com.lyon.rhythmictouch.systemui

import android.media.audiofx.BassBoost
import android.media.audiofx.Visualizer

class AudioCapturer private constructor() {

    private var visualizer: Visualizer? = null
    private var chainEffect: BassBoost? = null
    private var fallbackRecord: android.media.AudioRecord? = null
    private var recordThread: Thread? = null
    @Volatile
    private var isRecording = false
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
    @Volatile
    private var usingFallbackMode = false

    fun setFftListener(listener: ((ByteArray, Int) -> Unit)?) {
        fftListener = listener
        visualizer?.let { setListenerOn(it) }
    }

    fun startDefault(): Boolean {
        log("🎵 startDefault() called")
        val result = attachToSession(0)
        log("🎵 startDefault() result=$result, enabled=$enabled, usingFallback=$usingFallbackMode")
        return result
    }

    fun attachToSession(sessionId: Int): Boolean {
        log("🎯 attachToSession($sessionId) called, currentSession=$currentSession, enabled=$enabled")
        
        if (sessionId == currentSession && enabled) return enabled
        
        stopFallbackRecording()
        
        val old = visualizer
        visualizer = null
        try {
            old?.release()
        } catch (_: Throwable) {
        }
        releaseChainEffect()
        currentSession = sessionId
        enabled = false
        usingFallbackMode = false
        
        if (sessionId >= 0) {
            log("🔧 Attempting to attach visualizer for session=$sessionId...")
            val visualizerResult = tryAttachVisualizer(sessionId)
            
            if (!visualizerResult && sessionId == 0) {
                log("⚠️ Visualizer(0) failed, trying fallback AudioRecord mode...")
                return startFallbackRecording()
            }
            
            return visualizerResult
        }
        
        return false
    }

    private fun tryAttachVisualizer(sessionId: Int): Boolean {
        log("🎨 Creating Visualizer for session=$sessionId...")
        
        if (sessionId > 0) {
            setupChainEffect(sessionId)
        }
        
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
            usingFallbackMode = false
            log("✅✅ Visualizer SUCCESSFULLY attached to session=$sessionId samplingRate=$samplingRate captureSize=$captureSize")
        } else {
            log("❌❌ Visualizer FAILED to enable for session=$sessionId")
        }
        
        return enabled
    }

    fun startFallbackRecording(): Boolean {
        log("🎤🎤🎤 STARTING FALLBACK AUDIO RECORD MODE 🎤🎤🎤")
        stopFallbackRecording()
        
        var result = false
        
        try {
            log("🔐 Note: RECORD_AUDIO permission declared in manifest - SystemUI should have it automatically")
            
            val sampleRate = 44100
            val channelConfig = android.media.AudioFormat.CHANNEL_IN_MONO  
            val audioFormat = android.media.AudioFormat.ENCODING_PCM_16BIT
            val minBufferSize = android.media.AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            val bufferSize = minBufferSize * 4
            
            log("📊 AudioRecord params: sampleRate=$sampleRate, minBufferSize=$minBufferSize, finalBufferSize=$bufferSize")
            
            if (bufferSize <= 0 || minBufferSize <= 0) {
                log("❌ Invalid buffer size for AudioRecord (min=$minBufferSize, final=$bufferSize)")
                result = false
                return@startFallbackRecording result
            }
            
            var audioRecord: android.media.AudioRecord? = null
            
            val sourcesToTry = listOf(
                android.media.MediaRecorder.AudioSource.REMOTE_SUBMIX to "REMOTE_SUBMIX",
                android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION to "VOICE_RECOGNITION",  
                android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION to "VOICE_COMMUNICATION",
                android.media.MediaRecorder.AudioSource.MIC to "MIC"
            )
            
            for ((source, sourceName) in sourcesToTry) {
                log("🔧 Attempting $sourceName AudioRecord (source=$source)...")
                
                try {
                    audioRecord?.release()
                    audioRecord = null
                    
                    audioRecord = android.media.AudioRecord(
                        source,
                        sampleRate,
                        channelConfig,
                        audioFormat,
                        bufferSize
                    )
                    
                    log("🔍 $sourceName AudioRecord state: ${audioRecord.state} (expected=${android.media.AudioRecord.STATE_INITIALIZED})")
                    
                    if (audioRecord.state == android.media.AudioRecord.STATE_INITIALIZED) {
                        log("✅ $sourceName AudioRecord initialized successfully!")
                        break
                    } else {
                        log("⚠️ $sourceName not initialized properly")
                        try { audioRecord.release() } catch (_: Exception) {}
                        audioRecord = null
                    }
                    
                } catch (e: Exception) { 
                    log("⚠️ $sourceName creation failed: ${e.message}")
                    e.printStackTrace()
                    try { audioRecord?.release() } catch (_: Exception) {}
                    audioRecord = null
                }
            }
            
            if (audioRecord == null || audioRecord.state != android.media.AudioRecord.STATE_INITIALIZED) {
                log("❌❌❌ ALL AudioRecord SOURCES FAILED ❌❌❌")
                try { audioRecord?.release() } catch (_: Exception) {}
                result = false
                return@startFallbackRecording result
            }
            
            fallbackRecord = audioRecord
            samplingRate = sampleRate
            captureSize = 1024
            isRecording = true
            
            log("🚀 Starting recording thread...")
            recordThread = Thread({
                val buffer = ByteArray(bufferSize)
                val fftBuffer = ByteArray(captureSize)
                var readCount = 0L
                
                while (isRecording && !Thread.currentThread().isInterrupted) {
                    try {
                        val read = fallbackRecord?.read(buffer, 0, buffer.size) ?: -1
                        
                        if (read > 64) {
                            readCount++
                            performFFTOnBuffer(buffer, read, fftBuffer)
                            fftListener?.invoke(fftBuffer, sampleRate)
                            
                            if (readCount % 100L == 0L) {
                                log("📈 AudioRecord: $readCount reads successful, lastRead=$read bytes")
                            }
                        }
                        
                        Thread.sleep(10L)
                        
                    } catch (e: InterruptedException) {
                        log("🛑 Recording thread interrupted")
                        break
                    } catch (t: Throwable) {
                        if (isRecording) {
                            log("⚠️ AudioRecord read error: ${t.message}")
                        }
                    }
                }
                log("🏁 Recording thread stopped after $readCount total reads")
            }, "RhythmicAudioCapture").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }
            
            log("▶️ Starting AudioRecord...")
            fallbackRecord?.startRecording()
            enabled = true
            usingFallbackMode = true
            
            log("✅✅✅ FALLBACK AUDIO RECORD MODE SUCCESSFULLY STARTED ✅✅✅")
            log("📋 Config: sampleRate=$sampleRate, bufferSize=$bufferSize, captureSize=$captureSize")
            result = true
            
        } catch (t: Throwable) {
            log("❌❌❌ FATAL ERROR in fallback recording setup: ${t.message}")
            t.printStackTrace()
            result = false
        }
        
        return result
    }

    private fun stopFallbackRecording() {
        isRecording = false
        
        try {
            recordThread?.interrupt()
            recordThread?.join(1000L)
        } catch (_: Throwable) {}
        recordThread = null
        
        try {
            fallbackRecord?.stop()
            fallbackRecord?.release()
        } catch (_: Throwable) {}
        fallbackRecord = null
    }

    private fun performFFTOnBuffer(pcmData: ByteArray, size: Int, output: ByteArray) {
        try {
            val samples = FloatArray(size / 2)
            var idx = 0
            var i = 0
            while (i + 1 < size) {
                val sample = ((pcmData[i].toInt() and 0xFF) or (pcmData[i + 1].toInt() shl 8)).toShort()
                samples[idx] = sample.toFloat() / Short.MAX_VALUE
                idx++
                i += 2
            }
            
            val fftSize = output.size * 2
            if (samples.size < fftSize) return
            
            val real = FloatArray(fftSize)
            val imag = FloatArray(fftSize)
            System.arraycopy(samples, 0, real, 0, fftSize.coerceAtMost(samples.size))
            
            applyHanningWindow(real)
            computeFFT(real, imag)
            
            for (i in output.indices) {
                val realVal = real[i].toDouble()
                val imagVal = imag[i].toDouble()
                val magnitude = kotlin.math.sqrt(realVal * realVal + imagVal * imagVal).toFloat()
                val normalizedMagnitude = (kotlin.math.log10(magnitude + 1.0f) * 50.0f).coerceIn(0.0f, 127.0f)
                output[i] = normalizedMagnitude.toInt().toByte()
            }
            
        } catch (t: Throwable) {
        }
    }

    private fun applyHanningWindow(data: FloatArray) {
        for (i in data.indices) {
            val window = 0.5f * (1 - Math.cos(2 * Math.PI * i / (data.size - 1))).toFloat()
            data[i] *= window
        }
    }

    private fun computeFFT(real: FloatArray, imag: FloatArray) {
        val n = real.size
        var j = 0
        
        for (i in 0 until n - 1) {
            if (i < j) {
                val tempReal = real[i]
                val tempImag = imag[i]
                real[i] = real[j]
                imag[i] = imag[j]
                real[j] = tempReal
                imag[j] = tempImag
            }
            var k = n shr 1
            while (k <= j) {
                j -= k
                k = k shr 1
            }
            j += k
        }
        
        var len = 2
        while (len <= n) {
            val len2 = len shr 1
            val angle = (-2 * Math.PI / len).toFloat()
            val wReal = kotlin.math.cos(angle.toDouble()).toFloat()
            val wImag = kotlin.math.sin(angle.toDouble()).toFloat()
            
            var ii = 0
            while (ii < n) {
                var curReal = 1.0f
                var curImag = 0.0f
                
                for (k in 0 until len2) {
                    val tReal = curReal * real[ii + k + len2] - curImag * imag[ii + k + len2]
                    val tImag = curReal * imag[ii + k + len2] + curImag * real[ii + k + len2]
                    
                    real[ii + k + len2] = real[ii + k] - tReal
                    imag[ii + k + len2] = imag[ii + k] - tImag
                    real[ii + k] += tReal
                    imag[ii + k] += tImag
                    
                    val nextReal = curReal * wReal - curImag * wImag
                    curImag = curReal * wImag + curImag * wReal
                    curReal = nextReal
                }
                ii += len
            }
            len = len shl 1
        }
    }

    private fun setupChainEffect(sessionId: Int) {
        val effect = try {
            BassBoost(0, sessionId)
        } catch (t: Throwable) {
            log("BassBoost($sessionId) failed: $t")
            null
        }
        if (effect != null) {
            try {
                effect.setStrength(0)
                effect.enabled = true
            } catch (t: Throwable) {
                log("BassBoost enable failed: $t")
            }
            chainEffect = effect
            log("chain effect attached to session=$sessionId")
        }
    }

    private fun releaseChainEffect() {
        try {
            chainEffect?.enabled = false
        } catch (_: Throwable) {
        }
        try {
            chainEffect?.release()
        } catch (_: Throwable) {
        }
        chainEffect = null
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
        stopFallbackRecording()
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
        releaseChainEffect()
        usingFallbackMode = false
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