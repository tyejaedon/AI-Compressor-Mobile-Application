package com.example.core.data.queue

import com.example.core.domain.BatchItem
import com.example.core.domain.BatchProgress
import com.example.core.domain.BatchStatus

class BatchQueueManager(
    private val emit: (List<BatchProgress>) -> Unit,
    private val processItem: suspend (BatchItem) -> Result<Unit>,
) {
    private val queue = linkedMapOf<String, BatchProgress>()
    private val itemsById = mutableMapOf<String, BatchItem>()
    private val canceled = mutableSetOf<String>()

    suspend fun enqueue(items: List<BatchItem>) {
        items.forEach { item ->
            itemsById[item.id] = item
            queue[item.id] = BatchProgress(id = item.id, status = BatchStatus.QUEUED, progress = 0f)
        }
        emitSnapshot()
        items.forEach { processSingle(it) }
    }

    suspend fun retry(id: String) {
        val item = itemsById[id] ?: return
        val current = queue[id] ?: return
        if (current.status != BatchStatus.FAILED && current.status != BatchStatus.CANCELED) return

        canceled.remove(id)
        queue[id] = current.copy(status = BatchStatus.QUEUED, message = null, progress = 0f)
        emitSnapshot()
        processSingle(item)
    }

    fun cancel(id: String) {
        canceled += id
        val current = queue[id] ?: return
        if (current.status == BatchStatus.SUCCESS || current.status == BatchStatus.FAILED) return
        queue[id] = current.copy(status = BatchStatus.CANCELED, message = "Canceled by user", progress = 1f)
        emitSnapshot()
    }

    private suspend fun processSingle(item: BatchItem) {
        val id = item.id
        if (isCanceled(id)) {
            markCanceled(id)
            return
        }

        queue[id] = queue[id]?.copy(status = BatchStatus.RUNNING, message = null, progress = 0.1f)
            ?: BatchProgress(id = id, status = BatchStatus.RUNNING, progress = 0.1f)
        emitSnapshot()

        val result = processItem(item)
        if (isCanceled(id)) {
            markCanceled(id)
            return
        }

        queue[id] = if (result.isSuccess) {
            queue[id]?.copy(status = BatchStatus.SUCCESS, progress = 1f)
                ?: BatchProgress(id = id, status = BatchStatus.SUCCESS, progress = 1f)
        } else {
            queue[id]?.copy(
                status = BatchStatus.FAILED,
                progress = 1f,
                message = result.exceptionOrNull()?.message ?: "Batch item failed",
            ) ?: BatchProgress(id = id, status = BatchStatus.FAILED, progress = 1f, message = "Batch item failed")
        }
        emitSnapshot()
    }

    private fun isCanceled(id: String): Boolean = id in canceled

    private fun markCanceled(id: String) {
        queue[id] = queue[id]?.copy(status = BatchStatus.CANCELED, message = "Canceled by user", progress = 1f)
            ?: BatchProgress(id = id, status = BatchStatus.CANCELED, message = "Canceled by user", progress = 1f)
        emitSnapshot()
    }

    private fun emitSnapshot() {
        emit(queue.values.toList())
    }
}

