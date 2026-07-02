package com.example.core.data.queue

import android.net.Uri
import com.example.core.domain.BatchItem
import com.example.core.domain.BatchStatus
import com.example.core.domain.MediaType
import io.mockk.mockk
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchQueueManagerTest {

    @Test
    fun `enqueue marks item success when processor succeeds`() = runBlocking {
        var latest = emptyList<com.example.core.domain.BatchProgress>()
        val manager = BatchQueueManager(
            emit = { latest = it },
            processItem = { Result.success(Unit) },
        )

        manager.enqueue(listOf(testItem("ok-1")))

        assertEquals(BatchStatus.SUCCESS, latest.single().status)
        assertEquals(1f, latest.single().progress)
    }

    @Test
    fun `retry moves failed item to success`() = runBlocking {
        var latest = emptyList<com.example.core.domain.BatchProgress>()
        var failuresLeft = 1
        val manager = BatchQueueManager(
            emit = { latest = it },
            processItem = {
                if (failuresLeft-- > 0) Result.failure(IllegalStateException("first attempt fails"))
                else Result.success(Unit)
            },
        )

        manager.enqueue(listOf(testItem("retry-1")))
        assertEquals(BatchStatus.FAILED, latest.single().status)

        manager.retry("retry-1")

        assertEquals(BatchStatus.SUCCESS, latest.single().status)
    }

    @Test
    fun `cancel marks queued or running item as canceled`() = runBlocking {
        var latest = emptyList<com.example.core.domain.BatchProgress>()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)

        val manager = BatchQueueManager(
            emit = { latest = it },
            processItem = {
                started.countDown()
                release.await(1, TimeUnit.SECONDS)
                Result.success(Unit)
            },
        )

        val job = async(Dispatchers.Default) {
            manager.enqueue(listOf(testItem("cancel-1")))
        }

        assertTrue(started.await(1, TimeUnit.SECONDS))
        manager.cancel("cancel-1")
        release.countDown()
        job.await()

        assertEquals(BatchStatus.CANCELED, latest.single().status)
        assertEquals("Canceled by user", latest.single().message)
    }

    private fun testItem(id: String): BatchItem {
        return BatchItem(
            id = id,
            mediaType = MediaType.IMAGE,
            sourceUri = mockk<Uri>(relaxed = true),
        )
    }
}




