package com.example.core.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun CompressorCard(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = "$title compressor card" },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(text = subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

@Composable
fun MetricChip(label: String, value: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.shapes.small)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = "$label: $value",
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
fun WaveformPreview(samples: FloatArray, modifier: Modifier = Modifier) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(120.dp)
            .semantics { contentDescription = "Audio waveform preview" },
    ) {
        if (samples.isEmpty()) return@Canvas
        val step = samples.size / size.width.coerceAtLeast(1f)
        val path = Path()
        var x = 0f
        var index = 0f
        while (x < size.width) {
            val sample = samples[index.toInt().coerceIn(0, samples.lastIndex)]
            val y = size.height / 2 - sample * (size.height / 2)
            if (x == 0f) path.moveTo(x, y) else path.lineTo(x, y)
            x += 1f
            index += step
        }
        drawPath(path = path, color = Color.Cyan)
        drawLine(Color.Gray, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 1f)
    }
}

@Composable
fun MetricsRow(metrics: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        metrics.forEach { (label, value) ->
            MetricChip(label = label, value = value)
        }
    }
}

