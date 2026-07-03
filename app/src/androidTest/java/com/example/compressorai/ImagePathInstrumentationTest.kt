package com.example.compressorai

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.core.domain.ImageNormalizationMode
import com.example.core.ml.ImageTensorCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImagePathInstrumentationTest {

    @Test
    fun imageCodec_roundTripProducesExpectedShapeAndBounds() {
        val targetWidth = 32
        val targetHeight = 32
        val source = Bitmap.createBitmap(96, 72, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(120, 45, 200))
        }

        val input = ImageTensorCodec.preprocess(source, targetWidth = targetWidth, targetHeight = targetHeight)
        val outputBitmap = ImageTensorCodec.postprocess(input)

        assertEquals(1, input.size)
        assertEquals(targetHeight, input[0].size)
        assertEquals(targetWidth, input[0][0].size)
        assertEquals(3, input[0][0][0].size)
        assertEquals(targetWidth, outputBitmap.width)
        assertEquals(targetHeight, outputBitmap.height)

        val pixel = outputBitmap.getPixel(10, 10)
        assertTrue(Color.red(pixel) in 0..255)
        assertTrue(Color.green(pixel) in 0..255)
        assertTrue(Color.blue(pixel) in 0..255)
    }

    @Test
    fun imageCodec_negOneOneNormalizationRoundTripProducesVisibleColor() {
        val source = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(220, 30, 80))
        }

        val input = ImageTensorCodec.preprocess(
            bitmap = source,
            targetWidth = 32,
            targetHeight = 32,
            normalizationMode = ImageNormalizationMode.NEG_ONE_ONE,
        )
        val outputBitmap = ImageTensorCodec.postprocess(
            output = input,
            normalizationMode = ImageNormalizationMode.NEG_ONE_ONE,
        )

        val tensorPixel = input[0][0][0]
        assertTrue(tensorPixel.all { it in -1f..1f })

        val pixel = outputBitmap.getPixel(10, 10)
        assertTrue(Color.red(pixel) > Color.green(pixel))
        assertTrue(Color.red(pixel) > Color.blue(pixel))
    }
}

