package com.example.core.data.di

import android.content.Context
import androidx.work.WorkManager
import androidx.room.Room
import com.example.core.data.VortexCompressionRepository
import com.example.core.data.io.MediaFileValidator
import com.example.core.data.io.MediaIo
import com.example.core.data.local.CompressionHistoryDao
import com.example.core.data.local.VortexDatabase
import com.example.core.data.preferences.SettingsStore
import com.example.core.domain.CompressionRepository
import com.example.core.ml.ModelReportParser
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DataBindModule {
    @Binds
    @Singleton
    abstract fun bindCompressionRepository(repository: VortexCompressionRepository): CompressionRepository
}

@Module
@InstallIn(SingletonComponent::class)
object DataProvideModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): VortexDatabase {
        return Room.databaseBuilder(context, VortexDatabase::class.java, "vortex.db").build()
    }

    @Provides
    fun provideCompressionHistoryDao(db: VortexDatabase): CompressionHistoryDao = db.compressionHistoryDao()

    @Provides
    @Singleton
    fun provideSettingsStore(@ApplicationContext context: Context): SettingsStore = SettingsStore(context)

    @Provides
    @Singleton
    fun provideMediaIo(@ApplicationContext context: Context): MediaIo = MediaIo(context)

    @Provides
    @Singleton
    fun provideMediaValidator(@ApplicationContext context: Context): MediaFileValidator {
        return MediaFileValidator(context.contentResolver)
    }

    @Provides
    @Singleton
    fun provideModelReportParser(@ApplicationContext context: Context): ModelReportParser = ModelReportParser(context)

    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager = WorkManager.getInstance(context)
}


