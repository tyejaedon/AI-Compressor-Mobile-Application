package com.example.core.data.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.core.data.VortexServiceLocator
import com.example.core.domain.BatchItem
import com.example.core.domain.MediaType

class BatchCompressionWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ids = inputData.getStringArray(KEY_IDS).orEmpty()
        val uris = inputData.getStringArray(KEY_URIS).orEmpty()
        val types = inputData.getStringArray(KEY_TYPES).orEmpty()

        if (ids.size != uris.size || uris.size != types.size) return Result.failure()

        val items = ids.indices.map { index ->
            BatchItem(
                id = ids[index],
                sourceUri = android.net.Uri.parse(uris[index]),
                mediaType = runCatching { MediaType.valueOf(types[index]) }.getOrDefault(MediaType.IMAGE),
            )
        }
        val repository = VortexServiceLocator.repository(applicationContext)
        repository.enqueueBatch(items)
        return Result.success()
    }

    companion object {
        const val KEY_IDS = "ids"
        const val KEY_URIS = "uris"
        const val KEY_TYPES = "types"
    }
}

