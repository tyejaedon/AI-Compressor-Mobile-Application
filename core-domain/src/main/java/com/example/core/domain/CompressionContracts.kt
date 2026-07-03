package com.example.core.domain

import android.net.Uri
import kotlinx.coroutines.flow.Flow

enum class MediaType {
    IMAGE,
    AUDIO,
    VIDEO,
}

enum class InferenceDelegate {
    CPU,
    NNAPI,
    GPU,
}

enum class ImageNormalizationMode {
    ZERO_ONE,
    NEG_ONE_ONE,
}

data class ModelMetadata(
    val mediaType: MediaType,
    val modelAssetPath: String,
    val reportSummary: String,
    val inputShape: IntArray,
    val outputShape: IntArray,
    val inputDType: String,
    val outputDType: String,
)

data class CompressionMetrics(
    val psnr: Double? = null,
    val ssim: Double? = null,
    val snr: Double? = null,
    val compressionRatio: Double,
    val latencyMs: Double,
    val throughputItemsPerSec: Double,
)

data class CompressionResult(
    val inputUri: Uri,
    val outputUri: Uri,
    val previewUri: Uri? = null,
    val metrics: CompressionMetrics,
)

data class CompressionHistoryItem(
    val id: Long,
    val mediaType: MediaType,
    val createdAtEpochMs: Long,
    val compressionRatio: Double,
    val latencyMs: Double,
    val throughputItemsPerSec: Double,
    val psnr: Double? = null,
    val ssim: Double? = null,
    val snr: Double? = null,
)

enum class CompressionPipelineStage {
    VALIDATING_INPUT,
    LOADING_SOURCE,
    PREPARING_MODEL,
    RUNNING_INFERENCE,
    SAVING_OUTPUT,
    CALCULATING_METRICS,
    COMPLETED,
    FAILED,
}

data class CompressionPipelineStatus(
    val mediaType: MediaType,
    val stage: CompressionPipelineStage,
    val progress: Float,
    val etaSeconds: Int? = null,
    val message: String? = null,
)

data class BatchItem(
    val id: String,
    val mediaType: MediaType,
    val sourceUri: Uri,
)

enum class BatchStatus {
    QUEUED,
    RUNNING,
    SUCCESS,
    FAILED,
    CANCELED,
}

data class BatchProgress(
    val id: String,
    val status: BatchStatus,
    val message: String? = null,
    val progress: Float = 0f,
)

interface CompressionRepository {
    suspend fun modelMetadata(): List<ModelMetadata>

    suspend fun compressImage(uri: Uri): Result<CompressionResult>

    suspend fun compressAudio(uri: Uri): Result<CompressionResult>

    suspend fun compressVideo(uri: Uri): Result<CompressionResult>

    suspend fun enqueueBatch(items: List<BatchItem>)

    suspend fun retryBatch(id: String)

    suspend fun cancelBatch(id: String)

    fun batchProgress(): Flow<List<BatchProgress>>

    fun observeCompressionPipeline(): Flow<CompressionPipelineStatus?>

    fun observeCompressionHistory(): Flow<List<CompressionHistoryItem>>

    fun imageNormalizationMode(): Flow<ImageNormalizationMode>

    suspend fun setImageNormalizationMode(mode: ImageNormalizationMode)
}

