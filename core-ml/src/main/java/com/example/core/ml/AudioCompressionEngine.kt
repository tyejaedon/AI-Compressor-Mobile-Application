package com.example.core.ml

import android.content.Context
import com.example.core.domain.InferenceDelegate
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.roundToInt

class AudioCompressionEngine(
    private val context: Context,
    private val requestedDelegate: InferenceDelegate = InferenceDelegate.CPU,
    private val threads: Int = 4,
) : AutoCloseable {

    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private var nnApiDelegate: NnApiDelegate? = null

    val windowSize = 8000

    init {
        initializeModel()
    }

    private fun initializeModel() {
        val options = Interpreter.Options().apply {
            setNumThreads(threads)
            when (requestedDelegate) {
                InferenceDelegate.GPU -> {
                    runCatching {
                        val delegate = GpuDelegate()
                        gpuDelegate = delegate
                        addDelegate(delegate)
                    }
                }
                InferenceDelegate.NNAPI -> {
                    runCatching {
                        val delegate = NnApiDelegate()
                        nnApiDelegate = delegate
                        addDelegate(delegate)
                    }
                }
                InferenceDelegate.CPU -> Unit
            }
        }

        interpreter = runCatching {
            Interpreter(loadModelBuffer("models/audio_autoencoder_fp16.tflite"), options)
        }.getOrElse {
            val fallbackOpts = Interpreter.Options().apply { setNumThreads(threads) }
            Interpreter(loadModelBuffer("models/audio_autoencoder_fp16.tflite"), fallbackOpts)
        }
    }

    private fun loadModelBuffer(assetPath: String): ByteBuffer {
        val fileDescriptor = context.assets.openFd(assetPath)
        FileInputStream(fileDescriptor.fileDescriptor).use { inputStream ->
            val fileChannel = inputStream.channel
            return fileChannel.map(
                FileChannel.MapMode.READ_ONLY,
                fileDescriptor.startOffset,
                fileDescriptor.declaredLength,
            )
        }
    }

    data class AudioLatents(
        val quantizedBytes: ByteArray,
        val totalSampleCount: Int,
        val windowCount: Int,
        val bitDepth: Int,
    )

    fun encodeAudio(samples: FloatArray, bitDepth: Int = 8): AudioLatents {
        val interp = checkNotNull(interpreter) { "Audio interpreter not initialized" }
        val numWindows = ((samples.size + windowSize - 1) / windowSize).coerceAtLeast(1)
        val quantizedBytes = ByteArray(numWindows * windowSize)
        val maxLevel = (1 shl bitDepth) - 1

        val inputBuffer = ByteBuffer.allocateDirect(1 * windowSize * 1 * 4).apply {
            order(ByteOrder.nativeOrder())
        }
        val outputBuffer = ByteBuffer.allocateDirect(1 * windowSize * 1 * 4).apply {
            order(ByteOrder.nativeOrder())
        }

        synchronized(interp) {
            for (w in 0 until numWindows) {
                inputBuffer.rewind()
                val offset = w * windowSize
                for (i in 0 until windowSize) {
                    val idx = offset + i
                    val sample = if (idx < samples.size) samples[idx].coerceIn(-1f, 1f) else 0f
                    inputBuffer.putFloat(sample)
                }
                inputBuffer.rewind()
                outputBuffer.rewind()

                interp.run(inputBuffer, outputBuffer)
                outputBuffer.rewind()

                for (i in 0 until windowSize) {
                    val outFloat = outputBuffer.float.coerceIn(-1f, 1f)
                    val normalized01 = (outFloat + 1.0f) * 0.5f
                    val discrete = (normalized01 * maxLevel).roundToInt().coerceIn(0, maxLevel).toByte()
                    quantizedBytes[offset + i] = discrete
                }
            }
        }

        return AudioLatents(
            quantizedBytes = quantizedBytes,
            totalSampleCount = samples.size,
            windowCount = numWindows,
            bitDepth = bitDepth,
        )
    }

    fun decodeAudio(
        quantizedBytes: ByteArray,
        totalSampleCount: Int,
        bitDepth: Int = 8,
    ): FloatArray {
        val outSamples = FloatArray(totalSampleCount)
        val maxLevel = ((1 shl bitDepth) - 1).toFloat()
        val mask = (1 shl bitDepth) - 1

        val limit = minOf(quantizedBytes.size, totalSampleCount)
        for (i in 0 until limit) {
            val symbol = quantizedBytes[i].toInt() and mask
            val normalized01 = symbol / maxLevel
            val floatVal = (normalized01 * 2.0f) - 1.0f
            outSamples[i] = floatVal.coerceIn(-1f, 1f)
        }
        return outSamples
    }

    override fun close() {
        interpreter?.close()
        gpuDelegate?.close()
        nnApiDelegate?.close()
        interpreter = null
        gpuDelegate = null
        nnApiDelegate = null
    }
}
