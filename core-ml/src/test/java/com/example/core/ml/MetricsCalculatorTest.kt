package com.example.core.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetricsCalculatorTest {

    @Test
    fun testBitsPerPixelCalculation() {
        // 1000 bytes for a 100x100 image
        // 1000 * 8 / 10000 = 0.8 bpp
        val bpp = MetricsCalculator.calculateBitsPerPixel(1000L, 100, 100)
        assertEquals(0.8, bpp, 0.001)
    }

    @Test
    fun testStorageSavingsCalculation() {
        val original = 100_000L
        val compressed = 10_000L
        val savings = MetricsCalculator.calculateStorageSavings(original, compressed)
        assertEquals(90.0, savings, 0.001)
    }

    @Test
    fun testAudioSnrCalculation() {
        val original = FloatArray(1000) { (it % 100) / 100f }
        val noisy = FloatArray(1000) { original[it] + 0.01f }
        val snr = MetricsCalculator.calculateAudioSnr(original, noisy)
        assertTrue("SNR should be reasonable for low noise", snr > 20.0)
    }
}
