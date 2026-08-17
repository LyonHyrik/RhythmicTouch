package com.lyon.rhythmictouch.systemui

import android.os.SystemClock
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AAudioInterceptor private constructor() {

    private var fftListener: ((ByteArray, Int) -> Unit)? = null
    
    @Volatile
    var isIntercepting = false
        private set

    @Volatile
    private var hookedPackages = mutableSetOf<String>()

    @Volatile
    private var lastInterceptMs = 0L

    @Volatile
    var interceptIntervalMs: Long = 100L

    fun setFftListener(listener: ((ByteArray, Int) -> Unit)?) {
        fftListener = listener
    }

    fun updateInterval(intervalMs: Long) {
        interceptIntervalMs = intervalMs.coerceIn(33L, 300L)
    }

    fun interceptPackage(lpparam: XC_LoadPackage.LoadPackageParam, targetPkg: String): Boolean {
        log("🎯 interceptPackage called for: $targetPkg (current: ${lpparam.packageName})")
        
        if (targetPkg in hookedPackages) {
            log("⏭️ Already hooked: $targetPkg")
            return true
        }
        
        if (lpparam.packageName != targetPkg) {
            log("❌ Package mismatch: expected=$targetPkg, actual=${lpparam.packageName}")
            return false
        }
        
        try {
            log("🔧 Attempting to hook AAudio for package: $targetPkg")
            
            val aaudioStreamClass = XposedHelpers.findClass("android.media.AAudioStream", lpparam.classLoader)
            log("✅ Found AAudioStream class: $aaudioStreamClass")
            
            hookAAudioStreamOpen(lpparam, aaudioStreamClass)
            hookAAudioStreamSetDataCallback(lpparam, aaudioStreamClass)
            hookAAudioStreamWrite(lpparam, aaudioStreamClass)
            
            hookedPackages.add(targetPkg)
            log("✅ Successfully hooked AAudio for: $targetPkg")
            return true
            
        } catch (t: Throwable) {
            log("❌ Failed to hook AAudio for $targetPkg: ${t.message}")
            t.printStackTrace()
            return false
        }
    }

    fun interceptSystemLevel(lpparam: XC_LoadPackage.LoadPackageParam): Boolean {
        log("🎯 Starting system-level AAudio interception in SystemUI process")
        
        try {
            log("🔧 Attempting to hook system audio classes...")
            
            val success = hookSystemAudioRecord(lpparam) || 
                         hookAudioFlinger(lpparam) || 
                         hookGlobalAudioMix(lpparam)
            
            if (success) {
                isIntercepting = true
                log("✅ System-level AAudio interception activated")
            } else {
                log("⚠️ System-level hooks not available, falling back to enhanced capture")
                setupEnhancedAudioCapture(lpparam)
            }
            
            return true
            
        } catch (t: Throwable) {
            log("❌ System-level AAudio interception failed: ${t.message}")
            t.printStackTrace()
            return false
        }
    }

    private fun hookSystemAudioRecord(lpparam: XC_LoadPackage.LoadPackageParam): Boolean {
        return try {
            val audioRecordClass = XposedHelpers.findClass("android.media.AudioRecord", lpparam.classLoader)
            log("✅ Found AudioRecord class, setting up global capture hook")
            
            XposedHelpers.findAndHookMethod(
                audioRecordClass,
                "read",
                ByteArray::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val result = param.result as? Int ?: return
                        if (result <= 0) return
                        
                        val buffer = param.args[0] as? ByteArray ?: return
                        val size = param.args[1] as? Int ?: result
                        
                        if (size > 64) {
                            processAudioData(buffer, 0, size, "SystemUI-AudioRecord")
                        }
                    }
                }
            )
            
            hookedPackages.add("system-audiorecord")
            log("✅ Hooked AudioRecord.read() for global capture")
            true
            
        } catch (t: Throwable) {
            log("⚠️ AudioRecord hook failed: ${t.message}")
            false
        }
    }

    private fun hookAudioFlinger(lpparam: XC_LoadPackage.LoadPackageParam): Boolean {
        return try {
            log("🔧 Attempting to hook AudioFlinger...")
            
            try {
                val audioFlingerClass = XposedHelpers.findClass("android.media.AudioFlinger", lpparam.classLoader)
                log("✅ Found AudioFlinger class")
                
                hookedPackages.add("audioflinger")
                true
                
            } catch (e: Exception) {
                log("⚠️ AudioFlinger class not found: ${e.message}")
                false
            }
            
        } catch (t: Throwable) {
            log("❌ AudioFlinger hook failed: ${t.message}")
            false
        }
    }

    private fun hookGlobalAudioMix(lpparam: XC_LoadPackage.LoadPackageParam): Boolean {
        return try {
            log("🔧 Setting up global audio mix monitoring...")
            
            val audioManagerClass = XposedHelpers.findClass("android.media.AudioManager", lpparam.classLoader)
            
            XposedHelpers.findAndHookMethod(
                audioManagerClass,
                "isMusicActive",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val active = param.result as? Boolean == true
                        if (active && !isIntercepting) {
                            log("🎵 Music active detected, enabling interception")
                            isIntercepting = true
                        }
                    }
                }
            )
            
            hookedPackages.add("global-audio-monitor")
            log("✅ Global audio monitor activated")
            true
            
        } catch (t: Throwable) {
            log("⚠️ Global audio monitor failed: ${t.message}")
            false
        }
    }

    private fun setupEnhancedAudioCapture(lpparam: XC_LoadPackage.LoadPackageParam) {
        log("🔄 Setting up enhanced audio capture mode...")
        
        try {
            val context = try {
                Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication")
                    .invoke(null) as android.content.Context
            } catch (e: Exception) {
                log("❌ Cannot get application context: ${e.message}")
                return
            }
            
            startPeriodicAudioCheck(context)
            
        } catch (t: Throwable) {
            log("❌ Enhanced capture setup failed: ${t.message}")
        }
    }

    private var periodicCheckHandler: android.os.Handler? = null
    private var periodicRunnable: Runnable? = null
    
    private fun startPeriodicAudioCheck(context: android.content.Context) {
        if (periodicCheckHandler != null) return
        
        periodicCheckHandler = android.os.Handler(android.os.Looper.getMainLooper())
        
        periodicRunnable = object : Runnable {
            override fun run() {
                try {
                    val am = context.getSystemService(android.content.Context.AUDIO_SERVICE) as? android.media.AudioManager
                    
                    if (am?.isMusicActive == true) {
                        isIntercepting = true
                        
                        if (SystemClock.elapsedRealtime() % 3000L < 100L) {
                            log("🎵 Music active - interception enabled (mode=enhanced)")
                        }
                    }
                    
                    periodicCheckHandler?.postDelayed(this, 500L)
                    
                } catch (t: Throwable) {
                    log("Periodic check error: ${t.message}")
                }
            }
        }
        
        periodicCheckHandler?.post(periodicRunnable!!)
        log("✅ Periodic audio check started (500ms interval)")
    }

    private fun hookAAudioStreamOpen(lpparam: XC_LoadPackage.LoadPackageParam, streamClass: Class<*>) {
        try {
            XposedHelpers.findAndHookConstructor(
                streamClass,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val stream = param.thisObject ?: return
                        log("🎵 AAudio stream created: $stream")
                        
                        try {
                            XposedHelpers.setAdditionalInstanceField(stream, "rhythmic_intercepted", true)
                            XposedHelpers.setAdditionalInstanceField(stream, "rhythmic_package", lpparam.packageName)
                            XposedHelpers.setAdditionalInstanceField(stream, "rhythmic_open_time", SystemClock.elapsedRealtime())
                        } catch (t: Throwable) {
                            log("Failed to mark stream: ${t.message}")
                        }
                    }
                }
            )
            log("Hooked AAudioStream constructor")
        } catch (t: Throwable) {
            log("AAudioStream constructor hook failed: ${t.message}")
        }
    }

    private fun hookAAudioStreamSetDataCallback(lpparam: XC_LoadPackage.LoadPackageParam, streamClass: Class<*>) {
        try {
            XposedHelpers.findAndHookMethod(
                streamClass,
                "setDataCallback",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val originalCallback = param.args[0] ?: return
                        val thisObj = param.thisObject ?: return
                        
                        val intercepted = try {
                            XposedHelpers.getAdditionalInstanceField(thisObj, "rhythmic_intercepted") as? Boolean ?: false
                        } catch (e: Exception) { false }
                        
                        if (!intercepted) return
                        
                        log("🔄 Intercepting AAudio data callback for ${lpparam.packageName}")
                        
                        try {
                            val callbackProxy = createCallbackProxy(originalCallback, lpparam.packageName)
                            param.args[0] = callbackProxy
                            isIntercepting = true
                        } catch (t: Throwable) {
                            log("Failed to create callback proxy: ${t.message}")
                        }
                    }
                }
            )
            log("Hooked AAudioStream.setDataCallback()")
        } catch (t: Throwable) {
            log("setDataCallback not found (may use write mode): ${t.message}")
        }
    }

    private fun hookAAudioStreamWrite(lpparam: XC_LoadPackage.LoadPackageParam, streamClass: Class<*>) {
        try {
            arrayOf("write", "writeBlocking").forEach { methodName ->
                try {
                    XposedHelpers.findAndHookMethod(
                        streamClass,
                        methodName,
                        ByteArray::class.java,
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                val buffer = param.args[0] as? ByteArray ?: return
                                val offset = param.args[1] as? Int ?: 0
                                val size = param.args[2] as? Int ?: return
                                
                                if (size <= 0) return
                                
                                val thisObj = param.thisObject ?: return
                                val intercepted = try {
                                    XposedHelpers.getAdditionalInstanceField(thisObj, "rhythmic_intercepted") as? Boolean ?: false
                                } catch (e: Exception) { false }
                                
                                if (!intercepted) return
                                
                                processAudioData(buffer, offset, size, lpparam.packageName)
                            }
                        }
                    )
                    log("Hooked AAudioStream.$methodName()")
                } catch (t: Throwable) {
                    log("$methodName not available: ${t.message}")
                }
            }
        } catch (t: Throwable) {
            log("Failed to hook write methods: ${t.message}")
        }
    }

    private fun createCallbackProxy(originalCallback: Any, pkg: String): Any {
        return try {
            val callbackInterface = originalCallback.javaClass.interfaces.firstOrNull { 
                it.simpleName.contains("Callback", ignoreCase = true) 
            } ?: throw IllegalStateException("Cannot find callback interface")
            
            java.lang.reflect.Proxy.newProxyInstance(
                callbackInterface.classLoader,
                arrayOf(callbackInterface)
            ) { proxy, method, args ->
                if (method.name == "onAudioReady" && args != null && args.size >= 2) {
                    val buffer = args[1]
                    
                    if (buffer is ByteArray && buffer.isNotEmpty()) {
                        processAudioData(buffer, 0, buffer.size, pkg)
                    } else if (buffer is ByteBuffer && buffer.hasRemaining()) {
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        processAudioData(bytes, 0, bytes.size, pkg)
                        buffer.rewind()
                    }
                }
                
                try {
                    method.invoke(originalCallback, *(args ?: emptyArray()))
                } catch (t: Throwable) {
                    log("Original callback failed: ${t.message}")
                    0
                }
            }
        } catch (t: Throwable) {
            log("Failed to create callback proxy: ${t.message}")
            originalCallback
        }
    }

    private fun processAudioData(buffer: ByteArray, offset: Int, size: Int, source: String) {
        if (size < 64) return
        
        val now = SystemClock.elapsedRealtime()
        
        if (now - lastInterceptMs < interceptIntervalMs) return
        lastInterceptMs = now
        
        try {
            val actualBuffer = if (offset == 0 && size == buffer.size) {
                buffer
            } else {
                buffer.copyOfRange(offset, offset + size)
            }
            
            val fft = performFFT(actualBuffer)
            fft?.let {
                if (now % 2000L < 20L) { // 每2秒打印一次状态
                    log("🎵 Processing AAudio data from $source: ${size} bytes -> FFT[${it.size}]")
                }
                fftListener?.invoke(it, 44100)
                isIntercepting = true
            }
            
        } catch (t: Throwable) {
            if (now % 1000L < 20L) { // 只打印每秒一次的错误
                log("processAudioData error: ${t.message}")
            }
        }
    }

    private fun performFFT(pcmData: ByteArray): ByteArray? {
        return try {
            val samples = convertToSamples(pcmData) ?: return null
            val fftSize = 1024
            
            if (samples.size < fftSize) return null
            
            val real = FloatArray(fftSize)
            val imag = FloatArray(fftSize)
            
            System.arraycopy(samples, 0, real, 0, fftSize)
            
            applyHanningWindow(real)
            
            fft(real, imag)
            
            val result = ByteArray(fftSize / 2)
            for (i in 0 until result.size) {
                val realVal = real[i].toDouble()
                val imagVal = imag[i].toDouble()
                val magnitude = kotlin.math.sqrt(realVal * realVal + imagVal * imagVal).toFloat()
                val normalizedMagnitude = (kotlin.math.log10(magnitude + 1.0f) * 50.0f).coerceIn(0.0f, 127.0f)
                result[i] = normalizedMagnitude.toInt().toByte()
            }
            
            result
        } catch (t: Throwable) {
            log("FFT computation failed: ${t.message}")
            null
        }
    }

    private fun fft(real: FloatArray, imag: FloatArray) {
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
            
            var i = 0
            while (i < n) {
                var curReal = 1.0f
                var curImag = 0.0f
                
                for (k in 0 until len2) {
                    val tReal = curReal * real[i + k + len2] - curImag * imag[i + k + len2]
                    val tImag = curReal * imag[i + k + len2] + curImag * real[i + k + len2]
                    
                    real[i + k + len2] = real[i + k] - tReal
                    imag[i + k + len2] = imag[i + k] - tImag
                    real[i + k] += tReal
                    imag[i + k] += tImag
                    
                    val nextReal = curReal * wReal - curImag * wImag
                    curImag = curReal * wImag + curImag * wReal
                    curReal = nextReal
                }
                i += len
            }
            len = len shl 1
        }
    }

    private fun convertToSamples(pcmData: ByteArray): FloatArray? {
        return try {
            when (pcmData.size % 2) {
                0 -> {
                    val shortBuffer = ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    val samples = FloatArray(shortBuffer.capacity())
                    for (i in samples.indices) {
                        samples[i] = shortBuffer.get(i).toFloat() / Short.MAX_VALUE
                    }
                    samples
                }
                1 -> {
                    val samples = FloatArray(pcmData.size)
                    for (i in pcmData.indices) {
                        samples[i] = (pcmData[i].toInt() - 128).toFloat() / 128f
                    }
                    samples
                }
                else -> {
                    val sampleSize = pcmData.size / (pcmData.size / 2)
                    val samples = FloatArray(pcmData.size / sampleSize)
                    for (i in samples.indices) {
                        val start = i * sampleSize
                        if (start + 1 < pcmData.size) {
                            val sample = ((pcmData[start].toInt() and 0xFF) or ((pcmData[start + 1].toInt() and 0xFF) shl 8)).toShort()
                            samples[i] = sample.toFloat() / Short.MAX_VALUE
                        }
                    }
                    samples
                }
            }
        } catch (t: Throwable) {
            log("PCM conversion failed: ${t.message}")
            null
        }
    }

    private fun applyHanningWindow(data: FloatArray) {
        for (i in data.indices) {
            val window = 0.5f * (1 - Math.cos(2 * Math.PI * i / (data.size - 1))).toFloat()
            data[i] *= window
        }
    }

    fun stop() {
        isIntercepting = false
        hookedPackages.clear()
        log("AAudio interceptor stopped")
    }

    companion object {
        @Volatile
        private var instance: AAudioInterceptor? = null
        
        fun getInstance(): AAudioInterceptor {
            return instance ?: synchronized(this) {
                instance ?: AAudioInterceptor().also { instance = it }
            }
        }

        private fun log(msg: String) {
            RhythmicLog.x("RhythmicTouch-AAudio", msg)
        }
    }
}