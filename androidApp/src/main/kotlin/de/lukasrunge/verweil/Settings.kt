package de.lukasrunge.verweil

import android.content.Context
import android.os.Build
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.longPreferencesKey
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
    /** Writes every raw sensor event to a daily JSONL file for replay-based tuning. On in debug builds. */
    val recordRawEvents: Boolean = BuildConfig.DEBUG,
    /** Names stays in the timeline with the phone's geocoder, which sends their coordinates to its provider. */
    val lookUpPlaceNames: Boolean = true,
    /** The user wants tracking on; it resumes after a reboot, an app update, or the app being killed. */
    val trackingEnabled: Boolean = false,
    /** The user went through the permission steps once; later gaps show on the main screen instead. */
    val setupDone: Boolean = false,
) {
    val isConfigured: Boolean get() = serverUrl.isNotBlank() && apiKey.isNotBlank()
}

/** How the last uploads went. Stored, so the main screen still knows after the app was killed. */
data class UploadState(
    val lastSuccessMs: Long? = null,
    /** The last failure, cleared by the next successful upload. */
    val error: String? = null,
    /** Dawarich refused the API key; nothing goes out until the user signs in again. */
    val authRefused: Boolean = false,
)

class Settings(private val context: Context) {
    private object Keys {
        val serverUrl = stringPreferencesKey("server_url")
        val apiKey = stringPreferencesKey("api_key")
        val email = stringPreferencesKey("email")
        val customHeaders = stringPreferencesKey("custom_headers")
        val deviceId = stringPreferencesKey("device_id")
        val recordRawEvents = booleanPreferencesKey("record_raw_events")
        val lookUpPlaceNames = booleanPreferencesKey("look_up_place_names")
        val trackingEnabled = booleanPreferencesKey("tracking_enabled")
        val setupDone = booleanPreferencesKey("setup_done")
        val wifiSalt = stringPreferencesKey("wifi_salt")
        val lastUploadMs = longPreferencesKey("last_upload_ms")
        val uploadError = stringPreferencesKey("upload_error")
        val uploadAuthRefused = booleanPreferencesKey("upload_auth_refused")
        val interruptionNotified = booleanPreferencesKey("interruption_notified")
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
            lookUpPlaceNames = prefs[Keys.lookUpPlaceNames] ?: defaults.lookUpPlaceNames,
            trackingEnabled = prefs[Keys.trackingEnabled] ?: defaults.trackingEnabled,
            setupDone = prefs[Keys.setupDone] ?: defaults.setupDone,
        )
    }

    val uploadState: Flow<UploadState> = context.dataStore.data.map { prefs ->
        UploadState(
            lastSuccessMs = prefs[Keys.lastUploadMs],
            error = prefs[Keys.uploadError],
            authRefused = prefs[Keys.uploadAuthRefused] ?: false,
        )
    }

    suspend fun current(): SettingsValues = values.first()

    suspend fun setTrackingEnabled(enabled: Boolean) = edit { it[Keys.trackingEnabled] = enabled }

    suspend fun setSetupDone() = edit { it[Keys.setupDone] = true }

    suspend fun setRecordRawEvents(enabled: Boolean) = edit { it[Keys.recordRawEvents] = enabled }

    suspend fun setLookUpPlaceNames(enabled: Boolean) = edit { it[Keys.lookUpPlaceNames] = enabled }

    /** The name Dawarich shows for this phone's points; blank goes back to the model name. */
    suspend fun setDeviceId(deviceId: String) = edit {
        if (deviceId.isBlank()) it.remove(Keys.deviceId) else it[Keys.deviceId] = deviceId.trim()
    }

    suspend fun setCustomHeaders(headers: Map<String, String>) = edit { it[Keys.customHeaders] = formatHeaderLines(headers) }

    suspend fun uploadSucceeded(sent: Int, nowMs: Long) = edit {
        if (sent > 0) it[Keys.lastUploadMs] = nowMs
        it.remove(Keys.uploadError)
        it[Keys.uploadAuthRefused] = false
    }

    suspend fun uploadFailed(error: String, authRefused: Boolean) = edit {
        it[Keys.uploadError] = error
        it[Keys.uploadAuthRefused] = authRefused
    }

    /** Whether the user already heard that tracking stopped unexpectedly; reset once it runs again. */
    suspend fun interruptionNotified(): Boolean = context.dataStore.data.first()[Keys.interruptionNotified] ?: false

    suspend fun setInterruptionNotified(notified: Boolean) = edit { it[Keys.interruptionNotified] = notified }

    /**
     * Random per install, mixed into every BSSID hash. Without it, hashed BSSIDs in a shared recording
     * could be looked up in public Wi-Fi maps.
     */
    suspend fun wifiSalt(): String {
        var salt = ""
        edit { prefs ->
            salt = prefs[Keys.wifiSalt] ?: UUID.randomUUID().toString().also { prefs[Keys.wifiSalt] = it }
        }
        return salt
    }

    suspend fun signIn(credentials: DawarichCredentials, customHeaders: Map<String, String>) = edit { prefs ->
        prefs[Keys.serverUrl] = credentials.serverUrl
        prefs[Keys.apiKey] = credentials.apiKey
        prefs[Keys.email] = credentials.email
        prefs[Keys.customHeaders] = formatHeaderLines(customHeaders)
        prefs.remove(Keys.uploadError)
        prefs[Keys.uploadAuthRefused] = false
    }

    /** Forgets the server and key. Queued points stay, unless the caller clears the outbox too. */
    suspend fun signOut() = edit { prefs ->
        prefs.remove(Keys.serverUrl)
        prefs.remove(Keys.apiKey)
        prefs.remove(Keys.email)
        prefs.remove(Keys.customHeaders)
        prefs.remove(Keys.lastUploadMs)
        prefs.remove(Keys.uploadError)
        prefs.remove(Keys.uploadAuthRefused)
    }

    private suspend fun edit(block: (MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }
}
