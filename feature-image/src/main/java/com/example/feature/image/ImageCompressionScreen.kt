package com.example.feature.image

import android.content.Intent
import android.net.Uri
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import coil.compose.AsyncImage
import java.util.Locale
import kotlinx.coroutines.launch
import com.example.core.domain.ImageNormalizationMode
import com.example.core.ui.CompressorCard
import com.example.core.ui.MetricsRow

@Composable
fun ImageCompressionRoute(
    snackbarHostState: SnackbarHostState,
    viewModel: ImageCompressionViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingSaveUri by remember { mutableStateOf<Uri?>(null) }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { destination ->
        val source = pendingSaveUri
        if (destination != null && source != null) {
            runCatching {
                context.contentResolver.openInputStream(source).use { input ->
                    requireNotNull(input) { "Unable to open compressed image" }
                    context.contentResolver.openOutputStream(destination).use { output ->
                        requireNotNull(output) { "Unable to open destination" }
                        input.copyTo(output)
                    }
                }
            }.onSuccess {
                scope.launch { snackbarHostState.showSnackbar("Image saved to device") }
            }.onFailure {
                scope.launch { snackbarHostState.showSnackbar("Failed to save image") }
            }
        }
        pendingSaveUri = null
    }
    ImageCompressionScreen(
        state = state,
        onPickImage = viewModel::onImageSelected,
        onCompress = viewModel::compress,
        onNormalizationModeChanged = viewModel::setImageNormalizationMode,
        onSaveToDevice = { uri ->
            pendingSaveUri = uri
            saveLauncher.launch("compressed-image-${System.currentTimeMillis()}.png")
        },
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
    onNormalizationModeChanged: (ImageNormalizationMode) -> Unit,
    onSaveToDevice: (android.net.Uri) -> Unit,
    onShare: (android.net.Uri) -> Unit,
) {
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
        CompressorCard(title = "Image Compressor", subtitle = "32x32 RGB autoencoder reconstruction") {
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
                Button(onClick = { state.result?.outputUri?.let(onSaveToDevice) }, enabled = state.result != null) {
                    Text("Save")
                }
            }
            if (state.isLoading) {
                CircularProgressIndicator(modifier = Modifier.padding(top = 8.dp))
            }
            Text(
                text = "Normalization",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 8.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val zeroOneSelected = state.normalizationMode == ImageNormalizationMode.ZERO_ONE
                if (zeroOneSelected) {
                    Button(onClick = { onNormalizationModeChanged(ImageNormalizationMode.ZERO_ONE) }) {
                        Text("0..1")
                    }
                } else {
                    OutlinedButton(onClick = { onNormalizationModeChanged(ImageNormalizationMode.ZERO_ONE) }) {
                        Text("0..1")
                    }
                }

                val negOneOneSelected = state.normalizationMode == ImageNormalizationMode.NEG_ONE_ONE
                if (negOneOneSelected) {
                    Button(onClick = { onNormalizationModeChanged(ImageNormalizationMode.NEG_ONE_ONE) }) {
                        Text("-1..1")
                    }
                } else {
                    OutlinedButton(onClick = { onNormalizationModeChanged(ImageNormalizationMode.NEG_ONE_ONE) }) {
                        Text("-1..1")
                    }
                }
            }
        }

        state.pipelineStatus?.let { status ->
            CompressorCard(title = "Compression Pipeline", subtitle = "Realtime stage progress") {
                LinearProgressIndicator(
                    progress = { status.progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "Stage: ${status.stage.name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }}",
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    text = if (status.etaSeconds != null) {
                        "Estimated time remaining: ${status.etaSeconds}s"
                    } else {
                        "Estimated time remaining: calculating..."
                    },
                )
                status.message?.let { message ->
                    Text(text = message, color = MaterialTheme.colorScheme.error)
                }
                if (state.pipelineTimeline.isNotEmpty()) {
                    Text(
                        text = "Timeline",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    state.pipelineTimeline.takeLast(8).forEach { line ->
                        Text(text = "- $line")
                    }
                }
            }
        }

        Text(text = "Comparison", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "Original and reconstructed images are shown side-by-side for direct comparison.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = "Original", style = MaterialTheme.typography.labelMedium)
                AsyncImage(
                    model = state.sourceUri,
                    contentDescription = "Original image",
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = "Reconstructed", style = MaterialTheme.typography.labelMedium)
                AsyncImage(
                    model = state.result?.outputUri,
                    contentDescription = "Reconstructed image",
                )
            }
        }

        state.result?.let { result ->
            MetricsRow(
                metrics = listOf(
                    "PSNR" to String.format(Locale.US, "%.2f", result.metrics.psnr ?: 0.0),
                    "SSIM" to String.format(Locale.US, "%.3f", result.metrics.ssim ?: 0.0),
                    "Latency" to String.format(Locale.US, "%.2f ms", result.metrics.latencyMs),
                ),
            )
            MetricsRow(
                metrics = listOf(
                    "Ratio" to String.format(Locale.US, "%.2fx", result.metrics.compressionRatio),
                    "Throughput" to String.format(Locale.US, "%.2f/s", result.metrics.throughputItemsPerSec),
                ),
            )
        }
    }
}

