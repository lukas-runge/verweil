package de.lukasrunge.verweil

import android.content.Context
import android.os.Build
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import de.lukasrunge.verweil.core.dawarich.DawarichCredentials
import de.lukasrunge.verweil.core.dawarich.formatHeaderLines
import de.lukasrunge.verweil.core.dawarich.parseHeaderLines
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.dataStore by preferencesDataStore(name = "settings")

data class SettingsValues(
    val serverUrl: String = "",
    val apiKey: String = "",
    /** Account the API key belongs to, shown on the main screen. */
    val email: String = "",
    /** Sent with every request, for servers behind an authenticating reverse proxy. */
    val customHeaders: Map<String, String> = emptyMap(),
    val deviceId: String = Build.MODEL,
    /** Writes every raw sensor event to a daily JSONL file for replay-based tuning. */
    val recordRawEvents: Boolean = true,
    /** The user wants tracking on; it resumes after a reboot or an app update. */
    val trackingEnabled: Boolean = false,
) {
    val isConfigured: Boolean get() = serverUrl.isNotBlank() && apiKey.isNotBlank()
}

class Settings(private val context: Context) {
    private object Keys {
        val serverUrl = stringPreferencesKey("server_url")
        val apiKey = stringPreferencesKey("api_key")
        val email = stringPreferencesKey("email")
        val customHeaders = stringPreferencesKey("custom_headers")
        val deviceId = stringPreferencesKey("device_id")
        val recordRawEvents = booleanPreferencesKey("record_raw_events")
        val trackingEnabled = booleanPreferencesKey("tracking_enabled")
        val wifiSalt = stringPreferencesKey("wifi_salt")
    }

    val values: Flow<SettingsValues> = context.dataStore.data.map { prefs ->
        val defaults = SettingsValues()
        SettingsValues(
            serverUrl = prefs[Keys.serverUrl] ?: defaults.serverUrl,
            apiKey = prefs[Keys.apiKey] ?: defaults.apiKey,
            email = prefs[Keys.email] ?: defaults.email,
            customHeaders = prefs[Keys.customHeaders]?.let(::parseHeaderLines) ?: defaults.customHeaders,
            deviceId = prefs[Keys.deviceId] ?: defaults.deviceId,
            recordRawEvents = prefs[Keys.recordRawEvents] ?: defaults.recordRawEvents,
            trackingEnabled = prefs[Keys.trackingEnabled] ?: defaults.trackingEnabled,
        )
    }

    suspend fun current(): SettingsValues = values.first()

    suspend fun setTrackingEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.trackingEnabled] = enabled }
    }

    /**
     * Random per install, mixed into every BSSID hash. Without it, hashed BSSIDs in a shared recording
     * could be looked up in public Wi-Fi maps.
     */
    suspend fun wifiSalt(): String {
        var salt = ""
        context.dataStore.edit { prefs ->
            salt = prefs[Keys.wifiSalt] ?: UUID.randomUUID().toString().also { prefs[Keys.wifiSalt] = it }
        }
        return salt
    }

    suspend fun signIn(credentials: DawarichCredentials, customHeaders: Map<String, String>) {
        context.dataStore.edit { prefs ->
            prefs[Keys.serverUrl] = credentials.serverUrl
            prefs[Keys.apiKey] = credentials.apiKey
            prefs[Keys.email] = credentials.email
            prefs[Keys.customHeaders] = formatHeaderLines(customHeaders)
        }
    }

    /** Forgets the server and key. Queued points stay and go to whichever server is connected next. */
    suspend fun signOut() {
        context.dataStore.edit { prefs ->
            prefs.remove(Keys.serverUrl)
            prefs.remove(Keys.apiKey)
            prefs.remove(Keys.email)
            prefs.remove(Keys.customHeaders)
        }
    }
}
