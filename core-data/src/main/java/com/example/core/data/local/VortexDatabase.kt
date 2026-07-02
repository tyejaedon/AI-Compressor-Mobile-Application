package com.example.core.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [CompressionHistoryEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class VortexDatabase : RoomDatabase() {
    abstract fun compressionHistoryDao(): CompressionHistoryDao
}

