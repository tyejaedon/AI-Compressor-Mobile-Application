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

enum class AicModality(val code: Byte) {
    IMAGE(1),
    AUDIO(2),
    VIDEO(3);

    companion object {
        fun fromCode(code: Byte): AicModality = entries.firstOrNull { it.code == code } ?: IMAGE
    }
}

enum class AicCompressionAlgo(val code: Byte) {
    RAW(0),
    DEFLATE(1),
    ZSTD(2);

    companion object {
        fun fromCode(code: Byte): AicCompressionAlgo = entries.firstOrNull { it.code == code } ?: DEFLATE
    }
}

data class AicHeader(
    val modality: AicModality,
    val version: Byte = 1,
    val bitDepth: Byte = 8,
    val compressionAlgo: AicCompressionAlgo = AicCompressionAlgo.DEFLATE,
    val originalWidth: Int,
    val originalHeight: Int,
    val latentH: Int,
    val latentW: Int,
    val latentChannels: Int,
    val payloadByteLength: Int,
)

data class CompressionSettings(
    val delegate: InferenceDelegate = InferenceDelegate.GPU,
    val bitDepth: Int = 8,
    val compressionAlgo: AicCompressionAlgo = AicCompressionAlgo.DEFLATE,
    val enableSuperResolution: Boolean = false,
    val threads: Int = 4,
    val normalizationMode: ImageNormalizationMode = ImageNormalizationMode.ZERO_ONE,
)

data class AicContainerInspection(
    val header: AicHeader,
    val fileSizeBytes: Long,
    val uncompressedLatentBytes: Long,
    val compressionRatio: Double,
    val storageSavingsPercent: Double,
    val estimatedBpp: Double?,
)

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
    val bitsPerPixel: Double? = null,
    val entropyBitsPerSymbol: Double? = null,
)

data class CompressionResult(
    val inputUri: Uri,
    val outputUri: Uri,
    val previewUri: Uri? = null,
    val metrics: CompressionMetrics,
    val originalSizeBytes: Long = 0L,
    val compressedSizeBytes: Long = 0L,
    val storageSavingsPercent: Double = 0.0,
    val bitsPerPixel: Double? = null,
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
    val bitsPerPixel: Double? = null,
    val originalSizeBytes: Long = 0L,
    val compressedSizeBytes: Long = 0L,
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

    suspend fun compressImageToAic(
        uri: Uri,
        settings: CompressionSettings = CompressionSettings(),
    ): Result<CompressionResult>

    suspend fun decompressAicFile(
        aicUri: Uri,
        enableSuperResolution: Boolean = false,
    ): Result<CompressionResult>

    suspend fun inspectAicFile(aicUri: Uri): Result<AicContainerInspection>

    suspend fun compressAudio(uri: Uri): Result<CompressionResult>

    suspend fun compressAudioToAic(
        uri: Uri,
        settings: CompressionSettings = CompressionSettings(),
    ): Result<CompressionResult>

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

