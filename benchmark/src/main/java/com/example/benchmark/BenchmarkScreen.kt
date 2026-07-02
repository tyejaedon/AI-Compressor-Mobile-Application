package com.example.benchmark

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
fun BenchmarkScreen(latenciesMs: List<Double>, modifier: Modifier = Modifier) {
    val sorted = latenciesMs.sorted()
    val p50 = percentile(sorted, 50)
    val p90 = percentile(sorted, 90)
    val p99 = percentile(sorted, 99)

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = "Benchmark", style = MaterialTheme.typography.headlineSmall)
        Text("Warm-up runs: 5")
        Text("P50 latency: ${"%.2f".format(p50)} ms")
        Text("P90 latency: ${"%.2f".format(p90)} ms")
        Text("P99 latency: ${"%.2f".format(p99)} ms")
        Text("Samples: ${latenciesMs.size}")
    }
}

private fun percentile(sorted: List<Double>, p: Int): Double {
    if (sorted.isEmpty()) return 0.0
    val index = ((p / 100.0) * (sorted.lastIndex)).roundToInt().coerceIn(0, sorted.lastIndex)
    return sorted[index]
}

