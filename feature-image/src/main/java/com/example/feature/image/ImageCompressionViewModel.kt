package com.example.feature.image

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import com.example.core.domain.ImageNormalizationMode
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

data class ImageCompressionUiState(
    val sourceUri: Uri? = null,
    val sourceBitmap: Bitmap? = null,
    val reconstructedBitmap: Bitmap? = null,
    val result: CompressionResult? = null,
    val inspection: AicContainerInspection? = null,
    val settings: CompressionSettings = CompressionSettings(
        delegate = InferenceDelegate.GPU,
        bitDepth = 8,
        compressionAlgo = AicCompressionAlgo.DEFLATE,
        enableSuperResolution = false,
        threads = 4,
    ),
    val normalizationMode: ImageNormalizationMode = ImageNormalizationMode.ZERO_ONE,
    val pipelineStatus: CompressionPipelineStatus? = null,
    val pipelineTimeline: List<String> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

class ImageCompressionViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = VortexServiceLocator.repository(application)
    private val context = application.applicationContext

    private val _uiState = MutableStateFlow(ImageCompressionUiState())
    val uiState: StateFlow<ImageCompressionUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.imageNormalizationMode().collectLatest { mode ->
                _uiState.update { it.copy(normalizationMode = mode) }
            }
        }
        viewModelScope.launch {
            repository.observeCompressionPipeline().collectLatest { status ->
                if (status?.mediaType != MediaType.IMAGE) return@collectLatest
                _uiState.update { current ->
                    val stageLine = imageStageDescription(status)
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

    private fun imageStageDescription(status: CompressionPipelineStatus): String {
        val percent = "${(status.progress * 100).toInt()}%"
        return when (status.stage) {
            CompressionPipelineStage.VALIDATING_INPUT -> "Schema & file check $percent"
            CompressionPipelineStage.LOADING_SOURCE -> "Loading media to memory $percent"
            CompressionPipelineStage.PREPARING_MODEL -> "TFLite model & GPU delegate initialization $percent"
            CompressionPipelineStage.RUNNING_INFERENCE -> "Deep latent autoencoder inference $percent"
            CompressionPipelineStage.SAVING_OUTPUT -> "Serializing AIC container + Deflate stream $percent"
            CompressionPipelineStage.CALCULATING_METRICS -> "Evaluating PSNR, SSIM & BPP $percent"
            CompressionPipelineStage.COMPLETED -> "Completed"
            CompressionPipelineStage.FAILED -> "Failed: ${status.message ?: "Compression failed"}"
        }
    }

    fun onImageSelected(uri: Uri) {
        viewModelScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it)
                    }
                }.getOrNull()
            }
            _uiState.update {
                it.copy(
                    sourceUri = uri,
                    sourceBitmap = bitmap,
                    reconstructedBitmap = null,
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
            val decompressResult = repository.decompressAicFile(
                aicUri = uri,
                enableSuperResolution = _uiState.value.settings.enableSuperResolution,
            )

            if (decompressResult.isSuccess) {
                val res = decompressResult.getOrNull()
                val reconBitmap = withContext(Dispatchers.IO) {
                    res?.previewUri?.let { previewUri ->
                        context.contentResolver.openInputStream(previewUri)?.use {
                            BitmapFactory.decodeStream(it)
                        }
                    }
                }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        result = res,
                        inspection = inspectResult.getOrNull(),
                        reconstructedBitmap = reconBitmap,
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = decompressResult.exceptionOrNull()?.message ?: "Failed to open AIC file",
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
            val result = repository.compressImageToAic(source, settings)
            if (result.isSuccess) {
                val res = result.getOrNull()
                val reconBitmap = withContext(Dispatchers.IO) {
                    res?.previewUri?.let { previewUri ->
                        context.contentResolver.openInputStream(previewUri)?.use {
                            BitmapFactory.decodeStream(it)
                        }
                    }
                }
                val inspectResult = res?.outputUri?.let { repository.inspectAicFile(it).getOrNull() }

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        result = res,
                        inspection = inspectResult,
                        reconstructedBitmap = reconBitmap,
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = result.exceptionOrNull()?.message ?: "AIC Image compression failed",
                    )
                }
            }
        }
    }

    fun toggleSuperResolution(enabled: Boolean) {
        _uiState.update { it.copy(settings = it.settings.copy(enableSuperResolution = enabled)) }
    }

    fun setBitDepth(depth: Int) {
        _uiState.update { it.copy(settings = it.settings.copy(bitDepth = depth)) }
    }

    fun setDelegate(delegate: InferenceDelegate) {
        _uiState.update { it.copy(settings = it.settings.copy(delegate = delegate)) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun setImageNormalizationMode(mode: ImageNormalizationMode) {
        viewModelScope.launch {
            repository.setImageNormalizationMode(mode)
        }
    }
}
