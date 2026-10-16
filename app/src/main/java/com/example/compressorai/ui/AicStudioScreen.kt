package com.example.compressorai.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.core.data.VortexServiceLocator
import com.example.core.domain.AicContainerInspection
import com.example.core.domain.AicModality
import com.example.core.domain.CompressionResult
import com.example.core.ui.AicInspectionCard
import com.example.core.ui.SamsungHeader
import com.example.core.ui.SamsungWaveformVisualizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AicStudioUiState(
    val aicUri: Uri? = null,
    val inspection: AicContainerInspection? = null,
    val decompressionResult: CompressionResult? = null,
    val reconstructedBitmap: Bitmap? = null,
    val reconstructedAudioSamples: FloatArray = FloatArray(0),
    val enableSuperResolution: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
)

class AicStudioViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = VortexServiceLocator.repository(application)
    private val context = application.applicationContext

    private val _uiState = MutableStateFlow(AicStudioUiState())
    val uiState = _uiState.asStateFlow()

    fun onAicFileSelected(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, aicUri = uri) }
            val inspect = repository.inspectAicFile(uri)
            if (inspect.isSuccess) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        inspection = inspect.getOrNull(),
                        decompressionResult = null,
                        reconstructedBitmap = null,
                        reconstructedAudioSamples = FloatArray(0),
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = inspect.exceptionOrNull()?.message ?: "Failed to read AIC header",
                    )
                }
            }
        }
    }

    fun decompress() {
        val uri = _uiState.value.aicUri ?: return
        val sr = _uiState.value.enableSuperResolution
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            val result = repository.decompressAicFile(uri, enableSuperResolution = sr)
            if (result.isSuccess) {
                val res = result.getOrNull()
                val header = _uiState.value.inspection?.header

                var bmp: Bitmap? = null
                var audioSamples = FloatArray(0)

                val previewUri = res?.previewUri
                if (header?.modality == AicModality.IMAGE && previewUri != null) {
                    bmp = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(previewUri)?.use {
                            BitmapFactory.decodeStream(it)
                        }
                    }
                } else if (header?.modality == AicModality.AUDIO && previewUri != null) {
                    audioSamples = withContext(Dispatchers.IO) {
                        val bytes = context.contentResolver.openInputStream(previewUri)?.use { it.readBytes() }
                        if (bytes != null && bytes.size > 44) {
                            val pcm = bytes.copyOfRange(44, bytes.size)
                            val shortBuffer = java.nio.ByteBuffer.wrap(pcm).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            val out = FloatArray(shortBuffer.remaining())
                            var i = 0
                            while (shortBuffer.hasRemaining()) {
                                out[i++] = shortBuffer.get().toFloat() / Short.MAX_VALUE.toFloat()
                            }
                            out
                        } else FloatArray(0)
                    }
                }

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        decompressionResult = res,
                        reconstructedBitmap = bmp,
                        reconstructedAudioSamples = audioSamples,
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = result.exceptionOrNull()?.message ?: "Decompression failed",
                    )
                }
            }
        }
    }

    fun toggleSuperResolution(enabled: Boolean) {
        _uiState.update { it.copy(enableSuperResolution = enabled) }
    }
}

@Composable
fun AicStudioScreen(
    snackbarHostState: SnackbarHostState,
    viewModel: AicStudioViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var pendingSaveUri by remember { mutableStateOf<Uri?>(null) }
    var saveMimeType by remember { mutableStateOf("image/png") }

    val fileSaver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(saveMimeType),
    ) { destination ->
        val source = pendingSaveUri
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
                scope.launch { snackbarHostState.showSnackbar("File saved to device successfully!") }
            }.onFailure {
                scope.launch { snackbarHostState.showSnackbar("Failed to export file") }
            }
        }
        pendingSaveUri = null
    }

    val openAicLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::onAicFileSelected) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SamsungHeader(
            title = "AIC Container Studio",
            subtitle = "Universal inspector and decoder for .aic neural compression containers",
        )

        // Select File Button
        Button(
            onClick = { openAicLauncher.launch(arrayOf("*/*")) },
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text("Select .aic File from Device", fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }

        // Inspection Details Card
        state.inspection?.let { inspection ->
            AicInspectionCard(inspection = inspection)

            // Super-Resolution Option for Images
            if (inspection.header.modality == AicModality.IMAGE) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "2x ESPCN Super-Resolution Pass",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = "Upscale reconstructed image during decoding",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = state.enableSuperResolution,
                            onCheckedChange = viewModel::toggleSuperResolution,
                        )
                    }
                }
            }

            // Decompress Trigger
            Button(
                onClick = viewModel::decompress,
                enabled = !state.isLoading,
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary,
                ),
                modifier = Modifier.fillMaxWidth().height(50.dp),
            ) {
                if (state.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSecondary,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Decoding On-Device...")
                } else {
                    Text("Decompress & Render", fontWeight = FontWeight.Bold)
                }
            }
        }

        // Decompressed Image Viewer
        state.reconstructedBitmap?.let { bmp ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(26.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Black),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "DECOMPRESSED RECONSTRUCTION",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(280.dp)
                            .clip(RoundedCornerShape(18.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "Decompressed image",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = {
                            state.decompressionResult?.previewUri?.let { uri ->
                                pendingSaveUri = uri
                                saveMimeType = "image/png"
                                fileSaver.launch("decompressed-${System.currentTimeMillis()}.png")
                            }
                        },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Export Reconstructed PNG", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Decompressed Audio Waveform Viewer
        if (state.reconstructedAudioSamples.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SamsungWaveformVisualizer(
                    samples = state.reconstructedAudioSamples,
                    title = "DECOMPRESSED AUDIO WAVEFORM",
                    heightDp = 150,
                )
                Button(
                    onClick = {
                        state.decompressionResult?.previewUri?.let { uri ->
                            pendingSaveUri = uri
                            saveMimeType = "audio/wav"
                            fileSaver.launch("decompressed-${System.currentTimeMillis()}.wav")
                        }
                    },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Export Reconstructed WAV", fontWeight = FontWeight.Bold)
                }
            }
        }

        state.error?.let { err ->
            Text(text = err, color = MaterialTheme.colorScheme.error)
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}
