package com.gigaton.reader.ui

import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gigaton.reader.AppContainer
import com.gigaton.reader.GigatonReaderApp
import com.gigaton.reader.data.network.PageMetaDto
import com.gigaton.reader.data.repository.Payloads
import com.gigaton.reader.data.repository.Repository
import com.gigaton.reader.domain.ApiErrorKind
import com.gigaton.reader.ui.pdf.AnnotatedPage
import com.gigaton.reader.ui.pdf.InkStrokeNorm
import com.gigaton.reader.ui.pdf.OffsetNorm
import com.gigaton.reader.ui.pdf.QuadNorm
import com.gigaton.reader.ui.pdf.ToolMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    container: AppContainer,
    paperPath: String,
    paperName: String,
    onBack: () -> Unit,
    onAnnotations: (path: String, name: String) -> Unit,
) {
    val repository = remember { Repository(container) }
    val scope = rememberCoroutineScope()

    var meta by remember { mutableStateOf<PageMetaDto?>(null) }
    var fileError by remember { mutableStateOf<String?>(null) }
    var loadingPages by remember { mutableStateOf(true) }
    var fetchRetry by remember { mutableIntStateOf(0) }

    LaunchedEffect(paperPath, fetchRetry) {
        fileError = null
        loadingPages = true
        meta = null
        try {
            val m = repository.pageMeta(paperPath)
            meta = m
        } catch (e: Exception) {
            val msg = when (e) {
                is com.gigaton.reader.domain.ApiException -> when (e.kind) {
                    ApiErrorKind.UNREACHABLE -> "Cannot reach GIGATON. Check Tailscale and try again."
                    ApiErrorKind.UNAUTHORIZED -> "Invalid API token — fix it in Settings."
                    else -> e.message ?: "Could not read this paper."
                }
                else -> e.message ?: "Could not read this paper."
            }
            runCatching {
                (container.appContext as com.gigaton.reader.GigatonReaderApp)
                    .logRuntimeError("meta for $paperPath", e)
            }
            fileError = msg
        }
        loadingPages = false
    }

    val annotations by repository.annotationsFlow(paperPath).collectAsState(initial = emptyList())
    LaunchedEffect(paperPath) { repository.refreshAnnotations(paperPath) }

    // ------------------------------------------------------------------ //
    // zoom
    // ------------------------------------------------------------------ //
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.roundToPx() }
    var zoom by remember { mutableFloatStateOf(1f) }
    // The layout width grows with zoom, but the rendered bitmap is capped so
    // high zoom can never allocate huge bitmaps (OOM protection).
    val targetWidthPx = (screenWidthPx * zoom).toInt().coerceAtLeast(1)
    val hScroll = rememberScrollState()

    val listState = rememberLazyListState()
    var currentPage by remember { mutableIntStateOf(1) }

    // resume + page tracking (image pages: use server meta for page count)
    val pageCount = meta?.pageCount ?: 0
    LaunchedEffect(meta) {
        val count = meta?.pageCount ?: return@LaunchedEffect
        val p = repository.loadProgress(paperPath)
        currentPage = p.page.coerceIn(1, count)
        runCatching { listState.scrollToItem(currentPage - 1) }
    }

    var saveJob by remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(meta) {
        val count = meta?.pageCount ?: return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex + 1 }.collect { idx ->
            currentPage = idx.coerceIn(1, count)
            saveJob?.cancel()
            saveJob = scope.launch {
                delay(700)
                runCatching { repository.saveProgress(paperPath, currentPage) }
            }
        }
    }

    // Jump requests from the annotation list screen.
    LaunchedEffect(Unit) {
        container.jumpRequests.collect { page ->
            if (page != null) {
                val count = pageCount
                if (count > 0) {
                    runCatching { listState.scrollToItem(page.coerceIn(1, count) - 1) }
                }
                container.consumeJump()
            }
        }
    }

    // ------------------------------------------------------------------ //
    // annotation tools
    // ------------------------------------------------------------------ //
    var tool by remember { mutableStateOf(ToolMode.NONE) }
    var noteDialog by remember { mutableStateOf<Pair<Int, OffsetNorm>?>(null) }
    var noteText by remember { mutableStateOf("") }
    var jumpDialog by remember { mutableStateOf(false) }
    var jumpText by remember { mutableStateOf("") }

    // Session undo: every annotation created while this paper is open, on any
    // page, with any tool. The stack is cleared when the reader closes.
    data class UndoOp(val label: String, val id: String)
    val undoStack = remember { mutableStateListOf<UndoOp>() }
    val snackbarHostState = remember { SnackbarHostState() }

    fun save(kind: String, payload: kotlinx.serialization.json.JsonObject, page: Int) {
        val id = java.util.UUID.randomUUID().toString()
        scope.launch {
            val ok = runCatching {
                repository.saveAnnotation(paperPath, page, kind, payload, id = id)
            }.isSuccess
            if (ok) undoStack.add(UndoOp(labelFor(kind), id))
        }
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        val op = undoStack.removeAt(undoStack.size - 1)
        scope.launch {
            runCatching { repository.deleteAnnotation(op.id) }
            snackbarHostState.showSnackbar("Undid ${op.label}")
        }
    }

    val onInkStroke: (Int, InkStrokeNorm) -> Unit = { page, stroke ->
        save("ink", Payloads.ink(listOf(stroke)), page + 1)
    }
    val onHighlight: (Int, QuadNorm) -> Unit = { page, quad ->
        save("highlight", Payloads.highlight(null, Payloads.HIGHLIGHT_COLOR, quad), page + 1)
    }
    val onNoteTap: (Int, OffsetNorm) -> Unit = { page, pos ->
        noteDialog = page to pos
        noteText = ""
    }

    fun addBookmark() {
        save("bookmark", Payloads.bookmark("Page $currentPage", null), currentPage)
    }

    // ------------------------------------------------------------------ //
    // body
    // ------------------------------------------------------------------ //
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(paperName.ifBlank { "PDF" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "Page $currentPage of ${meta?.pageCount ?: "…"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    IconButton(onClick = { addBookmark() }) {
                        Icon(Icons.Filled.Bookmarks, "Bookmark this page")
                    }
                    IconButton(onClick = { onAnnotations(paperPath, paperName) }) {
                        Icon(Icons.Filled.FormatListBulleted, "Annotations list")
                    }
                },
            )
        },
        bottomBar = {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToolChip(selected = tool == ToolMode.NONE, icon = { Icon(Icons.Filled.Close, null, Modifier.size(20.dp)) }, label = "Off") { tool = ToolMode.NONE }
                ToolChip(selected = tool == ToolMode.HIGHLIGHT, icon = { Icon(Icons.Filled.Highlight, null, Modifier.size(20.dp)) }, label = "Hi") { tool = ToolMode.HIGHLIGHT }
                ToolChip(selected = tool == ToolMode.PEN, icon = { Icon(Icons.Filled.Create, null, Modifier.size(20.dp)) }, label = "Pen") { tool = ToolMode.PEN }
                ToolChip(selected = tool == ToolMode.NOTE, icon = { Icon(Icons.Filled.Edit, null, Modifier.size(20.dp)) }, label = "Note") { tool = ToolMode.NOTE }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { undo() }, enabled = undoStack.isNotEmpty()) {
                    Icon(Icons.Filled.Undo, "Undo last annotation")
                }
                IconButton(onClick = { zoom = (zoom * 1.25f).coerceAtMost(4f) }) {
                    Icon(Icons.Filled.ZoomIn, "Zoom in")
                }
                IconButton(onClick = { zoom = (zoom / 1.25f).coerceAtLeast(1f) }) {
                    Icon(Icons.Filled.ZoomOut, "Zoom out")
                }
                IconButton(onClick = {
                    jumpText = currentPage.toString()
                    jumpDialog = true
                }) {
                    Text("Pg", style = MaterialTheme.typography.labelMedium)
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                fileError != null -> Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Couldn't open this paper", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(fileError.orEmpty(), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(onClick = { fetchRetry++ }) { Text("Retry") }
                }
                meta == null -> Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.height(120.dp))
                    Text("Fetching paper…", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(12.dp))
                    CircularProgressIndicator()
                }
                else -> {
                    val count = meta?.pageCount ?: 0
                    val renderWidthDp = with(density) { targetWidthPx.toDp() }
                    Box(
                        Modifier
                            .fillMaxSize()
                            .horizontalScroll(hScroll)
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false)
                                    do {
                                        val event = awaitPointerEvent()
                                        val z = event.calculateZoom()
                                        if (event.changes.size > 1 && z != 1f) {
                                            zoom = (zoom * z).coerceIn(1f, 4f)
                                        }
                                    } while (event.changes.any { it.pressed })
                                }
                            }
                    ) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .width(renderWidthDp)
                                .fillMaxHeight(),
                            contentPadding = PaddingValues(bottom = 32.dp, top = 6.dp),
                        ) {
                            items(count, key = { it }) { index ->
                                val pageInfo = meta?.pages?.getOrNull(index)
                                val ratio = pageInfo?.let { (it.widthPt / it.heightPt).toFloat() } ?: 1f
                                AnnotatedPage(
                                    model = repository.pageImageModel(paperPath, index + 1),
                                    pageIndex = index,
                                    ratio = ratio,
                                    annotations = annotations.filter { it.page == index + 1 },
                                    tool = tool,
                                    inkColor = Payloads.INK_COLOR,
                                    inkWidthPx = 3f,
                                    onInkStroke = onInkStroke,
                                    onHighlight = onHighlight,
                                    onNoteTap = onNoteTap,
                                    onImageError = { page, t ->
                                        runCatching {
                                            (container.appContext as GigatonReaderApp)
                                                .logRuntimeError("page image ${page + 1}", t)
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (noteDialog != null) {
        AlertDialog(
            onDismissRequest = { noteDialog = null },
            title = { Text("Add a note on page ${noteDialog!!.first}") },
            text = {
                OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    placeholder = { Text("Research note") },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val (page, pos) = noteDialog!!
                    noteDialog = null
                    save("note", Payloads.note(noteText.trim(), null, pos), page)
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { noteDialog = null }) { Text("Cancel") }
            },
        )
    }

    if (jumpDialog) {
        AlertDialog(
            onDismissRequest = { jumpDialog = false },
            title = { Text("Jump to page") },
            text = {
                OutlinedTextField(
                    value = jumpText,
                    onValueChange = { jumpText = it.filter { c -> c.isDigit() } },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = jumpText.toIntOrNull() ?: 1
                    val count = pageCount
                    if (count > 0) {
                        val clamped = target.coerceIn(1, count)
                        scope.launch { runCatching { listState.scrollToItem(clamped - 1) } }
                    }
                    jumpDialog = false
                }) { Text("Go") }
            },
            dismissButton = { TextButton(onClick = { jumpDialog = false }) { Text("Cancel") } },
        )
    }
}

private fun labelFor(kind: String): String = when (kind) {
    "highlight" -> "highlight"
    "ink" -> "pen stroke"
    "note" -> "note"
    "bookmark" -> "bookmark"
    else -> kind
}

@Composable
private fun ToolChip(
    selected: Boolean,
    icon: @Composable () -> Unit,
    label: String,
    onClick: () -> Unit,
) {
    IconToggleButton(checked = selected, onCheckedChange = { onClick() }) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            icon()
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}