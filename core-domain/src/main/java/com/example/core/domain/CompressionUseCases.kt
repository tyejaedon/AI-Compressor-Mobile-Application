package com.example.core.domain

import android.net.Uri
import javax.inject.Inject

class CompressImageUseCase @Inject constructor(
    private val repository: CompressionRepository,
) {
    suspend operator fun invoke(uri: Uri) = repository.compressImage(uri)
}

class CompressAudioUseCase @Inject constructor(
    private val repository: CompressionRepository,
) {
    suspend operator fun invoke(uri: Uri) = repository.compressAudio(uri)
}

class CompressVideoUseCase @Inject constructor(
    private val repository: CompressionRepository,
) {
    suspend operator fun invoke(uri: Uri) = repository.compressVideo(uri)
}

class LoadModelMetadataUseCase @Inject constructor(
    private val repository: CompressionRepository,
) {
    suspend operator fun invoke() = repository.modelMetadata()
}

class ObserveBatchProgressUseCase @Inject constructor(
    private val repository: CompressionRepository,
) {
    operator fun invoke() = repository.batchProgress()
}

class CompressImageToAicUseCase @Inject constructor(
    private val repository: CompressionRepository,
) {
    suspend operator fun invoke(uri: Uri, settings: CompressionSettings = CompressionSettings()) =
        repository.compressImageToAic(uri, settings)
}

class DecompressAicFileUseCase @Inject constructor(
    private val repository: CompressionRepository,
) {
    suspend operator fun invoke(aicUri: Uri, enableSuperResolution: Boolean = false) =
        repository.decompressAicFile(aicUri, enableSuperResolution)
}

class InspectAicFileUseCase @Inject constructor(
    private val repository: CompressionRepository,
) {
    suspend operator fun invoke(aicUri: Uri) = repository.inspectAicFile(aicUri)
}

class CompressAudioToAicUseCase @Inject constructor(
    private val repository: CompressionRepository,
) {
    suspend operator fun invoke(uri: Uri, settings: CompressionSettings = CompressionSettings()) =
        repository.compressAudioToAic(uri, settings)
}


