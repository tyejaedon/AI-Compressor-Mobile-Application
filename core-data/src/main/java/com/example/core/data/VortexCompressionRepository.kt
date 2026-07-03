package com.example.core.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import com.example.core.data.io.MediaFileValidator
import com.example.core.data.io.MediaIo
import com.example.core.data.local.CompressionHistoryDao
import com.example.core.data.local.CompressionHistoryEntity
import com.example.core.data.preferences.SettingsStore
import com.example.core.data.queue.BatchQueueManager
import com.example.core.domain.BatchItem
import com.example.core.domain.BatchProgress
import com.example.core.domain.CompressionMetrics
import com.example.core.domain.CompressionHistoryItem
import com.example.core.domain.CompressionPipelineStage
import com.example.core.domain.CompressionPipelineStatus
import com.example.core.domain.CompressionRepository
import com.example.core.domain.CompressionResult
import com.example.core.domain.ImageNormalizationMode
import com.example.core.domain.InferenceDelegate
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
import kotlinx.coroutines.flow.map
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
    private val pipelineUpdates = MutableStateFlow<CompressionPipelineStatus?>(null)
    private val imageRunnerLock = Any()
    private var cachedImageRunner: TfliteRunner? = null
    private var cachedImageRunnerDelegate: InferenceDelegate? = null
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
        val startedAtNanos = System.nanoTime()
        updatePipeline(
            mediaType = MediaType.IMAGE,
            stage = CompressionPipelineStage.VALIDATING_INPUT,
            progress = 0.05f,
            startedAtNanos = startedAtNanos,
        )
        runCatching {
            validator.validateImage(uri).getOrThrow()
            updatePipeline(
                mediaType = MediaType.IMAGE,
                stage = CompressionPipelineStage.LOADING_SOURCE,
                progress = 0.12f,
                startedAtNanos = startedAtNanos,
            )
            val sourceBitmap = mediaIo.readBitmap(uri)
            val delegate = settingsStore.preferredDelegate.first()
            val normalizationMode = settingsStore.imageNormalizationMode.first()
            updatePipeline(
                mediaType = MediaType.IMAGE,
                stage = CompressionPipelineStage.PREPARING_MODEL,
                progress = 0.2f,
                startedAtNanos = startedAtNanos,
            )
            val runner = acquireImageRunner(delegate)
            var reconstructed = sourceBitmap
            val contract = runner.contract()
            val inputSpec = parseImageSpec(contract.input.shape)
            val outputSpec = parseImageSpec(contract.output.shape)
            require(inputSpec == outputSpec) {
                "Image model input/output shapes must match for tiled reconstruction. input=$inputSpec output=$outputSpec"
            }
            val latencyNanos = measureNanoTime {
                reconstructed = reconstructImageWithTiling(
                    sourceBitmap = sourceBitmap,
                    runner = runner,
                    inputSpec = inputSpec,
                    normalizationMode = normalizationMode,
                    onProgress = { completedTiles, totalTiles ->
                        val tileProgress = completedTiles.toFloat() / totalTiles.toFloat()
                        updatePipeline(
                            mediaType = MediaType.IMAGE,
                            stage = CompressionPipelineStage.RUNNING_INFERENCE,
                            progress = 0.2f + (tileProgress * 0.65f),
                            startedAtNanos = startedAtNanos,
                            completedUnits = completedTiles,
                            totalUnits = totalTiles,
                        )
                    },
                )
            }
            updatePipeline(
                mediaType = MediaType.IMAGE,
                stage = CompressionPipelineStage.SAVING_OUTPUT,
                progress = 0.9f,
                startedAtNanos = startedAtNanos,
            )
            val outputUri = mediaIo.saveBitmap(reconstructed, "image-reconstructed")
            updatePipeline(
                mediaType = MediaType.IMAGE,
                stage = CompressionPipelineStage.CALCULATING_METRICS,
                progress = 0.96f,
                startedAtNanos = startedAtNanos,
            )
            val metrics = imageMetrics(sourceBitmap, reconstructed, latencyNanos)
            storeHistory(uri, outputUri, MediaType.IMAGE, metrics)
            updatePipeline(
                mediaType = MediaType.IMAGE,
                stage = CompressionPipelineStage.COMPLETED,
                progress = 1f,
                startedAtNanos = startedAtNanos,
                etaSeconds = 0,
            )
            CompressionResult(inputUri = uri, outputUri = outputUri, previewUri = outputUri, metrics = metrics)
        }.onFailure { throwable ->
            updatePipeline(
                mediaType = MediaType.IMAGE,
                stage = CompressionPipelineStage.FAILED,
                progress = 1f,
                startedAtNanos = startedAtNanos,
                message = throwable.message ?: "Image compression failed",
            )
        }
    }

    override suspend fun compressAudio(uri: Uri): Result<CompressionResult> = withContext(ioDispatcher) {
        val startedAtNanos = System.nanoTime()
        updatePipeline(
            mediaType = MediaType.AUDIO,
            stage = CompressionPipelineStage.VALIDATING_INPUT,
            progress = 0.05f,
            startedAtNanos = startedAtNanos,
        )
        runCatching {
            validator.validateAudio(uri).getOrThrow()
            updatePipeline(
                mediaType = MediaType.AUDIO,
                stage = CompressionPipelineStage.LOADING_SOURCE,
                progress = 0.15f,
                startedAtNanos = startedAtNanos,
            )
            val source = mediaIo.readWavMonoFloat(uri)
            val delegate = settingsStore.preferredDelegate.first()

            val reconstructedChunks = mutableListOf<Array<Array<FloatArray>>>()
            var outputWindow = 1
            lateinit var chunks: List<Array<Array<FloatArray>>>
            updatePipeline(
                mediaType = MediaType.AUDIO,
                stage = CompressionPipelineStage.PREPARING_MODEL,
                progress = 0.25f,
                startedAtNanos = startedAtNanos,
            )
            val latencyNanos = TfliteRunner(context, AUDIO_MODEL_ASSET, delegate).use { runner ->
                val contract = runner.contract()
                val inputWindow = parseAudioWindow(contract.input.shape)
                outputWindow = parseAudioWindow(contract.output.shape)
                require(inputWindow == outputWindow) {
                    "Audio model input/output windows must match. input=$inputWindow output=$outputWindow"
                }
                chunks = AudioTensorCodec.preprocess(source, inputWindow)
                val totalChunks = chunks.size.coerceAtLeast(1)
                measureNanoTime {
                    chunks.forEachIndexed { index, chunk ->
                        val out = AudioTensorCodec.outputWindow(outputWindow)
                        runner.run(chunk, out)
                        reconstructedChunks += out
                        val completedChunks = index + 1
                        val chunkProgress = completedChunks.toFloat() / totalChunks.toFloat()
                        updatePipeline(
                            mediaType = MediaType.AUDIO,
                            stage = CompressionPipelineStage.RUNNING_INFERENCE,
                            progress = 0.3f + (chunkProgress * 0.55f),
                            startedAtNanos = startedAtNanos,
                            completedUnits = completedChunks,
                            totalUnits = totalChunks,
                        )
                    }
                }
            }

            updatePipeline(
                mediaType = MediaType.AUDIO,
                stage = CompressionPipelineStage.SAVING_OUTPUT,
                progress = 0.9f,
                startedAtNanos = startedAtNanos,
            )
            val reconstructed = AudioTensorCodec.postprocess(reconstructedChunks, source.size, outputWindow)
            val outputUri = mediaIo.writeWavMonoFloat(reconstructed, "audio-reconstructed", sampleRate = REQUIRED_AUDIO_SAMPLE_RATE)
            updatePipeline(
                mediaType = MediaType.AUDIO,
                stage = CompressionPipelineStage.CALCULATING_METRICS,
                progress = 0.96f,
                startedAtNanos = startedAtNanos,
            )
            val metrics = audioMetrics(source, reconstructed, latencyNanos)
            storeHistory(uri, outputUri, MediaType.AUDIO, metrics)
            updatePipeline(
                mediaType = MediaType.AUDIO,
                stage = CompressionPipelineStage.COMPLETED,
                progress = 1f,
                startedAtNanos = startedAtNanos,
                etaSeconds = 0,
            )
            CompressionResult(inputUri = uri, outputUri = outputUri, previewUri = null, metrics = metrics)
        }.onFailure { throwable ->
            updatePipeline(
                mediaType = MediaType.AUDIO,
                stage = CompressionPipelineStage.FAILED,
                progress = 1f,
                startedAtNanos = startedAtNanos,
                message = throwable.message ?: "Audio compression failed",
            )
        }
    }

    override suspend fun compressVideo(uri: Uri): Result<CompressionResult> = withContext(ioDispatcher) {
        val startedAtNanos = System.nanoTime()
        updatePipeline(
            mediaType = MediaType.VIDEO,
            stage = CompressionPipelineStage.VALIDATING_INPUT,
            progress = 0.05f,
            startedAtNanos = startedAtNanos,
        )
        runCatching {
            validator.validateVideo(uri).getOrThrow()
            updatePipeline(
                mediaType = MediaType.VIDEO,
                stage = CompressionPipelineStage.PREPARING_MODEL,
                progress = 0.15f,
                startedAtNanos = startedAtNanos,
            )
            val delegate = settingsStore.preferredDelegate.first()
            val (sourceFrames, reconstructedFrames, latencyNanos) = TfliteRunner(context, VIDEO_MODEL_ASSET, delegate).use { runner ->
                val contract = runner.contract()
                val inputSpec = parseVideoSpec(contract.input.shape)
                val outputSpec = parseVideoSpec(contract.output.shape)
                require(inputSpec == outputSpec) {
                    "Video model input/output specs must match. input=$inputSpec output=$outputSpec"
                }
                updatePipeline(
                    mediaType = MediaType.VIDEO,
                    stage = CompressionPipelineStage.LOADING_SOURCE,
                    progress = 0.25f,
                    startedAtNanos = startedAtNanos,
                )
                val sourceFrames = mediaIo.readVideoFrames(uri, inputSpec.frames)
                require(sourceFrames.isNotEmpty()) { "No frames decoded from source video" }
                val clip = toVideoClip(
                    frames = sourceFrames,
                    inputSpec = inputSpec,
                    onFramePrepared = { completed, total ->
                        val prepProgress = completed.toFloat() / total.toFloat()
                        updatePipeline(
                            mediaType = MediaType.VIDEO,
                            stage = CompressionPipelineStage.PREPARING_MODEL,
                            progress = 0.3f + (prepProgress * 0.2f),
                            startedAtNanos = startedAtNanos,
                            completedUnits = completed,
                            totalUnits = total,
                        )
                    },
                )
                val out = VideoTensorCodec.outputClip(outputSpec.frames, outputSpec.height, outputSpec.width, outputSpec.channels)
                updatePipeline(
                    mediaType = MediaType.VIDEO,
                    stage = CompressionPipelineStage.RUNNING_INFERENCE,
                    progress = 0.55f,
                    startedAtNanos = startedAtNanos,
                )
                val latencyNanos = measureNanoTime {
                    runner.run(clip, out)
                }
                updatePipeline(
                    mediaType = MediaType.VIDEO,
                    stage = CompressionPipelineStage.RUNNING_INFERENCE,
                    progress = 0.82f,
                    startedAtNanos = startedAtNanos,
                    etaSeconds = 1,
                )
                Triple(sourceFrames, fromVideoClip(out), latencyNanos)
            }
            updatePipeline(
                mediaType = MediaType.VIDEO,
                stage = CompressionPipelineStage.SAVING_OUTPUT,
                progress = 0.92f,
                startedAtNanos = startedAtNanos,
            )
            val outputPreviewUri = mediaIo.saveFramesAsPreviewStrip(reconstructedFrames, "video-reconstructed")
            updatePipeline(
                mediaType = MediaType.VIDEO,
                stage = CompressionPipelineStage.CALCULATING_METRICS,
                progress = 0.97f,
                startedAtNanos = startedAtNanos,
            )
            val metrics = videoMetrics(sourceFrames, reconstructedFrames, latencyNanos)
            storeHistory(uri, outputPreviewUri, MediaType.VIDEO, metrics)
            updatePipeline(
                mediaType = MediaType.VIDEO,
                stage = CompressionPipelineStage.COMPLETED,
                progress = 1f,
                startedAtNanos = startedAtNanos,
                etaSeconds = 0,
            )
            CompressionResult(inputUri = uri, outputUri = outputPreviewUri, previewUri = outputPreviewUri, metrics = metrics)
        }.onFailure { throwable ->
            updatePipeline(
                mediaType = MediaType.VIDEO,
                stage = CompressionPipelineStage.FAILED,
                progress = 1f,
                startedAtNanos = startedAtNanos,
                message = throwable.message ?: "Video compression failed",
            )
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

    override fun observeCompressionPipeline(): Flow<CompressionPipelineStatus?> = pipelineUpdates.asStateFlow()

    override fun observeCompressionHistory(): Flow<List<CompressionHistoryItem>> {
        return historyDao.observeRecent().map { entities ->
            entities.map { entity ->
                CompressionHistoryItem(
                    id = entity.id,
                    mediaType = runCatching { MediaType.valueOf(entity.mediaType) }.getOrDefault(MediaType.IMAGE),
                    createdAtEpochMs = entity.createdAtEpochMs,
                    compressionRatio = entity.compressionRatio,
                    latencyMs = entity.latencyMs,
                    throughputItemsPerSec = CompressionMetricsCalculator.throughput(1, entity.latencyMs),
                    psnr = entity.psnr,
                    ssim = entity.ssim,
                    snr = entity.snr,
                )
            }
        }
    }

    override fun imageNormalizationMode(): Flow<ImageNormalizationMode> = settingsStore.imageNormalizationMode

    override suspend fun setImageNormalizationMode(mode: ImageNormalizationMode) {
        settingsStore.setImageNormalizationMode(mode)
    }

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

    private fun toVideoClip(
        frames: List<Bitmap>,
        inputSpec: VideoModelSpec,
        onFramePrepared: (completed: Int, total: Int) -> Unit = { _, _ -> },
    ): Array<Array<Array<Array<FloatArray>>>> {
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
            onFramePrepared(frameIndex + 1, selected.size)
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
        normalizationMode: ImageNormalizationMode,
        onProgress: (completedTiles: Int, totalTiles: Int) -> Unit,
    ): Bitmap {
        val width = sourceBitmap.width
        val height = sourceBitmap.height
        val tileWidth = inputSpec.width
        val tileHeight = inputSpec.height
        if (width <= tileWidth && height <= tileHeight) {
            val outputTensor = ImageTensorCodec.outputBuffer(tileHeight, tileWidth, inputSpec.channels)
            val inputTensor = ImageTensorCodec.preprocess(
                bitmap = sourceBitmap,
                targetWidth = tileWidth,
                targetHeight = tileHeight,
                channels = inputSpec.channels,
                normalizationMode = normalizationMode,
            )
            logTensorDiagnostics("single/input", inputTensor)
            runner.run(inputTensor, outputTensor)
            logTensorDiagnostics("single/output", outputTensor)
            onProgress(1, 1)
            val reconstructedTile = ImageTensorCodec.postprocess(outputTensor, normalizationMode = normalizationMode)
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
        val totalTiles = xStarts.size * yStarts.size
        var completedTiles = 0
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
                val inputTensor = ImageTensorCodec.preprocess(
                    bitmap = sourcePatch,
                    targetWidth = tileWidth,
                    targetHeight = tileHeight,
                    channels = inputSpec.channels,
                    normalizationMode = normalizationMode,
                )
                val outputTensor = ImageTensorCodec.outputBuffer(tileHeight, tileWidth, inputSpec.channels)
                if (startX == 0 && startY == 0) {
                    logTensorDiagnostics("tile[0,0]/input", inputTensor)
                }
                runner.run(inputTensor, outputTensor)
                if (startX == 0 && startY == 0) {
                    logTensorDiagnostics("tile[0,0]/output", outputTensor)
                }
                completedTiles += 1
                onProgress(completedTiles, totalTiles)
                val reconstructedTile = ImageTensorCodec.postprocess(outputTensor, normalizationMode = normalizationMode)
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
        val height = resolveModelDim(
            actual = shape[1],
            required = REQUIRED_IMAGE_TILE_SIZE,
            label = "image height",
        )
        val width = resolveModelDim(
            actual = shape[2],
            required = REQUIRED_IMAGE_TILE_SIZE,
            label = "image width",
        )
        require(shape[3] == 3) { "Image model must use RGB channels, got ${shape[3]}" }
        return ImageModelSpec(height = height, width = width, channels = shape[3])
    }

    private fun acquireImageRunner(delegate: InferenceDelegate): TfliteRunner {
        synchronized(imageRunnerLock) {
            val existing = cachedImageRunner
            if (existing != null && cachedImageRunnerDelegate == delegate) {
                return existing
            }
            existing?.close()
            return TfliteRunner(context.applicationContext, IMAGE_MODEL_ASSET, delegate).also {
                cachedImageRunner = it
                cachedImageRunnerDelegate = delegate
            }
        }
    }

    private fun logTensorDiagnostics(tagSuffix: String, tensor: Array<Array<Array<FloatArray>>>) {
        val stats = ImageTensorCodec.stats(tensor)
        val channelSummary = stats.channelMean.indices.joinToString(separator = "; ") { idx ->
            "c$idx[min=${stats.channelMin[idx]}, max=${stats.channelMax[idx]}, mean=${stats.channelMean[idx]}]"
        }
        Log.d(
            LOG_TAG,
            "imageTensor[$tagSuffix] shape=[1,${tensor[0].size},${tensor[0][0].size},${tensor[0][0][0].size}] min=${stats.min} max=${stats.max} $channelSummary",
        )
    }

    private fun parseAudioWindow(shape: IntArray): Int {
        require(shape.size == 3) { "Audio model tensor must be rank-3, got ${shape.contentToString()}" }
        require(shape[0] == 1) { "Audio model must use batch size 1, got ${shape[0]}" }
        require(shape[2] == 1) { "Audio model must be mono, got channels=${shape[2]}" }
        return resolveModelDim(
            actual = shape[1],
            required = REQUIRED_AUDIO_WINDOW_SAMPLES,
            label = "audio window",
        )
    }

    private fun parseVideoSpec(shape: IntArray): VideoModelSpec {
        require(shape.size == 5) { "Video model tensor must be rank-5, got ${shape.contentToString()}" }
        require(shape[0] == 1) { "Video model must use batch size 1, got ${shape[0]}" }
        val frames = resolveModelDim(
            actual = shape[1],
            required = REQUIRED_VIDEO_FRAMES,
            label = "video frames",
        )
        val height = resolveModelDim(
            actual = shape[2],
            required = REQUIRED_VIDEO_HEIGHT,
            label = "video height",
        )
        val width = resolveModelDim(
            actual = shape[3],
            required = REQUIRED_VIDEO_WIDTH,
            label = "video width",
        )
        require(shape[4] == REQUIRED_VIDEO_CHANNELS) {
            "Video model must use $REQUIRED_VIDEO_CHANNELS channels, got ${shape[4]}"
        }
        return VideoModelSpec(frames = frames, height = height, width = width, channels = shape[4])
    }

    private fun resolveModelDim(actual: Int, required: Int, label: String): Int {
        if (actual == required) return required
        if (actual == 1) {
            Log.w(LOG_TAG, "Model $label dimension is dynamic placeholder=1; using required=$required")
            return required
        }
        error("Model $label must be $required, got $actual")
    }

    private fun updatePipeline(
        mediaType: MediaType,
        stage: CompressionPipelineStage,
        progress: Float,
        startedAtNanos: Long,
        completedUnits: Int? = null,
        totalUnits: Int? = null,
        etaSeconds: Int? = null,
        message: String? = null,
    ) {
        val computedEta = etaSeconds ?: computeEtaSeconds(startedAtNanos, completedUnits, totalUnits)
        pipelineUpdates.value = CompressionPipelineStatus(
            mediaType = mediaType,
            stage = stage,
            progress = progress.coerceIn(0f, 1f),
            etaSeconds = computedEta,
            message = message,
        )
    }

    private fun computeEtaSeconds(startedAtNanos: Long, completedUnits: Int?, totalUnits: Int?): Int? {
        if (completedUnits == null || totalUnits == null || completedUnits <= 0 || completedUnits >= totalUnits) {
            return null
        }
        val elapsedSeconds = (System.nanoTime() - startedAtNanos) / 1_000_000_000.0
        if (elapsedSeconds <= 0.0) return null
        val rate = completedUnits / elapsedSeconds
        if (rate <= 0.0) return null
        val remaining = (totalUnits - completedUnits) / rate
        return remaining.toInt().coerceAtLeast(1)
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
        const val LOG_TAG = "VortexCompressionRepo"
        const val REQUIRED_IMAGE_TILE_SIZE = 32
        const val REQUIRED_AUDIO_SAMPLE_RATE = 16_000
        const val REQUIRED_AUDIO_WINDOW_SAMPLES = 8_000
        const val REQUIRED_VIDEO_FRAMES = 4
        const val REQUIRED_VIDEO_HEIGHT = 32
        const val REQUIRED_VIDEO_WIDTH = 32
        const val REQUIRED_VIDEO_CHANNELS = 3
        const val IMAGE_MODEL_ASSET = "models/image/production_model.tflite"
        const val AUDIO_MODEL_ASSET = "models/audio/audio_autoencoder.tflite"
        const val VIDEO_MODEL_ASSET = "models/video/video_autoencoder.tflite"
    }
}
