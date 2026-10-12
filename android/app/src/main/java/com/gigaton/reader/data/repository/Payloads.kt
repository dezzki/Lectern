package com.gigaton.reader.data.repository

import com.gigaton.reader.domain.AnnotationRec
import com.gigaton.reader.domain.float
import com.gigaton.reader.ui.pdf.InkStrokeNorm
import com.gigaton.reader.ui.pdf.OffsetNorm
import com.gigaton.reader.ui.pdf.QuadNorm
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Builders and readers for annotation payloads (normalized coordinates). */
object Payloads {

    const val HIGHLIGHT_COLOR = "#FFEB3B"
    const val INK_COLOR = "#E91E63"

    // ------------------------- build ------------------------- //

    fun highlight(text: String?, color: String, quad: QuadNorm): JsonObject = buildJsonObject {
        if (!text.isNullOrBlank()) put("text", text)
        put("color", color)
        put("x0", quad.x0); put("y0", quad.y0)
        put("x1", quad.x1); put("y1", quad.y1)
    }

    fun note(text: String, selectedText: String?, pos: OffsetNorm): JsonObject = buildJsonObject {
        put("text", text)
        selectedText?.takeIf { it.isNotBlank() }?.let { put("selected_text", it) }
        put("x", pos.x); put("y", pos.y)
    }

    fun bookmark(label: String, pos: OffsetNorm?): JsonObject = buildJsonObject {
        put("label", label)
        if (pos != null) { put("x", pos.x); put("y", pos.y) }
    }

    fun ink(strokes: List<InkStrokeNorm>): JsonObject = buildJsonObject {
        put("strokes", buildJsonArray {
            for (s in strokes) {
                add(
                    buildJsonObject {
                        put("color", s.color)
                        put("width", s.widthPx)
                        put("points", buildJsonArray {
                            for (p in s.points) {
                                add(JsonArray(listOf(JsonPrimitive(p.x), JsonPrimitive(p.y))))
                            }
                        })
                    }
                )
            }
        })
    }

    // ------------------------- read ------------------------- //

    /** Normalized quad stored on a highlight annotation. */
    fun AnnotationRec.highlightQuad(): QuadNorm? {
        if (kind != "highlight") return null
        val x0 = payload.float("x0") ?: return null
        val y0 = payload.float("y0") ?: return null
        val x1 = payload.float("x1") ?: return null
        val y1 = payload.float("y1") ?: return null
        return QuadNorm(x0, y0, x1, y1)
    }

    fun AnnotationRec.noteText(): String = payload["text"]?.let {
        (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content
    } ?: ""

    fun AnnotationRec.bookmarkLabel(): String = payload["label"]?.let {
        (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content
    } ?: ""
}