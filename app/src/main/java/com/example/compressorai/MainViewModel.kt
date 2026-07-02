package com.example.compressorai

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.data.VortexServiceLocator
import com.example.core.domain.CompressionRepository
import com.example.core.domain.BatchProgress
import com.example.core.domain.MediaType
import com.example.core.domain.ModelMetadata
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MainUiState(
    val metadata: List<ModelMetadata> = emptyList(),
    val batchProgress: List<BatchProgress> = emptyList(),
    val compatibilityIssues: List<String> = emptyList(),
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: CompressionRepository = VortexServiceLocator.repository(application)

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.batchProgress().collect { progress ->
                _uiState.update { it.copy(batchProgress = progress) }
            }
        }
        refreshMetadata()
    }

    fun refreshMetadata() {
        viewModelScope.launch {
            val metadata = runCatching { repository.modelMetadata() }.getOrDefault(emptyList())
            val issues = metadata.flatMap(::validateContract)
            _uiState.update { it.copy(metadata = metadata, compatibilityIssues = issues) }
        }
    }

    private fun validateContract(modelMetadata: ModelMetadata): List<String> {
        val issues = mutableListOf<String>()

        when (modelMetadata.mediaType) {
            MediaType.IMAGE -> {
                if (modelMetadata.inputShape.size != 4 || modelMetadata.outputShape.size != 4) {
                    issues += "IMAGE model expects rank-4 tensors. input=${modelMetadata.inputShape.contentToString()} output=${modelMetadata.outputShape.contentToString()}"
                }
                if (modelMetadata.inputShape.lastOrNull() != 3 || modelMetadata.outputShape.lastOrNull() != 3) {
                    issues += "IMAGE model must use RGB channels. input=${modelMetadata.inputShape.contentToString()} output=${modelMetadata.outputShape.contentToString()}"
                }
            }

            MediaType.AUDIO -> {
                if (modelMetadata.inputShape.size != 3 || modelMetadata.outputShape.size != 3) {
                    issues += "AUDIO model expects rank-3 tensors. input=${modelMetadata.inputShape.contentToString()} output=${modelMetadata.outputShape.contentToString()}"
                }
                if (modelMetadata.inputShape.lastOrNull() != 1 || modelMetadata.outputShape.lastOrNull() != 1) {
                    issues += "AUDIO model must be mono. input=${modelMetadata.inputShape.contentToString()} output=${modelMetadata.outputShape.contentToString()}"
                }
            }

            MediaType.VIDEO -> {
                if (modelMetadata.inputShape.size != 5 || modelMetadata.outputShape.size != 5) {
                    issues += "VIDEO model expects rank-5 tensors. input=${modelMetadata.inputShape.contentToString()} output=${modelMetadata.outputShape.contentToString()}"
                }
                if (modelMetadata.inputShape.lastOrNull() != 3 || modelMetadata.outputShape.lastOrNull() != 3) {
                    issues += "VIDEO model must use RGB channels. input=${modelMetadata.inputShape.contentToString()} output=${modelMetadata.outputShape.contentToString()}"
                }
            }
        }

        if (modelMetadata.inputShape.firstOrNull() != 1) {
            issues += "${modelMetadata.mediaType.name} input batch size must be 1. input=${modelMetadata.inputShape.contentToString()}"
        }
        if (modelMetadata.outputShape.firstOrNull() != 1) {
            issues += "${modelMetadata.mediaType.name} output batch size must be 1. output=${modelMetadata.outputShape.contentToString()}"
        }
        if (modelMetadata.inputDType != "FLOAT32") {
            issues += "${modelMetadata.mediaType.name} input dtype is ${modelMetadata.inputDType}, expected FLOAT32"
        }
        if (modelMetadata.outputDType != "FLOAT32") {
            issues += "${modelMetadata.mediaType.name} output dtype is ${modelMetadata.outputDType}, expected FLOAT32"
        }
        return issues
    }
}

