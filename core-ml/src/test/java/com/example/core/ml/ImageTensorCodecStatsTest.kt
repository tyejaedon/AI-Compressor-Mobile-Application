package com.example.core.ml

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ImageTensorCodecStatsTest {

    @Test
    fun stats_reportsGlobalAndPerChannelRanges() {
        val tensor = arrayOf(
            arrayOf(
                arrayOf(floatArrayOf(0.10f, 0.20f, 0.30f), floatArrayOf(0.40f, 0.50f, 0.60f)),
                arrayOf(floatArrayOf(0.70f, 0.80f, 0.90f), floatArrayOf(0.00f, 1.00f, 0.50f)),
            )
        )

        val stats = ImageTensorCodec.stats(tensor)

        assertEquals(0.00f, stats.min, 1e-6f)
        assertEquals(1.00f, stats.max, 1e-6f)
        assertArrayEquals(floatArrayOf(0.00f, 0.20f, 0.30f), stats.channelMin, 1e-6f)
        assertArrayEquals(floatArrayOf(0.70f, 1.00f, 0.90f), stats.channelMax, 1e-6f)
        assertArrayEquals(floatArrayOf(0.30f, 0.625f, 0.575f), stats.channelMean, 1e-6f)
    }
}

