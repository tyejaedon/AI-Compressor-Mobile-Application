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

class UpscalerEngine(
    private val context: Context,
    private val requestedDelegate: InferenceDelegate = InferenceDelegate.GPU,
    private val threads: Int = 4,
) : AutoCloseable {

    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private var nnApiDelegate: NnApiDelegate? = null

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
            Interpreter(loadModelBuffer("models/image_upscaler_fp16.tflite"), options)
        }.getOrElse {
            val fallbackOpts = Interpreter.Options().apply { setNumThreads(threads) }
            Interpreter(loadModelBuffer("models/image_upscaler_fp16.tflite"), fallbackOpts)
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

    fun upscale(bitmap: Bitmap): Bitmap {
        val interp = checkNotNull(interpreter) { "Upscaler not initialized" }
        // Limit input size to prevent out of memory on low-end devices if needed (e.g. max 512x512)
        val maxInputDim = 256
        val targetInput = if (bitmap.width > maxInputDim || bitmap.height > maxInputDim) {
            val aspect = bitmap.width.toFloat() / bitmap.height.toFloat()
            if (aspect >= 1.0f) {
                bitmap.scale(maxInputDim, (maxInputDim / aspect).roundToInt().coerceAtLeast(1))
            } else {
                bitmap.scale((maxInputDim * aspect).roundToInt().coerceAtLeast(1), maxInputDim)
            }
        } else {
            bitmap
        }

        val inW = targetInput.width
        val inH = targetInput.height
        val outW = inW * 2
        val outH = inH * 2

        synchronized(interp) {
            interp.resizeInput(0, intArrayOf(1, inH, inW, 3))
            interp.allocateTensors()

            val inputBuffer = ByteBuffer.allocateDirect(1 * inH * inW * 3 * 4).apply {
                order(ByteOrder.nativeOrder())
                rewind()
            }

            val inPixels = IntArray(inW * inH)
            targetInput.getPixels(inPixels, 0, inW, 0, 0, inW, inH)

            for (pixel in inPixels) {
                val r = ((pixel shr 16) and 0xFF) / 255.0f
                val g = ((pixel shr 8) and 0xFF) / 255.0f
                val b = (pixel and 0xFF) / 255.0f
                inputBuffer.putFloat(r)
                inputBuffer.putFloat(g)
                inputBuffer.putFloat(b)
            }
            inputBuffer.rewind()

            val outputBuffer = ByteBuffer.allocateDirect(1 * outH * outW * 3 * 4).apply {
                order(ByteOrder.nativeOrder())
                rewind()
            }

            interp.run(inputBuffer, outputBuffer)
            outputBuffer.rewind()

            val outBitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            val outPixels = IntArray(outW * outH)
            for (i in 0 until outW * outH) {
                val r = (outputBuffer.float.coerceIn(0.0f, 1.0f) * 255.0f).roundToInt().coerceIn(0, 255)
                val g = (outputBuffer.float.coerceIn(0.0f, 1.0f) * 255.0f).roundToInt().coerceIn(0, 255)
                val b = (outputBuffer.float.coerceIn(0.0f, 1.0f) * 255.0f).roundToInt().coerceIn(0, 255)
                outPixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            outBitmap.setPixels(outPixels, 0, outW, 0, 0, outW, outH)
            return outBitmap
        }
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
