package com.lyon.rhythmictouch.systemui

import android.util.Log
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import com.lyon.rhythmictouch.config.VibrationParams
import java.util.LinkedList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class VibratorDriver(private val vibrator: Vibrator?, private val analyzer: BeatAnalyzer) {

    companion object {
        private const val TAG = "RhythmicVib"
        private const val HISTORY_SIZE = 200
        private const val BASELINE_UPDATE_INTERVAL = 30
        private const val MIN_VIBRATE_INTERVAL_MS = 50L

        private val MIHAPTIC_MODES = setOf(
            VibrationParams.KEY_HEAVY_SHORT,
            VibrationParams.KEY_MID_TAP,
            VibrationParams.KEY_MEDIUM_HIT,
            VibrationParams.KEY_RISING_TAP,
            VibrationParams.KEY_SOFT_TICK,
        )
    }

    private var lastVibrateMs = 0L
    private var prevTotalEnergy = 0f
    private var prevLowEnergy = 0f
    var currentMode: String = "安静"
        private set

    @Volatile
    private var params: VibrationParams = VibrationParams.defaults()

    @Volatile
    private var delayMs: Long = 0L

    @Volatile
    var effectiveMinIntervalMs = MIN_VIBRATE_INTERVAL_MS
        private set

    private val vibrateHandler = Handler(Looper.getMainLooper())
    private val pendingRunnables = mutableSetOf<Runnable>()

    fun updateParams(newParams: VibrationParams) {
        params = newParams
        RhythmicLog.d(TAG, "🎛️ [PARAMS] updated: heavyLong=${params.ampOf(VibrationParams.KEY_HEAVY_LONG)}%/${params.durOf(VibrationParams.KEY_HEAVY_LONG)}ms softTick=${params.ampOf(VibrationParams.KEY_SOFT_TICK)}%/${params.durOf(VibrationParams.KEY_SOFT_TICK)}ms")
    }

    fun updateDelayMs(newDelayMs: Long) {
        delayMs = newDelayMs.coerceIn(0L, 1000L)
        RhythmicLog.d(TAG, "⏱️ [DELAY] updated: ${delayMs}ms")
    }

    fun calibrate(ctx: android.content.Context? = null, onResult: ((Long) -> Unit)? = null) {
        if (vibrator == null || !vibrator.hasVibrator()) {
            RhythmicLog.x(TAG, "🎯 calibrate() SKIPPED: vibrator=${vibrator != null}")
            return
        }
        RhythmicLog.x(TAG, "🎯 calibrate() ENTERED")
        val calThread = HandlerThread("vibrator-calibrate").apply { start() }
        val calHandler = Handler(calThread.looper)
        calHandler.post {
            try {
                val testIntervals = longArrayOf(20, 25, 30, 35, 40, 45, 50, 60, 70, 80)
                var result = MIN_VIBRATE_INTERVAL_MS
                var probesDone = 0

                for (interval in testIntervals) {
                    val count = 10
                    val executionTimes = mutableListOf<Long>()
                    val latch = CountDownLatch(count)

                    for (i in 0 until count) {
                        vibrateHandler.postDelayed({
                            try {
                                vibrator.vibrate(VibrationEffect.createOneShot(5, 80))
                            } catch (_: Exception) {}
                            synchronized(executionTimes) { executionTimes.add(SystemClock.elapsedRealtime()) }
                            latch.countDown()
                        }, i * interval)
                    }

                    latch.await(3, TimeUnit.SECONDS)
                    probesDone++
                    val size = synchronized(executionTimes) { executionTimes.size }

                    if (size < 3) continue

                    val actualIntervals = synchronized(executionTimes) {
                        executionTimes.zipWithNext().map { (a, b) -> b - a }
                    }
                    val avgActual = actualIntervals.average().toLong()
                    val maxActual = actualIntervals.maxOrNull() ?: 0L

                    if (avgActual <= interval * 1.3 && maxActual <= interval * 2.0) {
                        result = interval
                    } else {
                        break
                    }

                    Thread.sleep(100)
                }

                effectiveMinIntervalMs = result
                onResult?.invoke(result)
                if (ctx != null) {
                    ctx.getSharedPreferences("vibrator_cal", android.content.Context.MODE_PRIVATE)
                        .edit()
                        .putLong("effectiveMinIntervalMs", result)
                        .putInt("probesDone", probesDone)
                        .putLong("timestamp", System.currentTimeMillis())
                        .apply()
                }
            } catch (e: Throwable) {
                if (ctx != null) {
                    ctx.getSharedPreferences("vibrator_cal", android.content.Context.MODE_PRIVATE)
                        .edit()
                        .putString("error", e.message)
                        .putLong("timestamp", System.currentTimeMillis())
                        .apply()
                }
            } finally {
                calThread.quitSafely()
            }
        }
    }

    fun testVibration(modeKey: String) {
        if (vibrator == null || !vibrator.hasVibrator()) return
        if (!params.modes.containsKey(modeKey)) return
        val dur = modeDur(modeKey)
        val amp = modeAmp(modeKey)
        RhythmicLog.d(TAG, "🧪 [TEST] mode=$modeKey dur=${dur}ms amp=${"%.0f".format(amp * 100)}% delay=${delayMs}ms")

        // Priority: RichTap → MiHaptic → Standard
        if (RichTapHelper.isAvailable()) {
            val intensity = (amp * RichTapHelper.getIntensityMultiplier(modeKey)).coerceIn(0.01f, 1.0f)
            val sharpness = RichTapHelper.getSharpnessForMode(modeKey)
            val ok = RichTapHelper.playTransient(modeKey, intensity, sharpness)
            if (ok) {
                RhythmicLog.d(TAG, "🧪 [TEST-RichTap] mode=$modeKey intensity=${"%.2f".format(intensity)} sharpness=${"%.2f".format(sharpness)}")
                return
            }
        }

        if (MIHAPTIC_MODES.contains(modeKey) && MiHapticHelper.isAvailable()) {
            val intensity = (amp * MiHapticHelper.getIntensityMultiplier(modeKey)).coerceIn(0.01f, 1.0f)
            val sharpness = MiHapticHelper.getSharpnessForMode(modeKey)
            val ok = MiHapticHelper.playTransient(intensity, sharpness)
            if (ok) {
                RhythmicLog.d(TAG, "🧪 [TEST-MiHaptic] mode=$modeKey intensity=${"%.2f".format(intensity)} sharpness=${"%.2f".format(sharpness)}")
                return
            }
        }

        scheduleVibration(VibrationEffect.createOneShot(dur, (amp * 255).toInt()))
    }

    private fun modeAmp(key: String): Float = (params.ampOf(key) / 100f).coerceIn(0f, 1f)

    private fun modeDur(key: String): Long = params.durOf(key).toLong().coerceIn(10L, 500L)

    private fun modeEnabled(key: String): Boolean = params.ampOf(key) > 0

    private fun bandEnergy(bands: FloatArray, key: String): Float {
        val active = params.activeBandsOf(key)
        if (active != null) {
            if (active.isEmpty()) return 0f
            val total = active.filter { it in bands.indices }.sumOf { bands[it].toDouble() }.toFloat()
            return total / active.size
        }
        val start = params.bandStartOf(key).coerceIn(0, bands.size - 1)
        val end = params.bandEndOf(key).coerceIn(0, bands.size - 1)
        if (start > end) return 0f
        val slice = bands.sliceArray(start..end)
        return if (slice.isEmpty()) 0f else slice.average().toFloat()
    }

    private val historyBuffer = LinkedList<FloatArray>()
    private var dynamicBaseline = floatArrayOf(0f, 0f, 0f)
    private var adaptiveThresholds = floatArrayOf(0.16f, 0.38f, 0.22f, 0.14f)
    private var sampleCount = 0

    private var recentDominantBand = -1
    private var dominanceSmoothed = 0f
    private var dominantLowRatio = 0f
    private var dominantMidRatio = 0f

    private var lowWeight = 2.5f
    private var midWeight = 1.5f
    private var highWeight = 0.6f

    fun stop() {
        currentMode = "安静"
        historyBuffer.clear()
        sampleCount = 0
        dominanceSmoothed = 0f
        pendingRunnables.forEach { vibrateHandler.removeCallbacks(it) }
        pendingRunnables.clear()
    }

    fun resetAdaptiveContext() {
        RhythmicLog.d(TAG, "🔄 [ADAPTIVE-RESET] Clearing all learned context for fresh start!")
        historyBuffer.clear()
        sampleCount = 0
        dynamicBaseline = floatArrayOf(0f, 0f, 0f)
        adaptiveThresholds = floatArrayOf(0.16f, 0.38f, 0.22f, 0.14f)
        dominanceSmoothed = 0f
        dominantLowRatio = 0f
        dominantMidRatio = 0f
        lowWeight = 2.5f
        midWeight = 1.5f
        highWeight = 0.6f
        prevTotalEnergy = 0f
        prevLowEnergy = 0f
        RhythmicLog.d(TAG, "✅ [ADAPTIVE-RESET] Context fully reset! Ready to relearn Phira's audio characteristics.")
    }

    private var currentIntensity = 1.0f

    fun onAnalysis(analysis: AnalysisResult, intensity: Float, nowMs: Long) {
        RhythmicLog.vibrationFrame = false
        RhythmicLog.d(TAG, "🔧 DRIVER ENTERED: vibrator=${if (vibrator != null) "✅" else "❌ NULL"}, hasVibrator=${if (vibrator?.hasVibrator() == true) "✅" else "❌"}, intensity=$intensity")

        if (vibrator == null || !vibrator.hasVibrator()) {
            RhythmicLog.d(TAG, "❌ EARLY RETURN: No vibrator available!")
            return
        }
        if (intensity <= 0.05f) {
            RhythmicLog.d(TAG, "❌ EARLY RETURN: intensity too low ($intensity <= 0.05)")
            return
        }

        currentIntensity = intensity.coerceIn(0.05f, 1.0f)

        val bands = analysis.bands
        if (bands.isEmpty()) {
            RhythmicLog.d(TAG, "❌ EARLY RETURN: bands empty!")
            return
        }

        RhythmicLog.d(TAG, "✅ DRIVER PASSED all checks! Processing ${bands.size} bands...")

        val lowEnergy = bands.sliceArray(0..minOf(7, bands.size - 1)).average().toFloat()
        val midEnergy = bands.sliceArray(minOf(8, bands.size - 1)..minOf(18, bands.size - 1)).average().toFloat()
        val highEnergy = bands.sliceArray(minOf(19, bands.size - 1)..minOf(bands.size - 1, 31)).average().toFloat()

        updateHistory(lowEnergy, midEnergy, highEnergy, bands)

        val currentDominant = findDominantBand(bands)
        smoothDominance(currentDominant, lowEnergy, midEnergy, highEnergy)

        val lowAttack = (lowEnergy - prevLowEnergy).coerceAtLeast(0f)
        val totalEnergy = (lowEnergy * lowWeight + midEnergy * midWeight + highEnergy * highWeight) / (lowWeight + midWeight + highWeight)

        val dominantLow = lowEnergy > midEnergy && lowEnergy > highEnergy
        val isRising = lowAttack > 0.04f || totalEnergy > prevTotalEnergy * 1.15f

        val effectiveThresholds = calculateEffectiveThresholds(totalEnergy)

        RhythmicLog.d(TAG, "🎵 [ADAPTIVE] L=${"%.2f".format(lowEnergy)}(↑${"%.2f".format(lowAttack)}) M=${"%.2f".format(midEnergy)} H=${"%.2f".format(highEnergy)} total=${"%.2f".format(totalEnergy)} beat=${analysis.beat} rising=$isRising dom=#$currentDominant(${"%.2f".format(dominanceSmoothed)})")

        when {
            modeEnabled(VibrationParams.KEY_HEAVY_LONG) && analysis.beat && lowEnergy > effectiveThresholds[0] && (dominantLow || lowAttack > 0.04f) && dominanceSmoothed < 0.4f && bandEnergy(bands, VibrationParams.KEY_HEAVY_LONG) > 0.03f -> {
                currentMode = "💥 重长振"
                RhythmicLog.vibrationFrame = true
                val dur = modeDur(VibrationParams.KEY_HEAVY_LONG)
                val finalAmp = (modeAmp(VibrationParams.KEY_HEAVY_LONG) * currentIntensity).coerceIn(0.05f, 1.0f)
                RhythmicLog.d(TAG, "💥 HEAVY-LONG-PULSE (beat+low) dur=${dur}ms amp=${"%.2f".format(finalAmp)} [intensity=${"%.0f".format(currentIntensity*100)}%] ✓BEAT")
                vibrateOnce(dur, finalAmp, nowMs)
            }

            modeEnabled(VibrationParams.KEY_HEAVY_SHORT) && analysis.beat && midEnergy > effectiveThresholds[1] * 0.88f && totalEnergy > effectiveThresholds[2] * 1.05f && bandEnergy(bands, VibrationParams.KEY_HEAVY_SHORT) > 0.03f -> {
                currentMode = "💢 重短振"
                RhythmicLog.vibrationFrame = true
                val dur = modeDur(VibrationParams.KEY_HEAVY_SHORT)
                val finalAmp = (modeAmp(VibrationParams.KEY_HEAVY_SHORT) * currentIntensity).coerceIn(0.05f, 1.0f)
                RhythmicLog.d(TAG, "💢 HEAVY-SHORT-HIT (beat+mid) dur=${dur}ms amp=${"%.2f".format(finalAmp)} [intensity=${"%.0f".format(currentIntensity*100)}%] ✓BEAT")
                vibrateOnceSmart(VibrationParams.KEY_HEAVY_SHORT, dur, finalAmp, nowMs)
            }

            modeEnabled(VibrationParams.KEY_MID_TAP) && analysis.beat && midEnergy > effectiveThresholds[1] * 0.85f && totalEnergy > effectiveThresholds[2] * 0.9f && bandEnergy(bands, VibrationParams.KEY_MID_TAP) > 0.03f -> {
                val dur = modeDur(VibrationParams.KEY_MID_TAP)
                currentMode = "🎵 中敲击"
                RhythmicLog.vibrationFrame = true
                val finalAmp = (modeAmp(VibrationParams.KEY_MID_TAP) * currentIntensity).coerceIn(0.05f, 1.0f)
                RhythmicLog.d(TAG, "🎵 MID-TAP (beat+mid) dur=${dur}ms amp=${"%.2f".format(finalAmp)} [intensity=${"%.0f".format(currentIntensity*100)}%] ✓BEAT")
                vibrateOnceSmart(VibrationParams.KEY_MID_TAP, dur, finalAmp, nowMs)
            }

            modeEnabled(VibrationParams.KEY_MEDIUM_HIT) && analysis.beat && totalEnergy > effectiveThresholds[3] * 1.3f && bandEnergy(bands, VibrationParams.KEY_MEDIUM_HIT) > 0.03f -> {
                currentMode = "⚡ 中等击打"
                RhythmicLog.vibrationFrame = true
                val dur = modeDur(VibrationParams.KEY_MEDIUM_HIT)
                val finalAmp = (modeAmp(VibrationParams.KEY_MEDIUM_HIT) * currentIntensity).coerceIn(0.05f, 1.0f)
                RhythmicLog.d(TAG, "⚡ MEDIUM-HIT (beat only) dur=${dur}ms amp=${"%.2f".format(finalAmp)} [intensity=${"%.0f".format(currentIntensity*100)}%] ✓BEAT")
                vibrateOnceSmart(VibrationParams.KEY_MEDIUM_HIT, dur, finalAmp, nowMs)
            }

            modeEnabled(VibrationParams.KEY_RISING_TAP) && analysis.beat && totalEnergy > effectiveThresholds[2] * 0.9f && isRising && bandEnergy(bands, VibrationParams.KEY_RISING_TAP) > 0.03f -> {
                currentMode = "🎶 上升轻击"
                RhythmicLog.vibrationFrame = true
                val dur = modeDur(VibrationParams.KEY_RISING_TAP)
                val finalAmp = (modeAmp(VibrationParams.KEY_RISING_TAP) * currentIntensity).coerceIn(0.05f, 1.0f)
                RhythmicLog.d(TAG, "🎶 RISING-TAP (beat+attack) dur=${dur}ms amp=${"%.2f".format(finalAmp)} [intensity=${"%.0f".format(currentIntensity*100)}%] ✓BEAT")
                vibrateOnceSmart(VibrationParams.KEY_RISING_TAP, dur, finalAmp, nowMs)
            }

            modeEnabled(VibrationParams.KEY_LONG_PULSE) && !analysis.beat && lowEnergy > effectiveThresholds[0] * 1.3f && (dominantLow || lowAttack > 0.06f) && bandEnergy(bands, VibrationParams.KEY_LONG_PULSE) > 0.03f -> {
                val dur = modeDur(VibrationParams.KEY_LONG_PULSE)
                currentMode = "🔊 长脉动"
                RhythmicLog.vibrationFrame = true
                val finalAmp = (modeAmp(VibrationParams.KEY_LONG_PULSE) * currentIntensity).coerceIn(0.05f, 1.0f)
                RhythmicLog.d(TAG, "🔊 LONG-PULSE (sustain bass) dur=${dur}ms amp=${"%.2f".format(finalAmp)} [intensity=${"%.0f".format(currentIntensity*100)}%] ○SUSTAIN")
                vibrateOnce(dur, finalAmp, nowMs)
            }

            modeEnabled(VibrationParams.KEY_EMOTION_PULSE) && !analysis.beat && totalEnergy > effectiveThresholds[2] * 1.15f && (isRising || midEnergy > 0.48f) && bandEnergy(bands, VibrationParams.KEY_EMOTION_PULSE) > 0.03f -> {
                val dur = modeDur(VibrationParams.KEY_EMOTION_PULSE)
                currentMode = "🔄 情感脉动"
                RhythmicLog.vibrationFrame = true
                val finalAmp = (modeAmp(VibrationParams.KEY_EMOTION_PULSE) * currentIntensity).coerceIn(0.05f, 1.0f)
                RhythmicLog.d(TAG, "🔄 EMOTION-PULSE (sustain strong) dur=${dur}ms amp=${"%.2f".format(finalAmp)} [intensity=${"%.0f".format(currentIntensity*100)}%] ○SUSTAIN")
                vibrateOnce(dur, finalAmp, nowMs)
            }

            modeEnabled(VibrationParams.KEY_SOFT_TICK) && !analysis.beat && totalEnergy > effectiveThresholds[3] * 1.5f && nowMs - lastVibrateMs > 120L && bandEnergy(bands, VibrationParams.KEY_SOFT_TICK) > 0.03f -> {
                currentMode = "✨ 柔和细节"
                RhythmicLog.vibrationFrame = true
                val dur = modeDur(VibrationParams.KEY_SOFT_TICK)
                val finalAmp = (modeAmp(VibrationParams.KEY_SOFT_TICK) * currentIntensity).coerceIn(0.05f, 1.0f)
                RhythmicLog.d(TAG, "✨ SOFT-TICK (sustain light) dur=${dur}ms amp=${"%.2f".format(finalAmp)} [intensity=${"%.0f".format(currentIntensity*100)}%] ○SUSTAIN [gap=${nowMs-lastVibrateMs}ms]")
                vibrateOnceSmart(VibrationParams.KEY_SOFT_TICK, dur, finalAmp, nowMs)
            }

            else -> {
                currentMode = "安静"
                RhythmicLog.d(TAG, "... quiet")
            }
        }

        prevTotalEnergy = totalEnergy
        prevLowEnergy = lowEnergy
    }

    private fun updateHistory(lowEnergy: Float, midEnergy: Float, highEnergy: Float, bands: FloatArray) {
        historyBuffer.addLast(floatArrayOf(lowEnergy, midEnergy, highEnergy))
        if (historyBuffer.size > HISTORY_SIZE) historyBuffer.removeFirst()

        sampleCount++

        if (sampleCount % BASELINE_UPDATE_INTERVAL == 0 && historyBuffer.size >= 30) {
            dynamicBaseline[0] = historyBuffer.map { it[0] }.average().toFloat()
            dynamicBaseline[1] = historyBuffer.map { it[1] }.average().toFloat()
            dynamicBaseline[2] = historyBuffer.map { it[2] }.average().toFloat()

            val totalBaseline = dynamicBaseline[0] + dynamicBaseline[1] + dynamicBaseline[2]
            if (totalBaseline > 0.01f) {
                dominantLowRatio = dynamicBaseline[0] / totalBaseline
                dominantMidRatio = dynamicBaseline[1] / totalBaseline

                lowWeight = (1.5f + dominantLowRatio * 2.5f).coerceIn(1.5f, 4.0f)
                midWeight = (1.0f + dominantMidRatio * 1.8f).coerceIn(1.0f, 2.8f)
                highWeight = (0.4f + (1f - dominantLowRatio - dominantMidRatio) * 0.6f).coerceIn(0.3f, 1.0f)

                val baseAvg = dynamicBaseline.sum() / 3f

                var threshL = dynamicBaseline[0] * 1.3f
                var threshM = dynamicBaseline[1] * 1.1f
                var threshT = baseAvg * 1.0f
                var threshS = baseAvg * 0.65f

                if (dominantLowRatio < 0.25f) {
                    threshL *= 0.75f
                    threshM *= 0.9f
                }

                if (dynamicBaseline[2] > dynamicBaseline[0] * 2.5f) {
                    threshL *= 0.7f
                    threshM *= 0.85f
                    threshT *= 1.05f
                }

                adaptiveThresholds[0] = threshL.coerceIn(0.12f, 0.24f)
                adaptiveThresholds[1] = threshM.coerceIn(0.30f, 0.45f)
                adaptiveThresholds[2] = threshT.coerceIn(0.16f, 0.30f)
                adaptiveThresholds[3] = threshS.coerceIn(0.08f, 0.18f)
            }

            RhythmicLog.d(TAG, "📊 [BASELINE] L=${"%.3f".format(dynamicBaseline[0])} M=${"%.3f".format(dynamicBaseline[1])} H=${"%.3f".format(dynamicBaseline[2])} | weights: L=${"%.2f".format(lowWeight)} M=${"%.2f".format(midWeight)} H=${"%.2f".format(highWeight)} | thresholds: [${"%.2f".format(adaptiveThresholds[0])}, ${"%.2f".format(adaptiveThresholds[1])}, ${"%.2f".format(adaptiveThresholds[2])}, ${"%.2f".format(adaptiveThresholds[3])}]")
        }
    }

    private fun findDominantBand(bands: FloatArray): Int {
        return if (bands.isNotEmpty()) {
            bands.indices.maxByOrNull { bands[it] } ?: -1
        } else -1
    }

    private fun smoothDominance(dominantBand: Int, lowE: Float, midE: Float, highE: Float) {
        recentDominantBand = dominantBand

        when {
            dominantBand in 0..7 -> {
                val target = (lowE / maxOf(lowE + midE + highE, 0.001f)).coerceIn(0f, 1f)
                dominanceSmoothed = dominanceSmoothed * 0.85f + target * 0.15f
            }
            dominantBand in 8..18 -> {
                val target = (midE / maxOf(lowE + midE + highE, 0.001f)).coerceIn(0f, 1f)
                dominanceSmoothed = dominanceSmoothed * 0.85f + target * 0.15f
            }
            dominantBand in 19..31 -> {
                val target = (highE / maxOf(lowE + midE + highE, 0.001f)).coerceIn(0f, 1f)
                dominanceSmoothed = dominanceSmoothed * 0.85f + target * (-0.3f)
            }
            else -> {
                dominanceSmoothed *= 0.9f
            }
        }

        dominanceSmoothed = dominanceSmoothed.coerceIn(-0.3f, 1.0f)
    }

    private fun calculateEffectiveThresholds(currentTotalEnergy: Float): FloatArray {
        val energyFactor = when {
            currentTotalEnergy > 0.40f -> 1.15f
            currentTotalEnergy > 0.30f -> 1.05f
            currentTotalEnergy > 0.20f -> 0.95f
            else -> 0.85f
        }

        return floatArrayOf(
            adaptiveThresholds[0] * energyFactor,
            adaptiveThresholds[1] * energyFactor,
            adaptiveThresholds[2] * energyFactor,
            adaptiveThresholds[3] * energyFactor
        )
    }

    private fun vibrateOnceSmart(modeKey: String, durationMs: Long, amplitude: Float, nowMs: Long) {
        if (nowMs - lastVibrateMs < effectiveMinIntervalMs) return

        // Priority: RichTap → MiHaptic → Standard VibrationEffect
        if (RichTapHelper.isAvailable()) {
            val intensity = (amplitude * RichTapHelper.getIntensityMultiplier(modeKey)).coerceIn(0.01f, 1.0f)
            val sharpness = RichTapHelper.getSharpnessForMode(modeKey)
            val ok = RichTapHelper.playTransient(modeKey, intensity, sharpness, delayMs)
            if (ok) {
                lastVibrateMs = nowMs
                RhythmicLog.d(TAG, "✨ RichTap → mode=$modeKey intensity=${"%.2f".format(intensity)} sharpness=${"%.2f".format(sharpness)} delay=${delayMs}ms")
                return
            }
        }

        if (MIHAPTIC_MODES.contains(modeKey) && MiHapticHelper.isAvailable()) {
            val intensity = (amplitude * MiHapticHelper.getIntensityMultiplier(modeKey)).coerceIn(0.01f, 1.0f)
            val sharpness = MiHapticHelper.getSharpnessForMode(modeKey)
            val ok = MiHapticHelper.playTransient(intensity, sharpness, delayMs)
            if (ok) {
                lastVibrateMs = nowMs
                RhythmicLog.d(TAG, "🫨 MiHaptic → mode=$modeKey intensity=${"%.2f".format(intensity)} sharpness=${"%.2f".format(sharpness)} delay=${delayMs}ms")
                return
            }
        }

        vibrateOnce(durationMs, amplitude, nowMs)
    }

    private fun vibrateOnce(durationMs: Long, amplitude: Float, nowMs: Long) {
        if (nowMs - lastVibrateMs < effectiveMinIntervalMs) return

        // Try RichTap continuous haptic first
        if (RichTapHelper.isAvailable()) {
            val ok = RichTapHelper.playContinuous(durationMs.toInt(), amplitude, 0.5f, delayMs)
            if (ok) {
                lastVibrateMs = nowMs
                RhythmicLog.d(TAG, "✨ RichTap continuous → dur=${durationMs}ms amp=${"%.2f".format(amplitude)} delay=${delayMs}ms")
                return
            }
        }

        // Fallback to standard VibrationEffect
        try {
            scheduleVibration(
                VibrationEffect.createOneShot(
                    durationMs,
                    (amplitude * 255).toInt()
                )
            )
            lastVibrateMs = nowMs
        } catch (_: Exception) {}
    }

    private fun scheduleVibration(effect: VibrationEffect) {
        val runnable = object : Runnable {
            override fun run() {
                pendingRunnables.remove(this)
                try {
                    vibrator?.vibrate(effect)
                } catch (_: Exception) {}
            }
        }
        pendingRunnables.add(runnable)
        if (delayMs > 0) {
            vibrateHandler.postDelayed(runnable, delayMs)
        } else {
            vibrateHandler.post(runnable)
        }
    }
}
