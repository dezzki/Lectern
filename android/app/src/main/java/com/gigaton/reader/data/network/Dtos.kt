package com.gigaton.reader.data.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

@Serializable
data class HealthDto(
    val ok: Boolean = false,
    @SerialName("root_exists") val rootExists: Boolean = false,
    val version: String = "",
    @SerialName("schema_version") val schemaVersion: Int = 1,
)

@Serializable
data class BrowseItemDto(
    val name: String,
    val path: String,
    val type: String,
    val size: Long = 0,
    val modified: String? = null,
)

@Serializable
data class BrowseResponse(
    val path: String = "",
    val items: List<BrowseItemDto> = emptyList(),
    val total: Int = 0,
)

@Serializable
data class AnnotationDto(
    val id: String,
    @SerialName("paper_path") val paperPath: String,
    val page: Int,
    val kind: String,
    val payload: JsonObject = buildJsonObject {},
    @SerialName("schema_version") val schemaVersion: Int = 1,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    val deleted: Boolean = false,
)

@Serializable
data class AnnotationPut(
    val id: String? = null,
    @SerialName("paper_path") val paperPath: String,
    val page: Int,
    val kind: String,
    val payload: JsonObject = buildJsonObject {},
    @SerialName("schema_version") val schemaVersion: Int = 1,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class SimpleOk(
    val ok: Boolean = false,
    val id: String? = null,
    val applied: Boolean = true,
)

@Serializable
data class ProgressDto(
    @SerialName("paper_path") val paperPath: String,
    val page: Int = 1,
    val position: JsonObject? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class ProgressPut(
    @SerialName("paper_path") val paperPath: String,
    val page: Int,
    val position: JsonObject? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class SyncStateDto(
    val pending: Int = 0,
    val lastError: String? = null,
)

@Serializable
data class PageMetaPageDto(
    val page: Int,
    @SerialName("width_pt") val widthPt: Double,
    @SerialName("height_pt") val heightPt: Double,
)

@Serializable
data class PageMetaDto(
    val path: String,
    @SerialName("page_count") val pageCount: Int = 0,
    val pages: List<PageMetaPageDto> = emptyList(),
)