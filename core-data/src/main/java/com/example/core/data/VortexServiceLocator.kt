package com.example.core.data

import android.content.Context
import com.example.core.data.io.MediaFileValidator
import com.example.core.data.io.MediaIo
import com.example.core.data.local.InMemoryCompressionHistoryDao
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
        return VortexCompressionRepository(
            context = context,
            mediaIo = MediaIo(context),
            validator = MediaFileValidator(context.contentResolver),
            settingsStore = SettingsStore(context),
            historyDao = InMemoryCompressionHistoryDao(),
            reportParser = ModelReportParser(context),
        )
    }
}

