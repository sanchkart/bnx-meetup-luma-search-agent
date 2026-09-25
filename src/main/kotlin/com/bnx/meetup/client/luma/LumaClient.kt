package com.bnx.meetup.client.luma

import com.bnx.meetup.client.http.RetryingHttpClient
import com.bnx.meetup.client.luma.filter.MeetupFilters.cityMatches
import com.bnx.meetup.client.luma.filter.MeetupFilters.isAiFocused
import com.bnx.meetup.client.luma.filter.MeetupFilters.isFree
import com.bnx.meetup.client.luma.filter.MeetupFilters.isRegistrationOpen
import com.bnx.meetup.client.luma.filter.MeetupFilters.isUpcoming
import com.bnx.meetup.client.luma.filter.MeetupFilters.titleMatches
import com.bnx.meetup.client.luma.filter.MeetupPrioritizer
import com.bnx.meetup.client.luma.format.EventSummarizer
import com.bnx.meetup.client.luma.format.PriceFormatter
import com.bnx.meetup.domain.Meetup
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.lang.System.Logger.Level
import java.net.URLEncoder
import java.net.http.HttpClient
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.OffsetDateTime

/**
 * Minimal client for Luma's public "discover" feed.
 *
 * Luma exposes an unauthenticated endpoint that powers its local discovery pages:
 *   GET https://api.lu.ma/discover/get-paginated-events
 *
 * The feed is biased to the caller's geographic region and returns events from
 * multiple nearby cities, so we paginate and filter client-side by city.
 *
 * Responsibilities are split across the `client` package: HTTP + retries live in
 * [RetryingHttpClient], relevance predicates in [MeetupFilters], ranking in
 * [MeetupPrioritizer], and detail enrichment in [EventSummarizer] / [PriceFormatter].
 *
 * Transient failures (I/O errors, HTTP 429 and 5xx) are retried up to [maxRetries]
 * times with exponential backoff starting at [retryBackoff]. Any failure that
 * survives the retries surfaces as a [LumaClientException]. The client is
 * stateless and safe to share between threads.
 *
 * @param httpClient shared HTTP client; the default follows redirects and uses a 15s connect timeout.
 * @param baseUrl API origin, overridable for tests.
 * @param requestTimeout per-request timeout (must be positive).
 * @param maxRetries how many times a failed request is retried (0 disables retries).
 * @param retryBackoff initial delay before the first retry; doubled on every further attempt.
 * @param sleeper hook used to wait between retries, injectable to keep tests fast.
 */
class LumaClient(
    httpClient: HttpClient = defaultHttpClient(),
    baseUrl: String = DEFAULT_BASE_URL,
    requestTimeout: Duration = DEFAULT_REQUEST_TIMEOUT,
    maxRetries: Int = DEFAULT_MAX_RETRIES,
    retryBackoff: Duration = DEFAULT_RETRY_BACKOFF,
    sleeper: (Duration) -> Unit = { Thread.sleep(it.toMillis()) },
) {
    private val baseUrl: String = baseUrl.trim().trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true }
    private val http: RetryingHttpClient

    init {
        require(this.baseUrl.isNotEmpty()) { "baseUrl must not be blank" }
        http = RetryingHttpClient(httpClient, requestTimeout, maxRetries, retryBackoff, sleeper)
    }

    /**
     * Fetches upcoming events for the given [city] (case-insensitive match against
     * Luma's `geo_address_info.city`), optionally keeping only those whose title
     * matches one of [keywords].
     *
     * Non-AI events and free events are prioritized: pagination keeps scanning until
     * [maxResults] preferred (free, non-AI) events are collected (or [maxPages] / the
     * over-fetch cap is reached), and the final selection is made by [MeetupPrioritizer].
     *
     * Events whose detail lookup fails are skipped (never advertised) rather than
     * failing the whole call; a failing *page* request, however, is fatal and raises
     * a [LumaClientException] once retries are exhausted.
     *
     * @throws IllegalArgumentException when [city] is blank or a limit is not positive.
     * @throws LumaClientException when the discover feed cannot be fetched or parsed.
     */
    fun findEvents(
        city: String,
        keywords: List<String> = emptyList(),
        maxResults: Int = 30,
        maxPages: Int = 10,
        pageSize: Int = 50,
        now: OffsetDateTime = OffsetDateTime.now(),
    ): List<Meetup> {
        requireValidSearchArgs(city, maxResults, maxPages, pageSize)

        val scan = Scan(city, keywords, now, maxResults)
        var cursor: String? = null
        while (scan.pages < maxPages && !scan.isComplete()) {
            val root = fetchPage(cursor, pageSize)
            scan.consume(root["entries"] as? JsonArray ?: break)
            cursor = root["next_cursor"].asStringOrNull()
            if (root["has_more"].asBooleanOrNull() != true || cursor == null) break
        }

        val selected = MeetupPrioritizer.prioritizeMeetups(scan.matches, maxResults)
        log.log(
            Level.INFO,
            "Luma search for city ''{0}'' scanned {1} events over {2} page(s); {3} candidate(s), {4} selected",
            city,
            scan.scanned,
            scan.pages,
            scan.matches.size,
            selected.size,
        )
        return selected
    }

    /** Mutable state of one [findEvents] call: the candidates collected so far and the stop conditions. */
    private inner class Scan(
        private val city: String,
        private val keywords: List<String>,
        private val now: OffsetDateTime,
        private val maxResults: Int,
    ) {
        val matches = mutableListOf<Meetup>()
        private val seen = mutableSetOf<String>()
        private val overFetchLimit = maxResults * OVERFETCH_FACTOR
        var pages = 0
            private set
        var scanned = 0
            private set

        /** True once enough preferred (free, non-AI) events were found or the over-fetch cap is hit. */
        fun isComplete(): Boolean =
            matches.size >= overFetchLimit || matches.count { isFree(it) && !isAiFocused(it) } >= maxResults

        fun consume(entries: JsonArray) {
            pages++
            for (entry in entries) {
                scanned++
                matches += toCandidate(entry, city, keywords, now, seen) ?: continue
                if (isComplete()) break
            }
        }
    }

    private fun requireValidSearchArgs(city: String, maxResults: Int, maxPages: Int, pageSize: Int) {
        require(city.isNotBlank()) { "city must not be blank" }
        require(maxResults > 0) { "maxResults must be positive, was $maxResults" }
        require(maxPages > 0) { "maxPages must be positive, was $maxPages" }
        require(pageSize in 1..MAX_PAGE_SIZE) { "pageSize must be in 1..$MAX_PAGE_SIZE, was $pageSize" }
    }

    /**
     * Parses a discover-feed [entry] and applies all filters: upcoming, in [city],
     * matching [keywords], not yet [seen] (so each event is looked up only once) and,
     * via one detail lookup, registration currently open (not sold-out or waitlist-only).
     * Returns the enriched [Meetup] or null when the event should be skipped.
     */
    private fun toCandidate(
        entry: JsonElement,
        city: String,
        keywords: List<String>,
        now: OffsetDateTime,
        seen: MutableSet<String>,
    ): Meetup? {
        val meetup = entry.asObjectOrNull()?.let(::parseDiscoverEntry) ?: return null
        val relevant = isUpcoming(meetup, now) &&
            cityMatches(meetup, city) &&
            (keywords.isEmpty() || titleMatches(meetup.name, keywords)) &&
            seen.add(meetup.apiId)
        if (!relevant) return null
        // One per-event detail lookup gives us the registration status, a short description and the price.
        val detail = fetchDetail(meetup.slug)
        return if (isRegistrationOpen(detail.availability)) meetup.copy(summary = detail.summary, price = detail.price) else null
    }

    /** Registration status, a short summary and the ticket price, derived from an event's detail page. */
    private data class EventDetail(val availability: String?, val summary: String?, val price: String?) {
        companion object {
            val EMPTY = EventDetail(null, null, null)
        }
    }

    /**
     * Fetches the per-event detail endpoint (`/url?url=<slug>`). On any request/parse
     * failure we return an empty detail (treated as not open) so we never advertise an
     * event nobody can join; the failure is logged instead of aborting the whole search.
     */
    private fun fetchDetail(slug: String): EventDetail {
        val url = "$baseUrl/url?url=${encode(slug)}"
        return try {
            val response = http.get(url)
            if (response.statusCode() != HTTP_OK) {
                log.log(Level.WARNING, "Luma detail lookup for ''{0}'' returned HTTP {1}; skipping event", slug, response.statusCode())
                return EventDetail.EMPTY
            }
            val data = parseJsonObject(response.body(), url)["data"].asObjectOrNull()
            EventDetail(
                availability = data?.get("registration_availability").asStringOrNull(),
                summary = data?.get("description_mirror")?.let { EventSummarizer.summarize(it) },
                price = data?.get("ticket_info").asObjectOrNull()?.let { PriceFormatter.formatPrice(it) },
            )
        } catch (e: LumaClientException) {
            log.log(Level.WARNING, "Luma detail lookup for ''{0}'' failed; skipping event: {1}", slug, e.message)
            EventDetail.EMPTY
        }
    }

    private fun fetchPage(cursor: String?, pageSize: Int): JsonObject {
        val url = buildString {
            append(baseUrl).append("/discover/get-paginated-events?pagination_limit=").append(pageSize)
            if (cursor != null) append("&pagination_cursor=").append(encode(cursor))
        }
        val response = http.get(url)
        if (response.statusCode() != HTTP_OK) {
            throw LumaClientException(
                "Luma request failed with HTTP ${response.statusCode()}: ${response.body().take(ERROR_BODY_EXCERPT)}",
            )
        }
        return parseJsonObject(response.body(), url)
    }

    /** Parses [body] as a JSON object, converting parser failures into [LumaClientException]. */
    private fun parseJsonObject(body: String, url: String): JsonObject = try {
        json.parseToJsonElement(body) as? JsonObject
            ?: throw LumaClientException("Unexpected Luma response from $url: expected a JSON object")
    } catch (e: SerializationException) {
        throw LumaClientException("Malformed JSON in Luma response from $url", e)
    }

    companion object {
        private val log: System.Logger = System.getLogger(LumaClient::class.java.name)

        const val DEFAULT_BASE_URL = "https://api.lu.ma"
        private const val HTTP_OK = 200

        /** How much of an error response body is quoted in exception messages. */
        private const val ERROR_BODY_EXCERPT = 200
        private val DEFAULT_CONNECT_TIMEOUT: Duration = Duration.ofSeconds(15)
        private val DEFAULT_REQUEST_TIMEOUT: Duration = Duration.ofSeconds(20)
        private const val DEFAULT_MAX_RETRIES = 3
        private val DEFAULT_RETRY_BACKOFF: Duration = Duration.ofMillis(500)

        /** Largest page size Luma's discover endpoint accepts. */
        const val MAX_PAGE_SIZE = 100

        /**
         * How many matches (as a multiple of `maxResults`) we are willing to collect
         * while hunting for enough preferred (free, non-AI) events before settling
         * for the rest.
         */
        private const val OVERFETCH_FACTOR = 3

        private fun defaultHttpClient(): HttpClient = HttpClient.newBuilder()
            .connectTimeout(DEFAULT_CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()

        private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
    }
}
