package com.example.benchmark

import android.graphics.Bitmap
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import com.example.core.domain.InferenceDelegate
import com.example.core.ml.AudioCompressionEngine
import com.example.core.ml.ImageCompressionEngine
import com.example.core.ml.UpscalerEngine
import com.example.core.ui.MetricBadge
import com.example.core.ui.SamsungHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.system.measureNanoTime

enum class BenchmarkModelTarget(val displayName: String) {
    IMAGE_ENCODER("Image Encoder (128×128)"),
    IMAGE_DECODER("Image Decoder (32×32)"),
    IMAGE_UPSCALER("2x ESPCN Upscaler"),
    AUDIO_AUTOENCODER("Audio Autoencoder (8k)"),
}

data class BenchmarkResultData(
    val modelTarget: BenchmarkModelTarget,
    val delegate: InferenceDelegate,
    val iterations: Int,
    val warmUpRuns: Int,
    val latenciesMs: List<Double>,
    val p50Ms: Double,
    val p90Ms: Double,
    val p99Ms: Double,
    val avgMs: Double,
    val throughputFps: Double,
)

@Composable
fun BenchmarkScreen(
    latenciesMs: List<Double> = emptyList(),
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedTarget by remember { mutableStateOf(BenchmarkModelTarget.IMAGE_ENCODER) }
    var selectedDelegate by remember { mutableStateOf(InferenceDelegate.GPU) }
    var iterationsCount by remember { mutableIntStateOf(20) }

    var isRunning by remember { mutableStateOf(false) }
    var currentProgress by remember { mutableFloatStateOf(0f) }
    var benchmarkResult by remember { mutableStateOf<BenchmarkResultData?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SamsungHeader(
            title = "NPU & GPU Benchmark",
            subtitle = "Direct on-device profiling of neural compression subgraphs & delegates",
        )

        // Config Card
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
                    text = "BENCHMARK TARGET MODEL",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 1.1.sp,
                )

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    BenchmarkModelTarget.entries.forEach { target ->
                        FilterChip(
                            selected = selectedTarget == target,
                            onClick = { selectedTarget = target },
                            label = { Text(target.displayName) },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "ACCELERATION DELEGATE",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 1.1.sp,
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = selectedDelegate == InferenceDelegate.GPU,
                        onClick = { selectedDelegate = InferenceDelegate.GPU },
                        label = { Text("GPU / Metal") },
                        shape = RoundedCornerShape(12.dp),
                    )
                    FilterChip(
                        selected = selectedDelegate == InferenceDelegate.NNAPI,
                        onClick = { selectedDelegate = InferenceDelegate.NNAPI },
                        label = { Text("NNAPI") },
                        shape = RoundedCornerShape(12.dp),
                    )
                    FilterChip(
                        selected = selectedDelegate == InferenceDelegate.CPU,
                        onClick = { selectedDelegate = InferenceDelegate.CPU },
                        label = { Text("CPU (4T)") },
                        shape = RoundedCornerShape(12.dp),
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "RUN ITERATIONS",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 1.1.sp,
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(10, 25, 50).forEach { count ->
                        FilterChip(
                            selected = iterationsCount == count,
                            onClick = { iterationsCount = count },
                            label = { Text("$count passes") },
                            shape = RoundedCornerShape(12.dp),
                        )
                    }
                }
            }
        }

        // Run Benchmark Button
        Button(
            onClick = {
                isRunning = true
                currentProgress = 0f
                scope.launch {
                    val result = withContext(Dispatchers.Default) {
                        runBenchmark(
                            context = context,
                            target = selectedTarget,
                            delegate = selectedDelegate,
                            iterations = iterationsCount,
                            onProgress = { progress -> currentProgress = progress },
                        )
                    }
                    benchmarkResult = result
                    isRunning = false
                }
            },
            enabled = !isRunning,
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
            modifier = Modifier.fillMaxWidth().height(54.dp),
        ) {
            if (isRunning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Profiling On-Device... ${(currentProgress * 100).toInt()}%", fontWeight = FontWeight.Bold)
            } else {
                Text("Start Benchmark Run", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
        }

        if (isRunning) {
            LinearProgressIndicator(
                progress = { currentProgress },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
            )
        }

        // Benchmark Results
        benchmarkResult?.let { res ->
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "BENCHMARK RESULTS",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            letterSpacing = 1.1.sp,
                        )

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        ) {
                            Text(
                                text = "${res.delegate.name} DELEGATE",
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }

                    Text(
                        text = res.modelTarget.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        MetricBadge(label = "P50 LATENCY", value = String.format(Locale.US, "%.1f ms", res.p50Ms), modifier = Modifier.weight(1f))
                        MetricBadge(label = "P90 LATENCY", value = String.format(Locale.US, "%.1f ms", res.p90Ms), modifier = Modifier.weight(1f))
                        MetricBadge(label = "P99 LATENCY", value = String.format(Locale.US, "%.1f ms", res.p99Ms), modifier = Modifier.weight(1f))
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        MetricBadge(label = "AVG LATENCY", value = String.format(Locale.US, "%.1f ms", res.avgMs), modifier = Modifier.weight(1f))
                        MetricBadge(label = "THROUGHPUT", value = String.format(Locale.US, "%.1f FPS", res.throughputFps), modifier = Modifier.weight(1f))
                        MetricBadge(label = "SAMPLES", value = "${res.iterations}", modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

private fun runBenchmark(
    context: android.content.Context,
    target: BenchmarkModelTarget,
    delegate: InferenceDelegate,
    iterations: Int,
    onProgress: (Float) -> Unit,
): BenchmarkResultData {
    val warmUpRuns = 4
    val totalRuns = warmUpRuns + iterations
    val measuredTimes = mutableListOf<Double>()

    val dummyBitmap128 = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
    val dummyBitmap64 = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
    val dummyAudio = FloatArray(8000) { 0.1f }
    val dummyLatents = ByteArray(32 * 32 * 16) { 120.toByte() }

    when (target) {
        BenchmarkModelTarget.IMAGE_ENCODER -> {
            val engine = ImageCompressionEngine(context, requestedDelegate = delegate)
            engine.use { eng ->
                for (i in 0 until totalRuns) {
                    val nanos = measureNanoTime {
                        eng.encodeBitmap(dummyBitmap128)
                    }
                    if (i >= warmUpRuns) {
                        measuredTimes += (nanos / 1_000_000.0)
                    }
                    onProgress((i + 1).toFloat() / totalRuns.toFloat())
                }
            }
        }
        BenchmarkModelTarget.IMAGE_DECODER -> {
            val engine = ImageCompressionEngine(context, requestedDelegate = delegate)
            engine.use { eng ->
                for (i in 0 until totalRuns) {
                    val nanos = measureNanoTime {
                        eng.decodeLatents(dummyLatents, 128, 128)
                    }
                    if (i >= warmUpRuns) {
                        measuredTimes += (nanos / 1_000_000.0)
                    }
                    onProgress((i + 1).toFloat() / totalRuns.toFloat())
                }
            }
        }
        BenchmarkModelTarget.IMAGE_UPSCALER -> {
            val upscaler = UpscalerEngine(context, requestedDelegate = delegate)
            upscaler.use { sr ->
                for (i in 0 until totalRuns) {
                    val nanos = measureNanoTime {
                        sr.upscale(dummyBitmap64)
                    }
                    if (i >= warmUpRuns) {
                        measuredTimes += (nanos / 1_000_000.0)
                    }
                    onProgress((i + 1).toFloat() / totalRuns.toFloat())
                }
            }
        }
        BenchmarkModelTarget.AUDIO_AUTOENCODER -> {
            val engine = AudioCompressionEngine(context, requestedDelegate = delegate)
            engine.use { eng ->
                for (i in 0 until totalRuns) {
                    val nanos = measureNanoTime {
                        eng.encodeAudio(dummyAudio)
                    }
                    if (i >= warmUpRuns) {
                        measuredTimes += (nanos / 1_000_000.0)
                    }
                    onProgress((i + 1).toFloat() / totalRuns.toFloat())
                }
            }
        }
    }

    val sorted = measuredTimes.sorted()
    val p50 = percentile(sorted, 50)
    val p90 = percentile(sorted, 90)
    val p99 = percentile(sorted, 99)
    val avg = if (sorted.isNotEmpty()) sorted.average() else 0.0
    val fps = if (avg > 0.0) 1000.0 / avg else 0.0

    return BenchmarkResultData(
        modelTarget = target,
        delegate = delegate,
        iterations = iterations,
        warmUpRuns = warmUpRuns,
        latenciesMs = sorted,
        p50Ms = p50,
        p90Ms = p90,
        p99Ms = p99,
        avgMs = avg,
        throughputFps = fps,
    )
}

private fun percentile(sorted: List<Double>, p: Int): Double {
    if (sorted.isEmpty()) return 0.0
    val index = ((p / 100.0) * (sorted.lastIndex)).roundToInt().coerceIn(0, sorted.lastIndex)
    return sorted[index]
}
