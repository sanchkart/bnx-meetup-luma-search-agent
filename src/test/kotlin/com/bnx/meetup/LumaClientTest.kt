package com.bnx.meetup

import com.bnx.meetup.client.luma.LumaClient
import com.bnx.meetup.client.luma.LumaClientException
import com.bnx.meetup.client.luma.filter.MeetupFilters
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.OffsetDateTime
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Exercises [LumaClient] end-to-end against a fake Luma API served by the JDK's
 * built-in HTTP server, covering pagination, filtering, detail lookups and the
 * retry / error handling paths.
 */
class LumaClientTest {

    private lateinit var server: HttpServer
    private val pageRequests = AtomicInteger()
    private val detailRequests = AtomicInteger()
    private val sleeps = mutableListOf<Duration>()

    /** Handler for the discover feed; tests override it to shape responses. */
    private var pageHandler: (HttpExchange) -> Unit = { it.respond(200, page(entries = emptyList())) }

    /** Per-slug detail responses; anything not listed gets a 404. */
    private val details = mutableMapOf<String, String>()

    private val now: OffsetDateTime = OffsetDateTime.parse("2026-06-01T00:00:00Z")

    @BeforeTest
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/discover/get-paginated-events") { exchange ->
            pageRequests.incrementAndGet()
            pageHandler(exchange)
        }
        server.createContext("/url") { exchange ->
            detailRequests.incrementAndGet()
            val slug = exchange.requestURI.rawQuery
                .split("&").map { it.split("=", limit = 2) }
                .firstOrNull { it[0] == "url" }?.getOrNull(1)
                ?.let { URLDecoder.decode(it, StandardCharsets.UTF_8) }
            val body = details[slug]
            if (body != null) exchange.respond(200, body) else exchange.respond(404, """{"message":"not found"}""")
        }
        server.start()
    }

    @AfterTest
    fun stopServer() {
        server.stop(0)
    }

    private fun client(maxRetries: Int = 2) = LumaClient(
        baseUrl = "http://127.0.0.1:${server.address.port}/",
        maxRetries = maxRetries,
        retryBackoff = Duration.ofMillis(100),
        sleeper = { sleeps += it },
    )

    @Test
    fun findEventsFiltersByCityKeywordsAndOpenRegistration() {
        pageHandler = {
            it.respond(
                200,
                page(
                    entries = listOf(
                        event("open", "Kotlin Meetup Amsterdam", "Amsterdam"),
                        event("waitlist", "Rust Amsterdam", "Amsterdam"),
                        event("utrecht", "Kotlin Utrecht", "Utrecht"),
                        event("yoga", "Sunrise Yoga", "Amsterdam"),
                        event("past", "Kotlin Retro", "Amsterdam", start = "2026-05-01T18:00:00.000Z"),
                        // Explicit JSON nulls must not crash parsing.
                        """{"event":{"api_id":"evt-nullgeo","name":"Dev Drinks","url":"nullgeo",""" +
                            """"start_at":"2026-06-20T18:00:00.000Z","geo_address_info":null,"timezone":null}}""",
                        // Garbage entries are skipped rather than fatal.
                        """"not-an-object"""",
                        """{"event":null}""",
                    ),
                ),
            )
        }
        details["open"] = detail("open", "A hands-on evening about Kotlin coroutines and Ktor.", cents = 0)
        details["waitlist"] = detail("waitlist", "Rust systems programming night.")
        details["utrecht"] = detail("open", "Kotlin in Utrecht.")

        val result = client().findEvents(
            city = "Amsterdam",
            keywords = MeetupFilters.DEFAULT_TECH_KEYWORDS,
            maxResults = 10,
            now = now,
        )

        assertEquals(listOf("Kotlin Meetup Amsterdam"), result.map { it.name })
        assertEquals("A hands-on evening about Kotlin coroutines and Ktor.", result.single().summary)
        assertEquals("Free", result.single().price)
        // Only Amsterdam tech events that are upcoming trigger a detail lookup.
        assertEquals(2, detailRequests.get())
    }

    @Test
    fun findEventsFollowsPaginationCursor() {
        pageHandler = { exchange ->
            val cursor = exchange.requestURI.query?.substringAfter("pagination_cursor=", "")
            if (cursor.isNullOrEmpty()) {
                exchange.respond(
                    200,
                    page(
                        listOf(event("a", "Kotlin A", "Amsterdam", start = "2026-06-19T18:00:00.000Z")),
                        hasMore = true,
                        nextCursor = "c 2",
                    ),
                )
            } else {
                assertEquals("c 2", URLDecoder.decode(cursor, StandardCharsets.UTF_8))
                exchange.respond(200, page(listOf(event("b", "Kotlin B", "Amsterdam"))))
            }
        }
        details["a"] = detail("open", "Kotlin A.", cents = 2500, currency = "eur")
        details["b"] = detail("open", "Kotlin B.")

        val result = client().findEvents("Amsterdam", maxResults = 10, now = now)

        assertEquals(listOf("Kotlin A", "Kotlin B"), result.map { it.name })
        assertEquals("\u20ac25", result.first().price)
        assertEquals(2, pageRequests.get())
    }

    @Test
    fun retriesTransientServerErrorsWithBackoff() {
        pageHandler = { exchange ->
            if (pageRequests.get() < 3) {
                exchange.responseHeaders.add("Retry-After", "1")
                exchange.respond(503, "busy")
            } else {
                exchange.respond(200, page(listOf(event("a", "Kotlin A", "Amsterdam"))))
            }
        }
        details["a"] = detail("open", "Kotlin A.")

        val result = client(maxRetries = 3).findEvents("Amsterdam", now = now)

        assertEquals(1, result.size)
        assertEquals(3, pageRequests.get())
        // Retry-After (1s) wins over the configured 100ms exponential backoff.
        assertEquals(listOf(Duration.ofSeconds(1), Duration.ofSeconds(1)), sleeps)
    }

    @Test
    fun failsWithLumaClientExceptionWhenRetriesAreExhausted() {
        pageHandler = { it.respond(500, "boom") }

        val error = assertFailsWith<LumaClientException> { client(maxRetries = 2).findEvents("Amsterdam", now = now) }

        assertTrue(error.message!!.contains("HTTP 500"), error.message)
        assertEquals(3, pageRequests.get())
        assertEquals(listOf(Duration.ofMillis(100), Duration.ofMillis(200)), sleeps)
    }

    @Test
    fun doesNotRetryClientErrors() {
        pageHandler = { it.respond(403, "forbidden") }

        assertFailsWith<LumaClientException> { client().findEvents("Amsterdam", now = now) }

        assertEquals(1, pageRequests.get())
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun malformedPageBodyIsReportedAsLumaClientException() {
        pageHandler = { it.respond(200, "<html>not json</html>") }

        val error = assertFailsWith<LumaClientException> { client().findEvents("Amsterdam", now = now) }

        assertTrue(error.message!!.contains("Malformed JSON"), error.message)
    }

    @Test
    fun eventIsSkippedWhenDetailLookupFails() {
        pageHandler = {
            it.respond(200, page(listOf(event("missing", "Kotlin A", "Amsterdam"), event("broken", "Kotlin B", "Amsterdam"))))
        }
        // "missing" has no detail entry -> 404; "broken" returns garbage.
        details["broken"] = "{not json"

        val result = client().findEvents("Amsterdam", now = now)

        assertTrue(result.isEmpty())
    }

    @Test
    fun connectionFailureSurfacesAsLumaClientException() {
        server.stop(0)

        val error = assertFailsWith<LumaClientException> { client(maxRetries = 1).findEvents("Amsterdam", now = now) }

        assertTrue(error.message!!.contains("failed after 2 attempt(s)"), error.message)
        assertEquals(1, sleeps.size)
    }

    @Test
    fun rejectsInvalidArguments() {
        val client = client()
        assertFailsWith<IllegalArgumentException> { client.findEvents("   ") }
        assertFailsWith<IllegalArgumentException> { client.findEvents("Amsterdam", maxResults = 0) }
        assertFailsWith<IllegalArgumentException> { client.findEvents("Amsterdam", maxPages = 0) }
        assertFailsWith<IllegalArgumentException> { client.findEvents("Amsterdam", pageSize = 0) }
        assertFailsWith<IllegalArgumentException> { client.findEvents("Amsterdam", pageSize = LumaClient.MAX_PAGE_SIZE + 1) }
        assertFailsWith<IllegalArgumentException> { LumaClient(maxRetries = -1) }
        assertFailsWith<IllegalArgumentException> { LumaClient(requestTimeout = Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> { LumaClient(baseUrl = " ") }
        assertEquals(0, pageRequests.get())
    }

    // --- fixtures -----------------------------------------------------------------

    private fun HttpExchange.respond(status: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        responseHeaders.add("Content-Type", "application/json")
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private fun page(entries: List<String>, hasMore: Boolean = false, nextCursor: String? = null): String =
        """{"entries":[${entries.joinToString(",")}],"has_more":$hasMore,"next_cursor":${nextCursor?.let { "\"$it\"" } ?: "null"}}"""

    private fun event(slug: String, name: String, city: String, start: String = "2026-06-20T18:00:00.000Z"): String =
        """{"event":{"api_id":"evt-$slug","name":"$name","url":"$slug","start_at":"$start",""" +
            """"geo_address_info":{"city":"$city","city_state":"$city, Netherlands"},"timezone":"Europe/Amsterdam"}}"""

    private fun detail(availability: String, text: String, cents: Int? = null, currency: String = "eur"): String {
        val price = if (cents == null) "null" else """{"cents":$cents,"currency":"$currency"}"""
        return """{"data":{"registration_availability":"$availability",""" +
            """"description_mirror":{"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"$text"}]}]},""" +
            """"ticket_info":{"price":$price,"is_free":${cents == null}}}}"""
    }
}
