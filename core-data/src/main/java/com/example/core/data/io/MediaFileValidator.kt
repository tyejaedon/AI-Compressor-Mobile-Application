package com.example.core.data.io

import android.content.ContentResolver
import android.net.Uri

class MediaFileValidator(
    private val contentResolver: ContentResolver,
) {
    fun validateImage(uri: Uri): Result<Unit> = validate(uri, setOf("image/jpeg", "image/png", "image/webp"))

    fun validateAudio(uri: Uri): Result<Unit> = validate(uri, setOf("audio/wav", "audio/x-wav", "audio/wave"))

    fun validateVideo(uri: Uri): Result<Unit> = validate(uri, setOf("video/mp4", "video/3gpp", "video/webm"))

    private fun validate(uri: Uri, allowedMimeTypes: Set<String>): Result<Unit> {
        val type = contentResolver.getType(uri)
            ?: return Result.failure(IllegalArgumentException("Unable to determine file type"))
        if (type !in allowedMimeTypes) {
            return Result.failure(IllegalArgumentException("Unsupported file type: $type"))
        }
        return Result.success(Unit)
    }
}

