package de.lukasrunge.verweil.ui

import android.content.Context
import android.content.res.Resources
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import de.lukasrunge.verweil.R
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
    val resources = LocalResources.current
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
                error = e.userMessage(resources)
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
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val previous = step.previous()
        if (previous == null) {
            Spacer(Modifier.height(40.dp))
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displayMedium)
            Text(
                stringResource(R.string.login_tagline),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        } else {
            IconButton(onClick = { if (!busy) go(previous) }, modifier = Modifier.padding(start = 0.dp)) {
                Icon(ImageVector.vectorResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
            }
        }

        when (step) {
            Step.Start -> {
                Text(stringResource(R.string.login_cloud), style = MaterialTheme.typography.titleLarge)
                Text(
                    stringResource(R.string.login_cloud_detail),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = { go(Step.CloudSignIn) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.login_with_email))
                }
                TextButton(onClick = { uriHandler.openUri("$DAWARICH_CLOUD_URL/users/sign_up") }) {
                    Text(stringResource(R.string.login_sign_up))
                }

                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.login_self_hosted), style = MaterialTheme.typography.titleLarge)
                Text(
                    stringResource(R.string.login_self_hosted_detail),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = { go(Step.SelfHosted) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.login_connect_own))
                }
            }

            Step.CloudSignIn -> {
                Title(stringResource(R.string.login_cloud_title))
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text(stringResource(R.string.login_email)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.login_password)) },
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
                ) { Text(stringResource(if (busy) R.string.login_signing_in else R.string.login_sign_in)) }
                TextButton(onClick = { uriHandler.openUri("$DAWARICH_CLOUD_URL/users/password/new") }) {
                    Text(stringResource(R.string.login_forgot_password))
                }
            }

            Step.TwoFactor -> {
                Title(stringResource(R.string.login_2fa_title))
                Text(stringResource(R.string.login_2fa_text), color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = otpCode,
                    onValueChange = { otpCode = it },
                    label = { Text(stringResource(R.string.login_code)) },
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
                ) { Text(stringResource(if (busy) R.string.login_verifying else R.string.login_verify)) }
            }

            Step.SelfHosted -> {
                Title(stringResource(R.string.login_self_hosted_title))
                Text(stringResource(R.string.login_self_hosted_text), color = MaterialTheme.colorScheme.onSurfaceVariant)
                ErrorText(error)
                val notDawarich = stringResource(R.string.login_not_a_dawarich_code)
                Button(
                    enabled = !busy,
                    onClick = {
                        attempt {
                            val code = parseConnectionCode(scanQrCode(context)) ?: throw DawarichException(notDawarich)
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
                ) {
                    Icon(ImageVector.vectorResource(R.drawable.ic_qr_code_scanner), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(if (busy) R.string.login_connecting else R.string.login_scan))
                }
                OutlinedButton(enabled = !busy, onClick = { go(Step.ManualSetup) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.login_manual))
                }
            }

            Step.ManualSetup -> {
                Title(stringResource(R.string.login_manual))
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    label = { Text(stringResource(R.string.login_server_url)) },
                    placeholder = { Text("https://dawarich.example.org") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (serverUrl.isNotBlank() && isInsecureServerUrl(serverUrl)) {
                    Text(
                        stringResource(R.string.login_insecure),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text(stringResource(R.string.login_api_key)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = headerLines,
                    onValueChange = { headerLines = it },
                    label = { Text(stringResource(R.string.login_headers)) },
                    placeholder = { Text("CF-Access-Client-Id: …") },
                    supportingText = { Text(stringResource(R.string.login_headers_hint)) },
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
                ) { Text(stringResource(if (busy) R.string.login_connecting else R.string.login_connect)) }
            }
        }
    }
}

@Composable
private fun Title(text: String) {
    Text(text, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 4.dp))
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

private fun Exception.userMessage(resources: Resources): String = when (this) {
    is DawarichException, is IllegalArgumentException -> message.orEmpty()
    is IOException -> resources.getString(R.string.login_unreachable, message ?: javaClass.simpleName)
    else -> message ?: javaClass.simpleName
}
