package com.gigaton.reader.data.network

import com.gigaton.reader.domain.ApiErrorKind
import com.gigaton.reader.domain.ApiException
import com.gigaton.reader.domain.BrowseEntry
import com.gigaton.reader.domain.EntryType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * HTTP client for the GIGATON Research Reader API.
 * All non-health calls carry `Authorization: Bearer <token>`.
 */
class ApiClient(
    baseUrl: String,
    private val token: () -> String?,
    enableLogging: Boolean = false,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    private val normalizedBase: String = baseUrl.trim().trimEnd('/') + "/"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val req = chain.request()
            val tok = token()
            val builder = req.newBuilder().header("User-Agent", "GigatonReader/1.0")
            if (!tok.isNullOrEmpty()) builder.header("Authorization", "Bearer $tok")
            chain.proceed(builder.build())
        }
        .apply {
            if (enableLogging) {
                addInterceptor(HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BASIC
                })
            }
        }
        .build()

    private fun url(path: String, vararg params: Pair<String, String>): String {
        val builder = (normalizedBase + path).toHttpUrl().newBuilder()
        for ((k, v) in params) builder.addQueryParameter(k, v)
        return builder.build().toString()
    }

    private fun jsonBody(value: String): okhttp3.RequestBody =
        value.toRequestBody("application/json".toMediaType())

    private suspend fun <T> request(
        method: String,
        path: String,
        params: List<Pair<String, String>> = emptyList(),
        body: String? = null,
        parse: (String) -> T,
    ): T = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(url(path, *params.toTypedArray()))
        if (body != null) builder.method(method, jsonBody(body))
        else builder.method(method, null)
        try {
            client.newCall(builder.build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                when {
                    resp.code in 200..299 -> runCatching { parse(text) }.getOrElse {
                        throw ApiException(ApiErrorKind.OTHER, "Unsupported response from server")
                    }
                    resp.code == 401 -> throw ApiException(ApiErrorKind.UNAUTHORIZED, "Invalid or missing API token")
                    resp.code == 404 -> throw ApiException(ApiErrorKind.NOT_FOUND, "Resource not found")
                    resp.code == 503 -> throw ApiException(ApiErrorKind.UNAUTHORIZED, "Server missing token config")
                    else -> throw ApiException(ApiErrorKind.SERVER, "Server error (HTTP ${resp.code})")
                }
            }
        } catch (e: ApiException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw ApiException(ApiErrorKind.UNREACHABLE, "Timed out connecting to server")
        } catch (e: IOException) {
            throw ApiException(ApiErrorKind.UNREACHABLE, "Cannot reach server: ${e.message ?: "network error"}")
        }
    }

    private inline fun <reified T> parseJson(text: String): T = json.decodeFromString(text)

    // ------------------------------------------------------------------ //
    // Public API
    // ------------------------------------------------------------------ //

    suspend fun health(): HealthDto =
        request("GET", "api/health") { parseJson(it) }

    suspend fun browse(path: String): List<BrowseEntry> {
        val resp = request("GET", "api/browse", listOf("path" to path)) { parseJson<BrowseResponse>(it) }
        return resp.items.map { item ->
            BrowseEntry(
                name = item.name,
                path = item.path,
                type = if (item.type == "folder") EntryType.FOLDER else EntryType.PDF,
                size = item.size,
                modified = item.modified,
            )
        }
    }

    /** Streams a PDF into [out], invoking [onProgress] with bytes so far and total (or null). */
    suspend fun fetchPdf(
        path: String,
        out: File,
        onProgress: (done: Long, total: Long?) -> Unit = { _, _ -> },
    ) = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(url("api/file", "path" to path))
        try {
            client.newCall(builder.build()).execute().use { resp ->
                when {
                    resp.code !in 200..299 -> {
                        val err = when (resp.code) {
                            401, 503 -> ApiException(ApiErrorKind.UNAUTHORIZED, "Invalid or missing API token")
                            404 -> ApiException(ApiErrorKind.NOT_FOUND, "PDF not found on server")
                            else -> ApiException(ApiErrorKind.SERVER, "Download failed (HTTP ${resp.code})")
                        }
                        throw err
                    }
                    else -> {
                        val body = resp.body ?: throw ApiException(ApiErrorKind.OTHER, "No response body")
                        val total = body.contentLength().takeIf { it >= 0 }
                        out.parentFile?.mkdirs()
                        body.byteStream().use { input ->
                            out.outputStream().use { output ->
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                var done = 0L
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    output.write(buffer, 0, read)
                                    done += read
                                    onProgress(done, total)
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: ApiException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw ApiException(ApiErrorKind.UNREACHABLE, "Timed out while downloading PDF")
        } catch (e: IOException) {
            throw ApiException(ApiErrorKind.UNREACHABLE, "Download failed: ${e.message ?: "network error"}")
        }
    }

    suspend fun listAnnotations(paperPath: String): List<AnnotationDto> =
        request("GET", "api/annotations", listOf("paper_path" to paperPath)) { parseJson(it) }

    suspend fun saveAnnotation(put: AnnotationPut): Boolean {
        val body = json.encodeToString(AnnotationPut.serializer(), put)
        val resp = request("PUT", "api/annotations", body = body) { parseJson<SimpleOk>(it) }
        return resp.applied
    }

    suspend fun deleteAnnotation(id: String) {
        // 404 on delete is treated as success so client-side cleanup is idempotent.
        try {
            request("DELETE", "api/annotations/$id") { it }
        } catch (e: ApiException) {
            if (e.kind == ApiErrorKind.NOT_FOUND) return
            throw e
        }
    }

    suspend fun getProgress(paperPath: String): ProgressDto =
        request("GET", "api/progress", listOf("paper_path" to paperPath)) { parseJson(it) }

    suspend fun saveProgress(put: ProgressPut) {
        val body = json.encodeToString(ProgressPut.serializer(), put)
        request("PUT", "api/progress", body = body) { it }
    }

    /** Validates connectivity: unauth health + authenticated browse of root. */
    suspend fun connectTest(): Pair<HealthDto, List<BrowseEntry>> {
        val h = health()
        if (!h.ok) throw ApiException(ApiErrorKind.OTHER, "Server did not report healthy")
        val entries = browse("")
        return h to entries
    }
}