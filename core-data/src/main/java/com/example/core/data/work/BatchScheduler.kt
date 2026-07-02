package com.example.core.data.work

import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.core.domain.BatchItem
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BatchScheduler @Inject constructor(
    private val workManager: WorkManager,
) {
    fun schedule(items: List<BatchItem>) {
        val data = Data.Builder()
            .putStringArray(BatchCompressionWorker.KEY_IDS, items.map { it.id }.toTypedArray())
            .putStringArray(BatchCompressionWorker.KEY_URIS, items.map { it.sourceUri.toString() }.toTypedArray())
            .putStringArray(BatchCompressionWorker.KEY_TYPES, items.map { it.mediaType.name }.toTypedArray())
            .build()

        val request = OneTimeWorkRequestBuilder<BatchCompressionWorker>()
            .setInputData(data)
            .build()

        workManager.enqueue(request)
    }
}

