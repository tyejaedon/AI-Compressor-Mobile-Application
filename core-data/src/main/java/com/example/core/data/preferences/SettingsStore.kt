package com.example.core.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.core.domain.ImageNormalizationMode
import com.example.core.domain.InferenceDelegate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("vortex_settings")

class SettingsStore(
    private val context: Context,
) {
    private val delegateKey = stringPreferencesKey("preferred_delegate")
    private val imageNormalizationKey = stringPreferencesKey("image_normalization_mode")

    val preferredDelegate: Flow<InferenceDelegate> = context.dataStore.data.map { prefs ->
        runCatching { InferenceDelegate.valueOf(prefs[delegateKey] ?: InferenceDelegate.CPU.name) }
            .getOrDefault(InferenceDelegate.CPU)
    }

    val imageNormalizationMode: Flow<ImageNormalizationMode> = context.dataStore.data.map { prefs ->
        runCatching { ImageNormalizationMode.valueOf(prefs[imageNormalizationKey] ?: ImageNormalizationMode.ZERO_ONE.name) }
            .getOrDefault(ImageNormalizationMode.ZERO_ONE)
    }

    suspend fun setPreferredDelegate(delegate: InferenceDelegate) {
        context.dataStore.edit { prefs ->
            prefs[delegateKey] = delegate.name
        }
    }

    suspend fun setImageNormalizationMode(mode: ImageNormalizationMode) {
        context.dataStore.edit { prefs ->
            prefs[imageNormalizationKey] = mode.name
        }
    }
}

