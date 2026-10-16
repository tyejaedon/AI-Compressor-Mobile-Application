package com.example.feature.image

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.core.domain.InferenceDelegate
import com.example.core.ui.AicInspectionCard
import com.example.core.ui.BeforeAfterSlider
import com.example.core.ui.FileSizeComparisonCard
import com.example.core.ui.SamsungHeader
import kotlinx.coroutines.launch

@Composable
fun ImageCompressionRoute(
    snackbarHostState: SnackbarHostState,
    viewModel: ImageCompressionViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var pendingSaveAicUri by remember { mutableStateOf<Uri?>(null) }
    var pendingSavePngUri by remember { mutableStateOf<Uri?>(null) }

    // SAF Document Creators
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
                scope.launch { snackbarHostState.showSnackbar("AIC compressed file saved successfully!") }
            }.onFailure {
                scope.launch { snackbarHostState.showSnackbar("Failed to export AIC file") }
            }
        }
        pendingSaveAicUri = null
    }

    val savePngLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/png"),
    ) { destination ->
        val source = pendingSavePngUri
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
                scope.launch { snackbarHostState.showSnackbar("Reconstructed PNG saved to gallery!") }
            }.onFailure {
                scope.launch { snackbarHostState.showSnackbar("Failed to save image") }
            }
        }
        pendingSavePngUri = null
    }

    ImageCompressionScreen(
        state = state,
        onPickImage = viewModel::onImageSelected,
        onPickAicFile = viewModel::onAicFileSelected,
        onCompressToAic = viewModel::compressToAic,
        onToggleSuperResolution = viewModel::toggleSuperResolution,
        onSetBitDepth = viewModel::setBitDepth,
        onSetDelegate = viewModel::setDelegate,
        onExportAic = { uri ->
            pendingSaveAicUri = uri
            saveAicLauncher.launch("compressed-media-${System.currentTimeMillis()}.aic")
        },
        onSavePng = { uri ->
            pendingSavePngUri = uri
            savePngLauncher.launch("reconstructed-${System.currentTimeMillis()}.png")
        },
        onShare = { uri ->
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(sendIntent, "Share Reconstructed Image"))
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
    onPickImage: (Uri) -> Unit,
    onPickAicFile: (Uri) -> Unit,
    onCompressToAic: () -> Unit,
    onToggleSuperResolution: (Boolean) -> Unit,
    onSetBitDepth: (Int) -> Unit,
    onSetDelegate: (InferenceDelegate) -> Unit,
    onExportAic: (Uri) -> Unit,
    onSavePng: (Uri) -> Unit,
    onShare: (Uri) -> Unit,
) {
    val imagePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(onPickImage) }

    val aicPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(onPickAicFile) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SamsungHeader(
            title = "Neural Image Studio",
            subtitle = "Edge-quantized autoencoder compression with 2x sub-pixel CNN remaster",
        )

        // Interactive Before/After Split Viewer
        BeforeAfterSlider(
            originalBitmap = state.sourceBitmap,
            reconstructedBitmap = state.reconstructedBitmap,
            heightDp = 300,
        )

        // Quick Action Pill Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = { imagePickerLauncher.launch(arrayOf("image/*")) },
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
                modifier = Modifier.weight(1f),
            ) {
                Text("Import Photo", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
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
                onClick = { aicPickerLauncher.launch(arrayOf("*/*")) },
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.weight(0.9f),
            ) {
                Text("Open .aic", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }
        }

        // Export and Share Row (when result is available)
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
                        onClick = { onSavePng(previewUri) },
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Save PNG", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
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
                psnrVal = result.metrics.psnr,
                ssimVal = result.metrics.ssim,
                latencyMs = result.metrics.latencyMs,
                bitsPerPixel = result.bitsPerPixel,
                superResolutionActive = state.settings.enableSuperResolution,
                delegateLabel = state.settings.delegate.name,
            )
        }

        // AIC Container Inspection (if inspecting .aic)
        state.inspection?.let { inspection ->
            AicInspectionCard(inspection = inspection)
        }

        // Neural Engine Tuning & Configuration Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(26.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = "NEURAL ENGINE PARAMETERS",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 1.1.sp,
                )

                // 2x ESPCN Learned Super-Resolution Remaster Switch
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "2x ESPCN Super-Resolution Remaster",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "Enhances edges and sub-pixel details on reconstruction",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = state.settings.enableSuperResolution,
                        onCheckedChange = onToggleSuperResolution,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                }

                // Discretization Bit Depth
                Column {
                    Text(
                        text = "Discretization Bit Depth",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = state.settings.bitDepth == 8,
                            onClick = { onSetBitDepth(8) },
                            label = { Text("8-bit (Best Fidelity)") },
                            shape = RoundedCornerShape(12.dp),
                        )
                        FilterChip(
                            selected = state.settings.bitDepth == 6,
                            onClick = { onSetBitDepth(6) },
                            label = { Text("6-bit (Max Compression)") },
                            shape = RoundedCornerShape(12.dp),
                        )
                    }
                }

                // Hardware Acceleration Delegate
                Column {
                    Text(
                        text = "Inference Hardware Acceleration",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = state.settings.delegate == InferenceDelegate.GPU,
                            onClick = { onSetDelegate(InferenceDelegate.GPU) },
                            label = { Text("GPU / Metal") },
                            shape = RoundedCornerShape(12.dp),
                        )
                        FilterChip(
                            selected = state.settings.delegate == InferenceDelegate.NNAPI,
                            onClick = { onSetDelegate(InferenceDelegate.NNAPI) },
                            label = { Text("NNAPI") },
                            shape = RoundedCornerShape(12.dp),
                        )
                        FilterChip(
                            selected = state.settings.delegate == InferenceDelegate.CPU,
                            onClick = { onSetDelegate(InferenceDelegate.CPU) },
                            label = { Text("CPU (4T)") },
                            shape = RoundedCornerShape(12.dp),
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}
