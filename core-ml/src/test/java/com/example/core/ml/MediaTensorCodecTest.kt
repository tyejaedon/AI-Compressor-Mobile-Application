package com.example.core.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaTensorCodecTest {

    @Test
    fun `audio preprocess and postprocess preserves original logical size`() {
        val source = FloatArray(300) { index -> ((index % 20) - 10) / 10f }
        val window = 256

        val chunks = AudioTensorCodec.preprocess(source, window)
        val reconstructed = AudioTensorCodec.postprocess(chunks, originalSize = source.size, outputWindow = window)

        assertEquals(2, chunks.size)
        assertEquals(300, reconstructed.size)
        assertTrue(reconstructed.all { it in -1f..1f })
    }

    @Test
    fun `video output clip matches expected dynamic shape`() {
        val clip = VideoTensorCodec.outputClip(frames = 6, height = 64, width = 64, channels = 3)

        assertEquals(1, clip.size)
        assertEquals(6, clip[0].size)
        assertEquals(64, clip[0][0].size)
        assertEquals(64, clip[0][0][0].size)
        assertEquals(3, clip[0][0][0][0].size)
    }
}

