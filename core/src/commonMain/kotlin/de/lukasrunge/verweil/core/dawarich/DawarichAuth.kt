package de.lukasrunge.verweil.core.dawarich

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/** Dawarich Cloud, the server the official apps sign in to unless you pick "self-hosted". */
const val DAWARICH_CLOUD_URL = "https://my.dawarich.app"

/** What the app keeps after signing in: everything later uploads need. */
data class DawarichCredentials(
    val serverUrl: String,
    val apiKey: String,
    val email: String,
)

sealed interface LoginResult {
    data class SignedIn(val credentials: DawarichCredentials) : LoginResult

    /** The account has two-factor authentication on; answer with [DawarichAuth.submitOtp] within [ttlSeconds]. */
    data class TwoFactorRequired(val challengeToken: String, val ttlSeconds: Int) : LoginResult
}

/**
 * Sign-in the way the official Dawarich apps do it, with the server's mobile auth API:
 * email and password through `POST /api/v1/auth/login` (plus the OTP challenge for 2FA accounts),
 * or an existing API key from the QR code or manual setup, checked through `GET /api/v1/users/me`.
 * Both paths end with the user's API key, which is all the upload APIs need.
 *
 * [customHeaders] go along with every request, for servers behind an authenticating reverse proxy.
 */
class DawarichAuth(
    serverUrl: String,
    engine: HttpClient,
    customHeaders: Map<String, String> = emptyMap(),
) {
    val serverUrl = normalizeServerUrl(serverUrl)

    private val http = engine.dawarichConfig(customHeaders)

    suspend fun login(email: String, password: String): LoginResult {
        val response = http.post("$serverUrl/api/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(email.trim(), password))
        }
        response.requireAuthSuccess()
        // 202 instead of 200: the password was right, the second factor is still missing.
        if (response.status == HttpStatusCode.Accepted) {
            val challenge = response.body<OtpChallenge>()
            return LoginResult.TwoFactorRequired(challenge.challengeToken, challenge.ttl)
        }
        return LoginResult.SignedIn(response.body<AuthSuccess>().toCredentials())
    }

    suspend fun submitOtp(challengeToken: String, code: String): DawarichCredentials {
        val response = http.post("$serverUrl/api/v1/auth/otp_challenge") {
            contentType(ContentType.Application.Json)
            setBody(OtpRequest(challengeToken, code.trim()))
        }
        response.requireAuthSuccess()
        return response.body<AuthSuccess>().toCredentials()
    }

    /** Checks an API key from the QR code or manual setup and looks up whose it is. */
    suspend fun verifyApiKey(apiKey: String): DawarichCredentials {
        val key = apiKey.trim()
        val response = http.get("$serverUrl/api/v1/users/me") { bearerAuth(key) }
        when {
            response.status == HttpStatusCode.Unauthorized -> throw DawarichException("Dawarich did not accept this API key.")
            !response.status.isSuccess() -> throw response.unexpected()
        }
        return DawarichCredentials(serverUrl, key, response.body<MeResponse>().user.email)
    }

    private fun AuthSuccess.toCredentials() = DawarichCredentials(serverUrl, apiKey, email)

    /** The auth API answers errors as `{"error": "auth_failed", "message": "…"}`; show that message. */
    private suspend fun HttpResponse.requireAuthSuccess() {
        if (status.isSuccess()) return
        val message = runCatching { lenientJson.decodeFromString<AuthError>(bodyAsText()).message }.getOrNull()
        throw if (message.isNullOrBlank()) unexpected() else DawarichException(message)
    }

    private suspend fun HttpResponse.unexpected() =
        DawarichException("$serverUrl answered ${status.value}. Is this the address of your Dawarich server? ${bodyAsText().take(200)}".trim())
}

/**
 * Accepts what people type or paste: adds `https://` when the scheme is missing and drops trailing slashes,
 * so `dawarich.example.org/` becomes `https://dawarich.example.org`.
 */
fun normalizeServerUrl(input: String): String {
    val trimmed = input.trim().trimEnd('/')
    if (trimmed.isEmpty()) return trimmed
    return if ("://" in trimmed) trimmed else "https://$trimmed"
}

/** The official apps accept plain HTTP but warn about it: the API key would travel unencrypted. */
fun isInsecureServerUrl(serverUrl: String): Boolean = normalizeServerUrl(serverUrl).startsWith("http://", ignoreCase = true)

/**
 * The QR code under Account → API access in Dawarich (`UserHelper#api_key_qr_code`):
 * `{"server_url": "https://…/", "api_key": "…"}`. Returns null for anything else.
 */
fun parseConnectionCode(text: String): ConnectionCode? {
    val code = runCatching { lenientJson.decodeFromString<ConnectionCode>(text.trim()) }.getOrNull() ?: return null
    if (code.serverUrl.isBlank() || code.apiKey.isBlank()) return null
    return code.copy(serverUrl = normalizeServerUrl(code.serverUrl), apiKey = code.apiKey.trim())
}

/** Parses one `Name: Value` header per line; blank lines are skipped. Throws on lines without a name. */
fun parseHeaderLines(text: String): Map<String, String> = text.lines()
    .filter { it.isNotBlank() }
    .associate { line ->
        val name = line.substringBefore(':', missingDelimiterValue = "").trim()
        require(name.isNotEmpty() && name.none { it.isWhitespace() }) { "Write each header as \"Name: Value\": $line" }
        name to line.substringAfter(':').trim()
    }

fun formatHeaderLines(headers: Map<String, String>): String = headers.entries.joinToString("\n") { "${it.key}: ${it.value}" }

internal val lenientJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

internal fun HttpClient.dawarichConfig(customHeaders: Map<String, String>) = config {
    install(ContentNegotiation) { json(lenientJson) }
    defaultRequest {
        customHeaders.forEach { (name, value) -> headers.append(name, value) }
    }
}
