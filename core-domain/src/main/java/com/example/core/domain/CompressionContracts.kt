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
}

