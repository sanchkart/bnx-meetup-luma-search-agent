package com.bnx.meetup

import com.bnx.meetup.agent.MeetupAgent
import com.bnx.meetup.client.LumaClient
import com.bnx.meetup.domain.Meetup
import java.time.OffsetDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MeetupAgentTest {

    private fun meetup(
        name: String = "Event",
        city: String? = "Amsterdam",
        cityState: String? = "Amsterdam, Netherlands",
        start: String = "2026-06-20T17:00:00.000Z",
        slug: String = "abc123",
        summary: String? = null,
        price: String? = null,
    ) = Meetup(
        apiId = "evt-$slug",
        name = name,
        slug = slug,
        startAt = OffsetDateTime.parse(start),
        city = city,
        cityState = cityState,
        timezone = "Europe/Amsterdam",
        summary = summary,
        price = price,
    )

    @Test
    fun cityMatchesIsCaseInsensitive() {
        assertTrue(LumaClient.cityMatches(meetup(city = "Amsterdam"), "amsterdam"))
        assertTrue(LumaClient.cityMatches(meetup(city = null, cityState = "Amsterdam, Netherlands"), "Amsterdam"))
        assertFalse(LumaClient.cityMatches(meetup(city = "Utrecht", cityState = "Utrecht, Netherlands"), "Amsterdam"))
    }

    @Test
    fun titleMatchesKeywords() {
        val kw = LumaClient.DEFAULT_TECH_KEYWORDS
        assertTrue(LumaClient.titleMatches("Kotlin & AI Meetup", kw))
        assertTrue(LumaClient.titleMatches("Amsterdam Developer Drinks", kw))
        assertFalse(LumaClient.titleMatches("Morning Yoga in the Park", kw))
    }

    @Test
    fun shortKeywordDoesNotMatchInsideWord() {
        // "ai" must not match "Email" or "Brain"
        assertFalse(LumaClient.titleMatches("Email marketing workshop", listOf("ai")))
        assertTrue(LumaClient.titleMatches("Hands-on AI workshop", listOf("ai")))
    }

    @Test
    fun isUpcomingSkipsStartedEvents() {
        val now = OffsetDateTime.parse("2026-06-20T12:00:00.000Z")
        assertTrue(LumaClient.isUpcoming(meetup(start = "2026-06-20T17:00:00.000Z"), now))
        assertFalse(LumaClient.isUpcoming(meetup(start = "2026-06-20T09:00:00.000Z"), now))
        // An event starting exactly now is considered already started.
        assertFalse(LumaClient.isUpcoming(meetup(start = "2026-06-20T12:00:00.000Z"), now))
    }

    @Test
    fun isRegistrationOpenOnlyForOpenAvailability() {
        assertTrue(LumaClient.isRegistrationOpen("open"))
        assertTrue(LumaClient.isRegistrationOpen("  OPEN "))
        assertFalse(LumaClient.isRegistrationOpen("waitlist"))
        assertFalse(LumaClient.isRegistrationOpen("sold-out"))
        assertFalse(LumaClient.isRegistrationOpen("closed"))
        assertFalse(LumaClient.isRegistrationOpen(null))
    }

    @Test
    fun formatDigestEscapesAndLinks() {
        val msg = MeetupAgent.formatDigest(
            "Amsterdam",
            listOf(meetup(name = "Rust & <Systems> Night", slug = "rustnl")),
        )
        assertContains(msg, "Upcoming tech meetups in Amsterdam")
        assertContains(msg, "https://lu.ma/rustnl")
        assertContains(msg, "Rust &amp; &lt;Systems&gt; Night")
    }

    @Test
    fun summarizeExtractsFirstSentence() {
        val doc = Json.parseToJsonElement(
            """
            {"type":"doc","content":[{"type":"paragraph","content":[
              {"type":"text","text":"Join us for a hands-on AI workshop."},
              {"type":"text","text":" There will be food and networking afterwards."}
            ]}]}
            """.trimIndent(),
        )
        assertEquals("Join us for a hands-on AI workshop.", LumaClient.summarize(doc))
    }

    @Test
    fun summarizeTruncatesLongText() {
        val long = "word ".repeat(80).trim()
        val doc = Json.parseToJsonElement(
            """{"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"$long"}]}]}""",
        )
        val summary = LumaClient.summarize(doc)!!
        assertTrue(summary.length <= 161, "summary too long: ${'$'}{summary.length}")
        assertTrue(summary.endsWith("\u2026"))
    }

    @Test
    fun summarizeSkipsGenericBoilerplate() {
        val doc = Json.parseToJsonElement(
            """
            {"type":"doc","content":[{"type":"paragraph","content":[
              {"type":"text","text":"Part of Amsterdam Tech Week 2026 \u00b7 June 16\u201319."},
              {"type":"text","text":" A deep-dive into building AI agents with Kotlin and Koog."}
            ]}]}
            """.trimIndent(),
        )
        assertEquals(
            "A deep-dive into building AI agents with Kotlin and Koog.",
            LumaClient.summarize(doc),
        )
    }

    @Test
    fun summarizeFallsBackWhenOnlyBoilerplate() {
        val doc = Json.parseToJsonElement(
            """{"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"Part of Amsterdam Tech Week 2026."}]}]}""",
        )
        // No descriptive sentence available -> fall back to the only sentence.
        assertEquals("Part of Amsterdam Tech Week 2026.", LumaClient.summarize(doc))
    }

    @Test
    fun summarizeSkipsSentencesWithDates() {
        val doc = Json.parseToJsonElement(
            """
            {"type":"doc","content":[{"type":"paragraph","content":[
              {"type":"text","text":"This meetup happens on June 16 at 6pm."},
              {"type":"text","text":" We explore hands-on machine learning with real datasets."}
            ]}]}
            """.trimIndent(),
        )
        assertEquals(
            "We explore hands-on machine learning with real datasets.",
            LumaClient.summarize(doc),
        )
    }

    @Test
    fun summarizeSkipsUpdateAnnouncements() {
        val doc = Json.parseToJsonElement(
            """
            {"type":"doc","content":[{"type":"paragraph","content":[
              {"type":"text","text":"Update: the venue has been rescheduled to a new location."},
              {"type":"text","text":" A practical workshop on building scalable cloud infrastructure."}
            ]}]}
            """.trimIndent(),
        )
        assertEquals(
            "A practical workshop on building scalable cloud infrastructure.",
            LumaClient.summarize(doc),
        )
    }

    @Test
    fun summarizeReturnsNullForEmpty() {
        val doc = Json.parseToJsonElement("""{"type":"doc","content":[]}""")
        assertNull(LumaClient.summarize(doc))
    }

    @Test
    fun formatDigestIncludesSummary() {
        val msg = MeetupAgent.formatDigest(
            "Amsterdam",
            listOf(meetup(name = "AI Night", slug = "ai1", summary = "A short blurb about AI.")),
        )
        assertContains(msg, "<i>A short blurb about AI.</i>")
    }

    @Test
    fun formatDigestHandlesEmpty() {
        val msg = MeetupAgent.formatDigest("Amsterdam", emptyList())
        assertContains(msg, "No upcoming tech meetups found in Amsterdam")
    }

    @Test
    fun formatDigestIncludesPrice() {
        val msg = MeetupAgent.formatDigest(
            "Amsterdam",
            listOf(meetup(name = "Paid Night", slug = "paid1", price = "\u20ac25")),
        )
        assertContains(msg, "\u20ac25")
    }

    @Test
    fun formatPriceHandlesTicketInfoVariants() {
        fun ticket(jsonBody: String) =
            Json.parseToJsonElement(jsonBody).jsonObject

        assertEquals(
            "Free",
            LumaClient.formatPrice(ticket("""{"price":null,"is_free":true}""")),
        )
        assertEquals(
            "\u20ac25",
            LumaClient.formatPrice(ticket("""{"price":{"cents":2500,"currency":"eur"},"is_free":false}""")),
        )
        assertEquals(
            "\u20ac9.99",
            LumaClient.formatPrice(ticket("""{"price":{"cents":999,"currency":"eur"},"is_free":false}""")),
        )
        assertEquals(
            "10 PLN",
            LumaClient.formatPrice(ticket("""{"price":{"cents":1000,"currency":"pln"},"is_free":false}""")),
        )
        // Luma frequently reports is_free=false with no concrete price (e.g.
        // approval-based tickets). With no amount to charge we treat these as Free.
        assertEquals(
            "Free",
            LumaClient.formatPrice(ticket("""{"price":null,"is_free":false}""")),
        )
        assertEquals(
            "Free",
            LumaClient.formatPrice(ticket("""{"price":null,"is_free":true}""")),
        )
    }

    @Test
    fun isFreeOnlyForExplicitFreePrice() {
        assertTrue(LumaClient.isFree(meetup(price = "Free")))
        assertTrue(LumaClient.isFree(meetup(price = "free")))
        assertFalse(LumaClient.isFree(meetup(price = "\u20ac25")))
        assertFalse(LumaClient.isFree(meetup(price = null)))
    }

    @Test
    fun prioritizeFreePrefersFreeEvents() {
        val paid1 = meetup(name = "Paid 1", slug = "p1", start = "2026-06-20T10:00:00.000Z", price = "\u20ac25")
        val free1 = meetup(name = "Free 1", slug = "f1", start = "2026-06-20T12:00:00.000Z", price = "Free")
        val paid2 = meetup(name = "Paid 2", slug = "p2", start = "2026-06-20T14:00:00.000Z", price = "\u20ac10")
        val free2 = meetup(name = "Free 2", slug = "f2", start = "2026-06-20T16:00:00.000Z", price = "Free")

        // Enough free events -> paid ones are dropped entirely.
        assertEquals(
            listOf(free1, free2),
            LumaClient.prioritizeFree(listOf(paid1, free1, paid2, free2), maxResults = 2),
        )
    }

    @Test
    fun prioritizeFreeTopsUpWithPaidWhenNotEnoughFree() {
        val paid1 = meetup(name = "Paid 1", slug = "p1", start = "2026-06-20T10:00:00.000Z", price = "\u20ac25")
        val free1 = meetup(name = "Free 1", slug = "f1", start = "2026-06-20T12:00:00.000Z", price = "Free")
        val paid2 = meetup(name = "Paid 2", slug = "p2", start = "2026-06-20T14:00:00.000Z", price = "\u20ac10")

        // Only one free event -> earliest paid event fills the remaining slot,
        // and the result is sorted by start time.
        assertEquals(
            listOf(paid1, free1),
            LumaClient.prioritizeFree(listOf(paid1, free1, paid2), maxResults = 2),
        )
    }

    @Test
    fun urlIsDerivedFromSlug() {
        assertEquals("https://lu.ma/abc123", meetup(slug = "abc123").url)
    }
}
