package com.gigaton.reader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gigaton.reader.AppContainer
import com.gigaton.reader.data.repository.Repository
import kotlinx.coroutines.launch

private val BUDGET_OPTIONS = listOf(
    "500 MB" to 500L * 1024 * 1024,
    "1 GB" to 1L * 1024 * 1024 * 1024,
    "2 GB" to 2L * 1024 * 1024 * 1024,
    "4 GB" to 4L * 1024 * 1024 * 1024,
    "Unlimited" to 0L,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    container: AppContainer,
    onEditConnection: () -> Unit,
    onBack: () -> Unit,
) {
    val repository = remember { Repository(container) }
    val scope = rememberCoroutineScope()

    var cachedBytes by remember { mutableStateOf(0L) }
    var pinnedBytes by remember { mutableStateOf(0L) }
    var budget by remember { mutableStateOf(repository.cacheBudgetBytes) }
    var autoCache by remember { mutableStateOf(repository.autoCacheEnabled) }
    var refreshTick by remember { mutableStateOf(0) }

    LaunchedEffect(refreshTick) {
        val stats = runCatching { repository.cacheStats() }.getOrNull()
        if (stats != null) {
            cachedBytes = stats.cachedBytes
            pinnedBytes = stats.pinnedBytes
            budget = stats.budgetBytes
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Connection", style = MaterialTheme.typography.titleMedium)
            Text(
                container.settings.baseUrl.ifEmpty { "Not configured" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onEditConnection, modifier = Modifier.fillMaxWidth()) {
                Text("Edit connection")
            }

            HorizontalDivider(Modifier.padding(vertical = 6.dp))

            Text("Reading cache", style = MaterialTheme.typography.titleMedium)
            Text(
                "Papers you open are cached as you read, so they stay readable offline. " +
                    "Cached pages are removed oldest-first when the limit is reached. " +
                    "Downloads you make are kept separately and are never removed automatically.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Cache: ${fmtBytes(cachedBytes)}    Downloads: ${fmtBytes(pinnedBytes)}",
                style = MaterialTheme.typography.bodyMedium,
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Cache papers I open", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Fetches the rest of a paper in the background.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = autoCache,
                    onCheckedChange = {
                        autoCache = it
                        repository.autoCacheEnabled = it
                    },
                )
            }

            Text("Cache limit", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BUDGET_OPTIONS.take(3).forEach { (label, value) ->
                    FilterChip(
                        selected = budget == value,
                        onClick = { budget = value; repository.cacheBudgetBytes = value; refreshTick++ },
                        label = { Text(label) },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BUDGET_OPTIONS.drop(3).forEach { (label, value) ->
                    FilterChip(
                        selected = budget == value,
                        onClick = { budget = value; repository.cacheBudgetBytes = value; refreshTick++ },
                        label = { Text(label) },
                    )
                }
            }

            Spacer(Modifier.height(4.dp))
            OutlinedButton(
                onClick = {
                    scope.launch {
                        runCatching { repository.clearReaderCache() }
                        refreshTick++
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Clear reading cache")
            }

            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Text(
                "Downloads and cached pages live in the app's private storage. " +
                    "Nothing here changes the files on the server.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

fun fmtBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024 -> "%.0f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}