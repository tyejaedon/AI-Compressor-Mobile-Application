package com.example.feature.audio

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.core.ui.AicInspectionCard
import com.example.core.ui.FileSizeComparisonCard
import com.example.core.ui.SamsungHeader
import com.example.core.ui.SamsungWaveformVisualizer
import kotlinx.coroutines.launch

@Composable
fun AudioCompressionRoute(
    snackbarHostState: SnackbarHostState,
    viewModel: AudioCompressionViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var pendingSaveAicUri by remember { mutableStateOf<Uri?>(null) }
    var pendingSaveWavUri by remember { mutableStateOf<Uri?>(null) }

    val saveAicLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { destination ->
        val source = pendingSaveAicUri
        if (destination != null && source != null) {
            runCatching {
                context.contentResolver.openInputStream(source).use { input ->
                    requireNotNull(input)
                    context.contentResolver.openOutputStream(destination).use { output ->
                        requireNotNull(output)
                        input.copyTo(output)
                    }
                }
            }.onSuccess {
                scope.launch { snackbarHostState.showSnackbar("AIC audio container saved!") }
            }.onFailure {
                scope.launch { snackbarHostState.showSnackbar("Failed to export AIC audio file") }
            }
        }
        pendingSaveAicUri = null
    }

    val saveWavLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("audio/wav"),
    ) { destination ->
        val source = pendingSaveWavUri
        if (destination != null && source != null) {
            runCatching {
                context.contentResolver.openInputStream(source).use { input ->
                    requireNotNull(input)
                    context.contentResolver.openOutputStream(destination).use { output ->
                        requireNotNull(output)
                        input.copyTo(output)
                    }
                }
            }.onSuccess {
                scope.launch { snackbarHostState.showSnackbar("Reconstructed WAV saved to device!") }
            }.onFailure {
                scope.launch { snackbarHostState.showSnackbar("Failed to save audio") }
            }
        }
        pendingSaveWavUri = null
    }

    AudioCompressionScreen(
        state = state,
        onPickAudio = viewModel::onAudioSelected,
        onPickAic = viewModel::onAicFileSelected,
        onCompressToAic = viewModel::compressToAic,
        onSetBitDepth = viewModel::setBitDepth,
        onExportAic = { uri ->
            pendingSaveAicUri = uri
            saveAicLauncher.launch("audio-${System.currentTimeMillis()}.aic")
        },
        onSaveWav = { uri ->
            pendingSaveWavUri = uri
            saveWavLauncher.launch("reconstructed-${System.currentTimeMillis()}.wav")
        },
        onShare = { uri ->
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "audio/wav"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(sendIntent, "Share Compressed Audio"))
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
    onPickAudio: (Uri) -> Unit,
    onPickAic: (Uri) -> Unit,
    onCompressToAic: () -> Unit,
    onSetBitDepth: (Int) -> Unit,
    onExportAic: (Uri) -> Unit,
    onSaveWav: (Uri) -> Unit,
    onShare: (Uri) -> Unit,
) {
    val audioPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(onPickAudio) }

    val aicPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(onPickAic) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SamsungHeader(
            title = "Neural Audio Studio",
            subtitle = "1D temporal strided autoencoder with discrete latent entropy coding",
        )

        // Waveform Visualizer
        if (state.reconstructedSamples.isNotEmpty()) {
            SamsungWaveformVisualizer(
                samples = state.reconstructedSamples,
                snrVal = state.result?.metrics?.snr,
                title = "AI RECONSTRUCTED WAVEFORM",
                heightDp = 150,
            )
        } else {
            SamsungWaveformVisualizer(
                samples = state.sourceSamples,
                snrVal = null,
                title = if (state.sourceSamples.isNotEmpty()) "ORIGINAL AUDIO WAVEFORM" else "STANDBY / NO AUDIO LOADED",
                heightDp = 150,
            )
        }

        // Action Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = { audioPicker.launch(arrayOf("audio/*")) },
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
                modifier = Modifier.weight(1f),
            ) {
                Text("Import WAV", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }

            Button(
                onClick = onCompressToAic,
                enabled = state.sourceUri != null && !state.isLoading,
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                modifier = Modifier.weight(1.2f),
            ) {
                if (state.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Compressing...", fontSize = 13.sp)
                } else {
                    Text("Compress (.aic)", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }

            OutlinedButton(
                onClick = { aicPicker.launch(arrayOf("*/*")) },
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.weight(0.9f),
            ) {
                Text("Open .aic", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }
        }

        // Export Actions Row
        AnimatedVisibility(visible = state.result != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.result?.outputUri?.let { aicUri ->
                    Button(
                        onClick = { onExportAic(aicUri) },
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Export .aic", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }

                state.result?.previewUri?.let { previewUri ->
                    OutlinedButton(
                        onClick = { onSaveWav(previewUri) },
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Save WAV", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    }

                    OutlinedButton(
                        onClick = { onShare(previewUri) },
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.weight(0.8f),
                    ) {
                        Text("Share", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    }
                }
            }
        }

        // Real-Time Pipeline Progress Indicator
        state.pipelineStatus?.let { status ->
            if (status.stage != com.example.core.domain.CompressionPipelineStage.COMPLETED) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "PIPELINE: ${status.stage.name.replace('_', ' ')}",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = "${(status.progress * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { status.progress },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                        )
                    }
                }
            }
        }

        // Storage Benchmark Card
        state.result?.let { result ->
            FileSizeComparisonCard(
                originalSizeBytes = result.originalSizeBytes,
                compressedSizeBytes = result.compressedSizeBytes,
                psnrVal = null,
                ssimVal = null,
                latencyMs = result.metrics.latencyMs,
                bitsPerPixel = null,
                superResolutionActive = false,
                delegateLabel = state.settings.delegate.name,
            )
        }

        // AIC Container Inspection (if inspecting .aic)
        state.inspection?.let { inspection ->
            AicInspectionCard(inspection = inspection)
        }

        // Quantization Tuning
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(26.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "QUANTIZATION CONFIGURATION",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 1.1.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = state.settings.bitDepth == 8,
                        onClick = { onSetBitDepth(8) },
                        label = { Text("8-bit Quantized") },
                        shape = RoundedCornerShape(12.dp),
                    )
                    FilterChip(
                        selected = state.settings.bitDepth == 6,
                        onClick = { onSetBitDepth(6) },
                        label = { Text("6-bit Quantized") },
                        shape = RoundedCornerShape(12.dp),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}
