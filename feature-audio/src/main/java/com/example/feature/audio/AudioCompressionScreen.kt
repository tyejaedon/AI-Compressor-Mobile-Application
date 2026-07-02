package com.example.feature.audio

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.core.ui.CompressorCard
import com.example.core.ui.MetricsRow

@Composable
fun AudioCompressionRoute(
    snackbarHostState: SnackbarHostState,
    viewModel: AudioCompressionViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    AudioCompressionScreen(
        state = state,
        onPickAudio = viewModel::onAudioSelected,
        onCompress = viewModel::compress,
        onShare = { uri ->
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "audio/wav"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(sendIntent, "Share compressed audio"))
        },
    )

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }
}

@Composable
private fun AudioCompressionScreen(
    state: AudioCompressionUiState,
    onPickAudio: (android.net.Uri) -> Unit,
    onCompress: () -> Unit,
    onShare: (android.net.Uri) -> Unit,
) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(onPickAudio)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CompressorCard(title = "Audio Compressor", subtitle = "256-sample windows @ 8 kHz") {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { launcher.launch(arrayOf("audio/*")) }) {
                    Text("Import")
                }
                Button(onClick = onCompress, enabled = state.sourceUri != null && !state.isLoading) {
                    Text("Compress")
                }
                Button(onClick = { state.result?.outputUri?.let(onShare) }, enabled = state.result != null) {
                    Text("Share")
                }
            }
            if (state.isLoading) {
                CircularProgressIndicator(modifier = Modifier.padding(top = 8.dp))
            }
        }

        state.result?.let { result ->
            MetricsRow(
                metrics = listOf(
                    "PSNR" to String.format("%.2f", result.metrics.psnr ?: 0.0),
                    "SNR" to String.format("%.2f dB", result.metrics.snr ?: 0.0),
                    "Latency" to String.format("%.2f ms", result.metrics.latencyMs),
                ),
            )
            MetricsRow(
                metrics = listOf(
                    "Ratio" to String.format("%.2fx", result.metrics.compressionRatio),
                    "Throughput" to String.format("%.2f/s", result.metrics.throughputItemsPerSec),
                ),
            )
        }
    }
}

