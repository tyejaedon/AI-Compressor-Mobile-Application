package com.example.core.ml

import android.content.Context
import com.example.core.domain.InferenceDelegate
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.Tensor
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.nnapi.NnApiDelegate

data class TensorContract(
    val shape: IntArray,
    val dataType: DataType,
)

data class ModelContract(
    val input: TensorContract,
    val output: TensorContract,
)

class TfliteRunner(
    context: Context,
    assetPath: String,
    requestedDelegate: InferenceDelegate,
) : AutoCloseable {
    private var nnApiDelegate: NnApiDelegate? = null
    private var gpuDelegate: GpuDelegate? = null

    private val interpreter: Interpreter

    init {
        val model = context.assets.open(assetPath).use { input ->
            val bytes = input.readBytes()
            ByteBuffer.allocateDirect(bytes.size).apply {
                order(ByteOrder.nativeOrder())
                put(bytes)
                rewind()
            }
        }
        interpreter = createInterpreterWithFallback(model, requestedDelegate)
    }

    fun contract(): ModelContract {
        return ModelContract(
            input = interpreter.getInputTensor(0).toContract(),
            output = interpreter.getOutputTensor(0).toContract(),
        )
    }

    fun run(input: Any, output: Any) {
        validate(
            inputTensor = interpreter.getInputTensor(0),
            outputTensor = interpreter.getOutputTensor(0),
            input = input,
            output = output,
        )
        interpreter.run(input, output)
    }

    private fun validate(inputTensor: Tensor, outputTensor: Tensor, input: Any, output: Any) {
        require(input is Array<*> || input is FloatArray || input is ByteBuffer) {
            "Unsupported input container: ${input::class.java.name}"
        }
        require(output is Array<*> || output is FloatArray || output is ByteBuffer) {
            "Unsupported output container: ${output::class.java.name}"
        }
        require(inputTensor.dataType() == DataType.FLOAT32) {
            "This runner currently supports FLOAT32 models only"
        }
        require(outputTensor.dataType() == DataType.FLOAT32) {
            "This runner currently supports FLOAT32 models only"
        }
        if (input is Array<*>) {
            val inputShape = inferArrayShape(input)
            require(inputShape.contentEquals(inputTensor.shape())) {
                "Input shape mismatch. expected=${inputTensor.shape().contentToString()} actual=${inputShape.contentToString()}"
            }
        }
        if (output is Array<*>) {
            val outputShape = inferArrayShape(output)
            require(outputShape.contentEquals(outputTensor.shape())) {
                "Output shape mismatch. expected=${outputTensor.shape().contentToString()} actual=${outputShape.contentToString()}"
            }
        }
    }

    private fun createInterpreterWithFallback(model: ByteBuffer, requestedDelegate: InferenceDelegate): Interpreter {
        val attempts = when (requestedDelegate) {
            InferenceDelegate.CPU -> listOf(InferenceDelegate.CPU)
            InferenceDelegate.NNAPI -> listOf(InferenceDelegate.NNAPI, InferenceDelegate.CPU)
            InferenceDelegate.GPU -> listOf(InferenceDelegate.GPU, InferenceDelegate.NNAPI, InferenceDelegate.CPU)
        }

        var lastError: Throwable? = null
        attempts.forEach { delegate ->
            val options = Interpreter.Options().apply {
                setNumThreads(4)
                when (delegate) {
                    InferenceDelegate.CPU -> Unit
                    InferenceDelegate.NNAPI -> {
                        nnApiDelegate = NnApiDelegate()
                        addDelegate(nnApiDelegate)
                    }
                    InferenceDelegate.GPU -> {
                        gpuDelegate = GpuDelegate()
                        addDelegate(gpuDelegate)
                    }
                }
            }
            runCatching { return Interpreter(model, options) }
                .onFailure {
                    lastError = it
                    nnApiDelegate?.close()
                    gpuDelegate?.close()
                    nnApiDelegate = null
                    gpuDelegate = null
                }
        }
        throw IllegalStateException("Unable to initialize TFLite interpreter", lastError)
    }

    private fun inferArrayShape(value: Any?): IntArray {
        when (value) {
            is FloatArray -> return intArrayOf(value.size)
            is IntArray -> return intArrayOf(value.size)
            is ByteArray -> return intArrayOf(value.size)
        }
        if (value !is Array<*>) return intArrayOf()
        if (value.isEmpty()) return intArrayOf(0)
        return intArrayOf(value.size) + inferArrayShape(value[0])
    }

    override fun close() {
        interpreter.close()
        nnApiDelegate?.close()
        gpuDelegate?.close()
    }
}

private fun Tensor.toContract(): TensorContract = TensorContract(shape = shape(), dataType = dataType())

