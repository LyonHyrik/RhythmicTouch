package com.lyon.rhythmictouch.systemui

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import de.robv.android.xposed.XposedBridge
import com.lyon.rhythmictouch.LiveState
import com.lyon.rhythmictouch.RhythmicConstants
import com.lyon.rhythmictouch.config.DeviceVibrationConfig
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
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val flatDetector = FlatDetector(appContext, configBridge)
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
    private var phiraActive = false

    @Volatile
    private var lastPhiraDataTimeMs = 0L

    @Volatile
    private var lastGlobalFftTimeMs = 0L

    @Volatile
    private var detectedGlobalIntervalMs = 100L

    fun start() {
        log("engine.start()")
        stop()
        val cap = AudioCapturer.create()
        capturer = cap
        cap.setFftListener(::onFftData)
        LiveState.engineActive = cap.startDefault()
        log("capturer started, engineActive=${LiveState.engineActive}, samplingRate=${cap.samplingRate}, captureSize=${cap.captureSize}")
        configBridge.refresh(force = true)
        DaemonManager.start(appContext)

        flatDetector.onStateChanged = { paused ->
            driver.flatPaused = paused
            log("Flat detector: paused=$paused")
        }
        flatDetector.start()

        driver.calibrate(appContext) { minMs ->
            try {
                val bundle = android.os.Bundle().apply { putLong("vibrator_min_ms", minMs) }
                val result = appContext.contentResolver.call(
                    android.net.Uri.parse("content://${RhythmicConstants.PROVIDER_AUTHORITY}"),
                    "set_vibrator_calibration", null, bundle
                )
                XposedBridge.log("[RhythmicTouch] 🎯 Calibration result sent to app: minMs=$minMs, providerResult=$result")
            } catch (e: Throwable) {
                XposedBridge.log("[RhythmicTouch] ❌ Calibration ContentProvider call failed: ${e.message}")
            }
        }
        startSessionWatcher()
    }

    private fun startSessionWatcher() {
        statusHandler.post(object : Runnable {
            override fun run() {
                val e = capturer ?: return

                val now = SystemClock.elapsedRealtime()

                if (phiraActive && now - lastPhiraDataTimeMs > PHIRA_TIMEOUT_MS) {
                    phiraActive = false
                    log("Phira timeout (${PHIRA_TIMEOUT_MS}ms), resuming Global Visualizer")
                }

                activeTracker.refresh()
                activeTracker.daemonUids = DaemonManager.refresh()
                val session = activeTracker.primarySessionId()
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
                    }
                    
                    if (attachFailed) {
                        log("Attachment FAILED for session=$session! Will retry in ${ATTACH_RETRY_MS}ms")
                    }
                }
                
                if (!phiraActive) {
                    e.getFftSnapshot()?.let { onFftData(it, e.samplingRate) }
                }
                statusHandler.postDelayed(this, SESSION_WATCH_MS)
            }
        })
    }

    fun stop() {
        LiveState.engineActive = false
        capturer?.stop()
        capturer?.release()
        capturer = null
        flatDetector.stop()
        driver.stop()
    }

    fun setObserving(value: Boolean) {
        observing = value
        LiveState.observing = value
        log("setObserving -> $value")
    }

    fun refreshConfig(): com.lyon.rhythmictouch.config.RhythmicConfig {
        val config = configBridge.refresh(force = true)
        RhythmicLog.mode = config.logMode
        driver.updateParams(config.vibrationParams)
        driver.updateDelayMs(config.vibrationDelay.toLong())
        flatDetector.refreshEnabled()
        log("refreshConfig: active profile params applied, heavyLong=${config.vibrationParams.ampOf(com.lyon.rhythmictouch.config.VibrationParams.KEY_HEAVY_LONG)}%/${config.vibrationParams.durOf(com.lyon.rhythmictouch.config.VibrationParams.KEY_HEAVY_LONG)}ms delay=${config.vibrationDelay}ms flatDetection=${config.flatDetection}")
        return config
    }

    private fun getCurrentOutputDeviceAddress(): String {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        for (device in devices) {
            when (device.type) {
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                AudioDeviceInfo.TYPE_BLE_SPEAKER -> {
                    val addr = device.address
                    if (addr.isNotBlank() && addr != "00:00:00:00:00:00") return addr
                }
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_USB_HEADSET -> return "headphone"
            }
        }
        return "speaker"
    }

    private fun getDeviceOverride(config: com.lyon.rhythmictouch.config.RhythmicConfig, deviceAddress: String): Pair<Int, Int>? {
        val match = config.deviceConfigs.find { it.deviceAddress == deviceAddress && it.enabled }
        return if (match != null) match.intensity to match.vibrationDelay else null
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

    fun onExternalFftData(fft: ByteArray, samplingRate: Int) {
        phiraActive = true
        lastPhiraDataTimeMs = SystemClock.elapsedRealtime()
        log("📥 onExternalFftData called: size=${fft.size}, rate=$samplingRate")
        onFftData(fft, samplingRate, isPhira = true)
        log("📤 onExternalFftData completed")
    }

    private fun onFftData(fft: ByteArray, samplingRate: Int, isPhira: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        
        if (!isPhira) {
            val delta = now - lastGlobalFftTimeMs
            if (delta in 10L..500L) {
                val smoothed = (detectedGlobalIntervalMs * 0.7f + delta * 0.3f).toLong()
                if (kotlin.math.abs(smoothed - detectedGlobalIntervalMs) >= 2L) {
                    detectedGlobalIntervalMs = smoothed.coerceIn(33L, 300L)
                    val config = configBridge.config
                    if (config.syncAaudioWithAudioTrack) {
                        try {
                            val intent = Intent(RhythmicConstants.ACTION_SYNC_AAUDIO_INTERVAL).apply {
                                putExtra(RhythmicConstants.EXTRA_AAUDIO_INTERVAL_MS, detectedGlobalIntervalMs.toInt())
                                setPackage(RhythmicConstants.SYSTEMUI_PACKAGE)
                            }
                            appContext.sendBroadcast(intent)
                            log("📡 Synced interval to Phira: ${detectedGlobalIntervalMs}ms (was ${delta}ms)")
                        } catch (_: Throwable) {}
                    }
                }
            }
            lastGlobalFftTimeMs = now
        }
        
        val result = analyzer?.analyze(fft, samplingRate, now)
        
        if (result == null) {
            log("⚠️ analyzer returned NULL! Skipping FFT processing")
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
        val allScopeApps = config.profiles.filter { it.scopeApps.isNotEmpty() }.flatMap { it.scopeApps }.toSet()
        val effectiveScopeApps = allScopeApps.ifEmpty { config.excludedApps }

        driver.updateParams(effectiveParams)
        val deviceAddr = getCurrentOutputDeviceAddress()
        val deviceOverride = getDeviceOverride(config, deviceAddr)
        val effectiveIntensity = deviceOverride?.first ?: config.intensity
        val effectiveDelay = deviceOverride?.second ?: config.vibrationDelay
        driver.updateDelayMs(effectiveDelay.toLong())
        val blocked = !config.enabled || activeTracker.isBlocked(config.whitelistMode, effectiveScopeApps) || config.quietPeriods.any { it.isActiveNow() }

        val triggeredToday = config.quietPeriods.filter { !it.repeatDaily && it.isActiveNow() && it.lastTriggeredDate != todayString() }
        if (triggeredToday.isNotEmpty()) {
            val updated = config.copy(quietPeriods = config.quietPeriods.map { q ->
                if (triggeredToday.any { it.id == q.id }) q.markTriggered() else q
            })
            configBridge.writeCache(updated)
        }

        log("🔍 DEBUG: enabled=${config.enabled}, whitelistMode=${config.whitelistMode}, scopeApps=$effectiveScopeApps, isBlocked=${activeTracker.isBlocked(config.whitelistMode, effectiveScopeApps)}, quiet=${config.quietPeriods.any { it.isActiveNow() }}, blocked=$blocked, level=${"%.2f".format(result.level)}, foreground=$foregroundApp, matchedProfile=${matchedProfile?.name ?: "默认"}")
        
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
            log("🔍 Before driver: bands.size=${result.bands.size}, first5=${result.bands.take(5)}, level=${"%.2f".format(result.level)}, device=$deviceAddr, intensity=$effectiveIntensity%, delay=${effectiveDelay}ms")
            driver.onAnalysis(result, effectiveIntensity.toFloat() / 100f, now)
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

    private fun todayString(): String {
        val now = java.util.Calendar.getInstance()
        return "%04d-%02d-%02d".format(now.get(java.util.Calendar.YEAR), now.get(java.util.Calendar.MONTH) + 1, now.get(java.util.Calendar.DAY_OF_MONTH))
    }

    private companion object {
        const val STATUS_INTERVAL_MS = 100L
        const val PROBE_INTERVAL_MS = 5000L
        const val SESSION_WATCH_MS = 100L
        const val ATTACH_RETRY_MS = 2000L
        const val PHIRA_TIMEOUT_MS = 2000L
        const val TAG = "RhythmicTouch"
    }
}