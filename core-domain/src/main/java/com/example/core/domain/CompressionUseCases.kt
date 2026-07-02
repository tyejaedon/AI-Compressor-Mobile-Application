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

