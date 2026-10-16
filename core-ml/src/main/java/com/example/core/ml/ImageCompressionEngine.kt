package com.example.core.ml

import android.content.Context
import android.graphics.Bitmap
import androidx.core.graphics.scale
import com.example.core.domain.InferenceDelegate
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.roundToInt

class ImageCompressionEngine(
    private val context: Context,
    private val requestedDelegate: InferenceDelegate = InferenceDelegate.GPU,
    private val threads: Int = 4,
) : AutoCloseable {

    private var encoderInterpreter: Interpreter? = null
    private var decoderInterpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private var nnApiDelegate: NnApiDelegate? = null

    val blockSize = 128
    val latentH = 32
    val latentW = 32
    val latentDim = 16

    init {
        initializeModels()
    }

    private fun initializeModels() {
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
                InferenceDelegate.CPU -> {
                    // Standard multi-threaded CPU execution
                }
            }
        }

        encoderInterpreter = runCatching {
            Interpreter(loadModelBuffer("models/image_encoder_fp16.tflite"), options)
        }.getOrElse {
            // Fallback to CPU if delegate initialization fails
            val fallbackOpts = Interpreter.Options().apply { setNumThreads(threads) }
            Interpreter(loadModelBuffer("models/image_encoder_fp16.tflite"), fallbackOpts)
        }

        decoderInterpreter = runCatching {
            Interpreter(loadModelBuffer("models/image_decoder_fp16.tflite"), options)
        }.getOrElse {
            val fallbackOpts = Interpreter.Options().apply { setNumThreads(threads) }
            Interpreter(loadModelBuffer("models/image_decoder_fp16.tflite"), fallbackOpts)
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

    data class LatentResult(
        val quantizedBytes: ByteArray,
        val originalWidth: Int,
        val originalHeight: Int,
        val latentH: Int,
        val latentW: Int,
        val latentChannels: Int,
        val bitDepth: Int,
    )

    fun encodeBitmap(
        bitmap: Bitmap,
        bitDepth: Int = 8,
    ): LatentResult {
        val scaled = bitmap.scale(blockSize, blockSize)
        val inputBuffer = ByteBuffer.allocateDirect(1 * blockSize * blockSize * 3 * 4).apply {
            order(ByteOrder.nativeOrder())
            rewind()
        }

        val pixels = IntArray(blockSize * blockSize)
        scaled.getPixels(pixels, 0, blockSize, 0, 0, blockSize, blockSize)

        for (pixel in pixels) {
            val r = ((pixel shr 16) and 0xFF) / 255.0f
            val g = ((pixel shr 8) and 0xFF) / 255.0f
            val b = (pixel and 0xFF) / 255.0f
            inputBuffer.putFloat(r)
            inputBuffer.putFloat(g)
            inputBuffer.putFloat(b)
        }
        inputBuffer.rewind()

        val outputBuffer = ByteBuffer.allocateDirect(1 * latentH * latentW * latentDim * 4).apply {
            order(ByteOrder.nativeOrder())
            rewind()
        }

        val encoder = checkNotNull(encoderInterpreter) { "Encoder not initialized" }
        synchronized(encoder) {
            encoder.run(inputBuffer, outputBuffer)
        }
        outputBuffer.rewind()

        val totalElements = latentH * latentW * latentDim
        val quantizedBytes = ByteArray(totalElements)
        val maxLevel = (1 shl bitDepth) - 1

        for (i in 0 until totalElements) {
            val floatVal = outputBuffer.float.coerceIn(0.0f, 1.0f)
            val discrete = (floatVal * maxLevel).roundToInt().coerceIn(0, maxLevel).toByte()
            quantizedBytes[i] = discrete
        }

        return LatentResult(
            quantizedBytes = quantizedBytes,
            originalWidth = bitmap.width,
            originalHeight = bitmap.height,
            latentH = latentH,
            latentW = latentW,
            latentChannels = latentDim,
            bitDepth = bitDepth,
        )
    }

    fun decodeLatents(
        quantizedBytes: ByteArray,
        originalWidth: Int,
        originalHeight: Int,
        latentH: Int = this.latentH,
        latentW: Int = this.latentW,
        latentChannels: Int = this.latentDim,
        bitDepth: Int = 8,
    ): Bitmap {
        val inputBuffer = ByteBuffer.allocateDirect(1 * latentH * latentW * latentChannels * 4).apply {
            order(ByteOrder.nativeOrder())
            rewind()
        }

        val maxLevel = ((1 shl bitDepth) - 1).toFloat()
        val mask = (1 shl bitDepth) - 1

        for (b in quantizedBytes) {
            val symbol = b.toInt() and mask
            inputBuffer.putFloat(symbol / maxLevel)
        }
        inputBuffer.rewind()

        val outputBuffer = ByteBuffer.allocateDirect(1 * blockSize * blockSize * 3 * 4).apply {
            order(ByteOrder.nativeOrder())
            rewind()
        }

        val decoder = checkNotNull(decoderInterpreter) { "Decoder not initialized" }
        synchronized(decoder) {
            decoder.run(inputBuffer, outputBuffer)
        }
        outputBuffer.rewind()

        val outBitmap = Bitmap.createBitmap(blockSize, blockSize, Bitmap.Config.ARGB_8888)
        val outPixels = IntArray(blockSize * blockSize)
        for (i in 0 until blockSize * blockSize) {
            val r = (outputBuffer.float.coerceIn(0.0f, 1.0f) * 255.0f).roundToInt().coerceIn(0, 255)
            val g = (outputBuffer.float.coerceIn(0.0f, 1.0f) * 255.0f).roundToInt().coerceIn(0, 255)
            val b = (outputBuffer.float.coerceIn(0.0f, 1.0f) * 255.0f).roundToInt().coerceIn(0, 255)
            outPixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        outBitmap.setPixels(outPixels, 0, blockSize, 0, 0, blockSize, blockSize)

        return if (originalWidth > 0 && originalHeight > 0 && (originalWidth != blockSize || originalHeight != blockSize)) {
            outBitmap.scale(originalWidth, originalHeight)
        } else {
            outBitmap
        }
    }

    override fun close() {
        encoderInterpreter?.close()
        decoderInterpreter?.close()
        gpuDelegate?.close()
        nnApiDelegate?.close()
        encoderInterpreter = null
        decoderInterpreter = null
        gpuDelegate = null
        nnApiDelegate = null
    }
}
