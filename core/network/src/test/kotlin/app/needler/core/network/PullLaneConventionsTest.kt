package app.needler.core.network

import app.needler.core.network.v1.DefaultV1Api
import app.needler.core.network.v1.RequestKind
import app.needler.core.network.v1.V1Api
import app.needler.core.network.v1.dto.AlbumRequestDto
import app.needler.core.network.v1.dto.BatchAlbumItemDto
import app.needler.core.network.v1.dto.BatchAlbumRequestDto
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The pull lane's status conventions, asserted over real HTTP.
 *
 * REQUIREMENTS.md "Request and acquire" names four traps in this lane, and every one of them is a
 * fact about a status code or a URL rather than about a mapper — so none of them is testable against
 * a fake `V1Api`, which is why there was no test for any of them until this file. A fake answers in
 * DTOs; the question here is what the transport does with `202`, `422`, `403`, `404` and `400`.
 *
 *  1. **The accepted status is `202`, not `200`.** "A client that treats anything other than 200 as
 *     failure reports every successful pull as an error." Both request endpoints answer 202.
 *  2. **The batch cap is a decode-time `422`.** 501 items never reach the handler, so the response's
 *     `overflow` is always `0` and the caller chunks instead.
 *  3. **Cancel and retry of a *request* refuse in-band; the download-task pair does not.** A refused
 *     request cancel is `200` with `success=false`; a refused task cancel is a real `404`, `403` or
 *     `400`. Two adjacent pairs with opposite conventions, so the tests come in pairs too.
 *  4. **`retryDownload` returns a *new* task id**, which is the fact the `pull` row has to be
 *     re-keyed on.
 *
 * Paging is checked here for the same reason: it is asymmetric across three endpoints in one lane,
 * and what proves it is the query string on the wire.
 */
class PullLaneConventionsTest {

    private lateinit var server: MockWebServer
    private lateinit var credentials: TestCredentials
    private lateinit var http: NeedlerHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        credentials = TestCredentials()
            .withServer("http://" + server.hostName + ":" + server.port)
        http = NeedlerHttpClient(credentials = credentials, retryPolicy = RetryPolicy.None)
    }

    @After
    fun tearDown() {
        http.shutdown()
        server.close()
    }

    private fun api(): V1Api = DefaultV1Api(http, credentials)

    // ---- 202 is an acceptance, not a failure --------------------------------

    @Test
    fun `a 202 from requests-new is a success and carries the server's status`() {
        server.enqueue(
            json(
                202,
                "{\"success\":true,\"message\":\"queued\",\"musicbrainz_id\":\"" + RG +
                    "\",\"status\":\"awaiting_approval\"," +
                    "\"quality_snapshot_summary\":\"Prefer FLAC\"}",
            ),
        )

        val response = runBlocking { api().requestAlbum(AlbumRequestDto(musicbrainzId = RG)) }

        assertEquals("awaiting_approval", response.status)
        assertEquals("Prefer FLAC", response.qualitySnapshotSummary)
    }

    /**
     * The failure mode item 1 names, reached through the body instead of the status line.
     *
     * The server's msgspec structs emit every field including nulls, and the decoder coerces an
     * unexpected null onto the field's default. With a non-null `success` defaulting to `false`,
     * this accepted 202 used to decode as a refusal.
     */
    @Test
    fun `a 202 whose success flag is null is still an acceptance`() {
        server.enqueue(
            json(
                202,
                "{\"success\":null,\"message\":null,\"musicbrainz_id\":\"" + RG +
                    "\",\"status\":\"pending\",\"quality_snapshot_summary\":null}",
            ),
        )

        val response = runBlocking { api().requestAlbum(AlbumRequestDto(musicbrainzId = RG)) }

        assertNull(response.success)
        assertEquals("pending", response.status)
    }

    @Test
    fun `a 202 that omits the success flag entirely is still an acceptance`() {
        server.enqueue(json(202, "{\"musicbrainz_id\":\"" + RG + "\",\"status\":\"pending\"}"))

        val response = runBlocking { api().requestAlbum(AlbumRequestDto(musicbrainzId = RG)) }

        assertNull(response.success)
        assertEquals("pending", response.status)
    }

    @Test
    fun `a 202 from requests-batch is a success`() {
        server.enqueue(
            json(202, "{\"success\":true,\"requested\":2,\"skipped\":1,\"overflow\":0,\"status\":\"pending\"}"),
        )

        val response = runBlocking { api().requestAlbums(batchOf(3)) }

        assertEquals(2, response.requested)
        assertEquals(1, response.skipped)
    }

    /** The one 202 fact a client can get wrong in the other direction: a real `4xx` still fails. */
    @Test
    fun `a 422 from requests-new is a failure, not an acceptance`() {
        server.enqueue(
            json(422, "{\"error\":{\"code\":\"VALIDATION_ERROR\",\"message\":\"unknown release group\"}}"),
        )

        val failure = failureFrom { api().requestAlbum(AlbumRequestDto(musicbrainzId = RG)) }

        assertTrue("got " + failure, failure is NetworkError.InvalidRequest)
        assertEquals(422, (failure as NetworkError.InvalidRequest).statusCode)
    }

    // ---- the batch cap is a decode-time 422 ---------------------------------

    @Test
    fun `501 items never leaves the device, because the server would reject it before the handler`() {
        val tooMany: BatchAlbumRequestDto = batchOf(BatchAlbumRequestDto.MAX_ITEMS + 1)

        val refused: Throwable = try {
            runBlocking { api().requestAlbums(tooMany) }
            throw AssertionError("a 501-item batch was expected to be refused before the call")
        } catch (error: IllegalArgumentException) {
            error
        }

        assertTrue(refused.message.orEmpty().contains("500"))
        // Nothing was sent: the cap is the caller's to honour, not something to learn from a 422.
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `an over-sized batch that did reach the server is a 422 rather than an overflow count`() {
        server.enqueue(json(422, "{\"error\":{\"code\":\"VALIDATION_ERROR\",\"message\":\"too many items\"}}"))

        val failure = failureFrom { api().requestAlbums(batchOf(BatchAlbumRequestDto.MAX_ITEMS)) }

        assertTrue("got " + failure, failure is NetworkError.InvalidRequest)
    }

    // ---- the two opposite refusal conventions ------------------------------

    @Test
    fun `a refused request cancel is a 200 with success false, which does not throw`() {
        server.enqueue(
            json(200, "{\"success\":false,\"message\":\"Cannot cancel request with status 'processing'\"}"),
        )

        val response = runBlocking { api().cancelRequest(RG, RequestKind.Album) }

        assertFalse(response.success)
        assertTrue(response.message.contains("processing"))
    }

    @Test
    fun `a request cancel for another user's MBID is the one real status on that endpoint`() {
        server.enqueue(json(403, "{\"error\":{\"code\":\"FORBIDDEN\",\"message\":\"not your request\"}}"))

        val failure = failureFrom { api().cancelRequest(RG, RequestKind.Album) }

        assertTrue("got " + failure, failure is NetworkError.Forbidden)
    }

    @Test
    fun `a refused request retry is also a 200 with success false`() {
        server.enqueue(json(200, "{\"success\":false,\"message\":\"nothing to retry\"}"))

        val response = runBlocking { api().retryRequest(RG, RequestKind.Album) }

        assertFalse(response.success)
    }

    @Test
    fun `a download cancel for a task that is gone is a 404, not an in-band false`() {
        server.enqueue(json(404, "{\"error\":{\"code\":\"NOT_FOUND\",\"message\":\"no such task\"}}"))

        val failure = failureFrom { api().cancelDownload("task-1") }

        assertTrue("got " + failure, failure is NetworkError.NotFound)
    }

    @Test
    fun `a download cancel for another user's task is a 403`() {
        server.enqueue(json(403, "{\"error\":{\"code\":\"FORBIDDEN\",\"message\":\"not your task\"}}"))

        val failure = failureFrom { api().cancelDownload("task-1") }

        assertTrue("got " + failure, failure is NetworkError.Forbidden)
    }

    @Test
    fun `a download retry on a state that cannot be retried is a 400`() {
        server.enqueue(json(400, "{\"error\":{\"code\":\"INVALID_STATE\",\"message\":\"task is downloading\"}}"))

        val failure = failureFrom { api().retryDownload("task-1") }

        assertTrue("got " + failure, failure is NetworkError.InvalidRequest)
        assertEquals(400, (failure as NetworkError.InvalidRequest).statusCode)
    }

    // ---- retry mints a new task id -----------------------------------------

    @Test
    fun `retrying a download answers with a new task id rather than the one that was asked about`() {
        server.enqueue(json(200, "{\"success\":true,\"task_id\":\"task-42\"}"))

        val response = runBlocking { api().retryDownload("old-task") }

        assertEquals("task-42", response.taskId)
        val request: RecordedRequest = server.takeRequest()
        assertTrue(request.target.contains("/downloads/old-task/retry"))
        assertEquals("POST", request.method)
    }

    // ---- monitor_artist reaches the body -----------------------------------

    @Test
    fun `monitor_artist is on the wire, not just on the sheet`() {
        server.enqueue(json(202, "{\"success\":true,\"musicbrainz_id\":\"" + RG + "\",\"status\":\"pending\"}"))

        runBlocking {
            api().requestAlbum(
                AlbumRequestDto(
                    musicbrainzId = RG,
                    artist = "Slint",
                    album = "Spiderland",
                    year = 1991,
                    monitorArtist = true,
                ),
            )
        }

        val body: String = server.takeRequest().body?.utf8().orEmpty()
        assertTrue("body was " + body, body.contains("\"monitor_artist\":true"))
        assertTrue(body.contains("\"musicbrainz_id\":\"" + RG + "\""))
    }

    // ---- paging is asymmetric ----------------------------------------------

    @Test
    fun `the two unpaged request lists ask for no page at all`() {
        server.enqueue(json(200, "{\"items\":[],\"count\":0}"))
        server.enqueue(json(200, "{\"items\":[],\"count\":0,\"retrying\":[]}"))

        runBlocking {
            api().activeRequests()
            api().wantedRequests()
        }

        repeat(2) {
            val url = server.takeRequest().url
            assertNull("paged " + url.encodedPath, url.queryParameter("page"))
            assertNull("paged " + url.encodedPath, url.queryParameter("page_size"))
        }
    }

    @Test
    fun `history is the one request list that pages, and the one with totals`() {
        server.enqueue(
            json(200, "{\"items\":[],\"total\":46,\"page\":3,\"page_size\":20,\"total_pages\":3}"),
        )

        val page = runBlocking { api().requestHistory(page = 3, pageSize = 20) }

        assertEquals(46, page.total)
        assertEquals(3, page.totalPages)
        val url = server.takeRequest().url
        assertEquals("3", url.queryParameter("page"))
        assertEquals("20", url.queryParameter("page_size"))
    }

    /**
     * The downloads list pages but reports no totals, so a caller can only ask and see.
     *
     * REQUIREMENTS.md: "an infinitely scrolling list is fine; a 'page 3 of 7' control is not
     * implementable". The body here carries a `total` anyway, as a server one version ahead might;
     * `DownloadListDto` has nowhere to put it, so it is ignored rather than becoming a page count a
     * screen could draw. The only honest end-of-list signal is a short page, which
     * `DefaultPullRepository.refreshPulls` is the test of.
     */
    @Test
    fun `the downloads list pages blindly, with no total to count against`() {
        server.enqueue(json(200, "{\"items\":[],\"page\":2,\"page_size\":50,\"total\":999}"))

        val list = runBlocking { api().downloads(page = 2, pageSize = 50) }

        assertEquals(2, list.page)
        assertEquals(50, list.pageSize)
        assertTrue(list.items.isEmpty())
        val url = server.takeRequest().url
        assertEquals("2", url.queryParameter("page"))
        assertEquals("50", url.queryParameter("page_size"))
    }

    // ---- helpers -----------------------------------------------------------

    private fun batchOf(count: Int): BatchAlbumRequestDto = BatchAlbumRequestDto(
        items = List(count) { BatchAlbumItemDto(musicbrainzId = "mbid-" + it) },
    )

    private fun json(code: Int, body: String): MockResponse = MockResponse.Builder()
        .code(code)
        .addHeader("Content-Type", "application/json")
        .body(body)
        .build()

    private fun failureFrom(block: suspend () -> Unit): NetworkError = try {
        runBlocking { block() }
        throw AssertionError("the call was expected to fail")
    } catch (error: NetworkError) {
        error
    }

    private companion object {
        const val RG = "0f1c3b5e-8a2d-4f6b-9c7e-1d2a3b4c5d6e"
    }
}
