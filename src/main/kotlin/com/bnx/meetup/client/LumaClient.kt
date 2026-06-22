package com.bnx.meetup.client

import com.bnx.meetup.domain.Meetup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.OffsetDateTime
import kotlin.collections.plusAssign

/**
 * Minimal client for Luma's public "discover" feed.
 *
 * Luma exposes an unauthenticated endpoint that powers its local discovery pages:
 *   GET https://api.lu.ma/discover/get-paginated-events
 *
 * The feed is biased to the caller's geographic region and returns events from
 * multiple nearby cities, so we paginate and filter client-side by city.
 */
class LumaClient(
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build(),
    private val baseUrl: String = "https://api.lu.ma",
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Fetches upcoming events for the given [city] (case-insensitive match against
     * Luma's `geo_address_info.city`), optionally keeping only those whose title
     * matches one of [keywords]. Pagination stops once [maxResults] matches are
     * collected or [maxPages] have been scanned.
     */
    fun findEvents(
        city: String,
        keywords: List<String> = emptyList(),
        maxResults: Int = 30,
        maxPages: Int = 10,
        pageSize: Int = 50,
        now: OffsetDateTime = OffsetDateTime.now(),
    ): List<Meetup> {
        val matches = mutableListOf<Meetup>()
        val seen = mutableSetOf<String>()
        var cursor: String? = null
        var page = 0

        while (page < maxPages && matches.size < maxResults) {
            val root = fetchPage(cursor, pageSize)
            val entries = root["entries"] as? JsonArray ?: break

            for (entry in entries) {
                val meetup = parseEntry(entry.jsonObject) ?: continue
                // Skip events that have already started; we only want upcoming meetups.
                if (!isUpcoming(meetup, now)) continue
                if (!cityMatches(meetup, city)) continue
                if (keywords.isNotEmpty() && !titleMatches(meetup.name, keywords)) continue
                if (!seen.add(meetup.apiId)) continue
                // One per-event detail lookup gives us both the registration status and a
                // short description we can summarise.
                val detail = fetchDetail(meetup.slug)
                // Only surface events whose registration is currently open (not sold-out
                // or waitlist-only).
                if (!isRegistrationOpen(detail.availability)) continue
                matches += meetup.copy(summary = detail.summary)
                if (matches.size >= maxResults) break
            }

            val hasMore = root["has_more"]?.jsonPrimitive?.boolean ?: false
            cursor = root["next_cursor"]?.jsonPrimitive?.contentOrNull()
            if (!hasMore || cursor == null) break
            page++
        }

        return matches.sortedBy { it.startAt }
    }

    /** Registration status plus a short summary, derived from an event's detail page. */
    private data class EventDetail(val availability: String?, val summary: String?)

    /**
     * Fetches the per-event detail endpoint (`/url?url=<slug>`) and extracts both the
     * `registration_availability` status and a short summary built from the event's
     * description. On any failure we return an empty detail (treated as not open) so
     * we never advertise an event nobody can join.
     */
    private fun fetchDetail(slug: String): EventDetail {
        return runCatching {
            val encodedSlug = URLEncoder.encode(slug, StandardCharsets.UTF_8)
            val response = httpGet("$baseUrl/url?url=$encodedSlug")
            if (response.statusCode() != 200) return@runCatching EventDetail(null, null)
            val data = json.parseToJsonElement(response.body()).jsonObject["data"]?.jsonObject
            val availability = data?.get("registration_availability")?.jsonPrimitive?.contentOrNull()
            val summary = data?.get("description_mirror")?.let { summarize(it) }
            EventDetail(availability, summary)
        }.getOrElse { EventDetail(null, null) }
    }

    private fun fetchPage(cursor: String?, pageSize: Int): JsonObject {
        val url = buildString {
            append("$baseUrl/discover/get-paginated-events?pagination_limit=$pageSize")
            if (cursor != null) {
                append("&pagination_cursor=")
                append(URLEncoder.encode(cursor, StandardCharsets.UTF_8))
            }
        }
        val response = httpGet(url)
        check(response.statusCode() == 200) {
            "Luma request failed with HTTP ${response.statusCode()}: ${response.body().take(200)}"
        }
        return json.parseToJsonElement(response.body()).jsonObject
    }

    /** Issues a JSON `GET` request with the shared headers and timeout. */
    private fun httpGet(url: String): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create(url))
            .header("accept", "application/json")
            .header("user-agent", USER_AGENT)
            .timeout(REQUEST_TIMEOUT)
            .GET()
            .build()
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun parseEntry(entry: JsonObject): Meetup? {
        val event = entry["event"]?.jsonObject ?: return null
        val apiId = event["api_id"]?.jsonPrimitive?.contentOrNull() ?: return null
        val name = event["name"]?.jsonPrimitive?.contentOrNull() ?: return null
        val slug = event["url"]?.jsonPrimitive?.contentOrNull() ?: return null
        val startRaw = event["start_at"]?.jsonPrimitive?.contentOrNull() ?: return null
        val startAt = runCatching { OffsetDateTime.parse(startRaw) }.getOrNull() ?: return null

        val geo = event["geo_address_info"]?.jsonObject
        val city = geo?.get("city")?.jsonPrimitive?.contentOrNull()
        val cityState = geo?.get("city_state")?.jsonPrimitive?.contentOrNull()
        val timezone = event["timezone"]?.jsonPrimitive?.contentOrNull()

        return Meetup(
            apiId = apiId,
            name = name,
            slug = slug,
            startAt = startAt,
            city = city,
            cityState = cityState,
            timezone = timezone,
        )
    }

    companion object {
        private const val USER_AGENT = "meetup-agent/1.0"
        private val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(20)

        /** Reasonable default keywords identifying "tech" meetups. */
        val DEFAULT_TECH_KEYWORDS: List<String> = listOf(
            "tech", "technology", "developer", "dev", "engineering", "engineer",
            "software", "coding", "code", "programming", "ai", "ml",
            "machine learning", "data", "startup", "cloud", "devops", "web3",
            "blockchain", "crypto", "cyber", "security", "hackathon",
            "kotlin", "java", "python", "javascript", "react", "rust", "golang",
            "api", "saas", "product", "design system", "llm", "gpt", "agent",
        )

        /** Maximum length of a generated event summary, in characters. */
        private const val MAX_SUMMARY_LENGTH = 160

        /** True if the event has not started yet relative to [now]. */
        internal fun isUpcoming(meetup: Meetup, now: OffsetDateTime): Boolean =
            meetup.startAt.isAfter(now)

        /** Minimum number of words a sentence must have to count as descriptive. */
        private const val MIN_SUMMARY_WORDS = 4

        /**
         * Sentences matching any of these patterns are boilerplate that doesn't
         * describe what the event is actually about (e.g. "Part of Amsterdam Tech
         * Week 2026 · June 16–19."), so we skip them when summarising. This also
         * covers "update" / announcement sentences (rescheduled, new venue, etc.)
         * which describe changes to the meetup rather than what it is about.
         */
        private val BOILERPLATE_PATTERNS: List<Regex> = listOf(
            Regex("^part of\\b", RegexOption.IGNORE_CASE),
            Regex("\\btech week\\b", RegexOption.IGNORE_CASE),
            Regex("^(join|come) us\\b.*\\b(for an? )?(evening|day|night|event)\\.?$", RegexOption.IGNORE_CASE),
            Regex("^(welcome|hello|hi|hey)\\b", RegexOption.IGNORE_CASE),
            // Sentences that are mostly a date/time/location with little prose.
            Regex("^[^a-z]*\\d{1,2}([:.]\\d{2})?\\s*(am|pm)?[^a-z]*$", RegexOption.IGNORE_CASE),
            // "Update" / announcement sentences describing changes to the meetup
            // rather than what the meetup is about.
            Regex("^(update|edit|news|important|please note|note)\\b", RegexOption.IGNORE_CASE),
            Regex("\\b(rescheduled|postponed|cancelled|canceled|moved to|relocated|new (date|time|venue|location))\\b", RegexOption.IGNORE_CASE),
            Regex("\\bwe('| ha)ve\\s+(updated|moved|changed|rescheduled|postponed)\\b", RegexOption.IGNORE_CASE),
        )

        /**
         * Matches date/time references (month names, weekdays, years, clock times,
         * day ranges). Sentences containing these are skipped for the summary, since
         * the date is already shown separately in the digest.
         */
        private val DATE_PATTERN: Regex = Regex(
            "\\b(" +
                "jan(uary)?|feb(ruary)?|mar(ch)?|apr(il)?|may|jun(e)?|jul(y)?|" +
                "aug(ust)?|sep(tember)?|oct(ober)?|nov(ember)?|dec(ember)?|" +
                "mon(day)?|tue(sday)?|wed(nesday)?|thu(rsday)?|fri(day)?|sat(urday)?|sun(day)?|" +
                "today|tomorrow|tonight|20\\d{2}" +
                ")\\b|\\b\\d{1,2}([:.]\\d{2})?\\s*(am|pm)\\b|\\b\\d{1,2}\\s*[\u2013\u2014-]\\s*\\d{1,2}\\b",
            RegexOption.IGNORE_CASE,
        )

        /**
         * Builds a really short, one-line summary from Luma's `description_mirror`
         * (a ProseMirror document). It flattens all text nodes, splits them into
         * sentences, skips generic boilerplate (event-week banners, dates, bare
         * greetings) and picks the first sentence that actually describes the event,
         * trimmed to [MAX_SUMMARY_LENGTH] characters. Returns null when there is no
         * usable descriptive text.
         */
        internal fun summarize(descriptionMirror: JsonElement): String? {
            val text = collectText(descriptionMirror)
                .replace(Regex("\\s+"), " ")
                .trim()
            if (text.isEmpty()) return null

            val sentences = splitSentences(text)
            // Prefer the first descriptive sentence; otherwise fall back to the first
            // non-empty sentence, and finally the whole text.
            val candidate = sentences.firstOrNull { isDescriptive(it) }
                ?: sentences.firstOrNull()
                ?: text

            return if (candidate.length <= MAX_SUMMARY_LENGTH) {
                candidate
            } else {
                candidate.take(MAX_SUMMARY_LENGTH).trimEnd().trimEnd(',', ';', ':', '-') + "\u2026"
            }
        }

        /** Splits text into trimmed, non-empty sentences on `.`/`!`/`?` boundaries. */
        private fun splitSentences(text: String): List<String> =
            Regex("[^.!?]+[.!?]?").findAll(text)
                .map { it.value.trim() }
                .filter { it.isNotEmpty() }
                .toList()

        /**
         * A sentence is "descriptive" if it has enough real words, isn't generic
         * boilerplate (event-week banners, greetings) or an "update"/announcement,
         * and doesn't reference a date/time (which is already shown in the digest).
         */
        private fun isDescriptive(sentence: String): Boolean {
            val wordCount = sentence.split(Regex("\\s+")).count { it.any(Char::isLetter) }
            if (wordCount < MIN_SUMMARY_WORDS) return false
            if (DATE_PATTERN.containsMatchIn(sentence)) return false
            return BOILERPLATE_PATTERNS.none { it.containsMatchIn(sentence) }
        }

        /** Recursively concatenates all `text` fields found within a JSON node. */
        private fun collectText(element: JsonElement): String {
            val sb = StringBuilder()
            fun walk(node: JsonElement) {
                when (node) {
                    is JsonObject -> {
                        (node["text"] as? JsonPrimitive)?.contentOrNull()?.let {
                            if (sb.isNotEmpty()) sb.append(' ')
                            sb.append(it)
                        }
                        node["content"]?.let { walk(it) }
                    }
                    is JsonArray -> node.forEach { walk(it) }
                    else -> {}
                }
            }
            walk(element)
            return sb.toString()
        }

        /**
         * True if Luma's `registration_availability` value indicates registration is
         * open (i.e. people can still sign up), as opposed to `waitlist`/`sold-out`/
         * `closed` or an unknown/missing value.
         */
        internal fun isRegistrationOpen(availability: String?): Boolean =
            availability?.trim()?.lowercase() == "open"

        internal fun cityMatches(meetup: Meetup, city: String): Boolean {
            val target = city.trim().lowercase()
            return meetup.city?.trim()?.lowercase() == target ||
                (meetup.cityState?.lowercase()?.contains(target) ?: false)
        }

        internal fun titleMatches(title: String, keywords: List<String>): Boolean {
            val lower = title.lowercase()
            return keywords.any { kw ->
                val k = kw.lowercase()
                // Short alphanumeric tokens (e.g. "ai", "dev") use word-boundary matching so
                // they don't match inside larger words; longer/multi-word keywords use a plain
                // substring match.
                if (k.length <= 4 && k.all { it.isLetterOrDigit() }) {
                    Regex("(?<![a-z0-9])${Regex.escape(k)}(?![a-z0-9])").containsMatchIn(lower)
                } else {
                    lower.contains(k)
                }
            }
        }
    }
}

private fun JsonPrimitive.contentOrNull(): String? =
    if (this is JsonNull) null else content
