package com.gigaton.reader.data.repository

import com.gigaton.reader.AppContainer
import com.gigaton.reader.data.local.AnnotationEntity
import com.gigaton.reader.data.local.DownloadEntity
import com.gigaton.reader.data.local.LocalProgressEntity
import com.gigaton.reader.data.local.OutboxEntity
import com.gigaton.reader.data.network.AnnotationDto
import com.gigaton.reader.data.network.AnnotationPut
import com.gigaton.reader.data.network.ProgressPut
import com.gigaton.reader.domain.AnnotationRec
import com.gigaton.reader.domain.ApiException
import com.gigaton.reader.domain.ApiErrorKind
import com.gigaton.reader.domain.BrowseEntry
import com.gigaton.reader.domain.ProgressRec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.time.Instant
import java.util.UUID

/**
 * Coordinates server API, Room persistence, secure settings and on-device
 * files. Owns the offline outbox: edits are always written locally first,
 * queued, and drained to the server whenever a call succeeds. Mistakes are
 * never silently lost: pending operations and the last sync error surface in
 * the UI.
 */
class Repository(private val container: AppContainer) {

    private val settings get() = container.settings
    private val api get() = container.api()
    private val db get() = container.database

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    private val _syncError = MutableStateFlow<String?>(null)
    val syncError: StateFlow<String?> = _syncError

    // ------------------------------------------------------------------ //
    // Connection
    // ------------------------------------------------------------------ //

    /** Saves URL/token and verifies them against health + authenticated browse. */
    suspend fun configureAndTest(baseUrl: String, token: String): ApiException? {
        settings.baseUrl = baseUrl
        settings.token = token
        container.resetApi()
        val failure = runCatching { api.connectTest() }.exceptionOrNull()
        return when (failure) {
            null -> null
            is ApiException -> failure
            else -> ApiException(ApiErrorKind.OTHER, failure.message ?: "Connection failed")
        }
    }

    suspend fun browse(path: String): List<BrowseEntry> = api.browse(path)

    // ------------------------------------------------------------------ //
    // Downloads / offline (page images, server-rendered)
    // ------------------------------------------------------------------ //

    companion object {
        /**
         * Width (px) for page images. Kept modest because a page is often
         * fetched over a high-latency Tailscale link; 1100 is sharp enough to
         * read and zoom into, and much smaller to transfer than 1400.
         */
        const val CLIENT_PAGE_WIDTH = 1100
    }

    fun downloads(): Flow<List<DownloadEntity>> = db.downloadDao().all()

    suspend fun isDownloaded(path: String): Boolean =
        container.pageStore.pinnedCount(path) > 0

    suspend fun pageMeta(path: String) = api.getPageMeta(path)

    /** True when every page of the paper is cached locally (offline-ready). */
    suspend fun isFullyCached(path: String): Boolean {
        val meta = runCatching { api.getPageMeta(path) }.getOrNull() ?: return false
        var local = 0
        for (page in 1..meta.pageCount) {
            if (container.pageStore.existing(path, page) != null) local++
        }
        return local >= meta.pageCount
    }

    /**
     * Resolves a page to a local file. Uses the pinned/cached copy when present;
     * otherwise fetches from the server and caches it (only when [allowNetwork]).
     */
    suspend fun ensurePage(path: String, page: Int, allowNetwork: Boolean): File? {
        container.pageStore.existing(path, page)?.let { file ->
            container.pageStore.touch(path, page)
            return file
        }
        if (!allowNetwork) return null
        val bytes = runCatching { api.fetchPageBytes(path, page, CLIENT_PAGE_WIDTH) }.getOrNull()
            ?: return null
        return runCatching { container.pageStore.put(path, page, bytes, pinned = false) }.getOrNull()
    }

    /**
     * "Download for offline": pins every page image so it is never evicted and
     * the paper opens with no network. Verified against the server's page count.
     */
    suspend fun downloadPaperForOffline(
        path: String,
        onProgress: (done: Int, total: Int) -> Unit,
    ) {
        val meta = api.getPageMeta(path)
        val total = meta.pageCount.coerceAtLeast(0)
        var done = 0
        for (page in 1..total) {
            val alreadyPinned = container.pageStore.isPinned(path, page) &&
                container.pageStore.existing(path, page) != null
            if (!alreadyPinned) {
                val bytes = api.fetchPageBytes(path, page, CLIENT_PAGE_WIDTH)
                container.pageStore.put(path, page, bytes, pinned = true)
            }
            done++
            onProgress(done, total)
        }
        val pinned = container.pageStore.pinnedCount(path)
        if (pinned < total) {
            throw IllegalStateException("Download incomplete ($pinned of $total pages)")
        }
        db.downloadDao().upsert(
            DownloadEntity(
                path = path,
                fileName = path.substringAfterLast('/'),
                size = container.pageStore.pinnedBytes(path),
                downloadedAt = nowIso(),
            )
        )
    }

    suspend fun removeDownload(path: String) {
        container.pageStore.removePaper(path)
        db.downloadDao().delete(path)
    }

    /**
     * Background prefetch of every page of a paper so it becomes offline-ready
     * after being opened. Pages near [fromPage] are fetched first. Cached pages
     * are evictable, unlike downloads.
     */
    suspend fun autoCachePaper(path: String, fromPage: Int, onProgress: (done: Int, total: Int) -> Unit) {
        if (!settings.autoCacheEnabled) return
        val meta = runCatching { api.getPageMeta(path) }.getOrNull() ?: return
        val total = meta.pageCount
        if (total <= 0) return
        val order = ((fromPage.coerceIn(1, total)..total) + (1 until fromPage.coerceIn(1, total))).toList()
        var done = 0
        for (page in order) {
            if (container.pageStore.existing(path, page) != null) {
                done++
                continue
            }
            val bytes = runCatching { api.fetchPageBytes(path, page, CLIENT_PAGE_WIDTH) }.getOrNull()
                ?: return // offline or server issue: stop quietly
            runCatching { container.pageStore.put(path, page, bytes, pinned = false) }
            done++
            onProgress(done, total)
        }
    }

    suspend fun cacheStats() = container.pageStore.stats()

    suspend fun clearReaderCache() = container.pageStore.clearCache()

    var cacheBudgetBytes: Long
        get() = settings.cacheBudgetBytes
        set(value) {
            settings.cacheBudgetBytes = value
        }

    var autoCacheEnabled: Boolean
        get() = settings.autoCacheEnabled
        set(value) {
            settings.autoCacheEnabled = value
        }

    // ------------------------------------------------------------------ //
    // Annotations
    // ------------------------------------------------------------------ //

    /** Reactive annotations for a paper (server-mirrored + unsynced local). */
    fun annotationsFlow(paperPath: String): Flow<List<AnnotationRec>> =
        db.annotationDao().forPaper(paperPath).map { list ->
            list.sortedWith(compareBy({ it.page }, { it.createdAt ?: "" })).map { it.toRec() }
        }

    /** Pulls server annotations into local storage without clobbering unsynced edits. */
    suspend fun refreshAnnotations(paperPath: String) {
        runCatching { api.listAnnotations(paperPath) }
            .onSuccess { dtos ->
                for (dto in dtos) {
                    val existing = db.annotationDao().byId(dto.id)
                    if (existing == null) {
                        db.annotationDao().upsert(dto.toEntity(unsynced = false))
                    } else if (!existing.unsynced &&
                        (existing.updatedAt ?: "") < (dto.updatedAt ?: "")
                    ) {
                        db.annotationDao().upsert(dto.toEntity(unsynced = false))
                    }
                }
            }
            .onFailure { /* offline reads still show local state */ }
    }

    /** Creates or updates an annotation (local-first, then queued sync). Returns the id. */
    suspend fun saveAnnotation(
        paperPath: String,
        page: Int,
        kind: String,
        payload: JsonObject,
        id: String? = null,
    ): String {
        val aid = id ?: UUID.randomUUID().toString()
        val now = nowIso()
        db.annotationDao().upsert(
            AnnotationEntity(
                id = aid, paperPath = paperPath, page = page, kind = kind,
                payloadJson = payload.toString(), schemaVersion = 1,
                createdAt = now, updatedAt = now, unsynced = true,
            )
        )
        val put = AnnotationPut(
            id = aid, paperPath = paperPath, page = page, kind = kind,
            payload = payload, schemaVersion = 1, createdAt = now, updatedAt = now,
        )
        db.outboxDao().insert(
            OutboxEntity(op = "annotation_put", body = json.encodeToString(AnnotationPut.serializer(), put), createdAt = now)
        )
        syncAll()
        return aid
    }

    suspend fun deleteAnnotation(id: String) {
        db.annotationDao().byId(id) ?: return
        db.annotationDao().deleteById(id)
        val body = kotlinx.serialization.json.buildJsonObject {
            put("id", kotlinx.serialization.json.JsonPrimitive(id))
        }.toString()
        db.outboxDao().insert(
            OutboxEntity(op = "annotation_delete", body = body, createdAt = nowIso())
        )
        syncAll()
    }

    // ------------------------------------------------------------------ //
    // Progress
    // ------------------------------------------------------------------ //

    suspend fun loadProgress(paperPath: String): ProgressRec {
        val server = runCatching { api.getProgress(paperPath) }.getOrNull()
        if (server != null) {
            db.progressDao().upsert(
                LocalProgressEntity(
                    paperPath = paperPath, page = server.page,
                    positionJson = server.position?.toString(),
                    updatedAt = server.updatedAt ?: nowIso(),
                )
            )
            return ProgressRec(
                paperPath = paperPath, page = server.page,
                position = server.position,
                updatedAt = server.updatedAt,
            )
        }
        val local = db.progressDao().forPaper(paperPath)
        return if (local != null) {
            ProgressRec(
                paperPath = paperPath, page = local.page,
                position = local.positionJson?.let { json.parseToJsonElement(it).jsonObject },
                updatedAt = local.updatedAt,
            )
        } else ProgressRec(paperPath = paperPath, page = 1)
    }

    /** Saves progress locally and queues it; only the latest entry per paper is kept. */
    suspend fun saveProgress(paperPath: String, page: Int) {
        val now = nowIso()
        db.progressDao().upsert(LocalProgressEntity(paperPath, page, null, now))
        db.outboxDao().deletePendingProgress(paperPath)
        val put = ProgressPut(paperPath = paperPath, page = page, updatedAt = now)
        db.outboxDao().insert(
            OutboxEntity(op = "progress_put", body = json.encodeToString(ProgressPut.serializer(), put), createdAt = now)
        )
        syncAll()
    }

    // ------------------------------------------------------------------ //
    // Outbox
    // ------------------------------------------------------------------ //

    fun pendingCount(): Flow<Int> = db.outboxDao().count()
    fun pendingOps(): Flow<List<OutboxEntity>> = db.outboxDao().all()

    /** Attempts to flush queued operations to the server. Keeps failures queued. */
    suspend fun syncAll() {
        val ops = db.outboxDao().allOnce()
        if (ops.isEmpty()) {
            _syncError.value = null
            return
        }
        var error: ApiException? = null
        for (op in ops) {
            try {
                when (op.op) {
                    "annotation_put" -> {
                        val put = json.decodeFromString(AnnotationPut.serializer(), op.body)
                        api.saveAnnotation(put)
                        db.annotationDao().markSynced(put.id!!)
                    }
                    "annotation_delete" -> {
                        val payload = json.parseToJsonElement(op.body).jsonObject
                        val id = payload["id"]!!.let {
                            (it as kotlinx.serialization.json.JsonPrimitive).content
                        }
                        api.deleteAnnotation(id)
                    }
                    "progress_put" -> {
                        val put = json.decodeFromString(ProgressPut.serializer(), op.body)
                        api.saveProgress(put)
                    }
                }
                db.outboxDao().delete(op.rowId)
            } catch (e: ApiException) {
                error = e
                when (e.kind) {
                    ApiErrorKind.UNREACHABLE, ApiErrorKind.UNAUTHORIZED -> break // retry later
                    ApiErrorKind.NOT_FOUND -> if (op.op == "annotation_delete") {
                        db.outboxDao().delete(op.rowId); continue
                    } else break
                    else -> break
                }
            } catch (t: Throwable) {
                error = ApiException(ApiErrorKind.OTHER, t.message ?: "sync error")
                break
            }
        }
        _syncError.value = error?.let {
            "${it.message} — ${db.outboxDao().allOnce().size} change(s) still pending"
        }
    }

    // ------------------------------------------------------------------ //
    // mappers
    // ------------------------------------------------------------------ //

    private fun AnnotationEntity.toRec(): AnnotationRec = AnnotationRec(
        id = id,
        paperPath = paperPath,
        page = page,
        kind = kind,
        payload = json.parseToJsonElement(payloadJson).jsonObject,
        createdAt = createdAt,
        updatedAt = updatedAt,
        unsynced = unsynced,
    )

    private fun AnnotationDto.toEntity(unsynced: Boolean): AnnotationEntity = AnnotationEntity(
        id = id,
        paperPath = paperPath,
        page = page,
        kind = kind,
        payloadJson = payload.toString(),
        schemaVersion = schemaVersion,
        createdAt = createdAt,
        updatedAt = updatedAt,
        unsynced = unsynced,
    )

    private fun nowIso(): String = Instant.now().toString()
}