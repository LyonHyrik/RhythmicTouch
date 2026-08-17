package com.lyon.rhythmictouch.systemui

import android.os.SystemClock
import de.robv.android.xposed.XposedBridge

class OboeBridge {

    var fftListener: ((ByteArray, Int) -> Unit)? = null

    @Volatile
    var frameCount = 0L  // Exposed for external access

    @Volatile
    var sampleRate = 44100
        private set

    init {
        try {
            System.loadLibrary("oboe-pcm-capture")
            XposedBridge.log("[RhythmicTouch-Oboe] ✅ Native library loaded successfully!")
        } catch (e: UnsatisfiedLinkError) {
            XposedBridge.log("[RhythmicTouch-Oboe] ❌ Failed to load native library: ${e.message}")
        }
    }

    fun install(): Boolean {
        return try {
            val result = installCapture(this)
            XposedBridge.log("[RhythmicTouch-Oboe] 🚀 install() result=$result")
            result
        } catch (t: Throwable) {
            XposedBridge.log("[RhythmicTouch-Oboe] ❌ install() failed: ${t.message}")
            false
        }
    }

    fun uninstall() {
        try {
            uninstallCapture()
            XposedBridge.log("[RhythmicTouch-Oboe] 🗑️ Uninstalled successfully")
        } catch (t: Throwable) {
            XposedBridge.log("[RhythmicTouch-Oboe] ⚠️ uninstall failed: ${t.message}")
        }
    }

    fun isInstalled(): Boolean {
        return try {
            isInstalledNative()
        } catch (t: Throwable) {
            false
        }
    }

    private fun onOboeAudioFrame(pcmData: ShortArray, nativeRate: Int) {
        if (pcmData.isEmpty()) return

        frameCount++
        if (nativeRate > 0) sampleRate = nativeRate

        // Append every incoming sample to a rolling buffer so the FFT window is
        // always a full, contiguous block of the most recent audio (like Visualizer).
        for (s in pcmData) {
            ring[ringHead] = s
            ringHead = if (ringHead + 1 >= RING_CAPACITY) 0 else ringHead + 1
            if (ringFill < RING_CAPACITY) ringFill++
        }

        // Throttle to the same cadence as the global Visualizer (33ms).
        val now = SystemClock.elapsedRealtime()
        if (now - lastFftMs < FFT_INTERVAL_MS) return
        if (ringFill < FFT_SAMPLES * 2) return
        lastFftMs = now

        val fft = performFixedFFT()
        if (fft != null && fft.isNotEmpty()) {
            if (frameCount % 500L == 0L) {
                val avgLevel = fft.map { it.toInt() }.average()
                XposedBridge.log("[RhythmicTouch-Oboe] 📊✅ FIXED FFT: ${fft.size} bytes, avg=$avgLevel")
            }

            try {
                fftListener?.invoke(fft, sampleRate)
            } catch (ex: Exception) {
                if (frameCount % 1000L == 0L) {
                    XposedBridge.log("[RhythmicTouch-Oboe] ❌ fftListener error: ${ex.message}")
                }
            }
        }
    }

    private val ring = ShortArray(RING_CAPACITY)
    private var ringHead = 0
    private var ringFill = 0
    private var lastFftMs = 0L

    // Raw complex FFT output, byte layout identical to Android Visualizer FFT:
    // [re0, im0, re1, im1, ..., re511, im511] for 512 bins.
    // No equalization, no normalization, no windowing — exactly what BeatAnalyzer
    // consumes from the global AudioTrack stream.
    private fun performFixedFFT(): ByteArray? {
        return try {
            // Mono-sum the latest FFT_SAMPLES stereo frames (same as Visualizer sums L+R).
            val real = FloatArray(FFT_SAMPLES)
            var idx = (ringHead - FFT_SAMPLES * 2 + RING_CAPACITY) % RING_CAPACITY
            for (i in 0 until FFT_SAMPLES) {
                val l = ring[idx].toInt()
                idx++
                if (idx >= RING_CAPACITY) idx = 0
                val r = ring[idx].toInt()
                idx++
                if (idx >= RING_CAPACITY) idx = 0
                real[i] = (l + r) / 65536.0f
            }

            val imag = FloatArray(FFT_SAMPLES)
            computeFFT(real, imag)

            val result = ByteArray(OUTPUT_BINS * 2)
            for (k in 0 until OUTPUT_BINS) {
                val re = (real[k] * FFTS_SCALE).toInt().coerceIn(-127, 127)
                val im = (imag[k] * FFTS_SCALE).toInt().coerceIn(-127, 127)
                result[k * 2] = re.toByte()
                result[k * 2 + 1] = im.toByte()
            }

            result
        } catch (t: Throwable) {
            null
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

    companion object {
        private const val FFT_SAMPLES = 1024
        private const val OUTPUT_BINS = 512
        private const val RING_CAPACITY = 8192
        private const val FFTS_SCALE = 0.25f
        private const val FFT_INTERVAL_MS = 33L

        init {
            try {
                System.loadLibrary("oboe-pcm-capture")
            } catch (e: Exception) {}
        }

        @JvmStatic
        external fun installCapture(bridge: OboeBridge): Boolean
        
        @JvmStatic
        external fun uninstallCapture()
        
        @JvmStatic
        external fun isInstalledNative(): Boolean
    }
}
