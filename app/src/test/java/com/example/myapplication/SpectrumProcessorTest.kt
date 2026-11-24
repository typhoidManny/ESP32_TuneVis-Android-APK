package com.example.bleaudioviz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class SpectrumProcessorTest {

    @Test
    fun silenceProducesZeroBands() {
        val processor = SpectrumProcessor()
        val silentFft = ByteArray(1024)
        val bands = processor.computeFromVisualizerFft(silentFft, 44_100_000, 1.0f)

        assertEquals(NUM_BANDS, bands.size)
        assertTrue(bands.all { (it.toInt() and 0xFF) == 0 })
    }

    @Test
    fun highFrequencyToneReachesRightSideAndFullHeightInPcmMode() {
        val processor = SpectrumProcessor()
        val sampleRate = 44_100.0
        val pcm = ShortArray(1024) { i ->
            // 5 kHz sine wave should land in the upper-right bands (around band 12-13)
            (sin(2.0 * PI * 5_000.0 * i / sampleRate) * 18_000.0).toInt().toShort()
        }

        // Feed a few frames so temporal smoothing settles
        var bands = ByteArray(NUM_BANDS)
        repeat(6) {
            bands = processor.computeFromPcm(pcm, pcm.size, sampleRate, 1.0f)
        }

        val values = bands.map { it.toInt() and 0xFF }
        val peakIndex = values.indices.maxBy { values[it] }
        val peakValue = values[peakIndex]

        assertTrue(
            "Expected 5 kHz tone on right side (band >= ${(NUM_BANDS * 0.65).toInt()}), got $peakIndex ($values)",
            peakIndex >= (NUM_BANDS * 0.65).toInt()
        )
        assertTrue("Expected AGC to drive peak near full height (>= 200), got $peakValue ($values)", peakValue >= 200)
    }

    @Test
    fun lowFrequencyToneLandsOnLeftSideInPcmMode() {
        val processor = SpectrumProcessor()
        val sampleRate = 44_100.0
        val pcm = ShortArray(1024) { i ->
            // 100 Hz bass sine wave should land in low bands (left 25%)
            (sin(2.0 * PI * 100.0 * i / sampleRate) * 18_000.0).toInt().toShort()
        }

        var bands = ByteArray(NUM_BANDS)
        repeat(6) {
            bands = processor.computeFromPcm(pcm, pcm.size, sampleRate, 1.0f)
        }

        val values = bands.map { it.toInt() and 0xFF }
        val peakIndex = values.indices.maxBy { values[it] }
        val peakValue = values[peakIndex]

        assertTrue(
            "Expected 100 Hz tone on left side (band <= ${(NUM_BANDS * 0.25).toInt()}), got $peakIndex ($values)",
            peakIndex <= (NUM_BANDS * 0.25).toInt()
        )
        assertTrue("Expected AGC to drive peak near full height (>= 200), got $peakValue ($values)", peakValue >= 200)
    }
}
