package com.example.feature.image

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.core.ui.CompressorCard
import com.example.core.ui.MetricsRow

@Composable
fun ImageCompressionRoute(
    snackbarHostState: SnackbarHostState,
    viewModel: ImageCompressionViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    ImageCompressionScreen(
        state = state,
        onPickImage = viewModel::onImageSelected,
        onCompress = viewModel::compress,
        onShare = { uri ->
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(sendIntent, "Share compressed image"))
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
private fun ImageCompressionScreen(
    state: ImageCompressionUiState,
    onPickImage: (android.net.Uri) -> Unit,
    onCompress: () -> Unit,
    onShare: (android.net.Uri) -> Unit,
) {
    var revealPosition by remember { mutableFloatStateOf(0.5f) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(onPickImage)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CompressorCard(title = "Image Compressor", subtitle = "64x64 RGB autoencoder reconstruction") {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { launcher.launch(arrayOf("image/*")) }) {
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

        Text(text = "Comparison", style = MaterialTheme.typography.titleMedium)
        Slider(
            value = revealPosition,
            onValueChange = { revealPosition = it },
            valueRange = 0f..1f,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AsyncImage(
                model = state.sourceUri,
                contentDescription = "Original image ${(revealPosition * 100).toInt()} percent visible",
                modifier = Modifier.weight(1f),
            )
            AsyncImage(
                model = state.result?.outputUri,
                contentDescription = "Reconstructed image",
                modifier = Modifier.weight(1f),
            )
        }

        state.result?.let { result ->
            MetricsRow(
                metrics = listOf(
                    "PSNR" to String.format("%.2f", result.metrics.psnr ?: 0.0),
                    "SSIM" to String.format("%.3f", result.metrics.ssim ?: 0.0),
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

