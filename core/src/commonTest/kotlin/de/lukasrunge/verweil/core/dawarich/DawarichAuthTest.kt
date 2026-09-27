package de.lukasrunge.verweil.core.dawarich

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class DawarichAuthTest {

    private val requests = mutableListOf<HttpRequestData>()

    private fun auth(
        serverUrl: String = "dawarich.example.org/",
        customHeaders: Map<String, String> = emptyMap(),
        handler: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ) = DawarichAuth(
        serverUrl = serverUrl,
        engine = HttpClient(
            MockEngine { request ->
                requests += request
                handler(request)
            },
        ),
        customHeaders = customHeaders,
    )

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun HttpRequestData.jsonBody() =
        Json.parseToJsonElement((body as OutgoingContent.ByteArrayContent).bytes().decodeToString()).jsonObject

    @Test
    fun passwordLoginReturnsTheApiKey() = runTest {
        val result = auth {
            json("""{"user_id": 1, "email": "ada@example.org", "api_key": "key-1", "status": "active", "plan": "pro"}""")
        }.login(" ada@example.org ", "hunter2")

        val request = requests.single()
        assertEquals("https://dawarich.example.org/api/v1/auth/login", request.url.toString())
        assertEquals("ada@example.org", request.jsonBody()["email"]!!.jsonPrimitive.content)
        assertEquals("hunter2", request.jsonBody()["password"]!!.jsonPrimitive.content)
        assertEquals(
            LoginResult.SignedIn(DawarichCredentials("https://dawarich.example.org", "key-1", "ada@example.org")),
            result,
        )
    }

    @Test
    fun twoFactorAccountsGetAChallengeThatTheCodeAnswers() = runTest {
        val auth = auth { request ->
            when (request.url.encodedPath) {
                "/api/v1/auth/login" -> json(
                    """{"two_factor_required": true, "challenge_token": "challenge", "ttl": 300}""",
                    HttpStatusCode.Accepted,
                )
                else -> json("""{"email": "ada@example.org", "api_key": "key-1"}""")
            }
        }

        val challenge = auth.login("ada@example.org", "hunter2")
        assertEquals(LoginResult.TwoFactorRequired("challenge", 300), challenge)

        val credentials = auth.submitOtp("challenge", " 123456 ")
        val otpRequest = requests.last()
        assertEquals("/api/v1/auth/otp_challenge", otpRequest.url.encodedPath)
        assertEquals("challenge", otpRequest.jsonBody()["challenge_token"]!!.jsonPrimitive.content)
        assertEquals("123456", otpRequest.jsonBody()["otp_code"]!!.jsonPrimitive.content)
        assertEquals("key-1", credentials.apiKey)
    }

    @Test
    fun serverErrorMessagesAreShownAsTheyAre() = runTest {
        val error = assertFailsWith<DawarichException> {
            auth {
                json("""{"error": "auth_failed", "message": "Invalid email or password"}""", HttpStatusCode.Unauthorized)
            }.login("ada@example.org", "wrong")
        }
        assertEquals("Invalid email or password", error.message)
    }

    @Test
    fun anythingButDawarichIsReportedWithTheStatus() = runTest {
        val error = assertFailsWith<DawarichException> {
            auth { respond("<html>Not here</html>", HttpStatusCode.NotFound) }.login("ada@example.org", "hunter2")
        }
        assertEquals(true, error.message!!.startsWith("https://dawarich.example.org answered 404."))
    }

    @Test
    fun apiKeysAreVerifiedAgainstTheAccount() = runTest {
        val credentials = auth(customHeaders = mapOf("CF-Access-Client-Id" to "proxy-id")) {
            json("""{"user": {"email": "ada@example.org", "theme": "dark"}, "features": {}}""")
        }.verifyApiKey(" key-1 ")

        val request = requests.single()
        assertEquals("/api/v1/users/me", request.url.encodedPath)
        assertEquals("Bearer key-1", request.headers[HttpHeaders.Authorization])
        assertEquals("proxy-id", request.headers["CF-Access-Client-Id"])
        assertEquals(DawarichCredentials("https://dawarich.example.org", "key-1", "ada@example.org"), credentials)
    }

    @Test
    fun rejectedApiKeysFail() = runTest {
        assertFailsWith<DawarichException> {
            auth { respond("", HttpStatusCode.Unauthorized) }.verifyApiKey("wrong")
        }
    }

    @Test
    fun serverUrlsAreNormalized() {
        assertEquals("https://dawarich.example.org", normalizeServerUrl(" dawarich.example.org/ "))
        assertEquals("http://192.168.1.5:3000", normalizeServerUrl("http://192.168.1.5:3000/"))
        assertEquals(true, isInsecureServerUrl("http://192.168.1.5:3000"))
        assertEquals(false, isInsecureServerUrl("dawarich.example.org"))
    }

    @Test
    fun qrCodesFromDawarichAreRead() {
        assertEquals(
            ConnectionCode("https://dawarich.example.org", "key-1"),
            parseConnectionCode("""{"server_url":"https://dawarich.example.org/","api_key":"key-1"}"""),
        )
        assertNull(parseConnectionCode("https://example.org"))
        assertNull(parseConnectionCode("""{"server_url":"","api_key":"key-1"}"""))
    }

    @Test
    fun headerLinesRoundTrip() {
        val headers = parseHeaderLines("CF-Access-Client-Id: id\n\nCF-Access-Client-Secret:  a:b \n")
        assertEquals(mapOf("CF-Access-Client-Id" to "id", "CF-Access-Client-Secret" to "a:b"), headers)
        assertEquals(headers, parseHeaderLines(formatHeaderLines(headers)))
        assertFailsWith<IllegalArgumentException> { parseHeaderLines("no colon here") }
    }
}
