package com.gigaton.reader.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gigaton.reader.AppContainer
import com.gigaton.reader.data.repository.Payloads
import com.gigaton.reader.data.repository.Payloads.bookmarkLabel
import com.gigaton.reader.data.repository.Payloads.highlightQuad
import com.gigaton.reader.data.repository.Payloads.noteText
import com.gigaton.reader.data.repository.Repository
import com.gigaton.reader.domain.AnnotationRec
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnnotationListScreen(
    container: AppContainer,
    paperPath: String,
    paperName: String,
    onBack: () -> Unit,
) {
    val repository = remember { Repository(container) }
    val scope = rememberCoroutineScope()

    val annotations by repository.annotationsFlow(paperPath).collectAsState(initial = emptyList())
    LaunchedEffect(paperPath) { repository.refreshAnnotations(paperPath) }

    var editing by remember { mutableStateOf<AnnotationRec?>(null) }
    var editText by remember { mutableStateOf("") }

    fun delete(rec: AnnotationRec) {
        scope.launch { repository.deleteAnnotation(rec.id) }
    }

    fun saveEdit(rec: AnnotationRec, newText: String) {
        scope.launch {
            when (rec.kind) {
                "note" -> {
                    val pos = com.gigaton.reader.ui.pdf.OffsetNorm(
                        retFloat(rec.payload, "x"),
                        retFloat(rec.payload, "y"),
                    )
                    repository.saveAnnotation(rec.paperPath, rec.page, "note", Payloads.note(newText, null, pos), id = rec.id)
                }
                "highlight" -> {
                    val quad = rec.highlightQuad()
                    val color = rec.payload["color"]?.let { (it as? JsonPrimitive)?.content } ?: Payloads.HIGHLIGHT_COLOR
                    if (quad != null) {
                        repository.saveAnnotation(
                            rec.paperPath, rec.page, "highlight",
                            Payloads.highlight(newText, color, quad), id = rec.id,
                        )
                    }
                }
                else -> Unit
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Annotations", style = MaterialTheme.typography.titleMedium)
                        Text(
                            paperName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (annotations.isEmpty()) {
                Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("No annotations on this paper yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Text(
                    text = "${annotations.groupBy { it.kind }.map { (k, v) -> "$k ×${v.size}" }.joinToString(" · ")}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
                LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
                    items(annotations, key = { it.id }) { rec ->
                        val quad = rec.highlightQuad()
                        val summary = when (rec.kind) {
                            "highlight" -> quad?.let {
                                "Highlight on ${rec.page}: ${"%.0f".format((it.x1 - it.x0) * 100)}% × ${"%.0f".format((it.y1 - it.y0) * 100)}% of page"
                            } ?: "Highlight"
                            "note" -> rec.noteText().ifBlank { "Note" }
                            "ink" -> "Ink strokes"
                            "bookmark" -> "Bookmark · ${rec.bookmarkLabel().ifBlank { "Page ${rec.page}" }}"
                            else -> rec.kind
                        }
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                when (rec.kind) {
                                    "highlight" -> Icons.Filled.Highlight
                                    "note" -> Icons.Filled.Edit
                                    "ink" -> Icons.Filled.Create
                                    else -> Icons.Filled.Bookmarks
                                },
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "Page ${rec.page}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.secondary,
                                )
                                Text(
                                    summary,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            IconButton(onClick = {
                                container.requestJump(rec.page)
                                onBack()
                            }) { Icon(Icons.Filled.OpenInNew, "Jump to this page") }
                            if (rec.kind == "note" || rec.kind == "highlight") {
                                IconButton(onClick = {
                                    editing = rec
                                    editText = if (rec.kind == "note") rec.noteText() else txtOf(rec)
                                }) { Icon(Icons.Filled.Edit, "Edit") }
                            }
                            IconButton(onClick = { delete(rec) }) {
                                Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }

    editing?.let { rec ->
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Edit ${rec.kind}") },
            text = {
                OutlinedTextField(
                    value = editText,
                    onValueChange = { editText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (rec.kind == "highlight") "Highlight label" else "Note text") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    saveEdit(rec, editText)
                    editing = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
}

private fun txtOf(rec: AnnotationRec): String =
    rec.payload["text"]?.let { (it as? JsonPrimitive)?.content } ?: ""

private fun retFloat(payload: kotlinx.serialization.json.JsonObject, key: String): Float {
    val v = payload[key] as? JsonPrimitive
    return v?.let { it.content.toFloatOrNull() } ?: 0.5f
}