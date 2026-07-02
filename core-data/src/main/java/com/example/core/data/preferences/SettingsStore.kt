package com.example.core.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.core.domain.InferenceDelegate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("vortex_settings")

class SettingsStore(
    private val context: Context,
) {
    private val key = stringPreferencesKey("preferred_delegate")

    val preferredDelegate: Flow<InferenceDelegate> = context.dataStore.data.map { prefs ->
        runCatching { InferenceDelegate.valueOf(prefs[key] ?: InferenceDelegate.CPU.name) }
            .getOrDefault(InferenceDelegate.CPU)
    }

    suspend fun setPreferredDelegate(delegate: InferenceDelegate) {
        context.dataStore.edit { prefs ->
            prefs[key] = delegate.name
        }
    }
}

