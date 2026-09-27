package de.lukasrunge.verweil

import android.content.Context
import android.os.Build
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

data class SettingsValues(
    val serverUrl: String = "",
    val apiKey: String = "",
    val deviceId: String = Build.MODEL,
    /** Writes every raw sensor event to a daily JSONL file for replay-based tuning. */
    val recordRawEvents: Boolean = true,
) {
    val isConfigured: Boolean get() = serverUrl.isNotBlank() && apiKey.isNotBlank()
}

class Settings(private val context: Context) {
    private object Keys {
        val serverUrl = stringPreferencesKey("server_url")
        val apiKey = stringPreferencesKey("api_key")
        val deviceId = stringPreferencesKey("device_id")
        val recordRawEvents = booleanPreferencesKey("record_raw_events")
    }

    val values: Flow<SettingsValues> = context.dataStore.data.map { prefs ->
        val defaults = SettingsValues()
        SettingsValues(
            serverUrl = prefs[Keys.serverUrl] ?: defaults.serverUrl,
            apiKey = prefs[Keys.apiKey] ?: defaults.apiKey,
            deviceId = prefs[Keys.deviceId] ?: defaults.deviceId,
            recordRawEvents = prefs[Keys.recordRawEvents] ?: defaults.recordRawEvents,
        )
    }

    suspend fun current(): SettingsValues = values.first()

    suspend fun save(values: SettingsValues) {
        context.dataStore.edit { prefs ->
            prefs[Keys.serverUrl] = values.serverUrl.trim()
            prefs[Keys.apiKey] = values.apiKey.trim()
            prefs[Keys.deviceId] = values.deviceId.trim()
            prefs[Keys.recordRawEvents] = values.recordRawEvents
        }
    }
}
