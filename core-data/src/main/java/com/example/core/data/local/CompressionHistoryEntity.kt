package com.example.core.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "compression_history")
data class CompressionHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceUri: String,
    val outputUri: String,
    val mediaType: String,
    val latencyMs: Double,
    val compressionRatio: Double,
    val psnr: Double?,
    val ssim: Double?,
    val snr: Double?,
    val createdAtEpochMs: Long,
)

