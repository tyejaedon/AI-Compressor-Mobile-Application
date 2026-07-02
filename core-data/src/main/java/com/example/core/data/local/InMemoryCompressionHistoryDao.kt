package com.example.core.data.local

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class InMemoryCompressionHistoryDao : CompressionHistoryDao {
    private val items = MutableStateFlow<List<CompressionHistoryEntity>>(emptyList())

    override suspend fun insert(entity: CompressionHistoryEntity) {
        val next = listOf(entity.copy(id = System.nanoTime())) + items.value
        items.value = next.take(100)
    }

    override fun observeRecent(): Flow<List<CompressionHistoryEntity>> {
        return items.map { list -> list.sortedByDescending { it.createdAtEpochMs }.take(100) }
    }
}

