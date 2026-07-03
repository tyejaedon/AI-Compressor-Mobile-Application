package com.example.feature.audio

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

data class AudioCompressionUiState(
    val sourceUri: Uri? = null,
    val result: CompressionResult? = null,
    val pipelineStatus: CompressionPipelineStatus? = null,
    val pipelineTimeline: List<String> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

class AudioCompressionViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = VortexServiceLocator.repository(application)

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
            CompressionPipelineStage.VALIDATING_INPUT -> "Schema check (audio) $percent"
            CompressionPipelineStage.LOADING_SOURCE -> "PCM ingestion $percent"
            CompressionPipelineStage.PREPARING_MODEL -> "Windowing + tensorization $percent"
            CompressionPipelineStage.RUNNING_INFERENCE -> "Encoder/decoder forward pass $percent"
            CompressionPipelineStage.SAVING_OUTPUT -> "WAV reconstruction + write $percent"
            CompressionPipelineStage.CALCULATING_METRICS -> "PSNR/SNR evaluation $percent"
            CompressionPipelineStage.COMPLETED -> "Completed"
            CompressionPipelineStage.FAILED -> "Failed: ${status.message ?: "Audio compression failed"}"
        }
    }

    fun onAudioSelected(uri: Uri) {
        _uiState.update { it.copy(sourceUri = uri, error = null) }
    }

    fun compress() {
        val source = _uiState.value.sourceUri ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, pipelineTimeline = emptyList()) }
            val result = repository.compressAudio(source)
            _uiState.update {
                if (result.isSuccess) {
                    it.copy(isLoading = false, result = result.getOrNull())
                } else {
                    it.copy(isLoading = false, error = result.exceptionOrNull()?.message ?: "Audio compression failed")
                }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}

