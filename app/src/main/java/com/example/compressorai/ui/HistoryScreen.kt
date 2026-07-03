package com.example.compressorai.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.compressorai.HistoryUiState
import com.example.compressorai.HistoryViewModel
import com.example.core.domain.CompressionHistoryItem
import com.example.core.domain.MediaType
import com.example.core.ui.CompressorCard
import com.example.core.ui.MetricsRow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun HistoryRoute(
    snackbarHostState: SnackbarHostState,
    viewModel: HistoryViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingCsv by remember { mutableStateOf<String?>(null) }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { destination: Uri? ->
        val csv = pendingCsv
        if (destination != null && csv != null) {
            runCatching {
                context.contentResolver.openOutputStream(destination).use { output ->
                    requireNotNull(output) { "Unable to open destination" }
                    output.write(csv.toByteArray())
                }
            }.onSuccess {
                scope.launch { snackbarHostState.showSnackbar("History CSV saved") }
            }.onFailure {
                scope.launch { snackbarHostState.showSnackbar("Failed to save CSV") }
            }
        }
        pendingCsv = null
    }
    HistoryScreen(
        state = state,
        onMediaFilter = viewModel::setMediaFilter,
        onWindowFilter = viewModel::setWindowDays,
        onExportCsv = {
            pendingCsv = viewModel.buildCsv()
            saveLauncher.launch("compression-history-${System.currentTimeMillis()}.csv")
        },
    )
}

@Composable
fun HistoryScreen(
    state: HistoryUiState,
    onMediaFilter: (MediaType?) -> Unit,
    onWindowFilter: (Int?) -> Unit,
    onExportCsv: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Compression History", style = MaterialTheme.typography.headlineMedium)
        }
        item {
            Text(
                text = "Track compression efficiency, quality metrics, and model profile over time.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item {
            CompressorCard(title = "Filters", subtitle = "Analyze by media and time window") {
                androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterButton("All", selected = state.selectedMediaFilter == null) { onMediaFilter(null) }
                    FilterButton("Image", selected = state.selectedMediaFilter == MediaType.IMAGE) { onMediaFilter(MediaType.IMAGE) }
                    FilterButton("Audio", selected = state.selectedMediaFilter == MediaType.AUDIO) { onMediaFilter(MediaType.AUDIO) }
                    FilterButton("Video", selected = state.selectedMediaFilter == MediaType.VIDEO) { onMediaFilter(MediaType.VIDEO) }
                }
                androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterButton("All Time", selected = state.selectedWindowDays == null) { onWindowFilter(null) }
                    FilterButton("7 Days", selected = state.selectedWindowDays == 7) { onWindowFilter(7) }
                    FilterButton("30 Days", selected = state.selectedWindowDays == 30) { onWindowFilter(30) }
                }
                Button(onClick = onExportCsv) {
                    Text("Export CSV")
                }
            }
        }

        item {
            CompressorCard(title = "Performance Overview", subtitle = "Aggregate efficiency") {
                MetricsRow(
                    metrics = listOf(
                        "Sessions" to state.filteredHistory.size.toString(),
                        "Avg Ratio" to String.format(Locale.US, "%.2fx", state.averageRatio),
                        "Avg Size Reduced" to String.format(Locale.US, "%.1f%%", state.averageReductionPercent),
                    ),
                )
                MetricsRow(
                    metrics = listOf(
                        "Avg Latency" to String.format(Locale.US, "%.1f ms", state.averageLatencyMs),
                        "Best PSNR" to String.format(Locale.US, "%.2f", state.bestPsnr),
                    ),
                )
            }
        }

        item {
            CompressorCard(title = "Model Metadata", subtitle = "User-friendly model contracts") {
                if (state.modelMetadata.isEmpty()) {
                    Text("No model metadata available")
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.modelMetadata.forEach { model ->
                            val mediaLabel = model.mediaType.name.lowercase().replaceFirstChar { it.uppercase() }
                            Text("$mediaLabel model", style = MaterialTheme.typography.titleMedium)
                            Text("Input: ${friendlyShape(model.inputShape)} | Output: ${friendlyShape(model.outputShape)}")
                            Text("Type: ${model.inputDType} -> ${model.outputDType}")
                        }
                    }
                }
            }
        }

        item {
            CompressorCard(title = "Efficiency by Media", subtitle = "Independent model behavior") {
                MetricsRow(
                    metrics = listOf(
                        "Image" to String.format(Locale.US, "%.2fx", state.avgRatioByMedia[MediaType.IMAGE] ?: 0.0),
                        "Audio" to String.format(Locale.US, "%.2fx", state.avgRatioByMedia[MediaType.AUDIO] ?: 0.0),
                        "Video" to String.format(Locale.US, "%.2fx", state.avgRatioByMedia[MediaType.VIDEO] ?: 0.0),
                    ),
                )
            }
        }

        if (state.filteredHistory.isEmpty()) {
            item {
                CompressorCard(title = "No History Yet", subtitle = "Run compression to populate analytics") {
                    Text("Your recent compression analyses will appear here.")
                }
            }
        } else {
            items(state.filteredHistory) { item ->
                HistoryItemCard(item = item)
            }
        }
    }
}

@Composable
private fun FilterButton(text: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick) { Text(text) }
    } else {
        OutlinedButton(onClick = onClick) { Text(text) }
    }
}

@Composable
private fun HistoryItemCard(item: CompressionHistoryItem) {
    val mediaLabel = item.mediaType.name.lowercase().replaceFirstChar { it.uppercase() }
    val reductionPercent = ((1.0 - (1.0 / item.compressionRatio.coerceAtLeast(1e-6))) * 100.0).coerceIn(0.0, 100.0)
    CompressorCard(
        title = "$mediaLabel compression",
        subtitle = formatTimestamp(item.createdAtEpochMs),
    ) {
        MetricsRow(
            metrics = listOf(
                "Ratio" to String.format(Locale.US, "%.2fx", item.compressionRatio),
                "Size Reduced" to String.format(Locale.US, "%.1f%%", reductionPercent),
                "Latency" to String.format(Locale.US, "%.1f ms", item.latencyMs),
            ),
        )
        MetricsRow(
            metrics = listOf(
                "PSNR" to String.format(Locale.US, "%.2f", item.psnr ?: 0.0),
                "SSIM" to String.format(Locale.US, "%.3f", item.ssim ?: 0.0),
                "SNR" to String.format(Locale.US, "%.2f", item.snr ?: 0.0),
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun friendlyShape(shape: IntArray): String {
    return shape.joinToString(separator = " x ")
}

private fun formatTimestamp(epochMs: Long): String {
    return SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.US).format(Date(epochMs))
}


