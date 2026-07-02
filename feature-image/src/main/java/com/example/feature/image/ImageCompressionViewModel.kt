package com.example.feature.image

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.data.VortexServiceLocator
import com.example.core.domain.CompressionResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ImageCompressionUiState(
    val sourceUri: Uri? = null,
    val result: CompressionResult? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
)

class ImageCompressionViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = VortexServiceLocator.repository(application)

    private val _uiState = MutableStateFlow(ImageCompressionUiState())
    val uiState: StateFlow<ImageCompressionUiState> = _uiState.asStateFlow()

    fun onImageSelected(uri: Uri) {
        _uiState.update { it.copy(sourceUri = uri, error = null) }
    }

    fun compress() {
        val source = _uiState.value.sourceUri ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
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
}

