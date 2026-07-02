package com.example.core.ml

import org.junit.Assert.assertTrue
import org.junit.Test

class CompressionMetricsCalculatorTest {

    @Test
    fun `psnr is high for identical arrays`() {
        val original = floatArrayOf(0.1f, 0.3f, 0.7f, 0.2f)
        val reconstructed = original.copyOf()

        val psnr = CompressionMetricsCalculator.psnr(original, reconstructed)

        assertTrue(psnr >= 90.0)
    }

    @Test
    fun `snr decreases when reconstruction has noise`() {
        val original = FloatArray(256) { 0.5f }
        val noisy = FloatArray(256) { if (it % 2 == 0) 0.45f else 0.55f }

        val snr = CompressionMetricsCalculator.snr(original, noisy)

        assertTrue(snr in 10.0..40.0)
    }
}

