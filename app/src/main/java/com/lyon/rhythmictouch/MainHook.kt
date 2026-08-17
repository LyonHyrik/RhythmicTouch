package com.lyon.rhythmictouch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.lyon.rhythmictouch.config.ConfigStore
import com.lyon.rhythmictouch.systemui.AAudioInterceptor
import com.lyon.rhythmictouch.systemui.NativeAudioInterceptor
import com.lyon.rhythmictouch.systemui.OboeBridge
import com.lyon.rhythmictouch.systemui.SystemUiHaptics
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.lyon.rhythmictouch.BuildConfig

class MainHook : IXposedHookLoadPackage {

    private val aaudioInterceptor = AAudioInterceptor.getInstance()
    private val nativeInterceptor = NativeAudioInterceptor.getInstance()

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        XposedBridge.log("[RhythmicTouch] 🔍 handleLoadPackage: ${lpparam.packageName} / process=${lpparam.processName}")
        
        when (lpparam.packageName) {
            RhythmicConstants.SYSTEMUI_PACKAGE -> {
                XposedBridge.log("[RhythmicTouch] ✅ Injected into SystemUI, scheduling engine start")
                try {
                    SystemUiHaptics.setAaudioInterceptor(aaudioInterceptor)
                    SystemUiHaptics.setNativeInterceptor(nativeInterceptor)
                    scheduleStart()
                    
                    Handler(Looper.getMainLooper()).postDelayed({
                        try {
                            XposedBridge.log("[RhythmicTouch] 🎵🔧 Installing NATIVE AudioTrack hooks in SystemUI...")
                            
                            nativeInterceptor.setAppContext(currentApplication())
                            val nativeResult = nativeInterceptor.interceptSystemLevel(lpparam)
                            
                            if (nativeResult) {
                                XposedBridge.log("[RhythmicTouch] ✅✅✅ SYSTEMUI AudioTrack HOOKS INSTALLED!")
                            } else {
                                XposedBridge.log("[RhythmicTouch] ⚠️ SystemUI hooks limited")
                            }
                            
                            val aaudioResult = aaudioInterceptor.interceptSystemLevel(lpparam)
                            XposedBridge.log("[RhythmicTouch] 🎵 SystemUI AAudio intercept: $aaudioResult")
                            
                            try {
                                val ctx = currentApplication()?.applicationContext
                                if (ctx != null) {
                                    val config = ConfigStore(ctx).read()
                                    aaudioInterceptor.updateInterval(config.aaudioIntervalMs.toLong())
                                    nativeInterceptor.setSyncEnabled(config.syncAaudioWithAudioTrack)
                                    XposedBridge.log("[RhythmicTouch] 📊 Initial config applied: interval=${config.aaudioIntervalMs}ms, sync=${config.syncAaudioWithAudioTrack}")
                                }
                            } catch (_: Throwable) {}
                            
                        } catch (t: Throwable) {
                            XposedBridge.log("[RhythmicTouch] ❌ SystemUI init failed: $t")
                        }
                    }, 3000)
                    
                } catch (t: Throwable) {
                    XposedBridge.log("[RhythmicTouch] ❌ scheduleStart failed: $t")
                    t.printStackTrace()
                }
            }
            
            "org.flos.phira" -> {
                XposedBridge.log("[RhythmicTouch] 🎮🎮🎮 PHIRA DETECTED! Installing Oboe/AAudio hooks in Phira process! 🎮🎮🎮")
                try {
                    Handler(Looper.getMainLooper()).postDelayed({
                        try {
                            XposedBridge.log("[RhythmicTouch] 🔧 Installing OboePcmCapture (C++ GOT Hook) for Phira...")
                            
                            val oboeBridge = OboeBridge()
                            
                            val vibrator = try {
                                val smClass = Class.forName("android.os.ServiceManager")
                                val method = smClass.getMethod("getService", String::class.java)
                                method.invoke(null, "vibrator")
                            } catch (e: Exception) { null }

                            var sendIntervalMs = 100L

                            try {
                                val app = Class.forName("android.app.ActivityThread")
                                    .getMethod("currentApplication")
                                    .invoke(null) as Context
                                val config = ConfigStore(app.applicationContext).read()
                                sendIntervalMs = config.aaudioIntervalMs.toLong()
                                oboeBridge.updateInterval(sendIntervalMs)
                                XposedBridge.log("[RhythmicTouch-Phira] 📊 Loaded config: aaudioIntervalMs=$sendIntervalMs, syncWithAudioTrack=${config.syncAaudioWithAudioTrack}")

                                val filter = IntentFilter().apply {
                                    addAction(RhythmicConstants.ACTION_REFRESH_CONFIG)
                                    addAction(RhythmicConstants.ACTION_SYNC_AAUDIO_INTERVAL)
                                }
                                val configReceiver = object : BroadcastReceiver() {
                                    override fun onReceive(context: Context?, intent: Intent?) {
                                        when (intent?.action) {
                                            RhythmicConstants.ACTION_REFRESH_CONFIG -> {
                                                val interval = intent.getIntExtra(RhythmicConstants.EXTRA_AAUDIO_INTERVAL_MS, -1)
                                                val syncMode = intent.getBooleanExtra(RhythmicConstants.EXTRA_SYNC_ENABLED, false)
                                                if (interval >= 0) {
                                                    if (!syncMode) {
                                                        sendIntervalMs = interval.toLong()
                                                        oboeBridge.updateInterval(sendIntervalMs)
                                                        XposedBridge.log("[RhythmicTouch-Phira] 📊 Manual interval updated: ${sendIntervalMs}ms")
                                                    } else {
                                                        try {
                                                            app.applicationContext.sendBroadcast(Intent(RhythmicConstants.ACTION_REQUEST_DETECTED_INTERVAL))
                                                            XposedBridge.log("[RhythmicTouch-Phira] 📡 Requesting detected AudioTrack interval...")
                                                        } catch (_: Exception) {}
                                                    }
                                                } else {
                                                    val config = ConfigStore(app.applicationContext).read()
                                                    sendIntervalMs = config.aaudioIntervalMs.toLong()
                                                    oboeBridge.updateInterval(sendIntervalMs)
                                                    XposedBridge.log("[RhythmicTouch-Phira] 📊 Config refreshed (fallback): ${sendIntervalMs}ms")
                                                }
                                            }
                                            RhythmicConstants.ACTION_SYNC_AAUDIO_INTERVAL -> {
                                                val interval = intent.getIntExtra(RhythmicConstants.EXTRA_AAUDIO_INTERVAL_MS, 0)
                                                if (interval > 0) {
                                                    sendIntervalMs = interval.toLong()
                                                    oboeBridge.updateInterval(sendIntervalMs)
                                                    XposedBridge.log("[RhythmicTouch-Phira] 📡 Interval synced: ${sendIntervalMs}ms")
                                                }
                                            }
                                        }
                                    }
                                }
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                    app.applicationContext.registerReceiver(configReceiver, filter, Context.RECEIVER_EXPORTED)
                                } else {
                                    @Suppress("DEPRECATION")
                                    app.applicationContext.registerReceiver(configReceiver, filter)
                                }
                            } catch (ctxEx: Exception) {
                                XposedBridge.log("[RhythmicTouch-Phira] ⚠️ Config init failed: ${ctxEx.message}")
                            }
                            
                            oboeBridge.fftListener = object : (ByteArray, Int) -> Unit {
                                var lastSendTimeMs = 0L
                                
                                override fun invoke(fft: ByteArray, rate: Int) {
                                    try {
                                        if (fft.isEmpty()) return
                                        
                                        val avgLevel = fft.map { it.toInt() and 0xFF }.average()
                                        val maxLevel = fft.maxOf { it.toInt() and 0xFF }
                                        
                                        if (oboeBridge.frameCount % 100L == 0L) {
                                            XposedBridge.log("[RhythmicTouch-Phira-Oboe] 🎵🔥 FFT: ${fft.size} bins, avg=$avgLevel, max=$maxLevel")
                                        }
                                        
                                        val nowMs = System.currentTimeMillis()
                                        if (nowMs - lastSendTimeMs < sendIntervalMs) return
                                        lastSendTimeMs = nowMs
                                        
                                        try {
                                            val intent = Intent("com.lyon.rhythmictouch.ACTION_PHIRA_FFT_DATA").apply {
                                                putExtra("fft_data", fft)
                                                putExtra("sampling_rate", rate)
                                                putExtra("source_app", "org.flos.phira")
                                                setPackage("com.android.systemui")
                                            }
                                            
                                            try {
                                                val app = Class.forName("android.app.ActivityThread")
                                                    .getMethod("currentApplication")
                                                    .invoke(null) as Context
                                                app.applicationContext.sendBroadcast(intent)
                                            } catch (ctxEx: Exception) {
                                                if (oboeBridge.frameCount % 1000L == 0L) {
                                                    XposedBridge.log("[RhythmicTouch-Phira-Oboe] ❌ Failed to get context: ${ctxEx.message}")
                                                }
                                            }
                                            
                                            if (oboeBridge.frameCount % 200L == 0L) {
                                                XposedBridge.log("[RhythmicTouch-Phira-Oboe] 📤✅ FFT data sent to SystemUI via IPC! size=${fft.size}")
                                            }
                                        } catch (ipcEx: Exception) {
                                            if (oboeBridge.frameCount % 1000L == 0L) {
                                                XposedBridge.log("[RhythmicTouch-Phira-Oboe] ❌ IPC send failed: ${ipcEx.message}")
                                            }
                                        }
                                    } catch (t: Throwable) {
                                        if (oboeBridge.frameCount % 1000L == 0L) {
                                            XposedBridge.log("[RhythmicTouch-Phira-Oboe] ❌ FFT processing error: ${t.message}")
                                        }
                                    }
                                }
                            }
                            
                            val oboeInstalled = oboeBridge.install()
                            
                            if (oboeInstalled) {
                                XposedBridge.log("[RhythmicTouch] ✅✅✅ PHIRA OBOE CAPTURE INSTALLED (C++ GOT Hook)!")
                                XposedBridge.log("[RhythmicTouch] 🎵 Phira's AAudioStream_write() will be intercepted at NATIVE level!")
                            } else {
                                XposedBridge.log("[RhythmicTouch] ⚠️ OboeCapture install failed, trying Kotlin fallback...")
                                
                                val phiraNativeHook = NativeAudioInterceptor.getInstance()
                                phiraNativeHook.setFftListener { fft, rate ->
                                    XposedBridge.log("[RhythmicTouch-Phira-Fallback] 📊 Fallback FFT: size=${fft.size}")
                                }
                                
                                val fallbackResult = phiraNativeHook.interceptSystemLevel(lpparam)
                                
                                if (fallbackResult) {
                                    XposedBridge.log("[RhythmicTouch] ⚠️ Using AudioTrack fallback (limited for AAudio apps)")
                                } else {
                                    XposedBridge.log("[RhythmicTouch] ❌ All capture methods failed for Phira")
                                }
                            }
                            
                        } catch (t: Throwable) {
                            XposedBridge.log("[RhythmicTouch] ❌ Phira hook FAILED: $t")
                            t.printStackTrace()
                        }
                    }, 2000)
                    
                } catch (t: Throwable) {
                    XposedBridge.log("[RhythmicTouch] ❌ Phira handler setup failed: $t")
                    t.printStackTrace()
                }
            }
            
            else -> {
                if (lpparam.packageName.contains("phira", ignoreCase = true)) {
                    XposedBridge.log("[RhythmicTouch] 🎮 Possible Phira variant: ${lpparam.packageName}")
                }
            }
        }
    }

    private fun scheduleStart() {
        XposedBridge.log("[RhythmicTouch] ⏰ scheduleStart() called, will start engine in 3000ms...")
        
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                XposedBridge.log("[RhythmicTouch] 🚀 3000ms elapsed! Starting engine now...")
                
                val app = currentApplication()
                XposedBridge.log("[RhythmicTouch] 🔍 currentApplication() result: $app")
                
                if (app == null) {
                    XposedBridge.log("[RhythmicTouch] ❌ currentApplication() is null, abort")
                    return@postDelayed
                }
                
                XposedBridge.log("[RhythmicTouch] ✅ currentApplication=${app.packageName}, starting engine")
                
                reportModuleVersion()
                SystemUiHaptics.start(app.applicationContext)
                
                aaudioInterceptor.setFftListener { fft, rate ->
                    SystemUiHaptics.onAaudioFftData(fft, rate)
                }
                
                XposedBridge.log("[RhythmicTouch] ✅ SystemUiHaptics.start() returned successfully")
            } catch (t: Throwable) {
                Log.e("RhythmicTouch", "engine start failed", t)
                XposedBridge.log("[RhythmicTouch] ❌ engine start failed: $t")
                t.printStackTrace()
            }
        }, 3000)
    }

    private fun currentApplication(): Context? = try {
        Class.forName("android.app.ActivityThread")
            .getMethod("currentApplication")
            .invoke(null) as Context?
    } catch (t: Throwable) {
        null
    }

    private fun reportModuleVersion() {
        try {
            val app = currentApplication() ?: return
            val bundle = android.os.Bundle()
            app.contentResolver.call(
                RhythmicConstants.PROVIDER_URI,
                RhythmicConstants.METHOD_SET_MODULE_VERSION,
                BuildConfig.VERSION_CODE.toString(),
                bundle,
            )
            XposedBridge.log("[RhythmicTouch] ✅ Module version reported: ${BuildConfig.VERSION_CODE}")
        } catch (t: Throwable) {
            XposedBridge.log("[RhythmicTouch] ⚠️ Failed to report module version: $t")
        }
    }
}