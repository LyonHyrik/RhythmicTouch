package com.lyon.rhythmictouch.systemui

import kotlin.math.sqrt
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

data class AnalysisResult(
    val level: Float,
    val bands: FloatArray,
    val bandCount: Int = bands.size,
    val beat: Boolean = false,
    val peakBand: Int = 0,
) {
    fun bass(): Float = if (bandCount >= 4) (bands[0] + bands[1]) / 2f else 0f
    fun mid(): Float = if (bandCount >= 12) {
        var sum = 0f
        for (i in 3 until min(10, bandCount)) sum += bands[i]
        sum / 7f
    } else 0f
    fun treble(): Float = if (bandCount > 12) {
        var sum = 0f
        for (i in max(11, bandCount / 2) until bandCount) sum += bands[i]
        sum / (bandCount - max(11, bandCount / 2))
    } else 0f
    
    fun bandSummary(): String {
        if (bandCount == 0) return "no data"
        val indexedBands = bands.mapIndexed { idx, value -> idx to value }
        val top3 = indexedBands.sortedByDescending { it.second }.take(3)
        return top3.joinToString(", ") { "#${it.first}=${"%.0f".format(it.second * 100)}%" }
    }
}

class BeatAnalyzer {

    companion object {
        private const val TAG = "RhythmicAnalyzer"
        const val NUM_BANDS = 32
        const val MIN_FREQ = 30f
        const val MAX_FREQ = 16000f
    }

    private var lastRateMhz = 0
    private var lastSize = 0
    private var freqPerBin = 0f

    private var peakLevel = 0f
    private var peakBands = FloatArray(NUM_BANDS)
    private var prevBands = FloatArray(NUM_BANDS)
    private var avgBands = FloatArray(NUM_BANDS)
    private var lastBeatMs = 0L

    private val bandEdges = FloatArray(NUM_BANDS + 1)

    init {
        for (i in 0..NUM_BANDS) {
            val t = i.toFloat() / NUM_BANDS
            bandEdges[i] = MIN_FREQ * (MAX_FREQ / MIN_FREQ).pow(t)
        }
    }

    fun analyze(fft: ByteArray, samplingRateMhz: Int, nowMs: Long): AnalysisResult {
        val n = fft.size / 2
        if (samplingRateMhz != lastRateMhz || n != lastSize) {
            lastRateMhz = samplingRateMhz
            lastSize = n
            val rateHz = when {
                samplingRateMhz > 100000 -> samplingRateMhz / 1000  // Abnormal rate like 48000000 -> 48000
                else -> samplingRateMhz  // Normal rate like 44100, 48000
            }
            freqPerBin = if (n > 0 && rateHz > 0) (rateHz.toFloat()) / (2 * n) else 0f
        }
        if (n < 2 || freqPerBin <= 0) return AnalysisResult(0f, FloatArray(NUM_BANDS))

        val bands = FloatArray(NUM_BANDS)
        var totalMag = 0.0
        var peakBandMag = -1.0
        var peakBandIdx = 0

        for (k in 1 until n) {
            val re = fft[k * 2].toDouble()
            val im = fft[k * 2 + 1].toDouble()
            val mag = sqrt(re * re + im * im)
            totalMag += mag

            val freqHz = k * freqPerBin
            if (freqHz < bandEdges[0] || freqHz >= bandEdges[NUM_BANDS]) continue

            for (i in 0 until NUM_BANDS) {
                if (freqHz >= bandEdges[i] && freqHz < bandEdges[i + 1]) {
                    bands[i] += mag.toFloat()
                    break
                }
            }

            if (mag > peakBandMag) {
                peakBandMag = mag
                val binFreq = k * freqPerBin
                for (i in 0 until NUM_BANDS) {
                    if (binFreq >= bandEdges[i] && binFreq < bandEdges[i + 1]) {
                        peakBandIdx = i
                        break
                    }
                }
            }
        }

        for (i in 0 until NUM_BANDS) {
            val countBins = ((bandEdges[i+1] - bandEdges[i]) / freqPerBin).toInt().coerceAtLeast(1)
            bands[i] /= countBins.toFloat()
        }

        val levelRaw = (totalMag / (n - 1)).toFloat()
        peakLevel = maxOf(peakLevel * 0.995f, levelRaw)
        val level = if (peakLevel > 1e-6f) (levelRaw / peakLevel).coerceIn(0f, 1f) else 0f

        RhythmicLog.d(TAG, "🔬 ANALYZE: n=$n totalMag=${"%.2f".format(totalMag)} levelRaw=${"%.3f".format(levelRaw)} peakLevel=${"%.3f".format(peakLevel)} level=${"%.3f".format(level)} freqPerBin=${"%.1f".format(freqPerBin)}")
        
        val preUpdatePrevBands = prevBands.copyOf()
        
        for (i in 0 until NUM_BANDS) {
            peakBands[i] = maxOf(peakBands[i] * 0.995f, bands[i])
            bands[i] = if (peakBands[i] > 1e-6f) (bands[i] / peakBands[i]).coerceIn(0f, 1f) else 0f

            avgBands[i] = avgBands[i] * 0.85f + bands[i] * 0.15f
            prevBands[i] = bands[i]
        }

        val lowEnergy = (bands[0] + bands[1] + bands[2]) / 3f
        val midEnergy = (bands[3] + bands[4] + bands[5]) / 3f
        val lowOnset = ((bands[0] - preUpdatePrevBands[0]) + (bands[1] - preUpdatePrevBands[1]) + (bands[2] - preUpdatePrevBands[2])).coerceAtLeast(0f) / 3f
        val midOnset = ((bands[3] - preUpdatePrevBands[3]) + (bands[4] - preUpdatePrevBands[4]) + (bands[5] - preUpdatePrevBands[5])).coerceAtLeast(0f) / 3f
        val totalLevel = level
        
        val beat = when {
            lowEnergy > 0.06f && lowOnset > 0.015f -> true
            midEnergy > 0.10f && midOnset > 0.02f -> true
            totalLevel > 0.25f && (lowOnset > 0.01f || midOnset > 0.015f) -> true
            else -> false
        } && nowMs - lastBeatMs >= 70
        
        if (beat) {
            lastBeatMs = nowMs
            RhythmicLog.d("RhythmicBeat", "✅ BEAT! L=${"%.2f".format(lowEnergy)}(↑${"%.3f".format(lowOnset)}) M=${"%.2f".format(midEnergy)}(↑${"%.3f".format(midOnset)}) total=${"%.2f".format(totalLevel)}")
        }
        
        RhythmicLog.d("RhythmicBeat", "beat=$beat L=${"%.2f".format(lowEnergy)}(↑${"%.3f".format(lowOnset)}) M=${"%.2f".format(midEnergy)}(↑${"%.3f".format(midOnset)}) total=${"%.2f".format(totalLevel)}")

        return AnalysisResult(level, bands, NUM_BANDS, beat, peakBandIdx)
    }

    fun getBandFrequencyRange(bandIndex: Int): Pair<Float,Float> {
        return if (bandIndex in 0 until NUM_BANDS) {
            Pair(bandEdges[bandIndex], bandEdges[bandIndex + 1])
        } else Pair(0f, 0f)
    }

    fun debugState(): String = "freqPerBin=$freqPerBin bands=$NUM_BANDS range=${bandEdges[0]}-${bandEdges[NUM_BANDS]}Hz"
    
    fun resetPeakTracking() {
        peakLevel = 0f
        peakBands.fill(0f)
        prevBands.fill(0f)
        avgBands.fill(0f)
        lastBeatMs = 0L
    }
}