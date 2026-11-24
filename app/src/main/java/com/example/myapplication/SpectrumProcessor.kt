package com.example.bleaudioviz

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

const val NUM_BANDS = 64

/**
 * Converts either Android Visualizer FFT bytes or raw 16-bit PCM microphone audio
 * into 16 logarithmically-spaced, treble-balanced, auto-gained spectrum bars (0..255).
 */
class SpectrumProcessor(private val numBands: Int = NUM_BANDS) {
    private val smoothed = DoubleArray(numBands)
    private var runningPeak = 2.0

    // Reusable buffers for 1024-point Radix-2 FFT (used in Microphone mode)
    private val fftSize = 1024
    private val fftRe = DoubleArray(fftSize)
    private val fftIm = DoubleArray(fftSize)
    private val hannWindow = DoubleArray(fftSize) { i ->
        0.5 * (1.0 - cos(2.0 * PI * i / (fftSize - 1)))
    }

    fun reset() {
        smoothed.fill(0.0)
        runningPeak = 2.0
    }

    fun computeFromVisualizerFft(
        fft: ByteArray,
        samplingRateRaw: Int,
        sensitivity: Float
    ): ByteArray {
        if (fft.size < 4) return ByteArray(numBands)

        // Visualizer reports samplingRate in milliHertz (e.g. 44100000 for 44.1 kHz)
        val sampleRateHz = if (samplingRateRaw > 100_000) {
            samplingRateRaw / 1000.0
        } else {
            samplingRateRaw.toDouble().coerceIn(8_000.0, 96_000.0)
        }

        val captureSize = fft.size
        val binCount = captureSize / 2
        val magnitudes = DoubleArray(binCount)
        for (b in 1 until binCount) {
            val re = fft[2 * b].toDouble()
            val im = fft[2 * b + 1].toDouble()
            magnitudes[b] = hypot(re, im)
        }

        return mapMagnitudesToBands(
            magnitudes = magnitudes,
            fftLength = captureSize,
            sampleRateHz = sampleRateHz,
            sensitivity = sensitivity,
            noiseFloor = 0.20,
            minPeakFloor = 1.4
        )
    }

    fun computeFromPcm(
        pcm: ShortArray,
        sampleCount: Int,
        sampleRateHz: Double,
        sensitivity: Float
    ): ByteArray {
        val n = min(sampleCount, fftSize)
        if (n < 64) return ByteArray(numBands)

        // Remove DC offset so bin 1 isn't artificially inflated
        var mean = 0.0
        for (i in 0 until n) {
            mean += pcm[i].toDouble()
        }
        mean /= n

        for (i in 0 until fftSize) {
            fftRe[i] = if (i < n) {
                ((pcm[i].toDouble() - mean) / 32768.0) * hannWindow[i]
            } else {
                0.0
            }
            fftIm[i] = 0.0
        }

        radix2Fft(fftRe, fftIm)

        val binCount = fftSize / 2
        val magnitudes = DoubleArray(binCount)
        // Scale normalized FFT amplitude into a similar numeric range as Visualizer FFT
        val scale = 256.0 / (fftSize / 4.0)
        for (b in 1 until binCount) {
            magnitudes[b] = hypot(fftRe[b], fftIm[b]) * scale
        }

        return mapMagnitudesToBands(
            magnitudes = magnitudes,
            fftLength = fftSize,
            sampleRateHz = sampleRateHz,
            sensitivity = sensitivity,
            noiseFloor = 0.55,
            minPeakFloor = 1.9
        )
    }

    private fun mapMagnitudesToBands(
        magnitudes: DoubleArray,
        fftLength: Int,
        sampleRateHz: Double,
        sensitivity: Float,
        noiseFloor: Double,
        minPeakFloor: Double
    ): ByteArray {
        val maxBin = magnitudes.size
        val binWidthHz = sampleRateHz / fftLength.toDouble()
        val fMin = 45.0
        val fMax = min(14_000.0, sampleRateHz * 0.45)

        val rawBands = DoubleArray(numBands)
        var frameMax = 0.0

        for (band in 0 until numBands) {
            val t0 = band.toDouble() / numBands
            val t1 = (band + 1).toDouble() / numBands
            val fLow = fMin * (fMax / fMin).pow(t0)
            val fHigh = fMin * (fMax / fMin).pow(t1)

            val exactStart = (fLow / binWidthHz).coerceIn(1.0, (maxBin - 1).toDouble())
            val exactEnd = (fHigh / binWidthHz).coerceIn(exactStart, (maxBin - 1).toDouble())
            val startBin = floor(exactStart).toInt().coerceIn(1, maxBin - 1)
            val endBin = ceil(exactEnd).toInt().coerceIn(startBin + 1, maxBin)

            val combined = if ((exactEnd - exactStart) < 1.0) {
                // Smooth linear interpolation for high band counts (e.g. 64) where low bands are < 1 FFT bin wide
                val centerBin = ((exactStart + exactEnd) * 0.5).coerceIn(1.0, (maxBin - 1).toDouble())
                val lo = floor(centerBin).toInt().coerceIn(1, maxBin - 1)
                val hi = (lo + 1).coerceAtMost(maxBin - 1)
                val frac = centerBin - lo
                magnitudes[lo] * (1.0 - frac) + magnitudes[hi] * frac
            } else {
                var sumSq = 0.0
                var peak = 0.0
                var count = 0
                for (b in startBin until endBin) {
                    val m = magnitudes[b]
                    sumSq += m * m
                    if (m > peak) peak = m
                    count++
                }
                val rms = if (count > 0) sqrt(sumSq / count) else 0.0
                // Blend RMS + peak so wide high-frequency bands stay responsive to transients
                (0.65 * rms) + (0.35 * peak)
            }

            // Pink-noise tilt compensation: tame sub-bass dominance & lift mids/treble
            val bandRatio = band.toDouble() / (numBands - 1).coerceAtLeast(1)
            val tiltGain = 0.72 + (2.35 * bandRatio.pow(1.15))
            val gated = (combined - noiseFloor).coerceAtLeast(0.0) * tiltGain

            // Logarithmic loudness compression (dB-like perception)
            val compressed = ln(1.0 + gated * 1.9)
            rawBands[band] = compressed
            if (compressed > frameMax) frameMax = compressed
        }

        // Adaptive Automatic Gain Control (AGC): fast attack, slow decay, clamped floor
        if (frameMax > runningPeak) {
            runningPeak = (runningPeak * 0.60) + (frameMax * 0.40)
        } else {
            runningPeak = maxOf(minPeakFloor, runningPeak * 0.985)
        }

        val out = ByteArray(numBands)
        val sens = sensitivity.toDouble().coerceIn(0.4, 3.0)
        for (band in 0 until numBands) {
            val normalized = ((rawBands[band] / runningPeak) * sens).coerceIn(0.0, 1.0)
            // Gentle contrast curve so peaks fill the full display height cleanly
            val target = normalized.pow(1.12) * 255.0

            // Fast rise on beats, smooth fall between frames
            val prev = smoothed[band]
            val next = if (target > prev) {
                (prev * 0.22) + (target * 0.78)
            } else {
                (prev * 0.62) + (target * 0.38)
            }
            smoothed[band] = if (next < 2.0) 0.0 else next
            out[band] = smoothed[band].toInt().coerceIn(0, 255).toByte()
        }

        return out
    }

    private fun radix2Fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while ((j and bit) != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]
                re[i] = re[j]
                re[j] = tr
                val ti = im[i]
                im[i] = im[j]
                im[j] = ti
            }
        }

        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wLenRe = cos(ang)
            val wLenIm = sin(ang)
            val half = len shr 1

            var i = 0
            while (i < n) {
                var wRe = 1.0
                var wIm = 0.0
                for (k in 0 until half) {
                    val uRe = re[i + k]
                    val uIm = im[i + k]
                    val vRe = re[i + k + half] * wRe - im[i + k + half] * wIm
                    val vIm = re[i + k + half] * wIm + im[i + k + half] * wRe
                    re[i + k] = uRe + vRe
                    im[i + k] = uIm + vIm
                    re[i + k + half] = uRe - vRe
                    im[i + k + half] = uIm - vIm
                    val nextWRe = wRe * wLenRe - wIm * wLenIm
                    wIm = wRe * wLenIm + wIm * wLenRe
                    wRe = nextWRe
                }
                i += len
            }
            len = len shl 1
        }
    }
}
