package com.example.core.data.container

import com.example.core.domain.AicCompressionAlgo
import com.example.core.domain.AicModality
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AicContainerTest {

    @Test
    fun testSerializationAndDeserializationRoundtrip() {
        val bos = ByteArrayOutputStream()
        val dummyLatents = ByteArray(32 * 32 * 16) { (it % 127).toByte() }

        AicContainer.write(
            out = bos,
            modality = AicModality.IMAGE,
            width = 128,
            height = 128,
            latentH = 32,
            latentW = 32,
            latentChannels = 16,
            bitDepth = 8,
            algo = AicCompressionAlgo.DEFLATE,
            rawLatentBytes = dummyLatents,
        )

        val serialized = bos.toByteArray()
        assertTrue("Serialized container should be smaller than raw payload", serialized.size < dummyLatents.size)

        val bis = ByteArrayInputStream(serialized)
        val (header, restoredLatents) = AicContainer.read(bis)

        assertEquals(AicModality.IMAGE, header.modality)
        assertEquals(1.toByte(), header.version)
        assertEquals(8.toByte(), header.bitDepth)
        assertEquals(AicCompressionAlgo.DEFLATE, header.compressionAlgo)
        assertEquals(128, header.originalWidth)
        assertEquals(128, header.originalHeight)
        assertEquals(32, header.latentH)
        assertEquals(32, header.latentW)
        assertEquals(16, header.latentChannels)
        assertArrayEquals(dummyLatents, restoredLatents)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testInvalidHeaderThrows() {
        val corrupted = byteArrayOf(0, 1, 2, 3, 4, 5)
        AicContainer.read(ByteArrayInputStream(corrupted))
    }
}
