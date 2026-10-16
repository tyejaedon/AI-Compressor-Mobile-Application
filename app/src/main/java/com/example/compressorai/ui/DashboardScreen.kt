package com.example.compressorai.ui

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.compressorai.MainUiState
import com.example.compressorai.navigation.VortexDestination
import com.example.core.ui.SamsungHeader

@Composable
fun DashboardScreen(
    state: MainUiState,
    onNavigate: (VortexDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            SamsungHeader(
                title = "AI Compressor",
                subtitle = "On-device deep neural compression suite running strictly local M1/ARM models",
            )
        }

        // On-Device Security & Edge AI Banner
        item {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF00C853)),
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "100% PRIVATE & ON-DEVICE",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            letterSpacing = 1.1.sp,
                        )
                        Text(
                            text = "Zero cloud dependencies. All TFLite encoder/decoder passes execute locally.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }

        // Neural Image Studio Card
        item {
            ExecutiveStudioCard(
                title = "Neural Image Studio",
                badgeText = "IMAGE LOSSLESS/LOSSY",
                description = "Quantized discrete latent autoencoder with optional 2x ESPCN sub-pixel CNN remaster for high-fidelity photo compression.",
                primaryButtonText = "Open Image Studio",
                accentColor = MaterialTheme.colorScheme.primary,
                onClick = { onNavigate(VortexDestination.Image) },
            )
        }

        // Neural Audio Studio Card
        item {
            ExecutiveStudioCard(
                title = "Neural Audio Studio",
                badgeText = "AUDIO TEMPORAL CODEC",
                description = "1D strided temporal convolution autoencoder with 8k sample windowing and discrete Deflate bitstream generation.",
                primaryButtonText = "Open Audio Studio",
                accentColor = Color(0xFF8C52FF),
                onClick = { onNavigate(VortexDestination.Audio) },
            )
        }

        // AIC Container Studio Card
        item {
            ExecutiveStudioCard(
                title = "AIC Container Studio",
                badgeText = ".AIC FILE FORMAT",
                description = "Universal binary container inspector for .aic files. Inspect header magic bytes, bit depth, latent dimensions, and decompress on-device.",
                primaryButtonText = "Open AIC Inspector",
                accentColor = Color(0xFF00B0FF),
                onClick = { onNavigate(VortexDestination.AicStudio) },
            )
        }

        // Hardware Benchmark Card
        item {
            ExecutiveStudioCard(
                title = "NPU & GPU Benchmark",
                badgeText = "HARDWARE PROFILER",
                description = "Benchmark on-device TFLite inference latency (P50, P90, P99), throughput (FPS), and memory footprint across GPU, NNAPI, and CPU.",
                primaryButtonText = "Run Benchmark",
                accentColor = Color(0xFFFF9100),
                onClick = { onNavigate(VortexDestination.Benchmark) },
            )
        }

        // History Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(26.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "COMPRESSION HISTORY & LOGS",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        letterSpacing = 1.1.sp,
                    )
                    Text(
                        text = "View saved benchmarks, compression ratios, and reconstructed previews.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = { onNavigate(VortexDestination.History) },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("View Compression History", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ExecutiveStudioCard(
    title: String,
    badgeText: String,
    description: String,
    primaryButtonText: String,
    accentColor: Color,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = accentColor.copy(alpha = 0.15f),
                ) {
                    Text(
                        text = badgeText,
                        color = accentColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }

            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 20.sp,
            )

            Button(
                onClick = onClick,
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = accentColor,
                    contentColor = Color.White,
                ),
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                Text(primaryButtonText, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        }
    }
}
