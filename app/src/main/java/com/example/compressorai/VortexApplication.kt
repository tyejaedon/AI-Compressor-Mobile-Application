package com.example.compressorai

import android.app.Application
import androidx.work.Configuration

class VortexApplication : Application(), Configuration.Provider {
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .build()
}

