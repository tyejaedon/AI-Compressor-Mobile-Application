package com.example.core.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CompressionHistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: CompressionHistoryEntity)

    @Query("SELECT * FROM compression_history ORDER BY createdAtEpochMs DESC LIMIT 100")
    fun observeRecent(): Flow<List<CompressionHistoryEntity>>
}

