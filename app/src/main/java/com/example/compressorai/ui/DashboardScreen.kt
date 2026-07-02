package com.example.compressorai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.compressorai.MainUiState
import com.example.compressorai.navigation.VortexDestination
import com.example.core.ui.CompressorCard

@Composable
fun DashboardScreen(
    state: MainUiState,
    onNavigate: (VortexDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Vortex Compressor", style = MaterialTheme.typography.headlineMedium)
        }
        item {
            Text(
                text = "Neural autoencoder compression dashboard",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (state.compatibilityIssues.isNotEmpty()) {
            item {
                CompressorCard(title = "Model Compatibility", subtitle = "Fix these before production run") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        state.compatibilityIssues.forEach { issue ->
                            Text(issue, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }

        item {
            CompressorCard(title = "Modules", subtitle = "Choose media type") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onNavigate(VortexDestination.Image) }) { Text("Image Compressor") }
                    Button(onClick = { onNavigate(VortexDestination.Audio) }) { Text("Audio Compressor") }
                    Button(onClick = { onNavigate(VortexDestination.Video) }) { Text("Video Compressor") }
                    Button(onClick = { onNavigate(VortexDestination.Benchmark) }) { Text("Benchmark") }
                }
            }
        }

        item {
            CompressorCard(title = "Batch Queue", subtitle = "Background WorkManager pipeline") {
                if (state.batchProgress.isEmpty()) {
                    Text("No active jobs")
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        state.batchProgress.forEach { item ->
                            Text("${item.id}: ${item.status.name} (${(item.progress * 100).toInt()}%)")
                        }
                    }
                }
            }
        }

        item {
            CompressorCard(title = "Model Metadata", subtitle = "Parsed from bundled reports") {
                if (state.metadata.isEmpty()) {
                    Text("No metadata available")
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.metadata.forEach { model ->
                            Text("${model.mediaType.name}: ${model.inputShape.contentToString()} -> ${model.outputShape.contentToString()}")
                            Text(model.reportSummary.take(180))
                        }
                    }
                }
            }
        }
    }
}

