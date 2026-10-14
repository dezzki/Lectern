package com.gigaton.reader.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.float
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A single entry in a folder listing. */
@Serializable
data class BrowseEntry(
    val name: String,
    val path: String,
    val type: EntryType,
    val size: Long = 0,
    val modified: String? = null,
)

@Serializable
enum class EntryType { FOLDER, PDF, COMIC }

/** Structured error kinds used to render helpful messages in the UI. */
enum class ApiErrorKind { UNREACHABLE, UNAUTHORIZED, NOT_FOUND, SERVER, OTHER }

class ApiException(val kind: ApiErrorKind, message: String) : Exception(message)

/** An annotation in the app-facing shape (mirrors the server DTO). */
data class AnnotationRec(
    val id: String,
    val paperPath: String,
    val page: Int,
    val kind: String, // highlight | note | ink | bookmark
    val payload: JsonObject,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val unsynced: Boolean = false,
)

/** Reading progress for a paper. */
data class ProgressRec(
    val paperPath: String,
    val page: Int,
    val position: JsonObject? = null,
    val updatedAt: String? = null,
)

/** Convenience readers for normalized payload fields. */
fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

fun JsonObject.float(key: String): Float? =
    (this[key] as? JsonPrimitive)?.floatOrNull

fun JsonObject.int(key: String): Int? =
    (this[key] as? JsonPrimitive)?.intOrNull

fun JsonObject.obj(key: String): JsonObject? = this[key]?.jsonObject

fun JsonObject.arr(key: String): JsonArray? = this[key]?.jsonArray

fun JsonObject.bool(key: String): Boolean? = this[key]?.jsonPrimitive?.booleanOrNull

internal val JsonPrimitive.contentOrNull: String?
    get() = if (this is JsonPrimitive && content != "null") content else null