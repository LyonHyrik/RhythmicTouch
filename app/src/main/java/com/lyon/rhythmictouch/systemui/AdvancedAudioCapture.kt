package com.lyon.rhythmictouch.systemui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.core.app.ActivityCompat

class AdvancedAudioCapture(private val context: Context) {
    
    private var mediaProjection: MediaProjection? = null
    private var audioRecord: android.media.AudioRecord? = null
    private var isCapturing = false
    private var fftListener: ((ByteArray, Int) -> Unit)? = null
    
    companion object {
        const val REQUEST_CODE_SCREEN_CAPTURE = 1001
        
        fun isAudioPlaybackCaptureSupported(): Boolean {
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        }
        
        fun hasRecordPermission(context: Context): Boolean {
            return ActivityCompat.checkSelfPermission(
                context, 
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        }
    }
    
    fun startAdvancedCapture(onSuccess: (Boolean) -> Unit) {
        when {
            isAudioPlaybackCaptureSupported() -> {
                log("🎯 Using AudioPlaybackCapture (Android 10+)")
                startAudioPlaybackCapture(onSuccess)
            }
            else -> {
                log("⚠️ Fallback to enhanced AudioRecord")
                startEnhancedAudioRecord(onSuccess)
            }
        }
    }
    
    private fun startAudioPlaybackCapture(onSuccess: (Boolean) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) 
                    as MediaProjectionManager
                
                val intent = projectionManager.createScreenCaptureIntent()
                
                log("📱 Requesting screen capture permission for audio...")
                log("   Note: This is required to capture AAudio/Oboe audio on Android 10+")
                
                // We need to handle this in an Activity context
                // For now, fallback to other methods
                log("⚠️ Screen capture requires Activity context, trying alternative...")
                startEnhancedAudioRecord(onSuccess)
                
            } catch (t: Throwable) {
                log("❌ AudioPlaybackCapture failed: ${t.message}")
                startEnhancedAudioRecord(onSuccess)
            }
        }
    }
    
    private fun startEnhancedAudioRecord(onSuccess: (Boolean) -> Unit) {
        log("🎤 Starting enhanced AudioRecord with multiple strategies...")
        
        val sampleRate = 44100
        val channelConfig = AudioFormat.CHANNEL_IN_STEREO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val bufferSize = android.media.AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat) * 4
        
        if (bufferSize <= 0) {
            log("❌ Invalid buffer size")
            onSuccess(false)
            return
        }
        
        // Strategy priority for different ROMs
        val strategies = listOf(
            MediaRecorder.AudioSource.REMOTE_SUBMIX to "REMOTE_SUBMIX (system mix)",
            MediaRecorder.AudioSource.VOICE_RECOGNITION to "VOICE_RECOGNITION",
            1998 to "UNPROCESSED (Android 10+)",
            MediaRecorder.AudioSource.VOICE_COMMUNICATION to "VOICE_COMMUNICATION",
            MediaRecorder.AudioSource.MIC to "MIC (last resort)"
        )
        
        var record: android.media.AudioRecord? = null
        var success = false
        
        for ((source, name) in strategies) {
            log("🔧 Trying: $name (source=$source)")
            
            try {
                record?.release()
                record = null
                
                record = android.media.AudioRecord(source, sampleRate, channelConfig, audioFormat, bufferSize)
                
                if (record.state == android.media.AudioRecord.STATE_INITIALIZED) {
                    log("✅ SUCCESS: $name initialized!")
                    
                    // Test if it can actually read data
                    record.startRecording()
                    val testBuffer = ByteArray(bufferSize / 2)
                    val readResult = record.read(testBuffer, 0, testBuffer.size, 100)
                    record.stop()
                    
                    if (readResult > 0) {
                        log("✅✅ $name can read audio data ($readResult bytes)")
                        success = true
                        break
                    } else {
                        log("⚠️ $name initialized but cannot read data")
                    }
                } else {
                    log("❌ $name failed to initialize")
                }
                
            } catch (e: Exception) {
                log("⚠️ $name error: ${e.message}")
            }
        }
        
        if (success && record != null) {
            audioRecord = record
            isCapturing = true
            
            startCaptureThread(sampleRate, bufferSize)
            
            log("✅✅✅ ENHANCED CAPTURE STARTED SUCCESSFULLY!")
            onSuccess(true)
        } else {
            log("❌❌❌ ALL STRATEGIES FAILED")
            record?.release()
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
                        
                        if (readCount % 500L == 0L) {
                            log("📊 Enhanced capture: $readCount reads, last=$read bytes")
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
        }, "AdvancedAudioCapture").apply { 
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
        
        try {
            mediaProjection?.stop()
        } catch (_: Throwable) {}
        mediaProjection = null
    }
    
    private fun log(msg: String) {
        RhythmicLog.x("RhythmicTouch-Advanced", msg)
    }
}