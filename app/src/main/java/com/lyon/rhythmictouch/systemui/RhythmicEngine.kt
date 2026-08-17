package com.lyon.rhythmictouch.systemui

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.lyon.rhythmictouch.LiveState
import com.lyon.rhythmictouch.RhythmicConstants
import com.lyon.rhythmictouch.config.VibrationParams

class RhythmicEngine(context: Context) {
    private val appContext = context.applicationContext
    private val vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
        (appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        appContext.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
    }
    private val analyzer = BeatAnalyzer()
    private val driver = VibratorDriver(vibrator, analyzer)
    private val configBridge = ConfigBridge(appContext)
    private val activeTracker = ActiveAppTracker(appContext)
    private val statusThread = HandlerThread("rhythmic-status").apply { start() }
    private val statusHandler = Handler(statusThread.looper)

    @Volatile
    private var capturer: AudioCapturer? = null

    @Volatile
    private var observing = false

    @Volatile
    private var lastStatusMs = 0L

    @Volatile
    private var lastProbeMs = 0L

    @Volatile
    private var attachedSession = Int.MIN_VALUE

    @Volatile
    private var attachFailed = false

    @Volatile
    private var lastAttachTryMs = 0L
    
    @Volatile
    private var frameCount = 0L

    fun start() {
        log("engine.start()")
        stop()
        val cap = AudioCapturer.create()
        capturer = cap
        cap.setFftListener(::onFftData)
        LiveState.engineActive = cap.startDefault()
        log("capturer started, engineActive=${LiveState.engineActive}, samplingRate=${cap.samplingRate}, captureSize=${cap.captureSize}")
        configBridge.refresh(force = true)
        startSessionWatcher()
    }

    private fun startSessionWatcher() {
        statusHandler.post(object : Runnable {
            override fun run() {
                val e = capturer ?: return
                activeTracker.refresh()
                val session = activeTracker.primarySessionId()
                val now = SystemClock.elapsedRealtime()
                val needRetry = attachFailed && now - lastAttachTryMs >= ATTACH_RETRY_MS
                
                if (session != attachedSession || needRetry) {
                    attachedSession = session
                    lastAttachTryMs = now
                    
                    log("Session switch: $attachedSession")
                    
                    if (session == 0) {
                        attachFailed = !e.attachToSession(0)
                        log("Global Visualizer: ${if (!attachFailed) "✅" else "❌"}")
                    } else if (session > 0) {
                        attachFailed = !e.attachToSession(session)
                        log("Player session $session: ${if (!attachFailed) "✅" else "❌"}")
                    } else if (session == -9999) {
                        log("🎮 Phira AAudio detected, trying Global Visualizer for main screen...")
                        attachFailed = !e.attachToSession(0)
                        if (!attachFailed) {
                            log("✅ Global Visualizer active for Phira (main screen support)")
                        } else {
                            log("⚠️ Global Visualizer failed, relying on in-process hook for gameplay")
                        }
                    }
                    
                    if (attachFailed && session != -9999) {
                        log("Attachment FAILED for session=$session! Will retry in ${ATTACH_RETRY_MS}ms")
                    }
                }
                
                e.getFftSnapshot()?.let { onFftData(it, e.samplingRate) }
                statusHandler.postDelayed(this, SESSION_WATCH_MS)
            }
        })
    }

    fun stop() {
        LiveState.engineActive = false
        capturer?.stop()
        capturer?.release()
        capturer = null
        driver.stop()
    }

    fun setObserving(value: Boolean) {
        observing = value
        LiveState.observing = value
        log("setObserving -> $value")
    }

    fun refreshConfig() {
        val config = configBridge.refresh(force = true)
        RhythmicLog.mode = config.logMode
        driver.updateParams(config.vibrationParams)
        driver.updateDelayMs(config.vibrationDelay.toLong())
        log("refreshConfig: active profile params applied, heavyLong=${config.vibrationParams.ampOf(com.lyon.rhythmictouch.config.VibrationParams.KEY_HEAVY_LONG)}%/${config.vibrationParams.durOf(com.lyon.rhythmictouch.config.VibrationParams.KEY_HEAVY_LONG)}ms delay=${config.vibrationDelay}ms")
    }

    fun testVibration(modeKey: String) {
        log("🧪 testVibration: modeKey=$modeKey")
        driver.testVibration(modeKey)
    }

    fun dispose() {
        setObserving(false)
        stop()
        statusThread.quitSafely()
    }

    @Volatile
    private var lastPhiraDataTimeMs = 0L
    
    fun onExternalFftData(fft: ByteArray, samplingRate: Int) {
        log("📥 onExternalFftData called: size=${fft.size}, rate=$samplingRate")
        val now = SystemClock.elapsedRealtime()
        lastPhiraDataTimeMs = now  // Mark Phira as active
        
        onFftData(fft, samplingRate, isPhira = true)
        
        log("📤 onExternalFftData completed")
    }

    private fun onFftData(fft: ByteArray, samplingRate: Int, isPhira: Boolean = false) {
        log("🔬 onFftData ENTERED: size=${fft.size}, rate=$samplingRate, source=${if (isPhira) "🎮Phira" else "🌐Global"}, analyzer=${if (analyzer != null) "✅" else "❌ NULL"}")
        val now = SystemClock.elapsedRealtime()
        
        val result = analyzer?.analyze(fft, samplingRate, now)
        
        if (result == null) {
            log("⚠️ analyzer returned NULL! Skipping FFT processing")
            return
        }
        
        // Smart filtering: If Phira is active recently, ignore Global's empty data
        val phiraActiveRecently = (now - lastPhiraDataTimeMs) < 2000L  // Within 2 seconds
        if (!isPhira && phiraActiveRecently && result.level < 0.05f) {
            if (frameCount++ % 100L == 0L) {
                log("⏭️ Skipping empty Global data (level=${"%.3f".format(result.level)}) while Phira is active")
            }
            return
        }
        
        log("✅ analyze() success: level=${"%.2f".format(result.level)}, source=${if (isPhira) "🎮Phira" else "🌐Global"}")

        if (now - lastProbeMs >= PROBE_INTERVAL_MS) {
            lastProbeMs = now
            log("fft level=${"%.2f".format(result.level)} bands=${result.bandCount} peakBand=#${result.peakBand} beat=${result.beat} top3=${result.bandSummary()}")
        }

        activeTracker.refresh()
        val config = configBridge.refresh()
        RhythmicLog.mode = config.logMode

        val foregroundApp = activeTracker.primaryApp()
        val matchedProfile = if (foregroundApp != null) {
            config.profiles.firstOrNull { it.scopeApps.isNotEmpty() && foregroundApp in it.scopeApps }
        } else null
        val effectiveParams = matchedProfile?.params ?: config.vibrationParams

        driver.updateParams(effectiveParams)
        driver.updateDelayMs(config.vibrationDelay.toLong())
        val blocked = !config.enabled || activeTracker.isBlocked(config.whitelistMode, config.excludedApps)

        log("🔍 DEBUG: enabled=${config.enabled}, whitelistMode=${config.whitelistMode}, scopeApps=${config.excludedApps}, isBlocked=${activeTracker.isBlocked(config.whitelistMode, config.excludedApps)}, blocked=$blocked, level=${"%.2f".format(result.level)}, foreground=$foregroundApp, matchedProfile=${matchedProfile?.name ?: "默认"}")
        
        if (blocked) {
            log("❌ VIBRATION BLOCKED! driver.stop() called")
        } else {
            log("✅ VIBRATION ALLOWED! driver.onAnalysis() called with intensity=${config.intensity}")
        }

        LiveState.level = result.level
        LiveState.bass = result.bass()
        LiveState.mid = result.mid()
        LiveState.treble = result.treble()
        LiveState.beat = result.beat
        LiveState.peakBandIndex = result.peakBand
        LiveState.bands = result.bands.mapIndexed { idx, value ->
            val (start, end) = analyzer!!.getBandFrequencyRange(idx)
            SpectrumBand(index = idx, value = value, freqStart = start, freqEnd = end)
        }
        LiveState.markUpdated()

        if (blocked) {
            log("❌ VIBRATION BLOCKED! driver.stop() called")
        } else {
            log("🔍 Before driver: bands.size=${result.bands.size}, first5=${result.bands.take(5)}, level=${"%.2f".format(result.level)}")
            driver.onAnalysis(result, config.intensity.toFloat() / 100f, now)
        }

        if (observing) {
            if (now - lastStatusMs >= STATUS_INTERVAL_MS) {
                lastStatusMs = now
                val app = activeTracker.primaryApp()
                statusHandler.post {
                    try {
                        val intent = Intent(RhythmicConstants.ACTION_LIVE_STATUS).apply {
                            setPackage(RhythmicConstants.MODULE_PACKAGE)
                            putExtra(RhythmicConstants.EXTRA_ACTIVE, true)
                            putExtra(RhythmicConstants.EXTRA_LEVEL, result.level)
                            putExtra(RhythmicConstants.EXTRA_BASS, result.bass())
                            putExtra(RhythmicConstants.EXTRA_MID, result.mid())
                            putExtra(RhythmicConstants.EXTRA_TREBLE, result.treble())
                            putExtra(RhythmicConstants.EXTRA_BEAT, result.beat)
                            putExtra(RhythmicConstants.EXTRA_ACTIVE_APP, app)
                            putExtra(RhythmicConstants.EXTRA_BLOCKED, blocked)
                            putExtra(RhythmicConstants.EXTRA_BANDS, result.bands)
                            putExtra(RhythmicConstants.EXTRA_PEAK_BAND_INDEX, result.peakBand)
                            putExtra(RhythmicConstants.EXTRA_VIBRATION_MODE, driver.currentMode)
                        }
                        appContext.sendBroadcast(intent)
                    } catch (t: Throwable) {
                        log("sendBroadcast failed: $t")
                    }
                }
            }
        }
    }

    private fun log(msg: String) {
        RhythmicLog.x(TAG, msg)
    }

    private companion object {
        const val STATUS_INTERVAL_MS = 100L
        const val PROBE_INTERVAL_MS = 5000L
        const val SESSION_WATCH_MS = 100L
        const val ATTACH_RETRY_MS = 2000L
        const val TAG = "RhythmicTouch"
    }
}
