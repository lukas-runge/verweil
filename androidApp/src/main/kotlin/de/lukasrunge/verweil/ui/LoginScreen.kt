package de.lukasrunge.verweil.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import de.lukasrunge.verweil.Settings
import de.lukasrunge.verweil.core.dawarich.DAWARICH_CLOUD_URL
import de.lukasrunge.verweil.core.dawarich.DawarichAuth
import de.lukasrunge.verweil.core.dawarich.DawarichException
import de.lukasrunge.verweil.core.dawarich.LoginResult
import de.lukasrunge.verweil.core.dawarich.isInsecureServerUrl
import de.lukasrunge.verweil.core.dawarich.parseConnectionCode
import de.lukasrunge.verweil.core.dawarich.parseHeaderLines
import de.lukasrunge.verweil.core.platformHttpClient
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/** The screens of the sign-in flow, laid out like the official Dawarich apps. */
private enum class Step { Start, CloudSignIn, TwoFactor, SelfHosted, ManualSetup }

private fun Step.previous(): Step? = when (this) {
    Step.Start -> null
    Step.CloudSignIn, Step.SelfHosted -> Step.Start
    Step.TwoFactor -> Step.CloudSignIn
    Step.ManualSetup -> Step.SelfHosted
}

/**
 * Dawarich Cloud: email and password, with a second step for accounts with 2FA.
 * Self-hosted: scan the QR code from Account → API access, or enter URL, API key and proxy headers by hand.
 * Every path checks the credentials with the server before saving them.
 */
@Composable
fun LoginScreen(settings: Settings) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()

    var step by rememberSaveable { mutableStateOf(Step.Start) }
    var busy by remember { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }

    var email by rememberSaveable { mutableStateOf("") }
    // Not saveable on purpose: the password should not end up in the saved instance state.
    var password by remember { mutableStateOf("") }
    var challengeToken by rememberSaveable { mutableStateOf("") }
    var otpCode by rememberSaveable { mutableStateOf("") }
    var serverUrl by rememberSaveable { mutableStateOf("") }
    var apiKey by rememberSaveable { mutableStateOf("") }
    var headerLines by rememberSaveable { mutableStateOf("") }

    fun go(next: Step) {
        error = null
        step = next
    }

    /** Runs one server round trip; the buttons are disabled meanwhile and a failure shows under the form. */
    fun attempt(block: suspend () -> Unit) {
        if (busy) return
        scope.launch {
            busy = true
            error = null
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.userMessage()
            } finally {
                busy = false
            }
        }
    }

    BackHandler(enabled = step.previous() != null && !busy) { go(step.previous()!!) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Verweil", style = MaterialTheme.typography.headlineMedium)

        when (step) {
            Step.Start -> {
                Text("Connect Verweil to your Dawarich account. Your locations are uploaded there.")

                Text("Dawarich Cloud", style = MaterialTheme.typography.titleMedium)
                Button(onClick = { go(Step.CloudSignIn) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Sign in with email")
                }
                TextButton(onClick = { uriHandler.openUri("$DAWARICH_CLOUD_URL/users/sign_up") }) {
                    Text("No account yet? Sign up on dawarich.app")
                }

                HorizontalDivider()

                Text("For self-hosters", style = MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick = { go(Step.SelfHosted) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Connect to your own Dawarich instance")
                }
            }

            Step.CloudSignIn -> {
                Text("Sign in to Dawarich Cloud", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                ErrorText(error)
                Button(
                    enabled = !busy && email.isNotBlank() && password.isNotEmpty(),
                    onClick = {
                        attempt {
                            when (val result = withAuth(DAWARICH_CLOUD_URL) { it.login(email, password) }) {
                                is LoginResult.SignedIn -> settings.signIn(result.credentials, customHeaders = emptyMap())
                                is LoginResult.TwoFactorRequired -> {
                                    challengeToken = result.challengeToken
                                    otpCode = ""
                                    go(Step.TwoFactor)
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (busy) "Signing in…" else "Sign in") }
                TextButton(onClick = { uriHandler.openUri("$DAWARICH_CLOUD_URL/users/password/new") }) {
                    Text("Forgot password?")
                }
            }

            Step.TwoFactor -> {
                Text("Two-factor authentication", style = MaterialTheme.typography.titleMedium)
                Text("Enter the code from your authenticator app, or one of your backup codes.")
                OutlinedTextField(
                    value = otpCode,
                    onValueChange = { otpCode = it },
                    label = { Text("Code") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                ErrorText(error)
                Button(
                    enabled = !busy && otpCode.isNotBlank(),
                    onClick = {
                        attempt {
                            val credentials = withAuth(DAWARICH_CLOUD_URL) { it.submitOtp(challengeToken, otpCode) }
                            settings.signIn(credentials, customHeaders = emptyMap())
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (busy) "Verifying…" else "Verify") }
            }

            Step.SelfHosted -> {
                Text("Connect to your own Dawarich instance", style = MaterialTheme.typography.titleMedium)
                Text("Open Dawarich in a browser and go to Account → API access. There you find a QR code, the server URL and your API key.")
                ErrorText(error)
                Button(
                    enabled = !busy,
                    onClick = {
                        attempt {
                            val code = parseConnectionCode(scanQrCode(context))
                                ?: throw DawarichException("This is not a Dawarich QR code. Scan the one under Account → API access.")
                            // Prefilled so a failed check can be fixed by hand, for example by adding proxy headers.
                            serverUrl = code.serverUrl
                            apiKey = code.apiKey
                            try {
                                settings.signIn(withAuth(code.serverUrl) { it.verifyApiKey(code.apiKey) }, customHeaders = emptyMap())
                            } catch (e: Exception) {
                                if (e !is CancellationException) step = Step.ManualSetup
                                throw e
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (busy) "Connecting…" else "Scan QR code") }
                OutlinedButton(enabled = !busy, onClick = { go(Step.ManualSetup) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Manual setup")
                }
            }

            Step.ManualSetup -> {
                Text("Manual setup", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    label = { Text("Server URL") },
                    placeholder = { Text("https://dawarich.example.org") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (serverUrl.isNotBlank() && isInsecureServerUrl(serverUrl)) {
                    Text(
                        "This URL uses plain HTTP: your API key and locations travel unencrypted. Use HTTPS if you can.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = headerLines,
                    onValueChange = { headerLines = it },
                    label = { Text("Custom headers (optional)") },
                    placeholder = { Text("CF-Access-Client-Id: …") },
                    supportingText = { Text("One \"Name: Value\" per line, for reverse proxies such as Cloudflare Access or Pangolin.") },
                    minLines = 2,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                ErrorText(error)
                Button(
                    enabled = !busy && serverUrl.isNotBlank() && apiKey.isNotBlank(),
                    onClick = {
                        attempt {
                            val headers = parseHeaderLines(headerLines)
                            settings.signIn(withAuth(serverUrl, headers) { it.verifyApiKey(apiKey) }, headers)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (busy) "Connecting…" else "Connect") }
            }
        }
    }
}

@Composable
private fun ErrorText(error: String?) {
    if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
}

private suspend fun <T> withAuth(
    serverUrl: String,
    customHeaders: Map<String, String> = emptyMap(),
    block: suspend (DawarichAuth) -> T,
): T {
    val http = platformHttpClient()
    try {
        return block(DawarichAuth(serverUrl, http, customHeaders))
    } finally {
        http.close()
    }
}

/** Google's code scanner: no camera permission needed, Play Services shows the scanner UI. */
private suspend fun scanQrCode(context: Context): String {
    val options = GmsBarcodeScannerOptions.Builder()
        .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
        .build()
    return GmsBarcodeScanning.getClient(context, options).startScan().await().rawValue.orEmpty()
}

private fun Exception.userMessage(): String = when (this) {
    is DawarichException, is IllegalArgumentException -> message.orEmpty()
    is IOException -> "Could not reach the server. Check the address and your connection. (${message ?: javaClass.simpleName})"
    else -> message ?: javaClass.simpleName
}
