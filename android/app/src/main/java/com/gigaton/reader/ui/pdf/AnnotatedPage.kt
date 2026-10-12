package com.gigaton.reader.ui.pdf

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.gigaton.reader.data.repository.Payloads.highlightQuad
import com.gigaton.reader.domain.AnnotationRec
import com.gigaton.reader.domain.arr
import com.gigaton.reader.domain.float
import com.gigaton.reader.domain.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Page interaction tool. */
enum class ToolMode { NONE, HIGHLIGHT, PEN, NOTE }

/** Normalized point within a page (0..1 both axes). */
data class OffsetNorm(val x: Float, val y: Float)

/** Normalized rectangle for highlights. */
data class QuadNorm(val x0: Float, val y0: Float, val x1: Float, val y1: Float)

/** A single ink stroke with normalized points. */
data class InkStrokeNorm(val color: String, val widthPx: Float, val points: List<OffsetNorm>)

private fun Offset.toNorm(size: IntSize): OffsetNorm =
    OffsetNorm(if (size.width > 0) x / size.width else 0f, if (size.height > 0) y / size.height else 0f)

private fun OffsetNorm.toOffset(size: IntSize): Offset = Offset(x * size.width, y * size.height)

private fun androidx.compose.ui.geometry.Size.toIntSize(): IntSize =
    androidx.compose.ui.unit.IntSize(width.toInt(), height.toInt())

private fun parseHexColor(hex: String?, fallback: Color): Color {
    if (hex.isNullOrBlank()) return fallback
    return runCatching {
        var h = hex.removePrefix("#")
        if (h.length == 6) h = "FF$h"
        Color(android.graphics.Color.parseColor("#$h"))
    }.getOrDefault(fallback)
}

/**
 * A single page of a paper. The page is a server-rendered PNG loaded over
 * Coil (the app's OkHttp client injects auth). The annotation overlay draws
 * with normalized coordinates so highlights/ink/notes stay aligned at any
 * zoom. Page interaction gestures feed the active annotation tool.
 */
@Composable
fun AnnotatedPage(
    model: Any,
    pageIndex: Int,
    ratio: Float,
    annotations: List<AnnotationRec>,
    tool: ToolMode,
    inkColor: String,
    inkWidthPx: Float,
    onInkStroke: (Int, InkStrokeNorm) -> Unit,
    onHighlight: (Int, QuadNorm) -> Unit,
    onNoteTap: (Int, OffsetNorm) -> Unit,
    onImageError: (Int, Throwable) -> Unit = { _, _ -> },
) {
    var loadFailed by remember(pageIndex) { mutableStateOf(false) }

    var liveInk by remember(pageIndex) { mutableStateOf<List<OffsetNorm>>(emptyList()) }
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragCurrent by remember { mutableStateOf<Offset?>(null) }

    val interactionModifier = when (tool) {
        ToolMode.PEN -> Modifier.pointerInput(pageIndex) {
            detectDragGestures(
                onDragStart = { pos ->
                    liveInk = listOf(pos.toNorm(size))
                },
                onDrag = { change, _ ->
                    liveInk = liveInk + change.position.toNorm(size)
                    change.consume()
                },
                onDragEnd = {
                    if (liveInk.size >= 2) onInkStroke(pageIndex, InkStrokeNorm(inkColor, inkWidthPx, liveInk))
                    liveInk = emptyList()
                },
                onDragCancel = { liveInk = emptyList() },
            )
        }
        ToolMode.HIGHLIGHT -> Modifier.pointerInput(pageIndex) {
            detectDragGestures(
                onDragStart = { dragStart = it },
                onDrag = { change, _ -> dragCurrent = change.position; change.consume() },
                onDragEnd = {
                    val s = dragStart ?: return@detectDragGestures
                    val e = dragCurrent ?: s
                    val q = QuadNorm(
                        x0 = minOf(s.x, e.x) / size.width, y0 = minOf(s.y, e.y) / size.height,
                        x1 = maxOf(s.x, e.x) / size.width, y1 = maxOf(s.y, e.y) / size.height,
                    )
                    if ((q.x1 - q.x0) * size.width > 16f || (q.y1 - q.y0) * size.height > 16f) {
                        onHighlight(pageIndex, q)
                    }
                    dragStart = null; dragCurrent = null
                },
                onDragCancel = { dragStart = null; dragCurrent = null },
            )
        }
        ToolMode.NOTE -> Modifier.pointerInput(pageIndex) {
            detectTapGestures { pos -> onNoteTap(pageIndex, pos.toNorm(size)) }
        }
        ToolMode.NONE -> Modifier
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(ratio.coerceAtLeast(0.05f))
            .then(interactionModifier)
    ) {
        if (loadFailed) {
            Box(
                Modifier.fillMaxSize().background(Color(0xFFF0F0F0))
                    .padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "This page couldn't be loaded. Check the connection and try again.",
                    color = Color(0xFF777777),
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                )
            }
        } else {
            AsyncImage(
                model = model,
                contentDescription = "Page ${pageIndex + 1}",
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize(),
                onError = {
                    loadFailed = true
                    onImageError(pageIndex, it.result.throwable)
                },
            )
        }

        Canvas(Modifier.fillMaxSize()) {
            val pxSize = size.toIntSize()
            for (a in annotations) {
                when (a.kind) {
                    "highlight" -> {
                        val q = a.highlightQuad() ?: continue
                        val rect = androidx.compose.ui.geometry.Rect(
                            q.x0 * size.width, q.y0 * size.height,
                            q.x1 * size.width, q.y1 * size.height,
                        )
                        drawRect(
                            color = parseHexColor(a.payload.str("color"), Color(0x66FFEB3B)).copy(alpha = 0.35f),
                            topLeft = rect.topLeft,
                            size = rect.size,
                        )
                        drawRect(
                            color = parseHexColor(a.payload.str("color"), Color(0x66FFEB3B)),
                            topLeft = rect.topLeft,
                            size = rect.size,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f),
                        )
                    }
                    "ink" -> {
                        val strokes = a.payload.arr("strokes") ?: continue
                        for (stroke in strokes) {
                            val obj = stroke.jsonObject
                            val color = obj.str("color") ?: "#E91E63"
                            val width = obj.float("width") ?: 3f
                            val pts = obj["points"] as? JsonArray ?: continue
                            val path = Path()
                            var first = true
                            for (p in pts) {
                                val arr = p.jsonArray
                                val x = arr[0].jsonPrimitive.floatOrNull ?: continue
                                val y = arr[1].jsonPrimitive.floatOrNull ?: continue
                                val offset = OffsetNorm(x, y).toOffset(pxSize)
                                if (first) { path.moveTo(offset.x, offset.y); first = false }
                                else path.lineTo(offset.x, offset.y)
                            }
                            drawPath(
                                path = path,
                                color = parseHexColor(color, Color(0xFFE91E63)),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = width),
                            )
                        }
                    }
                    "note", "bookmark" -> {
                        val x = a.payload.float("x")
                        val y = a.payload.float("y")
                        if (x != null && y != null) {
                            val pos = OffsetNorm(x, y).toOffset(pxSize)
                            drawCircle(
                                color = if (a.kind == "bookmark") Color(0xFF00BCD4) else Color(0xFFFF9800),
                                radius = 9f,
                                center = pos,
                            )
                            drawCircle(Color.White, radius = 3.5f, center = pos)
                        }
                    }
                }
            }

            if (tool == ToolMode.PEN && liveInk.isNotEmpty()) {
                val path = Path()
                liveInk.forEachIndexed { i, p ->
                    val o = p.toOffset(pxSize)
                    if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
                }
                drawPath(path, parseHexColor(inkColor, Color(0xFFE91E63)), style = androidx.compose.ui.graphics.drawscope.Stroke(width = inkWidthPx))
            }
            if (tool == ToolMode.HIGHLIGHT && dragStart != null && dragCurrent != null) {
                val rect = androidx.compose.ui.geometry.Rect(
                    dragStart!!.x, dragStart!!.y, dragCurrent!!.x, dragCurrent!!.y
                )
                drawRect(Color(0x5903A9F4), topLeft = rect.topLeft, size = rect.size)
            }
        }
    }
}