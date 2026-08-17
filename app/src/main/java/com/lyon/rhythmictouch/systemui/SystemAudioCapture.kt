package com.lyon.rhythmictouch.systemui

import android.content.Context
import android.media.AudioFormat
import android.media.MediaRecorder
import android.os.Build

class SystemAudioCapture(private val context: Context) {
    
    private var audioRecord: android.media.AudioRecord? = null
    private var isCapturing = false
    private var fftListener: ((ByteArray, Int) -> Unit)? = null
    
    companion object {
        fun isSupported(): Boolean = true
        
        fun canUseRemoteSubmix(): Boolean {
            return try {
                val testRecord = android.media.AudioRecord(
                    MediaRecorder.AudioSource.REMOTE_SUBMIX,
                    44100,
                    AudioFormat.CHANNEL_IN_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    8192
                )
                val result = testRecord.state == android.media.AudioRecord.STATE_INITIALIZED
                testRecord.release()
                result
            } catch (t: Throwable) {
                false
            }
        }
    }
    
    fun startCapture(onSuccess: (Boolean) -> Unit) {
        log("🎯 Starting System Audio Capture...")
        
        try {
            val sampleRate = 44100
            val channelConfig = AudioFormat.CHANNEL_IN_STEREO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val bufferSize = android.media.AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat) * 4
            
            if (bufferSize <= 0) {
                log("❌ Invalid buffer size")
                onSuccess(false)
                return
            }
            
            // Strategy priority for capturing system/AAudio audio
            val strategies: List<Pair<Int, String>> = listOf(
                Pair(MediaRecorder.AudioSource.REMOTE_SUBMIX, "REMOTE_SUBMIX (system mix)"),
                Pair(MediaRecorder.AudioSource.VOICE_RECOGNITION, "VOICE_RECOGNITION"),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Pair(1998, "UNPROCESSED (Android 10+)") else null,
                Pair(MediaRecorder.AudioSource.VOICE_COMMUNICATION, "VOICE_COMMUNICATION"),
                Pair(MediaRecorder.AudioSource.MIC, "MIC")
            ).filterNotNull()
            
            for ((source, name) in strategies) {
                log("🔧 Trying: $name (source=$source)")
                
                try {
                    val record = android.media.AudioRecord(source, sampleRate, channelConfig, audioFormat, bufferSize)
                    
                    if (record.state == android.media.AudioRecord.STATE_INITIALIZED) {
                        log("✅ $name initialized successfully!")
                        
                        // Test if it can actually capture audio
                        record.startRecording()
                        val testBuffer = ByteArray(bufferSize / 2)
                        val readResult = record.read(testBuffer, 0, testBuffer.size, 100)
                        record.stop()
                        
                        if (readResult > 0) {
                            log("✅✅ $name CAN CAPTURE AUDIO! ($readResult bytes)")
                            
                            audioRecord = record
                            isCapturing = true
                            
                            startCaptureThread(sampleRate, bufferSize)
                            
                            log("✅✅✅ SYSTEM AUDIO CAPTURE ACTIVE! Source: $name")
                            onSuccess(true)
                            return
                        } else {
                            log("⚠️ $name initialized but no data (read=$readResult)")
                            record.release()
                        }
                    } else {
                        log("❌ $name failed to initialize")
                    }
                    
                } catch (e: Exception) {
                    log("⚠️ $name error: ${e.message}")
                }
            }
            
            log("❌❌❌ ALL CAPTURE STRATEGIES FAILED ❌❌❌")
            onSuccess(false)
            
        } catch (t: Throwable) {
            log("❌ Fatal error in SystemAudioCapture: ${t.message}")
            t.printStackTrace()
            onSuccess(false)
        }
    }
    
    private fun startCaptureThread(sampleRate: Int, bufferSize: Int) {
        Thread({
            val buffer = ByteArray(bufferSize)
            val fftBuffer = ByteArray(1024)
            var readCount = 0L
            
            while (isCapturing) {
                try {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    
                    if (read > 64) {
                        readCount++
                        
                        performFFT(buffer, read, fftBuffer)
                        fftListener?.invoke(fftBuffer, sampleRate)
                        
                        if (readCount % 1000L == 0L) {
                            log("📊 System Capture: $readCount reads, last=$read bytes")
                        }
                    }
                    
                    Thread.sleep(5L)
                    
                } catch (e: InterruptedException) {
                    break
                } catch (t: Throwable) {
                    if (isCapturing) {
                        log("⚠️ Read error: ${t.message}")
                    }
                }
            }
            log("🏁 Capture thread ended ($readCount total reads)")
        }, "SystemAudioCapture").apply { 
            priority = Thread.MAX_PRIORITY 
            start() 
        }
    }
    
    private fun performFFT(pcmData: ByteArray, size: Int, output: ByteArray) {
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
            System.arraycopy(samples, 0, real, 0, minOf(fftSize, samples.size))
            
            applyHanningWindow(real)
            computeFFT(real, imag)
            
            for (i in output.indices) {
                val magnitude = kotlin.math.sqrt(
                    real[i].toDouble() * real[i].toDouble() + 
                    imag[i].toDouble() * imag[i].toDouble()
                ).toFloat()
                val normalized = (kotlin.math.log10(magnitude + 1.0f) * 50.0f).coerceIn(0.0f, 127.0f)
                output[i] = normalized.toInt().toByte()
            }
        } catch (t: Throwable) {}
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
    
    fun setFftListener(listener: ((ByteArray, Int) -> Unit)?) {
        fftListener = listener
    }
    
    fun stop() {
        isCapturing = false
        
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Throwable) {}
        audioRecord = null
        
        log("System audio capture stopped")
    }
    
    private fun log(msg: String) {
        RhythmicLog.x("RhythmicTouch-SystemCapture", msg)
    }
}