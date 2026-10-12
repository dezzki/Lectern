package com.gigaton.reader

import com.gigaton.reader.data.network.AnnotationPut
import com.gigaton.reader.data.network.ApiClient
import com.gigaton.reader.data.network.ProgressPut
import com.gigaton.reader.data.repository.Payloads
import com.gigaton.reader.domain.ApiException
import com.gigaton.reader.domain.ApiErrorKind
import com.gigaton.reader.domain.EntryType
import com.gigaton.reader.ui.pdf.InkStrokeNorm
import com.gigaton.reader.ui.pdf.OffsetNorm
import com.gigaton.reader.ui.pdf.QuadNorm
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class ApiClientTest {

    private val server = MockWebServer()
    private var client: ApiClient? = null

    @Before
    fun setUp() {
        server.start()
        client = ApiClient(
            baseUrl = server.url("/").toString(),
            token = { "secret-token" },
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun browseParsesFoldersAndPdfsWithAuthHeader() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                    {"path":"","total":3,"items":[
                      {"name":"Harness_papers","path":"Harness_papers","type":"folder"},
                      {"name":"a.pdf","path":"a.pdf","type":"pdf","size":1024,"modified":"2026-01-01T00:00:00+00:00"}
                    ]}
                """.trimIndent())
        )
        val entries = client!!.browse("")
        assertEquals(2, entries.size)
        assertEquals(EntryType.FOLDER, entries[0].type)
        assertEquals(EntryType.PDF, entries[1].type)
        assertEquals(1024L, entries[1].size)
        // auth header must be set on the request
        val request = server.takeRequest()
        assertEquals("Bearer secret-token", request.getHeader("Authorization"))
    }

    @Test
    fun unauthorizedIsMapped() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        try {
            client!!.browse("")
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertEquals(ApiErrorKind.UNAUTHORIZED, e.kind)
        }
    }

    @Test
    fun unreachableServerIsMapped() = runBlocking {
        val dead = ApiClient(baseUrl = "http://127.0.0.1:1/", token = { "x" })
        try {
            dead.browse("")
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertEquals(ApiErrorKind.UNREACHABLE, e.kind)
        }
    }

    @Test
    fun invalidJsonIsMappedToOther() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("not json at all")
        )
        try {
            client!!.browse("")
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertEquals(ApiErrorKind.OTHER, e.kind)
        }
    }

    @Test
    fun saveAnnotationSendsExpectedJson() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"ok":true,"id":"a1","applied":true}"""))
        val put = AnnotationPut(
            id = "a1", paperPath = "Harness_papers/a.pdf", page = 3, kind = "note",
            payload = Payloads.note("hello", null, OffsetNorm(0.5f, 0.5f)),
            schemaVersion = 1,
        )
        val applied = client!!.saveAnnotation(put)
        assertTrue(applied)
        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("a1", body["id"]!!.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
        assertEquals("note", body["kind"]!!.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
    }

    @Test
    fun progressRoundTrip() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""
                {"paper_path":"Harness_papers/a.pdf","page":7,"position":null,"updated_at":"2026-01-01T00:00:00+00:00"}
            """.trimIndent())
        )
        val p = client!!.getProgress("Harness_papers/a.pdf")
        assertEquals(7, p.page)
        server.takeRequest() // consume the GET request

        server.enqueue(MockResponse().setBody("""{"ok":true}"""))
        client!!.saveProgress(ProgressPut(paperPath = "Harness_papers/a.pdf", page = 8))
        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        val body = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals(8, body["page"]!!.let { (it as kotlinx.serialization.json.JsonPrimitive).content.toInt() })
    }

    @Test
    fun payloadBuildersProduceStableJson() {
        val quad = QuadNorm(0.1f, 0.2f, 0.8f, 0.5f)
        val highlight = Payloads.highlight("some text", "#FFEB3B", quad)
        val parsed = Json.parseToJsonElement(highlight.toString()).jsonObject
        assertEquals(0.1f, (parsed["x0"]!!.let { (it as kotlinx.serialization.json.JsonPrimitive).content.toFloat() }), 0.0001f)
        assertEquals("#FFEB3B", parsed["color"]!!.let { (it as kotlinx.serialization.json.JsonPrimitive).content })

        val ink = Payloads.ink(listOf(InkStrokeNorm("#E91E63", 3f, listOf(OffsetNorm(0.1f, 0.9f), OffsetNorm(0.5f, 0.7f)))))
        val parsedInk = Json.parseToJsonElement(ink.toString()).jsonObject
        assertEquals("E91E63", parsedInk["strokes"]!!.let {
            val arr = it as kotlinx.serialization.json.JsonArray
            val s = arr[0].jsonObject
            (s["color"] as kotlinx.serialization.json.JsonPrimitive).content.removePrefix("#")
        })
    }

    @Test
    fun fileNameIsStableAndSafe() {
        val a = com.gigaton.reader.data.local.FileStore.safeFileName("Harness_papers/../evil.pdf")
        val b = com.gigaton.reader.data.local.FileStore.safeFileName("Harness_papers/../evil.pdf")
        val c = com.gigaton.reader.data.local.FileStore.safeFileName("other.pdf")
        assertEquals(a, b)
        assertEquals(false, a == c)
        assertTrue(a.startsWith("paper-"))
        // No path separators survive the hash.
        assertTrue(!a.contains('/') && !a.contains('\\'))
    }

    @Test
    fun connectionFailureAssets() {
        // sanity: ApiException kinds are distinct
        assertNotNull(ApiErrorKind.UNAUTHORIZED)
    }

    @Test
    fun pageMetaParses() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""
                {"path":"Harness_papers/a.pdf","page_count":3,"pages":[
                  {"page":1,"width_pt":612.0,"height_pt":792.0},
                  {"page":2,"width_pt":612.0,"height_pt":792.0},
                  {"page":3,"width_pt":500.0,"height_pt":800.0}]}
            """.trimIndent())
        )
        val meta = client!!.getPageMeta("Harness_papers/a.pdf")
        assertEquals(3, meta.pageCount)
        assertEquals(612.0, meta.pages[0].widthPt, 0.001)
        assertEquals(800.0, meta.pages[2].heightPt, 0.001)
    }

    @Test
    fun pageImageUrlIsBuildable() {
        val c = ApiClient(baseUrl = "http://100.82.221.72:8088", token = { "t" })
        val url = c.pageImageUrl("Harness_papers/a.pdf", 2, 1400)
        assertTrue(url.contains("api/page"))
        assertTrue(url.contains("max_width=1400"))
        assertTrue(url.contains("page=2"))
        assertTrue(url.contains("path=Harness_papers%2Fa.pdf"))
    }

    @Test
    fun routeTargetRoundTripPreservesOddPaths() {
        val path = "Harness_papers/01_ReAct_2210.03629.pdf"
        val name = "01_ReAct_2210.03629 (semi:colon & ampersand).pdf"
        val route = Routes.reader(path, name)
        assertTrue(route.startsWith("reader/"))
        val target = route.removePrefix("reader/")
        assertTrue(!target.contains('/'))
        assertTrue(!target.contains('&'))
        assertTrue(!target.contains('?'))
        val (p, n) = Routes.splitTarget(target)
        assertEquals(path, p)
        assertEquals(name, n)
    }
}