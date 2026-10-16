package com.example.core.ml

import android.graphics.Bitmap
import kotlin.math.log10
import kotlin.math.max

object MetricsCalculator {

    fun calculateBitmapPsnr(original: Bitmap, reconstructed: Bitmap): Double {
        val w = minOf(original.width, reconstructed.width)
        val h = minOf(original.height, reconstructed.height)
        if (w <= 0 || h <= 0) return 0.0

        val origPixels = IntArray(w * h)
        val reconPixels = IntArray(w * h)
        original.getPixels(origPixels, 0, w, 0, 0, w, h)
        reconstructed.getPixels(reconPixels, 0, w, 0, 0, w, h)

        var sumSquaredError = 0.0
        val totalChannels = (w * h * 3).toDouble()

        for (i in 0 until (w * h)) {
            val p1 = origPixels[i]
            val p2 = reconPixels[i]

            val rDiff = ((p1 shr 16) and 0xFF) - ((p2 shr 16) and 0xFF)
            val gDiff = ((p1 shr 8) and 0xFF) - ((p2 shr 8) and 0xFF)
            val bDiff = (p1 and 0xFF) - (p2 and 0xFF)

            sumSquaredError += (rDiff * rDiff) + (gDiff * gDiff) + (bDiff * bDiff)
        }

        val mse = sumSquaredError / totalChannels
        if (mse == 0.0) return 99.0
        val maxI = 255.0
        return 10.0 * log10((maxI * maxI) / mse)
    }

    fun calculateBitmapSsim(original: Bitmap, reconstructed: Bitmap): Double {
        val w = minOf(original.width, reconstructed.width)
        val h = minOf(original.height, reconstructed.height)
        if (w <= 0 || h <= 0) return 0.0

        val origPixels = IntArray(w * h)
        val reconPixels = IntArray(w * h)
        original.getPixels(origPixels, 0, w, 0, 0, w, h)
        reconstructed.getPixels(reconPixels, 0, w, 0, 0, w, h)

        val n = (w * h).toDouble()
        var sumX = 0.0
        var sumY = 0.0

        for (i in 0 until (w * h)) {
            val p1 = origPixels[i]
            val p2 = reconPixels[i]
            val lumX = 0.299 * ((p1 shr 16) and 0xFF) + 0.587 * ((p1 shr 8) and 0xFF) + 0.114 * (p1 and 0xFF)
            val lumY = 0.299 * ((p2 shr 16) and 0xFF) + 0.587 * ((p2 shr 8) and 0xFF) + 0.114 * (p2 and 0xFF)
            sumX += lumX
            sumY += lumY
        }

        val meanX = sumX / n
        val meanY = sumY / n

        var varX = 0.0
        var varY = 0.0
        var covXY = 0.0

        for (i in 0 until (w * h)) {
            val p1 = origPixels[i]
            val p2 = reconPixels[i]
            val lumX = 0.299 * ((p1 shr 16) and 0xFF) + 0.587 * ((p1 shr 8) and 0xFF) + 0.114 * (p1 and 0xFF)
            val lumY = 0.299 * ((p2 shr 16) and 0xFF) + 0.587 * ((p2 shr 8) and 0xFF) + 0.114 * (p2 and 0xFF)
            val dx = lumX - meanX
            val dy = lumY - meanY
            varX += dx * dx
            varY += dy * dy
            covXY += dx * dy
        }

        val lmax = 255.0
        val c1 = (0.01 * lmax) * (0.01 * lmax)
        val c2 = (0.03 * lmax) * (0.03 * lmax)

        val denomCov = max(n - 1.0, 1.0)
        val sX = varX / denomCov
        val sY = varY / denomCov
        val sXY = covXY / denomCov

        val numerator = (2.0 * meanX * meanY + c1) * (2.0 * sXY + c2)
        val denominator = (meanX * meanX + meanY * meanY + c1) * (sX + sY + c2)

        return (numerator / denominator).coerceIn(0.0, 1.0)
    }

    fun calculateBitsPerPixel(compressedSizeBytes: Long, width: Int, height: Int): Double {
        val totalPixels = (width * height).toDouble().coerceAtLeast(1.0)
        return (compressedSizeBytes * 8.0) / totalPixels
    }

    fun calculateStorageSavings(originalBytes: Long, compressedBytes: Long): Double {
        if (originalBytes <= 0L) return 0.0
        val saved = (originalBytes - compressedBytes).toDouble()
        return ((saved / originalBytes.toDouble()) * 100.0).coerceIn(0.0, 100.0)
    }

    fun calculateAudioSnr(original: FloatArray, reconstructed: FloatArray): Double {
        val len = minOf(original.size, reconstructed.size)
        if (len <= 0) return 0.0

        var signalPower = 0.0
        var noisePower = 0.0

        for (i in 0 until len) {
            val s = original[i].toDouble()
            val diff = (original[i] - reconstructed[i]).toDouble()
            signalPower += s * s
            noisePower += diff * diff
        }

        if (noisePower == 0.0) return 99.0
        if (signalPower == 0.0) return 0.0
        return 10.0 * log10(signalPower / noisePower)
    }
}
