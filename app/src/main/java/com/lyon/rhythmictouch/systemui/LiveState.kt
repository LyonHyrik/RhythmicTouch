package com.lyon.rhythmictouch.systemui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SpectrumBand(
    val index: Int,
    val value: Float,
    val freqStart: Float,
    val freqEnd: Float,
)

data class LiveState(
    val active: Boolean = false,
    val level: Float = 0f,
    val bands: List<SpectrumBand> = emptyList(),
    val beat: Boolean = false,
    val peakBandIndex: Int = 0,
    val hapticActive: Boolean = false,
    val lastUpdateMs: Long = 0L,
) {
    fun bass(): Float = if (bands.size >= 2) (bands[0].value + bands[1].value) / 2f else 0f
    fun mid(): Float = if (bands.size >= 10) {
        var sum = 0f
        for (i in 3 until minOf(10, bands.size)) sum += bands[i].value
        sum / 7f
    } else 0f
    fun treble(): Float = if (bands.size > 12) {
        var sum = 0f
        for (i in maxOf(11, bands.size / 2) until bands.size) sum += bands[i].value
        sum / (bands.size - maxOf(11, bands.size / 2))
    } else 0f
    
    fun bandSummary(): String {
        if (bands.isEmpty()) return "no data"
        val top3 = bands.sortedByDescending { it.value }.take(3)
        return top3.joinToString(", ") { 
            "${it.index}[${it.freqStart.toInt()}-${it.freqEnd.toInt()}Hz]=${"%.0f".format(it.value * 100)}%" 
        }
    }
}

class LiveStateHolder(private val analyzer: BeatAnalyzer) {

    private val _state = MutableStateFlow(LiveState())
    val state: StateFlow<LiveState> = _state.asStateFlow()

    fun updateFromAnalysis(analysis: AnalysisResult, nowMs: Long = System.currentTimeMillis()) {
        val spectrumBands = analysis.bands.mapIndexed { idx, value ->
            val (start, end) = analyzer.getBandFrequencyRange(idx)
            SpectrumBand(
                index = idx,
                value = value,
                freqStart = start,
                freqEnd = end,
            )
        }
        
        _state.value = LiveState(
            active = true,
            level = analysis.level,
            bands = spectrumBands,
            beat = analysis.beat,
            peakBandIndex = analysis.peakBand,
            hapticActive = _state.value.hapticActive,
            lastUpdateMs = nowMs,
        )
    }

    fun setHapticActive(active: Boolean) {
        _state.value = _state.value.copy(hapticActive = active)
    }

    fun clear() {
        _state.value = LiveState()
    }
}