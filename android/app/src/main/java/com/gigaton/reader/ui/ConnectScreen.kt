package com.gigaton.reader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import android.widget.Toast
import com.gigaton.reader.AppContainer
import com.gigaton.reader.GigatonReaderApp
import com.gigaton.reader.data.repository.Repository
import com.gigaton.reader.domain.ApiErrorKind
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreen(
    container: AppContainer,
    onConnected: () -> Unit,
) {
    val repository = remember { Repository(container) }
    val scope = rememberCoroutineScope()

    var baseUrl by remember { mutableStateOf(container.settings.baseUrl) }
    var token by remember { mutableStateOf(container.settings.token) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var connected by remember { mutableStateOf(false) }

    fun runConnect(asFormReTest: Boolean) {
        if (baseUrl.isBlank() || token.isBlank()) {
            error = "Enter both the server URL and your API token."
            return
        }
        loading = true
        error = null
        scope.launch {
            val failure = repository.configureAndTest(baseUrl, token)
            loading = false
            if (failure == null) {
                connected = true
                error = null
                if (!asFormReTest) onConnected()
            } else {
                error = when (failure.kind) {
                    ApiErrorKind.UNREACHABLE ->
                        "Cannot reach $baseUrl. Check that Tailscale is connected and the server is running."
                    ApiErrorKind.UNAUTHORIZED -> "Incorrect API token (or the server token is misconfigured)."
                    ApiErrorKind.NOT_FOUND -> "Server reachable, but the API path was not found."
                    ApiErrorKind.OTHER, ApiErrorKind.SERVER -> failure.message
                }
            }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Connect to GIGATON") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Your papers live on the GIGATON server at /storage/magnamite/learningPapers. Point the app at the API and your personal token.",
                style = MaterialTheme.typography.bodyMedium,
            )

            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("API base URL") },
                placeholder = { Text("http://100.82.221.72:8088") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("API token") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )

            if (error != null) {
                Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
            }

            Button(
                onClick = { runConnect(asFormReTest = false) },
                enabled = !loading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (loading) {
                    CircularProgressIndicator(modifier = Modifier.height(20.dp), strokeWidth = 2.dp)
                } else {
                    Text("Save & Connect")
                }
            }

            if (connected) {
                Text("✓ Connected — your library is reachable.", color = MaterialTheme.colorScheme.primary)
                OutlinedButton(onClick = { onConnected() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Continue to library")
                }
            } else if (container.settings.isConfigured) {
                OutlinedButton(
                    onClick = { runConnect(asFormReTest = true) },
                    enabled = !loading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Re-test saved connection")
                }
            }

            CrashLogCard()
        }
    }
}

/** Quick way to export an on-device crash report without adb. */
@Composable
private fun CrashLogCard() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val crashFile = remember {
        (context.applicationContext as GigatonReaderApp).crashLogFile()
    }
    var exists by remember { mutableStateOf(crashFile.exists()) }
    var copied by remember { mutableStateOf(false) }

    OutlinedButton(
        onClick = {
            val text = runCatching { crashFile.readText() }.getOrDefault("")
            if (text.isNotBlank()) {
                clipboard.setText(AnnotatedString(text.takeLast(6000)))
                copied = true
                android.widget.Toast.makeText(
                    context,
                    "Crash log copied (${text.length} chars)",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
        },
        enabled = exists,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(if (copied) "✓ Crash log copied — paste it to the developer" else "Copy crash log")
    }
    exists = crashFile.exists()
    if (!exists) {
        Text(
            "No crashes recorded on this device yet.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}