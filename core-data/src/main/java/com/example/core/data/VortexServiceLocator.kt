package com.example.core.data

import android.content.Context
import androidx.room.Room
import com.example.core.data.io.MediaFileValidator
import com.example.core.data.io.MediaIo
import com.example.core.data.local.VortexDatabase
import com.example.core.data.preferences.SettingsStore
import com.example.core.domain.CompressionRepository
import com.example.core.ml.ModelReportParser

object VortexServiceLocator {
    @Volatile
    private var repository: CompressionRepository? = null

    fun repository(context: Context): CompressionRepository {
        return repository ?: synchronized(this) {
            repository ?: buildRepository(context.applicationContext).also { repository = it }
        }
    }

    private fun buildRepository(context: Context): CompressionRepository {
        val database = Room.databaseBuilder(context, VortexDatabase::class.java, "vortex.db").build()
        return VortexCompressionRepository(
            context = context,
            mediaIo = MediaIo(context),
            validator = MediaFileValidator(context.contentResolver),
            settingsStore = SettingsStore(context),
            historyDao = database.compressionHistoryDao(),
            reportParser = ModelReportParser(context),
        )
    }
}

