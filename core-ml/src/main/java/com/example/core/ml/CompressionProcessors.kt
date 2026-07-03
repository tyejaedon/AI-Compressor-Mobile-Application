package com.example.core.ml

import android.graphics.Bitmap
import com.example.core.domain.ImageNormalizationMode
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import androidx.core.graphics.scale
import androidx.core.graphics.get
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set

object ImageTensorCodec {
    private const val DEFAULT_CHANNELS = 3

    class TensorStats(
        val min: Float,
        val max: Float,
        val channelMin: FloatArray,
        val channelMax: FloatArray,
        val channelMean: FloatArray,
    )

    fun preprocess(
        bitmap: Bitmap,
        targetWidth: Int,
        targetHeight: Int,
        channels: Int = DEFAULT_CHANNELS,
        normalizationMode: ImageNormalizationMode = ImageNormalizationMode.ZERO_ONE,
    ): Array<Array<Array<FloatArray>>> {
        require(channels == DEFAULT_CHANNELS) { "Image codec currently supports RGB channels only" }
        val resized = bitmap.scale(targetWidth, targetHeight)
        return Array(1) {
            Array(targetHeight) { y ->
                Array(targetWidth) { x ->
                    val pixel = resized[x, y]
                    floatArrayOf(
                        normalizeChannel(((pixel shr 16) and 0xFF) / 255f, normalizationMode),
                        normalizeChannel(((pixel shr 8) and 0xFF) / 255f, normalizationMode),
                        normalizeChannel((pixel and 0xFF) / 255f, normalizationMode),
                    )
                }
            }
        }
    }

    fun postprocess(
        output: Array<Array<Array<FloatArray>>>,
        normalizationMode: ImageNormalizationMode = ImageNormalizationMode.ZERO_ONE,
    ): Bitmap {
        val height = output[0].size
        val width = output[0][0].size
        val bitmap = createBitmap(width, height)
        repeat(height) { y ->
            repeat(width) { x ->
                val rgb = output[0][y][x]
                val r = (denormalizeChannel(rgb[0], normalizationMode) * 255f).toInt()
                val g = (denormalizeChannel(rgb[1], normalizationMode) * 255f).toInt()
                val b = (denormalizeChannel(rgb[2], normalizationMode) * 255f).toInt()
                val packed = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                bitmap[x, y] = packed
            }
        }
        return bitmap
    }

    fun outputBuffer(
        targetHeight: Int,
        targetWidth: Int,
        channels: Int = DEFAULT_CHANNELS,
    ): Array<Array<Array<FloatArray>>> {
        return Array(1) { Array(targetHeight) { Array(targetWidth) { FloatArray(channels) } } }
    }

    fun stats(tensor: Array<Array<Array<FloatArray>>>): TensorStats {
        val height = tensor[0].size
        val width = tensor[0][0].size
        val channels = tensor[0][0][0].size
        var minValue = Float.POSITIVE_INFINITY
        var maxValue = Float.NEGATIVE_INFINITY
        val channelMin = FloatArray(channels) { Float.POSITIVE_INFINITY }
        val channelMax = FloatArray(channels) { Float.NEGATIVE_INFINITY }
        val channelSum = FloatArray(channels)
        val total = (height * width).coerceAtLeast(1)
        repeat(height) { y ->
            repeat(width) { x ->
                val values = tensor[0][y][x]
                repeat(channels) { c ->
                    val value = values[c]
                    minValue = min(minValue, value)
                    maxValue = max(maxValue, value)
                    channelMin[c] = min(channelMin[c], value)
                    channelMax[c] = max(channelMax[c], value)
                    channelSum[c] += value
                }
            }
        }
        val channelMean = FloatArray(channels) { index -> channelSum[index] / total.toFloat() }
        return TensorStats(
            min = minValue,
            max = maxValue,
            channelMin = channelMin,
            channelMax = channelMax,
            channelMean = channelMean,
        )
    }

    private fun normalizeChannel(valueZeroOne: Float, mode: ImageNormalizationMode): Float {
        val bounded = valueZeroOne.coerceIn(0f, 1f)
        return when (mode) {
            ImageNormalizationMode.ZERO_ONE -> bounded
            ImageNormalizationMode.NEG_ONE_ONE -> (bounded * 2f) - 1f
        }
    }

    private fun denormalizeChannel(value: Float, mode: ImageNormalizationMode): Float {
        val zeroOne = when (mode) {
            ImageNormalizationMode.ZERO_ONE -> value
            ImageNormalizationMode.NEG_ONE_ONE -> (value + 1f) * 0.5f
        }
        return zeroOne.coerceIn(0f, 1f)
    }
}

object AudioTensorCodec {
    fun preprocess(samples: FloatArray, window: Int): List<Array<Array<FloatArray>>> {
        require(window > 0) { "window must be > 0" }
        if (samples.isEmpty()) {
            return listOf(Array(1) { Array(window) { FloatArray(1) } })
        }
        val chunks = mutableListOf<Array<Array<FloatArray>>>()
        var cursor = 0
        while (cursor < samples.size) {
            val chunk = Array(1) { Array(window) { FloatArray(1) } }
            repeat(window) { i ->
                val index = cursor + i
                chunk[0][i][0] = if (index < samples.size) samples[index].coerceIn(-1f, 1f) else 0f
            }
            chunks += chunk
            cursor += window
        }
        return chunks
    }

    fun postprocess(chunks: List<Array<Array<FloatArray>>>, originalSize: Int, outputWindow: Int): FloatArray {
        require(outputWindow > 0) { "outputWindow must be > 0" }
        val out = FloatArray(chunks.size * outputWindow)
        var write = 0
        for (chunk in chunks) {
            repeat(outputWindow) { i ->
                out[write++] = chunk[0][i][0].coerceIn(-1f, 1f)
            }
        }
        return out.copyOf(max(originalSize, 1))
    }

    fun outputWindow(window: Int): Array<Array<FloatArray>> {
        require(window > 0) { "window must be > 0" }
        return Array(1) { Array(window) { FloatArray(1) } }
    }
}

object VideoTensorCodec {
    fun outputClip(
        frames: Int,
        height: Int,
        width: Int,
        channels: Int = 3,
    ): Array<Array<Array<Array<FloatArray>>>> {
        return Array(1) { Array(frames) { Array(height) { Array(width) { FloatArray(channels) } } } }
    }
}

object CompressionMetricsCalculator {
    fun psnr(original: FloatArray, reconstructed: FloatArray, peak: Float = 1f): Double {
        require(original.size == reconstructed.size)
        var mse = 0.0
        for (i in original.indices) {
            val diff = (original[i] - reconstructed[i]).toDouble()
            mse += diff * diff
        }
        mse /= original.size.toDouble().coerceAtLeast(1.0)
        if (mse == 0.0) return 99.0
        return 10.0 * log10((peak * peak) / mse)
    }

    fun snr(original: FloatArray, reconstructed: FloatArray): Double {
        require(original.size == reconstructed.size)
        var signal = 0.0
        var noise = 0.0
        for (i in original.indices) {
            val s = original[i].toDouble()
            val n = (original[i] - reconstructed[i]).toDouble()
            signal += s * s
            noise += n * n
        }
        if (noise == 0.0) return 99.0
        return 10.0 * log10(signal / noise)
    }

    fun ssimApprox(original: FloatArray, reconstructed: FloatArray): Double {
        require(original.size == reconstructed.size)
        val n = original.size.toDouble().coerceAtLeast(1.0)
        val meanX = original.sum() / n
        val meanY = reconstructed.sum() / n

        var varX = 0.0
        var varY = 0.0
        var cov = 0.0
        for (i in original.indices) {
            val dx = original[i] - meanX
            val dy = reconstructed[i] - meanY
            varX += dx * dx
            varY += dy * dy
            cov += dx * dy
        }
        val c1 = (0.01 * 0.01)
        val c2 = (0.03 * 0.03)
        val numerator = (2 * meanX * meanY + c1) * (2 * cov / (n - 1 + 1e-6) + c2)
        val denominator = (meanX * meanX + meanY * meanY + c1) * ((varX + varY) / (n - 1 + 1e-6) + c2)
        return (numerator / denominator).coerceIn(0.0, 1.0)
    }

    fun compressionRatio(originalBytes: Long, compressedBytes: Long): Double {
        if (compressedBytes <= 0L) return 0.0
        return originalBytes.toDouble() / compressedBytes.toDouble()
    }

    fun throughput(itemCount: Int, latencyMs: Double): Double {
        if (latencyMs <= 0.0) return 0.0
        return itemCount / (latencyMs / 1000.0)
    }
}

