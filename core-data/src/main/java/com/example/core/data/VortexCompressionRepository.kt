package com.example.core.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.example.core.data.io.MediaFileValidator
import com.example.core.data.io.MediaIo
import com.example.core.data.local.CompressionHistoryDao
import com.example.core.data.local.CompressionHistoryEntity
import com.example.core.data.preferences.SettingsStore
import com.example.core.data.queue.BatchQueueManager
import com.example.core.domain.BatchItem
import com.example.core.domain.BatchProgress
import com.example.core.domain.CompressionMetrics
import com.example.core.domain.CompressionRepository
import com.example.core.domain.CompressionResult
import com.example.core.domain.MediaType
import com.example.core.domain.ModelMetadata
import com.example.core.ml.AudioTensorCodec
import com.example.core.ml.CompressionMetricsCalculator
import com.example.core.ml.ImageTensorCodec
import com.example.core.ml.ModelReportParser
import com.example.core.ml.TfliteRunner
import com.example.core.ml.VideoTensorCodec
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min
import kotlin.system.measureNanoTime
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import androidx.core.graphics.createBitmap

@Singleton
class VortexCompressionRepository @Inject constructor(
    private val context: Context,
    private val mediaIo: MediaIo,
    private val validator: MediaFileValidator,
    private val settingsStore: SettingsStore,
    private val historyDao: CompressionHistoryDao,
    private val reportParser: ModelReportParser,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : CompressionRepository {

    private val batchUpdates = MutableStateFlow<List<BatchProgress>>(emptyList())
    private val batchQueueManager = BatchQueueManager(
        emit = { updates -> batchUpdates.value = updates },
        processItem = { item -> processBatchItem(item) },
    )

    override suspend fun modelMetadata(): List<ModelMetadata> = withContext(ioDispatcher) {
        val delegate = settingsStore.preferredDelegate.first()
        val contracts = mapOf(
            MediaType.IMAGE to TfliteRunner(context, IMAGE_MODEL_ASSET, delegate).use { it.contract() },
            MediaType.AUDIO to TfliteRunner(context, AUDIO_MODEL_ASSET, delegate).use { it.contract() },
            MediaType.VIDEO to TfliteRunner(context, VIDEO_MODEL_ASSET, delegate).use { it.contract() },
        )
        reportParser.parseAll(contracts)
    }

    override suspend fun compressImage(uri: Uri): Result<CompressionResult> = withContext(ioDispatcher) {
        runCatching {
            validator.validateImage(uri).getOrThrow()
            val sourceBitmap = mediaIo.readBitmap(uri)
            val delegate = settingsStore.preferredDelegate.first()
            var reconstructed = sourceBitmap
            val latencyNanos = TfliteRunner(context, IMAGE_MODEL_ASSET, delegate).use { runner ->
                val spec = parseImageSpec(runner.contract().input.shape)
                measureNanoTime {
                    reconstructed = reconstructImageWithTiling(
                        sourceBitmap = sourceBitmap,
                        runner = runner,
                        inputSpec = spec,
                    )
                }
            }
            val outputUri = mediaIo.saveBitmap(reconstructed, "image-reconstructed")
            val metrics = imageMetrics(sourceBitmap, reconstructed, latencyNanos)
            storeHistory(uri, outputUri, MediaType.IMAGE, metrics)
            CompressionResult(inputUri = uri, outputUri = outputUri, previewUri = outputUri, metrics = metrics)
        }
    }

    override suspend fun compressAudio(uri: Uri): Result<CompressionResult> = withContext(ioDispatcher) {
        runCatching {
            validator.validateAudio(uri).getOrThrow()
            val source = mediaIo.readWavMonoFloat(uri)
            val delegate = settingsStore.preferredDelegate.first()

            val reconstructedChunks = mutableListOf<Array<Array<FloatArray>>>()
            var outputWindow = 1
            lateinit var chunks: List<Array<Array<FloatArray>>>
            val latencyNanos = TfliteRunner(context, AUDIO_MODEL_ASSET, delegate).use { runner ->
                val contract = runner.contract()
                val inputWindow = parseAudioWindow(contract.input.shape)
                outputWindow = parseAudioWindow(contract.output.shape)
                chunks = AudioTensorCodec.preprocess(source, inputWindow)
                measureNanoTime {
                    chunks.forEach { chunk ->
                        val out = AudioTensorCodec.outputWindow(outputWindow)
                        runner.run(chunk, out)
                        reconstructedChunks += out
                    }
                }
            }

            val reconstructed = AudioTensorCodec.postprocess(reconstructedChunks, source.size, outputWindow)
            val outputUri = mediaIo.writeWavMonoFloat(reconstructed, "audio-reconstructed", sampleRate = 16_000)
            val metrics = audioMetrics(source, reconstructed, latencyNanos)
            storeHistory(uri, outputUri, MediaType.AUDIO, metrics)
            CompressionResult(inputUri = uri, outputUri = outputUri, previewUri = null, metrics = metrics)
        }
    }

    override suspend fun compressVideo(uri: Uri): Result<CompressionResult> = withContext(ioDispatcher) {
        runCatching {
            validator.validateVideo(uri).getOrThrow()
            val delegate = settingsStore.preferredDelegate.first()
            val (sourceFrames, reconstructedFrames, latencyNanos) = TfliteRunner(context, VIDEO_MODEL_ASSET, delegate).use { runner ->
                val contract = runner.contract()
                val inputSpec = parseVideoSpec(contract.input.shape)
                val outputSpec = parseVideoSpec(contract.output.shape)
                val sourceFrames = mediaIo.readVideoFrames(uri, inputSpec.frames)
                require(sourceFrames.isNotEmpty()) { "No frames decoded from source video" }
                val clip = toVideoClip(sourceFrames, inputSpec)
                val out = VideoTensorCodec.outputClip(outputSpec.frames, outputSpec.height, outputSpec.width, outputSpec.channels)
                val latencyNanos = measureNanoTime {
                    runner.run(clip, out)
                }
                Triple(sourceFrames, fromVideoClip(out), latencyNanos)
            }
            val outputPreviewUri = mediaIo.saveFramesAsPreviewStrip(reconstructedFrames, "video-reconstructed")
            val metrics = videoMetrics(sourceFrames, reconstructedFrames, latencyNanos)
            storeHistory(uri, outputPreviewUri, MediaType.VIDEO, metrics)
            CompressionResult(inputUri = uri, outputUri = outputPreviewUri, previewUri = outputPreviewUri, metrics = metrics)
        }
    }

    override suspend fun enqueueBatch(items: List<BatchItem>) {
        withContext(ioDispatcher) { batchQueueManager.enqueue(items) }
    }

    override suspend fun retryBatch(id: String) {
        withContext(ioDispatcher) { batchQueueManager.retry(id) }
    }

    override suspend fun cancelBatch(id: String) {
        withContext(ioDispatcher) { batchQueueManager.cancel(id) }
    }

    override fun batchProgress(): Flow<List<BatchProgress>> = batchUpdates.asStateFlow()

    private suspend fun processBatchItem(item: BatchItem): Result<Unit> {
        val result = when (item.mediaType) {
            MediaType.IMAGE -> compressImage(item.sourceUri)
            MediaType.AUDIO -> compressAudio(item.sourceUri)
            MediaType.VIDEO -> compressVideo(item.sourceUri)
        }
        return result.fold(
            onSuccess = { Result.success(Unit) },
            onFailure = { Result.failure(it) },
        )
    }

    private suspend fun storeHistory(inputUri: Uri, outputUri: Uri, mediaType: MediaType, metrics: CompressionMetrics) {
        historyDao.insert(
            CompressionHistoryEntity(
                sourceUri = inputUri.toString(),
                outputUri = outputUri.toString(),
                mediaType = mediaType.name,
                latencyMs = metrics.latencyMs,
                compressionRatio = metrics.compressionRatio,
                psnr = metrics.psnr,
                ssim = metrics.ssim,
                snr = metrics.snr,
                createdAtEpochMs = System.currentTimeMillis(),
            )
        )
    }

    private fun imageMetrics(source: Bitmap, reconstructed: Bitmap, latencyNanos: Long): CompressionMetrics {
        val sourceArray = bitmapToFloatArray(source)
        val reconstructedArray = bitmapToFloatArray(reconstructed)
        val latencyMs = latencyNanos / 1_000_000.0
        return CompressionMetrics(
            psnr = CompressionMetricsCalculator.psnr(sourceArray, reconstructedArray),
            ssim = CompressionMetricsCalculator.ssimApprox(sourceArray, reconstructedArray),
            compressionRatio = CompressionMetricsCalculator.compressionRatio(
                source.byteCount.toLong(),
                reconstructed.byteCount.toLong(),
            ),
            latencyMs = latencyMs,
            throughputItemsPerSec = CompressionMetricsCalculator.throughput(1, latencyMs),
        )
    }

    private fun audioMetrics(source: FloatArray, reconstructed: FloatArray, latencyNanos: Long): CompressionMetrics {
        val latencyMs = latencyNanos / 1_000_000.0
        return CompressionMetrics(
            psnr = CompressionMetricsCalculator.psnr(source, reconstructed),
            snr = CompressionMetricsCalculator.snr(source, reconstructed),
            compressionRatio = CompressionMetricsCalculator.compressionRatio(
                source.size.toLong() * 2,
                reconstructed.size.toLong() * 2,
            ),
            latencyMs = latencyMs,
            throughputItemsPerSec = CompressionMetricsCalculator.throughput(source.size, latencyMs),
        )
    }

    private fun videoMetrics(sourceFrames: List<Bitmap>, reconstructedFrames: List<Bitmap>, latencyNanos: Long): CompressionMetrics {
        val framePairs = sourceFrames.zip(reconstructedFrames)
        require(framePairs.isNotEmpty()) { "No aligned video frames available for metric calculation" }
        val src = framePairs.flatMap { (source, _) ->
            bitmapToFloatArray(source).asList()
        }.toFloatArray()
        val dst = framePairs.flatMap { (source, reconstructed) ->
            val aligned = if (reconstructed.width != source.width || reconstructed.height != source.height) {
                Bitmap.createScaledBitmap(reconstructed, source.width, source.height, true)
            } else {
                reconstructed
            }
            bitmapToFloatArray(aligned).asList()
        }.toFloatArray()
        val latencyMs = latencyNanos / 1_000_000.0
        return CompressionMetrics(
            psnr = CompressionMetricsCalculator.psnr(src, dst),
            ssim = CompressionMetricsCalculator.ssimApprox(src, dst),
            compressionRatio = CompressionMetricsCalculator.compressionRatio(
                sourceFrames.sumOf { it.byteCount }.toLong(),
                reconstructedFrames.sumOf { it.byteCount }.toLong(),
            ),
            latencyMs = latencyMs,
            throughputItemsPerSec = CompressionMetricsCalculator.throughput(sourceFrames.size, latencyMs),
        )
    }

    private fun bitmapToFloatArray(bitmap: Bitmap): FloatArray {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return FloatArray(pixels.size * 3).also { out ->
            var cursor = 0
            pixels.forEach { p ->
                out[cursor++] = ((p shr 16) and 0xFF) / 255f
                out[cursor++] = ((p shr 8) and 0xFF) / 255f
                out[cursor++] = (p and 0xFF) / 255f
            }
        }
    }

    private fun toVideoClip(frames: List<Bitmap>, inputSpec: VideoModelSpec): Array<Array<Array<Array<FloatArray>>>> {
        val clip = Array(1) {
            Array(inputSpec.frames) {
                Array(inputSpec.height) { Array(inputSpec.width) { FloatArray(inputSpec.channels) } }
            }
        }
        val selected = if (frames.size >= inputSpec.frames) frames.take(inputSpec.frames) else {
            frames + List(inputSpec.frames - frames.size) { frames.last() }
        }
        selected.forEachIndexed { frameIndex, frame ->
            val resized = Bitmap.createScaledBitmap(frame, inputSpec.width, inputSpec.height, true)
            repeat(inputSpec.height) { y ->
                repeat(inputSpec.width) { x ->
                    val pixel = resized.getPixel(x, y)
                    clip[0][frameIndex][y][x][0] = ((pixel shr 16) and 0xFF) / 255f
                    clip[0][frameIndex][y][x][1] = ((pixel shr 8) and 0xFF) / 255f
                    clip[0][frameIndex][y][x][2] = (pixel and 0xFF) / 255f
                }
            }
        }
        return clip
    }

    private fun fromVideoClip(clip: Array<Array<Array<Array<FloatArray>>>>): List<Bitmap> {
        val frameCount = clip[0].size
        val height = clip[0][0].size
        val width = clip[0][0][0].size
        return List(frameCount) { frameIndex ->
            createBitmap(width, height).apply {
                repeat(height) { y ->
                    repeat(width) { x ->
                        val rgb = clip[0][frameIndex][y][x]
                        val r = (rgb[0].coerceIn(0f, 1f) * 255f).toInt()
                        val g = (rgb[1].coerceIn(0f, 1f) * 255f).toInt()
                        val b = (rgb[2].coerceIn(0f, 1f) * 255f).toInt()
                        setPixel(x, y, (0xFF shl 24) or (r shl 16) or (g shl 8) or b)
                    }
                }
            }
        }
    }

    private fun reconstructImageWithTiling(
        sourceBitmap: Bitmap,
        runner: TfliteRunner,
        inputSpec: ImageModelSpec,
    ): Bitmap {
        val width = sourceBitmap.width
        val height = sourceBitmap.height
        val tileWidth = inputSpec.width
        val tileHeight = inputSpec.height
        if (width <= tileWidth && height <= tileHeight) {
            val outputTensor = ImageTensorCodec.outputBuffer(tileHeight, tileWidth, inputSpec.channels)
            val inputTensor = ImageTensorCodec.preprocess(sourceBitmap, tileWidth, tileHeight, inputSpec.channels)
            runner.run(inputTensor, outputTensor)
            val reconstructedTile = ImageTensorCodec.postprocess(outputTensor)
            return if (reconstructedTile.width != width || reconstructedTile.height != height) {
                Bitmap.createScaledBitmap(reconstructedTile, width, height, true)
            } else {
                reconstructedTile
            }
        }

        val overlapX = max(8, tileWidth / 8)
        val overlapY = max(8, tileHeight / 8)
        val stepX = max(1, tileWidth - overlapX)
        val stepY = max(1, tileHeight - overlapY)
        val xStarts = tileStarts(width, tileWidth, stepX)
        val yStarts = tileStarts(height, tileHeight, stepY)
        val featherX = max(2, min(overlapX / 2, tileWidth / 2))
        val featherY = max(2, min(overlapY / 2, tileHeight / 2))

        val accumR = FloatArray(width * height)
        val accumG = FloatArray(width * height)
        val accumB = FloatArray(width * height)
        val weights = FloatArray(width * height)

        yStarts.forEach { startY ->
            xStarts.forEach { startX ->
                val patchWidth = min(tileWidth, width - startX)
                val patchHeight = min(tileHeight, height - startY)
                val sourcePatch = Bitmap.createBitmap(sourceBitmap, startX, startY, patchWidth, patchHeight)
                val inputTensor = ImageTensorCodec.preprocess(sourcePatch, tileWidth, tileHeight, inputSpec.channels)
                val outputTensor = ImageTensorCodec.outputBuffer(tileHeight, tileWidth, inputSpec.channels)
                runner.run(inputTensor, outputTensor)
                val reconstructedTile = ImageTensorCodec.postprocess(outputTensor)
                val alignedTile = if (patchWidth != tileWidth || patchHeight != tileHeight) {
                    Bitmap.createScaledBitmap(reconstructedTile, patchWidth, patchHeight, true)
                } else {
                    reconstructedTile
                }

                repeat(patchHeight) { y ->
                    repeat(patchWidth) { x ->
                        val pixel = alignedTile.getPixel(x, y)
                        val wx = edgeWeight(x, patchWidth, featherX)
                        val wy = edgeWeight(y, patchHeight, featherY)
                        val weight = wx * wy
                        val index = (startY + y) * width + (startX + x)
                        accumR[index] += ((pixel shr 16) and 0xFF) / 255f * weight
                        accumG[index] += ((pixel shr 8) and 0xFF) / 255f * weight
                        accumB[index] += (pixel and 0xFF) / 255f * weight
                        weights[index] += weight
                    }
                }
            }
        }

        return createBitmap(width, height).apply {
            repeat(height) { y ->
                repeat(width) { x ->
                    val index = y * width + x
                    val weight = weights[index].takeIf { it > 1e-6f } ?: 1f
                    val r = (accumR[index] / weight).coerceIn(0f, 1f)
                    val g = (accumG[index] / weight).coerceIn(0f, 1f)
                    val b = (accumB[index] / weight).coerceIn(0f, 1f)
                    val packed = (0xFF shl 24) or ((r * 255f).toInt() shl 16) or ((g * 255f).toInt() shl 8) or (b * 255f).toInt()
                    setPixel(x, y, packed)
                }
            }
        }
    }

    private fun tileStarts(fullSize: Int, tileSize: Int, step: Int): List<Int> {
        if (fullSize <= tileSize) return listOf(0)
        val starts = mutableListOf<Int>()
        var cursor = 0
        val last = fullSize - tileSize
        while (cursor < last) {
            starts += cursor
            cursor += step
        }
        if (starts.lastOrNull() != last) starts += last
        return starts
    }

    private fun edgeWeight(index: Int, length: Int, feather: Int): Float {
        if (feather <= 0 || length <= feather * 2) return 1f
        val left = (index + 1).toFloat() / feather
        val right = (length - index).toFloat() / feather
        return min(1f, min(left, right)).coerceAtLeast(0.1f)
    }

    private fun parseImageSpec(shape: IntArray): ImageModelSpec {
        require(shape.size == 4) { "Image model input must be rank-4, got ${shape.contentToString()}" }
        require(shape[0] == 1) { "Image model must use batch size 1, got ${shape[0]}" }
        require(shape[3] == 3) { "Image model must use RGB channels, got ${shape[3]}" }
        return ImageModelSpec(height = shape[1], width = shape[2], channels = shape[3])
    }

    private fun parseAudioWindow(shape: IntArray): Int {
        require(shape.size == 3) { "Audio model tensor must be rank-3, got ${shape.contentToString()}" }
        require(shape[0] == 1) { "Audio model must use batch size 1, got ${shape[0]}" }
        require(shape[2] == 1) { "Audio model must be mono, got channels=${shape[2]}" }
        return shape[1]
    }

    private fun parseVideoSpec(shape: IntArray): VideoModelSpec {
        require(shape.size == 5) { "Video model tensor must be rank-5, got ${shape.contentToString()}" }
        require(shape[0] == 1) { "Video model must use batch size 1, got ${shape[0]}" }
        require(shape[4] == 3) { "Video model must use RGB channels, got ${shape[4]}" }
        return VideoModelSpec(frames = shape[1], height = shape[2], width = shape[3], channels = shape[4])
    }

    private data class ImageModelSpec(
        val height: Int,
        val width: Int,
        val channels: Int,
    )

    private data class VideoModelSpec(
        val frames: Int,
        val height: Int,
        val width: Int,
        val channels: Int,
    )

    private companion object {
        const val IMAGE_MODEL_ASSET = "models/image/production_model.tflite"
        const val AUDIO_MODEL_ASSET = "models/audio/audio_autoencoder.tflite"
        const val VIDEO_MODEL_ASSET = "models/video/video_autoencoder.tflite"
    }
}

