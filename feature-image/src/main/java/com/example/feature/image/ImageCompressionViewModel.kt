package com.example.feature.image

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.data.VortexServiceLocator
import com.example.core.domain.CompressionPipelineStatus
import com.example.core.domain.CompressionResult
import com.example.core.domain.CompressionPipelineStage
import com.example.core.domain.ImageNormalizationMode
import com.example.core.domain.MediaType
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ImageCompressionUiState(
    val sourceUri: Uri? = null,
    val result: CompressionResult? = null,
    val normalizationMode: ImageNormalizationMode = ImageNormalizationMode.ZERO_ONE,
    val pipelineStatus: CompressionPipelineStatus? = null,
    val pipelineTimeline: List<String> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

class ImageCompressionViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = VortexServiceLocator.repository(application)

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
            CompressionPipelineStage.VALIDATING_INPUT -> "Schema check (image) $percent"
            CompressionPipelineStage.LOADING_SOURCE -> "Data ingestion (bitmap decode) $percent"
            CompressionPipelineStage.PREPARING_MODEL -> "Tensorization + normalization $percent"
            CompressionPipelineStage.RUNNING_INFERENCE -> "Forward pass over tiles $percent"
            CompressionPipelineStage.SAVING_OUTPUT -> "Postprocess + PNG serialization $percent"
            CompressionPipelineStage.CALCULATING_METRICS -> "PSNR/SSIM evaluation $percent"
            CompressionPipelineStage.COMPLETED -> "Completed"
            CompressionPipelineStage.FAILED -> "Failed: ${status.message ?: "Image compression failed"}"
        }
    }

    fun onImageSelected(uri: Uri) {
        _uiState.update { it.copy(sourceUri = uri, error = null) }
    }

    fun compress() {
        val source = _uiState.value.sourceUri ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, pipelineTimeline = emptyList()) }
            val result = repository.compressImage(source)
            _uiState.update {
                if (result.isSuccess) {
                    it.copy(isLoading = false, result = result.getOrNull())
                } else {
                    it.copy(isLoading = false, error = result.exceptionOrNull()?.message ?: "Image compression failed")
                }
            }
        }
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

