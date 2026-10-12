package com.gigaton.reader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gigaton.reader.AppContainer
import com.gigaton.reader.data.repository.Repository
import com.gigaton.reader.domain.ApiErrorKind
import com.gigaton.reader.domain.BrowseEntry
import com.gigaton.reader.domain.EntryType
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(
    container: AppContainer,
    onOpenPdf: (path: String, name: String) -> Unit,
    onOpenAnnotations: (path: String, name: String) -> Unit,
    onSettings: () -> Unit,
    onReconnect: () -> Unit,
) {
    val repository = remember { Repository(container) }
    val scope = rememberCoroutineScope()

    var folderStack by remember { mutableStateOf(listOf("")) }
    val currentPath = folderStack.last()

    var entries by remember { mutableStateOf<List<BrowseEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshTick by remember { mutableStateOf(0) }

    val downloads by repository.downloads().collectAsState(initial = emptyList())
    val pending by repository.pendingCount().collectAsState(initial = 0)
    val syncError by repository.syncError.collectAsState()
    val downloadingProgress = remember { mutableStateMapOf<String, Float>() }

    suspend fun load() {
        loading = true
        error = null
        runCatching { repository.browse(currentPath) }
            .onSuccess { entries = it; loading = false }
            .onFailure {
                resetApiOnUnauthorized(it, container, onReconnect)
                error = friendlyBrowseError(it)
                loading = false
            }
    }

    androidx.compose.runtime.LaunchedEffect(currentPath, refreshTick) { load() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            if (currentPath.isBlank()) "Research Library" else currentPath,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            if (currentPath.isBlank()) "Magnamite · learningPapers" else currentPath.substringBeforeLast('/'),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    if (folderStack.size > 1) {
                        IconButton(onClick = { folderStack = folderStack.dropLast(1) }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Up one folder")
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { refreshTick++ }) {
                        Icon(Icons.Filled.Refresh, "Refresh")
                    }
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Filled.Settings, "Settings")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (syncError != null || pending > 0) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.Download,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = buildString {
                            if (pending > 0) append("$pending change(s) pending sync")
                            if (pending > 0 && syncError != null) append(" · ")
                            if (syncError != null) append(syncError)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (error != null) {
                Column(Modifier.padding(20.dp)) {
                    Text("There was a problem", style = MaterialTheme.typography.titleMedium)
                    Text(error.orEmpty(), style = MaterialTheme.typography.bodySmall)
                    androidx.compose.material3.Button(onClick = {
                        error = null; refreshTick++
                    }) { Text("Retry") }
                }
            } else if (!loading && entries.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("This folder is empty.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
                    items(entries, key = { it.path }) { entry ->
                        val downloaded = downloads.any { it.path == entry.path }
                        val progress = downloadingProgress[entry.path]
                        BrowseRow(
                            entry = entry,
                            downloaded = downloaded,
                            progress = progress,
                            onOpen = {
                                if (entry.type == EntryType.FOLDER) {
                                    folderStack = folderStack + entry.path
                                } else {
                                    onOpenPdf(entry.path, entry.name)
                                }
                            },
                            onToggleDownload = {
                                if (entry.type != EntryType.PDF) return@BrowseRow
                                if (downloaded) {
                                    scope.launch { repository.removeDownload(entry.path) }
                                } else {
                                    scope.launch {
                                        downloadingProgress[entry.path] = 0f
                                        runCatching {
                                            repository.downloadPaperForOffline(entry.path) { done, total ->
                                                downloadingProgress[entry.path] =
                                                    if (total > 0) done.toFloat() / total else 0f
                                            }
                                        }.onFailure {
                                            error = friendlyDownloadError(it)
                                        }
                                        downloadingProgress.remove(entry.path)
                                    }
                                }
                            },
                            onAnnotations = {
                                if (entry.type == EntryType.PDF) onOpenAnnotations(entry.path, entry.name)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowseRow(
    entry: BrowseEntry,
    downloaded: Boolean,
    progress: Float?,
    onOpen: () -> Unit,
    onToggleDownload: () -> Unit,
    onAnnotations: () -> Unit,
) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (entry.type == EntryType.FOLDER) Icons.Filled.Folder else Icons.Filled.PictureAsPdf,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(entry.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                Text(
                    text = when {
                        entry.type == EntryType.FOLDER -> "Folder"
                        downloaded -> "Downloaded · ${formatSize(entry.size)}"
                        else -> formatSize(entry.size)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (entry.type == EntryType.PDF) {
                if (progress != null) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = onToggleDownload) {
                        Icon(
                            Icons.Filled.Download,
                            contentDescription = if (downloaded) "Remove local copy" else "Download for offline",
                            tint = if (downloaded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                IconButton(onClick = onAnnotations) {
                    Text("¶", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        if (progress != null && progress > 0f && progress < 1f) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            )
        }
        androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

private fun friendlyBrowseError(t: Throwable): String = when {
    t.message?.contains("401") == true || t.message?.contains("token") == true -> "Invalid or missing API token. Open Settings and re-enter it."
    t is com.gigaton.reader.domain.ApiException && t.kind == ApiErrorKind.UNREACHABLE -> t.message ?: "Server unreachable."
    else -> t.message ?: "Unexpected error."
}

private fun friendlyDownloadError(t: Throwable): String = t.message ?: "Download failed."

/**
 * When the server starts rejecting because the token/URL changed, bounce to the
 * connect screen so the user can re-authenticate instead of hitting dead ends.
 */
private fun resetApiOnUnauthorized(t: Throwable, container: AppContainer, onReconnect: () -> Unit) {
    if (t is com.gigaton.reader.domain.ApiException && (t.kind == ApiErrorKind.UNAUTHORIZED || t.kind == ApiErrorKind.NOT_FOUND)) {
        onReconnect()
    }
}