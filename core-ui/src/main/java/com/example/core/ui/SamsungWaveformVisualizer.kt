package com.example.core.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

@Composable
fun SamsungWaveformVisualizer(
    samples: FloatArray,
    modifier: Modifier = Modifier,
    snrVal: Double? = null,
    title: String = "NEURAL AUDIO WAVEFORM",
    heightDp: Int = 180,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        color = Color(0xFF0D0E15),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF232536)),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF8C52FF)),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF9E86FF),
                    letterSpacing = 1.1.sp,
                )
                Spacer(modifier = Modifier.weight(1f))
                snrVal?.let {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF8C52FF).copy(alpha = 0.2f),
                    ) {
                        Text(
                            text = "SNR: ${"%.1f".format(it)} dB",
                            color = Color(0xFFD1B3FF),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(heightDp.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF13141F)),
            ) {
                val midY = size.height / 2f
                val totalBars = 80
                val barWidth = (size.width / totalBars) * 0.65f
                val barSpacing = size.width / totalBars

                // Baseline
                drawLine(
                    color = Color(0xFF282B3D),
                    start = Offset(0f, midY),
                    end = Offset(size.width, midY),
                    strokeWidth = 1.dp.toPx(),
                )

                if (samples.isEmpty()) return@Canvas

                val step = samples.size.toFloat() / totalBars.toFloat()
                val neonBrush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF00E5FF),
                        Color(0xFF8C52FF),
                        Color(0xFFE040FB),
                    ),
                )

                for (i in 0 until totalBars) {
                    val sampleIdx = (i * step).toInt().coerceIn(0, samples.lastIndex)
                    val rawSample = samples[sampleIdx]
                    val magnitude = (abs(rawSample).coerceIn(0.02f, 1.0f) * (midY * 0.9f))

                    val startX = i * barSpacing + (barSpacing - barWidth) / 2f

                    drawLine(
                        brush = neonBrush,
                        start = Offset(startX, midY - magnitude),
                        end = Offset(startX, midY + magnitude),
                        strokeWidth = barWidth,
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
    }
}
