package com.example.compressorai

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.data.VortexServiceLocator
import com.example.core.domain.CompressionHistoryItem
import com.example.core.domain.MediaType
import com.example.core.domain.ModelMetadata
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HistoryUiState(
    val history: List<CompressionHistoryItem> = emptyList(),
    val filteredHistory: List<CompressionHistoryItem> = emptyList(),
    val modelMetadata: List<ModelMetadata> = emptyList(),
    val averageRatio: Double = 0.0,
    val averageReductionPercent: Double = 0.0,
    val averageLatencyMs: Double = 0.0,
    val bestPsnr: Double = 0.0,
    val avgRatioByMedia: Map<MediaType, Double> = emptyMap(),
    val selectedMediaFilter: MediaType? = null,
    val selectedWindowDays: Int? = null,
)

class HistoryViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = VortexServiceLocator.repository(application)

    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val metadata = runCatching { repository.modelMetadata() }.getOrDefault(emptyList())
            repository.observeCompressionHistory().collect { history ->
                _uiState.update { current ->
                    current.copy(history = history, modelMetadata = metadata)
                }
                recomputeDerivedState()
            }
        }
    }

    fun setMediaFilter(filter: MediaType?) {
        _uiState.update { it.copy(selectedMediaFilter = filter) }
        recomputeDerivedState()
    }

    fun setWindowDays(days: Int?) {
        _uiState.update { it.copy(selectedWindowDays = days) }
        recomputeDerivedState()
    }

    fun buildCsv(): String {
        val rows = _uiState.value.filteredHistory
        val header = "timestamp,media,ratio,size_reduced_percent,latency_ms,throughput,psnr,ssim,snr"
        val body = rows.joinToString(separator = "\n") { item ->
            listOf(
                item.createdAtEpochMs,
                item.mediaType.name,
                formatCsv(item.compressionRatio),
                formatCsv(reductionPercent(item.compressionRatio)),
                formatCsv(item.latencyMs),
                formatCsv(item.throughputItemsPerSec),
                formatCsv(item.psnr ?: 0.0),
                formatCsv(item.ssim ?: 0.0),
                formatCsv(item.snr ?: 0.0),
            ).joinToString(separator = ",")
        }
        return "$header\n$body"
    }

    private fun recomputeDerivedState() {
        val current = _uiState.value
        val now = System.currentTimeMillis()
        val filtered = current.history.filter { item ->
            val mediaMatches = current.selectedMediaFilter?.let { it == item.mediaType } ?: true
            val windowMatches = current.selectedWindowDays?.let { days ->
                val cutoff = now - (days * 24L * 60L * 60L * 1000L)
                item.createdAtEpochMs >= cutoff
            } ?: true
            mediaMatches && windowMatches
        }
        _uiState.update {
            it.copy(
                filteredHistory = filtered,
                averageRatio = filtered.map { entry -> entry.compressionRatio }.averageOrZero(),
                averageReductionPercent = filtered.map { entry -> reductionPercent(entry.compressionRatio) }.averageOrZero(),
                averageLatencyMs = filtered.map { entry -> entry.latencyMs }.averageOrZero(),
                bestPsnr = filtered.mapNotNull { entry -> entry.psnr }.maxOrNull() ?: 0.0,
                avgRatioByMedia = MediaType.entries.associateWith { media ->
                    filtered.filter { entry -> entry.mediaType == media }
                        .map { entry -> entry.compressionRatio }
                        .averageOrZero()
                },
            )
        }
    }

    private fun formatCsv(value: Double): String = String.format(java.util.Locale.US, "%.4f", value)

    private fun reductionPercent(ratio: Double): Double {
        if (ratio <= 0.0) return 0.0
        return ((1.0 - (1.0 / ratio)) * 100.0).coerceIn(0.0, 100.0)
    }
}

private fun List<Double>.averageOrZero(): Double = if (isEmpty()) 0.0 else average()


