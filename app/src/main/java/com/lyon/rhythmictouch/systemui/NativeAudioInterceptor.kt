package com.lyon.rhythmictouch.systemui

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import com.lyon.rhythmictouch.RhythmicConstants
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.nio.ByteBuffer
import java.nio.ByteOrder

class NativeAudioInterceptor private constructor() {

    private var fftListener: ((ByteArray, Int) -> Unit)? = null
    
    @Volatile
    var isIntercepting = false
    
    @Volatile
    private var interceptedPackets = 0L

    @Volatile
    private var lastWriteTimeMs = 0L

    @Volatile
    private var detectedIntervalMs = 100L

    @Volatile
    private var syncEnabled = false

    private var appContext: Context? = null

    fun setSyncEnabled(enabled: Boolean) {
        syncEnabled = enabled
        if (enabled) {
            broadcastDetectedInterval()
        }
    }

    fun setAppContext(context: Context?) {
        appContext = context?.applicationContext
    }

    fun getDetectedIntervalMs(): Long = detectedIntervalMs
    
    private val audioBuffer = java.util.concurrent.ConcurrentLinkedQueue<ByteArray>()
    
    fun setFftListener(listener: ((ByteArray, Int) -> Unit)?) {
        fftListener = listener
    }

    fun interceptSystemLevel(lpparam: XC_LoadPackage.LoadPackageParam): Boolean {
        log("🎯 Starting ULTRA audio interception for ${lpparam.packageName}")
        
        try {
            // Strategy 1: Hook ALL AudioTrack methods (most important!)
            hookAllAudioTrackMethods(lpparam)
            
            // Strategy 2: Hook AudioSystem (low-level)
            hookAudioSystem(lpparam)
            
            // Strategy 3: Try to hook native layer via reflection
            hookNativeLayer(lpparam)
            
            // ⚠️ DISABLED: AudioRecord was causing media sound to disappear!
            // If you need backup capture, uncomment at your own risk:
            // startAudioRecordBackup(lpparam)
            
            isIntercepting = true
            log("✅✅✅ ULTRA INTERCEPTION ACTIVE! (AudioRecord disabled to preserve sound)")
            
            return true
            
        } catch (t: Throwable) {
            log("❌ Ultra interception failed: ${t.message}")
            t.printStackTrace()
            return false
        }
    }

    private fun hookAllAudioTrackMethods(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            log("🔧 Hooking ALL AudioTrack methods...")
            
            val audioTrackClass = XposedHelpers.findClass("android.media.AudioTrack", lpparam.classLoader)
            
            // Hook constructor to capture creation parameters
            for (constructor in audioTrackClass.declaredConstructors) {
                try {
                    XposedHelpers.findAndHookConstructor(
                        audioTrackClass,
                        *constructor.parameterTypes,
                        object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                log("🎵 AudioTrack created: ${param.args.joinToString(", ") { it?.javaClass?.simpleName ?: "null" }}")
                            }
                        }
                    )
                } catch (e: Exception) {}
            }
            
            // Hook ALL write methods with enhanced logging
            val writeMethods = arrayOf(
                Triple("write", arrayOf(ByteArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType), "byte[]"),
                Triple("write", arrayOf(ByteArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType), "byte[]+sync"),
                Triple("write", arrayOf(ByteBuffer::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType), "ByteBuffer"),
                Triple("write", arrayOf(FloatArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType), "float[]"),
                Triple("write", arrayOf(FloatArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType), "float[]+sync"),
                Triple("write", arrayOf(ShortArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType), "short[]"),
                Triple("write", arrayOf(ShortArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType), "short[]+sync")
            )
            
            for ((methodName, paramTypes, desc) in writeMethods) {
                try {
                    XposedHelpers.findAndHookMethod(
                        audioTrackClass,
                        methodName,
                        *paramTypes,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                super.beforeHookedMethod(param)
                                
                                val buffer = param.args[0] ?: return
                                val size = when (buffer) {
                                    is ByteArray -> buffer.size
                                    is ByteBuffer -> buffer.remaining()
                                    is FloatArray -> buffer.size * 4
                                    is ShortArray -> buffer.size * 2
                                    else -> 0
                                }
                                
                                if (size > 0) {
                                    interceptedPackets++
                                    
                                    if (interceptedPackets % 100L == 0L) {
                                        log("🎵📊 AudioTrack.$desc WRITE: packets=$interceptedPackets size=${size}B")
                                    }
                                    
                                    processAudioData(buffer, 0, size, "AudioTrack-$desc")
                                }
                            }

                            override fun afterHookedMethod(param: MethodHookParam) {
                                val result = param.result as? Int ?: return
                                if (result > 0 && interceptedPackets % 500L == 0L) {
                                    log("   ✅ Write result: $result bytes")
                                }
                            }
                        }
                    )
                    log("   ✅ Hooked AudioTrack.$methodName($desc)")
                } catch (t: Throwable) {
                    log("   ⚠️ Failed $methodName($desc): ${t.message}")
                }
            }
            
            // Hook play() and stop() to track lifecycle
            try {
                XposedHelpers.findAndHookMethod(audioTrackClass, "play", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        log("▶️ AudioTrack.play() called - TRACK STARTED")
                        isIntercepting = true
                    }
                })
                log("   ✅ Hooked AudioTrack.play()")
            } catch (e: Exception) {}
            
            try {
                XposedHelpers.findAndHookMethod(audioTrackClass, "stop", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        log("⏹️ AudioTrack.stop() called")
                    }
                })
                log("   ✅ Hooked AudioTrack.stop()")
            } catch (e: Exception) {}
            
            // Hook release()
            try {
                XposedHelpers.findAndHookMethod(audioTrackClass, "release", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        log("🗑️ AudioTrack.release() called")
                    }
                })
                log("   ✅ Hooked AudioTrack.release()")
            } catch (e: Exception) {}
            
            log("✅ All AudioTrack hooks installed!")
            
        } catch (t: Throwable) {
            log("❌ AudioTrack hook failed: ${t.message}")
        }
    }

    private fun hookAudioSystem(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            log("🔧 Hooking AudioSystem...")
            
            val audioSystemClass = XposedHelpers.findClass("android.media.AudioSystem", lpparam.classLoader)
            
            // Hook all static methods that might indicate audio activity
            for (method in audioSystemClass.declaredMethods) {
                if (method.name.contains("audio", ignoreCase = true) || 
                    method.name.contains("track", ignoreCase = true) ||
                    method.name.contains("stream", ignoreCase = true)) {
                    
                    try {
                        XposedHelpers.findAndHookMethod(
                            audioSystemClass,
                            method.name,
                            *method.parameterTypes,
                            object : XC_MethodHook() {
                                override fun afterHookedMethod(param: MethodHookParam) {
                                    if (interceptedPackets % 1000L == 0L) {
                                        log("🔊 AudioSystem.${method.name}(${param.args.size} args)")
                                    }
                                }
                            }
                        )
                    } catch (e: Exception) {}
                }
            }
            
            log("✅ AudioSystem hooked")
            
        } catch (t: Throwable) {
            log("⚠️ AudioSystem not found or hook failed: ${t.message}")
        }
    }

    private fun hookNativeLayer(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            log("🔧 Attempting Native layer hooks via reflection...")
            
            // Try to access libandroid.so / libmedia.so functions through JNI
            val runtime = Runtime.getRuntime()
            
            // Try loading libraries to see what's available
            val libraries = listOf(
                "libaaudio.so",
                "libaaudio_internal.so",
                "libmedia.so",
                "libaudioclient.so"
            )
            
            for (lib in libraries) {
                try {
                    runtime.loadLibrary(lib.removePrefix("lib").removeSuffix(".so"))
                    log("   ✅ Loaded: $lib")
                } catch (e: UnsatisfiedLinkError) {
                    log("   ⚪ Not available: $lib")
                }
            }
            
            // Try to find and hook Oboe-related classes
            val possibleOboeClasses = listOf(
                "com.google.oboe.AudioStream",
                "com.google.oboe.AudioOutputStream",
                "oboe.AudioStream"
            )
            
            for (className in possibleOboeClasses) {
                try {
                    val clazz = XposedHelpers.findClass(className, lpparam.classLoader)
                    log("   🎯 Found Oboe class: $className")
                    
                    // Hook all methods
                    for (method in clazz.declaredMethods) {
                        if (method.name.contains("write", ignoreCase = true) ||
                            method.name.contains("open", ignoreCase = true)) {
                            
                            XposedHelpers.findAndHookMethod(
                                clazz,
                                method.name,
                                *method.parameterTypes,
                                object : XC_MethodHook() {
                                    override fun afterHookedMethod(param: MethodHookParam) {
                                        log("🎮 OBOE.${method.name} CALLED! args=${param.args.size}")
                                        isIntercepting = true
                                    }
                                }
                            )
                        }
                    }
                } catch (e: Exception) {}
            }
            
        } catch (t: Throwable) {
            log("⚠️ Native layer hook failed: ${t.message}")
        }
    }

    private var audioRecordThread: Thread? = null
    private var audioRecordRunning = false
    
    private fun startAudioRecordBackup(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            log("🎤 Starting AudioRecord backup capture...")
            
            audioRecordRunning = true
            audioRecordThread = Thread {
                try {
                    val sampleRate = 44100
                    val channelConfig = AudioFormat.CHANNEL_IN_STEREO
                    val audioFormat = AudioFormat.ENCODING_PCM_16BIT
                    val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
                    
                    if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
                        log("❌ Invalid buffer size")
                        return@Thread
                    }
                    
                    val recorder = AudioRecord(
                        MediaRecorder.AudioSource.REMOTE_SUBMIX,
                        sampleRate,
                        channelConfig,
                        audioFormat,
                        bufferSize * 2
                    )
                    
                    if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                        log("⚠️ REMOTE_SUBMIX failed, trying default source...")
                        
                        // Try alternative sources
                        val altSources = intArrayOf(
                            MediaRecorder.AudioSource.MIC,
                            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                            MediaRecorder.AudioSource.VOICE_RECOGNITION,
                            0  // Default
                        )
                        
                        var initialized = false
                        for (source in altSources) {
                            try {
                                val altRecorder = AudioRecord(source, sampleRate, channelConfig, audioFormat, bufferSize * 2)
                                if (altRecorder.state == AudioRecord.STATE_INITIALIZED) {
                                    log("✅ AudioRecord initialized with source=$source")
                                    
                                    altRecorder.startRecording()
                                    
                                    val buffer = ByteArray(bufferSize)
                                    var readCount = 0L
                                    
                                    while (audioRecordRunning) {
                                        val read = altRecorder.read(buffer, 0, buffer.size)
                                        if (read > 0) {
                                            readCount++
                                            processAudioData(buffer, 0, read, "AudioRecord-source$source")
                                            
                                            if (readCount % 100L == 0L) {
                                                log("🎤 AudioRecord: read #$readCount, $read bytes")
                                            }
                                        }
                                        Thread.sleep(10)
                                    }
                                    
                                    altRecorder.stop()
                                    altRecorder.release()
                                    initialized = true
                                    break
                                }
                                altRecorder.release()
                            } catch (e: Exception) {}
                        }
                        
                        if (!initialized) {
                            log("❌ All AudioRecord sources failed")
                        }
                    } else {
                        log("✅ AudioRecord with REMOTE_SUBMIX started")
                        recorder.startRecording()
                        
                        val buffer = ByteArray(bufferSize)
                        var readCount = 0L
                        
                        while (audioRecordRunning) {
                            val read = recorder.read(buffer, 0, buffer.size)
                            if (read > 0) {
                                readCount++
                                processAudioData(buffer, 0, read, "AudioRecord-REMOTE_SUBMIX")
                                
                                if (readCount % 100L == 0L) {
                                    log("🎤 AudioRecord REMOTE_SUBMIX: read #$readCount, $read bytes")
                                }
                            }
                            Thread.sleep(10)
                        }
                        
                        recorder.stop()
                        recorder.release()
                    }
                    
                } catch (t: Throwable) {
                    log("❌ AudioRecord thread error: ${t.message}")
                }
            }.apply {
                name = "AudioRecord-Backup"
                start()
            }
            
            log("✅ AudioRecord backup thread started")
            
        } catch (t: Throwable) {
            log("❌ Failed to start AudioRecord: ${t.message}")
        }
    }

    @Volatile
    private var lastBroadcastTimeMs = 0L

    private fun processAudioData(buffer: Any, offset: Int, size: Int, source: String) {
        if (size < 64) return
        
        val now = SystemClock.elapsedRealtime()
        if (lastWriteTimeMs > 0L) {
            val delta = now - lastWriteTimeMs
            if (delta in 5L..500L) {
                val smoothed = (detectedIntervalMs * 0.7f + delta * 0.3f).toLong()
                val changed = kotlin.math.abs(smoothed - detectedIntervalMs) >= 2L
                detectedIntervalMs = smoothed.coerceIn(33L, 300L)
                if (syncEnabled && (changed || now - lastBroadcastTimeMs > 500L)) {
                    broadcastDetectedInterval()
                    lastBroadcastTimeMs = now
                }
            }
        }
        lastWriteTimeMs = now
        
        try {
            val byteArray = when (buffer) {
                is ByteArray -> if (offset == 0 && size == buffer.size) buffer else buffer.copyOfRange(offset, offset + size)
                is ByteBuffer -> {
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    buffer.rewind()
                    bytes
                }
                is FloatArray -> {
                    val bytes = ByteArray(size)
                    val shorts = ShortArray(size / 2)
                    for (i in 0 until minOf(shorts.size, buffer.size)) {
                        shorts[i] = (buffer[offset + i] * Short.MAX_VALUE).toInt().toShort()
                    }
                    for (i in shorts.indices) {
                        bytes[i * 2] = (shorts[i].toInt() and 0xFF).toByte()
                        bytes[i * 2 + 1] = (shorts[i].toInt() shr 8).toByte()
                    }
                    bytes
                }
                is ShortArray -> {
                    val bytes = ByteArray(size)
                    for (i in 0 until minOf(size / 2, buffer.size)) {
                        bytes[i * 2] = (buffer[offset + i].toInt() and 0xFF).toByte()
                        bytes[i * 2 + 1] = (buffer[offset + i].toInt() shr 8).toByte()
                    }
                    bytes
                }
                else -> return
            }
            
            val fft = performFFT(byteArray)
            fft?.let {
                fftListener?.invoke(it, 44100)
                
                if (interceptedPackets % 2000L == 0L) {
                    log("🎵✨ FFT from $source: ${it.size} bins, level=${it.average()}, detectedInterval=${detectedIntervalMs}ms")
                }
            }
            
        } catch (t: Throwable) {
            if (interceptedPackets % 5000L == 0L) {
                log("⚠️ Process error at packet $interceptedPackets: ${t.message}")
            }
        }
    }

    fun broadcastDetectedInterval() {
        if (!syncEnabled) return
        try {
            val ctx = appContext ?: return
            val interval = detectedIntervalMs.toInt()
            val intent = Intent(RhythmicConstants.ACTION_SYNC_AAUDIO_INTERVAL).apply {
                putExtra(RhythmicConstants.EXTRA_AAUDIO_INTERVAL_MS, interval)
            }
            ctx.sendBroadcast(intent)
            if (interceptedPackets % 50L == 0L) {
                log("📡 Broadcast detected AudioTrack interval: ${interval}ms")
            }
        } catch (_: Throwable) {}
    }

    private fun performFFT(pcmData: ByteArray): ByteArray? {
        return try {
            val samples = convertPCMToFloats(pcmData) ?: return null
            val fftSize = 1024
            
            if (samples.size < fftSize) return null
            
            val real = FloatArray(fftSize)
            val imag = FloatArray(fftSize)
            System.arraycopy(samples, 0, real, 0, minOf(fftSize, samples.size))
            
            applyHanningWindow(real)
            computeFFT(real, imag)
            
            val result = ByteArray(fftSize / 2)
            for (i in result.indices) {
                val magnitude = kotlin.math.sqrt(
                    real[i].toDouble() * real[i].toDouble() + 
                    imag[i].toDouble() * imag[i].toDouble()
                ).toFloat()
                val normalized = (kotlin.math.log10(magnitude + 1.0f) * 50.0f).coerceIn(0.0f, 127.0f)
                result[i] = normalized.toInt().toByte()
            }
            
            result
        } catch (t: Throwable) {
            null
        }
    }

    private fun convertPCMToFloats(pcmData: ByteArray): FloatArray? {
        return try {
            when {
                pcmData.size % 4 == 0 -> {
                    val samples = FloatArray(pcmData.size / 2)
                    val shortBuffer = ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    for (i in samples.indices) {
                        samples[i] = shortBuffer.get(i).toFloat() / Short.MAX_VALUE
                    }
                    samples
                }
                pcmData.size % 2 == 0 -> {
                    val samples = FloatArray(pcmData.size / 2)
                    val shortBuffer = ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    for (i in samples.indices) {
                        samples[i] = shortBuffer.get(i).toFloat() / Short.MAX_VALUE
                    }
                    samples
                }
                else -> {
                    val samples = FloatArray(pcmData.size)
                    for (i in pcmData.indices) {
                        samples[i] = (pcmData[i].toInt() - 128).toFloat() / 128f
                    }
                    samples
                }
            }
        } catch (t: Throwable) {
            null
        }
    }

    private fun applyHanningWindow(data: FloatArray) {
        for (i in data.indices) {
            val window = 0.5f * (1 - Math.cos(2 * Math.PI * i / (data.size - 1)).toDouble()).toFloat()
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

    fun stop() {
        isIntercepting = false
        audioRecordRunning = false
        
        audioRecordThread?.interrupt()
        audioRecordThread = null
        
        log("Ultra interceptor stopped (total packets: $interceptedPackets)")
    }

    companion object {
        @Volatile
        private var instance: NativeAudioInterceptor? = null
        
        fun getInstance(): NativeAudioInterceptor {
            return instance ?: synchronized(this) {
                instance ?: NativeAudioInterceptor().also { instance = it }
            }
        }

        private fun log(msg: String) {
            RhythmicLog.x("RhythmicTouch-Ultra", msg)
        }
    }
}