package com.example.feature.audio

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.data.VortexServiceLocator
import com.example.core.domain.AicCompressionAlgo
import com.example.core.domain.AicContainerInspection
import com.example.core.domain.CompressionPipelineStage
import com.example.core.domain.CompressionPipelineStatus
import com.example.core.domain.CompressionResult
import com.example.core.domain.CompressionSettings
import com.example.core.domain.InferenceDelegate
import com.example.core.domain.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AudioCompressionUiState(
    val sourceUri: Uri? = null,
    val sourceSamples: FloatArray = FloatArray(0),
    val reconstructedSamples: FloatArray = FloatArray(0),
    val result: CompressionResult? = null,
    val inspection: AicContainerInspection? = null,
    val settings: CompressionSettings = CompressionSettings(
        delegate = InferenceDelegate.CPU,
        bitDepth = 8,
        compressionAlgo = AicCompressionAlgo.DEFLATE,
        threads = 4,
    ),
    val pipelineStatus: CompressionPipelineStatus? = null,
    val pipelineTimeline: List<String> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

class AudioCompressionViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = VortexServiceLocator.repository(application)
    private val context = application.applicationContext

    private val _uiState = MutableStateFlow(AudioCompressionUiState())
    val uiState: StateFlow<AudioCompressionUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeCompressionPipeline().collectLatest { status ->
                if (status?.mediaType != MediaType.AUDIO) return@collectLatest
                _uiState.update { current ->
                    val stageLine = audioStageDescription(status)
                    val timeline = if (current.pipelineTimeline.lastOrNull() == stageLine) {
                        current.pipelineTimeline
                    } else {
                        current.pipelineTimeline + stageLine
                    }
                    current.copy(pipelineStatus = status, pipelineTimeline = timeline)
                }
            }
        }
    }

    private fun audioStageDescription(status: CompressionPipelineStatus): String {
        val percent = "${(status.progress * 100).toInt()}%"
        return when (status.stage) {
            CompressionPipelineStage.VALIDATING_INPUT -> "Schema & WAV header check $percent"
            CompressionPipelineStage.LOADING_SOURCE -> "PCM16 16kHz decoding $percent"
            CompressionPipelineStage.PREPARING_MODEL -> "TFLite strided conv engine init $percent"
            CompressionPipelineStage.RUNNING_INFERENCE -> "1D temporal neural compression $percent"
            CompressionPipelineStage.SAVING_OUTPUT -> "AIC container serialization $percent"
            CompressionPipelineStage.CALCULATING_METRICS -> "SNR & storage ratio evaluation $percent"
            CompressionPipelineStage.COMPLETED -> "Completed"
            CompressionPipelineStage.FAILED -> "Failed: ${status.message ?: "Audio compression failed"}"
        }
    }

    fun onAudioSelected(uri: Uri) {
        viewModelScope.launch {
            val samples = withContext(Dispatchers.IO) {
                runCatching {
                    readWavSamples(uri)
                }.getOrDefault(FloatArray(0))
            }
            _uiState.update {
                it.copy(
                    sourceUri = uri,
                    sourceSamples = samples,
                    reconstructedSamples = FloatArray(0),
                    result = null,
                    inspection = null,
                    error = null,
                )
            }
        }
    }

    fun onAicFileSelected(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            val inspectResult = repository.inspectAicFile(uri)
            val decompressResult = repository.decompressAicFile(uri)

            if (decompressResult.isSuccess) {
                val res = decompressResult.getOrNull()
                val reconSamples = withContext(Dispatchers.IO) {
                    res?.previewUri?.let { previewUri ->
                        readWavSamples(previewUri)
                    } ?: FloatArray(0)
                }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        result = res,
                        inspection = inspectResult.getOrNull(),
                        reconstructedSamples = reconSamples,
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = decompressResult.exceptionOrNull()?.message ?: "Failed to open AIC audio file",
                    )
                }
            }
        }
    }

    fun compressToAic() {
        val source = _uiState.value.sourceUri ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, pipelineTimeline = emptyList()) }
            val settings = _uiState.value.settings
            val result = repository.compressAudioToAic(source, settings)
            if (result.isSuccess) {
                val res = result.getOrNull()
                val reconSamples = withContext(Dispatchers.IO) {
                    res?.previewUri?.let { previewUri ->
                        readWavSamples(previewUri)
                    } ?: FloatArray(0)
                }
                val inspectResult = res?.outputUri?.let { repository.inspectAicFile(it).getOrNull() }

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        result = res,
                        inspection = inspectResult,
                        reconstructedSamples = reconSamples,
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = result.exceptionOrNull()?.message ?: "AIC Audio compression failed",
                    )
                }
            }
        }
    }

    fun setBitDepth(depth: Int) {
        _uiState.update { it.copy(settings = it.settings.copy(bitDepth = depth)) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    private fun readWavSamples(uri: Uri): FloatArray {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return FloatArray(0)
        if (bytes.size <= 44) return FloatArray(0)
        val pcm = bytes.copyOfRange(44, bytes.size)
        val shortBuffer = java.nio.ByteBuffer.wrap(pcm).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val out = FloatArray(shortBuffer.remaining())
        var i = 0
        while (shortBuffer.hasRemaining()) {
            out[i++] = shortBuffer.get().toFloat() / Short.MAX_VALUE.toFloat()
        }
        return out
    }
}
