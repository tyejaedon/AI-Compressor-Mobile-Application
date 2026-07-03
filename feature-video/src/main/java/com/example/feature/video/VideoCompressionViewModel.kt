package com.example.feature.video

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.data.VortexServiceLocator
import com.example.core.domain.CompressionPipelineStatus
import com.example.core.domain.CompressionResult
import com.example.core.domain.CompressionPipelineStage
import com.example.core.domain.MediaType
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class VideoCompressionUiState(
    val sourceUri: Uri? = null,
    val result: CompressionResult? = null,
    val pipelineStatus: CompressionPipelineStatus? = null,
    val pipelineTimeline: List<String> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

class VideoCompressionViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = VortexServiceLocator.repository(application)

    private val _uiState = MutableStateFlow(VideoCompressionUiState())
    val uiState: StateFlow<VideoCompressionUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeCompressionPipeline().collectLatest { status ->
                if (status?.mediaType != MediaType.VIDEO) return@collectLatest
                _uiState.update { current ->
                    val stageLine = videoStageDescription(status)
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

    private fun videoStageDescription(status: CompressionPipelineStatus): String {
        val percent = "${(status.progress * 100).toInt()}%"
        return when (status.stage) {
            CompressionPipelineStage.VALIDATING_INPUT -> "Schema check (video) $percent"
            CompressionPipelineStage.LOADING_SOURCE -> "Frame extraction $percent"
            CompressionPipelineStage.PREPARING_MODEL -> "Clip tensorization + normalization $percent"
            CompressionPipelineStage.RUNNING_INFERENCE -> "Spatiotemporal forward pass $percent"
            CompressionPipelineStage.SAVING_OUTPUT -> "Preview strip serialization $percent"
            CompressionPipelineStage.CALCULATING_METRICS -> "PSNR/SSIM frame metrics $percent"
            CompressionPipelineStage.COMPLETED -> "Completed"
            CompressionPipelineStage.FAILED -> "Failed: ${status.message ?: "Video compression failed"}"
        }
    }

    fun onVideoSelected(uri: Uri) {
        _uiState.update { it.copy(sourceUri = uri, error = null) }
    }

    fun compress() {
        val source = _uiState.value.sourceUri ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, pipelineTimeline = emptyList()) }
            val result = repository.compressVideo(source)
            _uiState.update {
                if (result.isSuccess) {
                    it.copy(isLoading = false, result = result.getOrNull())
                } else {
                    it.copy(isLoading = false, error = result.exceptionOrNull()?.message ?: "Video compression failed")
                }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}

