package com.bnx.meetup

import com.bnx.meetup.client.luma.LumaClient
import com.bnx.meetup.client.luma.filter.MeetupFilters
import com.bnx.meetup.client.luma.filter.MeetupPrioritizer
import com.bnx.meetup.client.luma.format.EventSummarizer
import com.bnx.meetup.client.luma.format.PriceFormatter
import com.bnx.meetup.domain.Meetup
import com.bnx.meetup.utils.DigestFormatter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
        assertTrue(MeetupFilters.cityMatches(meetup(city = "Amsterdam"), "amsterdam"))
        assertTrue(MeetupFilters.cityMatches(meetup(city = null, cityState = "Amsterdam, Netherlands"), "Amsterdam"))
        assertFalse(MeetupFilters.cityMatches(meetup(city = "Utrecht", cityState = "Utrecht, Netherlands"), "Amsterdam"))
    }

    @Test
    fun titleMatchesKeywords() {
        val kw = MeetupFilters.DEFAULT_TECH_KEYWORDS
        assertTrue(MeetupFilters.titleMatches("Kotlin & AI Meetup", kw))
        assertTrue(MeetupFilters.titleMatches("Amsterdam Developer Drinks", kw))
        assertFalse(MeetupFilters.titleMatches("Morning Yoga in the Park", kw))
    }

    @Test
    fun shortKeywordDoesNotMatchInsideWord() {
        // "ai" must not match "Email" or "Brain"
        assertFalse(MeetupFilters.titleMatches("Email marketing workshop", listOf("ai")))
        assertTrue(MeetupFilters.titleMatches("Hands-on AI workshop", listOf("ai")))
    }

    @Test
    fun isUpcomingSkipsStartedEvents() {
        val now = OffsetDateTime.parse("2026-06-20T12:00:00.000Z")
        assertTrue(MeetupFilters.isUpcoming(meetup(start = "2026-06-20T17:00:00.000Z"), now))
        assertFalse(MeetupFilters.isUpcoming(meetup(start = "2026-06-20T09:00:00.000Z"), now))
        // An event starting exactly now is considered already started.
        assertFalse(MeetupFilters.isUpcoming(meetup(start = "2026-06-20T12:00:00.000Z"), now))
    }

    @Test
    fun isRegistrationOpenOnlyForOpenAvailability() {
        assertTrue(MeetupFilters.isRegistrationOpen("open"))
        assertTrue(MeetupFilters.isRegistrationOpen("  OPEN "))
        assertFalse(MeetupFilters.isRegistrationOpen("waitlist"))
        assertFalse(MeetupFilters.isRegistrationOpen("sold-out"))
        assertFalse(MeetupFilters.isRegistrationOpen("closed"))
        assertFalse(MeetupFilters.isRegistrationOpen(null))
    }

    @Test
    fun formatDigestEscapesAndLinks() {
        val msg = DigestFormatter.format(
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
        assertEquals("Join us for a hands-on AI workshop.", EventSummarizer.summarize(doc))
    }

    @Test
    fun summarizeTruncatesLongText() {
        val long = "word ".repeat(80).trim()
        val doc = Json.parseToJsonElement(
            """{"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"$long"}]}]}""",
        )
        val summary = EventSummarizer.summarize(doc)!!
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
            EventSummarizer.summarize(doc),
        )
    }

    @Test
    fun summarizeFallsBackWhenOnlyBoilerplate() {
        val doc = Json.parseToJsonElement(
            """{"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"Part of Amsterdam Tech Week 2026."}]}]}""",
        )
        // No descriptive sentence available -> fall back to the only sentence.
        assertEquals("Part of Amsterdam Tech Week 2026.", EventSummarizer.summarize(doc))
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
            EventSummarizer.summarize(doc),
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
            EventSummarizer.summarize(doc),
        )
    }

    @Test
    fun summarizeReturnsNullForEmpty() {
        val doc = Json.parseToJsonElement("""{"type":"doc","content":[]}""")
        assertNull(EventSummarizer.summarize(doc))
    }

    @Test
    fun formatDigestIncludesSummary() {
        val msg = DigestFormatter.format(
            "Amsterdam",
            listOf(meetup(name = "AI Night", slug = "ai1", summary = "A short blurb about AI.")),
        )
        assertContains(msg, "<i>A short blurb about AI.</i>")
    }

    @Test
    fun formatDigestHandlesEmpty() {
        val msg = DigestFormatter.format("Amsterdam", emptyList())
        assertContains(msg, "No upcoming tech meetups found in Amsterdam")
    }

    @Test
    fun formatDigestEscapesCityInEmptyMessage() {
        val msg = DigestFormatter.format("<Ams&terdam>", emptyList())
        assertContains(msg, "&lt;Ams&amp;terdam&gt;")
    }

    @Test
    fun formatDigestIncludesPrice() {
        val msg = DigestFormatter.format(
            "Amsterdam",
            listOf(meetup(name = "Paid Night", slug = "paid1", price = "\u20ac25")),
        )
        assertContains(msg, "\u20ac25")
    }

    @Test
    fun formatPriceHandlesTicketInfoVariants() {
        fun ticket(jsonBody: String) = Json.parseToJsonElement(jsonBody).jsonObject

        assertEquals(
            "Free",
            PriceFormatter.formatPrice(ticket("""{"price":null,"is_free":true}""")),
        )
        assertEquals(
            "\u20ac25",
            PriceFormatter.formatPrice(ticket("""{"price":{"cents":2500,"currency":"eur"},"is_free":false}""")),
        )
        assertEquals(
            "\u20ac9.99",
            PriceFormatter.formatPrice(ticket("""{"price":{"cents":999,"currency":"eur"},"is_free":false}""")),
        )
        assertEquals(
            "10 PLN",
            PriceFormatter.formatPrice(ticket("""{"price":{"cents":1000,"currency":"pln"},"is_free":false}""")),
        )
        // Luma frequently reports is_free=false with no concrete price (e.g.
        // approval-based tickets). With no amount to charge we treat these as Free.
        assertEquals(
            "Free",
            PriceFormatter.formatPrice(ticket("""{"price":null,"is_free":false}""")),
        )
        assertEquals(
            "Free",
            PriceFormatter.formatPrice(ticket("""{"price":null,"is_free":true}""")),
        )
    }

    @Test
    fun isFreeOnlyForExplicitFreePrice() {
        assertTrue(MeetupFilters.isFree(meetup(price = "Free")))
        assertTrue(MeetupFilters.isFree(meetup(price = "free")))
        assertFalse(MeetupFilters.isFree(meetup(price = "\u20ac25")))
        assertFalse(MeetupFilters.isFree(meetup(price = null)))
    }

    @Test
    fun isAiFocusedMatchesAiTitlesOnly() {
        assertTrue(MeetupFilters.isAiFocused(meetup(name = "AI Builders Night")))
        assertTrue(MeetupFilters.isAiFocused(meetup(name = "Machine Learning Amsterdam")))
        assertTrue(MeetupFilters.isAiFocused(meetup(name = "LLM Agents in Production")))
        assertFalse(MeetupFilters.isAiFocused(meetup(name = "Kotlin Meetup Amsterdam")))
        // "ai" inside a larger word must not match.
        assertFalse(MeetupFilters.isAiFocused(meetup(name = "Sustainability in Tech")))
    }

    @Test
    fun prioritizeMeetupsPrefersFreeEvents() {
        val paid1 = meetup(name = "Paid 1", slug = "p1", start = "2026-06-20T10:00:00.000Z", price = "\u20ac25")
        val free1 = meetup(name = "Free 1", slug = "f1", start = "2026-06-20T12:00:00.000Z", price = "Free")
        val paid2 = meetup(name = "Paid 2", slug = "p2", start = "2026-06-20T14:00:00.000Z", price = "\u20ac10")
        val free2 = meetup(name = "Free 2", slug = "f2", start = "2026-06-20T16:00:00.000Z", price = "Free")

        // Enough free events -> paid ones are dropped entirely.
        assertEquals(
            listOf(free1, free2),
            MeetupPrioritizer.prioritizeMeetups(listOf(paid1, free1, paid2, free2), maxResults = 2),
        )
    }

    @Test
    fun prioritizeMeetupsTopsUpWithPaidWhenNotEnoughFree() {
        val paid1 = meetup(name = "Paid 1", slug = "p1", start = "2026-06-20T10:00:00.000Z", price = "\u20ac25")
        val free1 = meetup(name = "Free 1", slug = "f1", start = "2026-06-20T12:00:00.000Z", price = "Free")
        val paid2 = meetup(name = "Paid 2", slug = "p2", start = "2026-06-20T14:00:00.000Z", price = "\u20ac10")

        // Only one free event -> earliest paid event fills the remaining slot,
        // and the result is sorted by start time.
        assertEquals(
            listOf(paid1, free1),
            MeetupPrioritizer.prioritizeMeetups(listOf(paid1, free1, paid2), maxResults = 2),
        )
    }

    @Test
    fun prioritizeMeetupsPrefersNonAiEvents() {
        val ai1 = meetup(name = "AI Builders Night", slug = "a1", start = "2026-06-20T10:00:00.000Z", price = "Free")
        val tech1 = meetup(name = "Kotlin Meetup", slug = "t1", start = "2026-06-20T12:00:00.000Z", price = "Free")
        val ai2 = meetup(name = "LLM Agents Demo", slug = "a2", start = "2026-06-20T14:00:00.000Z", price = "Free")
        val tech2 = meetup(name = "DevOps Drinks", slug = "t2", start = "2026-06-20T16:00:00.000Z", price = "Free")

        // With no reserved AI share and enough non-AI events, AI ones are dropped entirely.
        assertEquals(
            listOf(tech1, tech2),
            MeetupPrioritizer.prioritizeMeetups(listOf(ai1, tech1, ai2, tech2), maxResults = 2, aiShare = 0.0),
        )
    }

    @Test
    fun prioritizeMeetupsReservesShareForAiEvents() {
        val ai1 = meetup(name = "AI Builders Night", slug = "a1", start = "2026-06-20T10:00:00.000Z", price = "Free")
        val tech1 = meetup(name = "Kotlin Meetup", slug = "t1", start = "2026-06-20T12:00:00.000Z", price = "Free")
        val ai2 = meetup(name = "LLM Agents Demo", slug = "a2", start = "2026-06-20T14:00:00.000Z", price = "Free")
        val tech2 = meetup(name = "DevOps Drinks", slug = "t2", start = "2026-06-20T16:00:00.000Z", price = "Free")
        val tech3 = meetup(name = "Rust Amsterdam", slug = "t3", start = "2026-06-20T18:00:00.000Z", price = "Free")
        val tech4 = meetup(name = "Cloud Native Night", slug = "t4", start = "2026-06-20T20:00:00.000Z", price = "Free")

        // Default share (30%) of 4 slots -> 1 slot reserved for the earliest AI event,
        // even though there are enough non-AI events to fill the whole digest.
        assertEquals(
            listOf(ai1, tech1, tech2, tech3),
            MeetupPrioritizer.prioritizeMeetups(listOf(ai1, tech1, ai2, tech2, tech3, tech4), maxResults = 4),
        )

        // Higher share -> more AI slots.
        assertEquals(
            listOf(ai1, tech1, ai2, tech2),
            MeetupPrioritizer.prioritizeMeetups(listOf(ai1, tech1, ai2, tech2, tech3, tech4), maxResults = 4, aiShare = 0.5),
        )

        // Reserved AI slots that cannot be filled are given back to non-AI events.
        assertEquals(
            listOf(ai1, tech1, tech2, tech3),
            MeetupPrioritizer.prioritizeMeetups(listOf(ai1, tech1, tech2, tech3, tech4), maxResults = 4, aiShare = 0.5),
        )
    }

    @Test
    fun prioritizeMeetupsTopsUpWithAiWhenNotEnoughNonAi() {
        val ai1 = meetup(name = "AI Builders Night", slug = "a1", start = "2026-06-20T10:00:00.000Z", price = "Free")
        val tech1 = meetup(name = "Kotlin Meetup", slug = "t1", start = "2026-06-20T12:00:00.000Z", price = "Free")
        val ai2 = meetup(name = "LLM Agents Demo", slug = "a2", start = "2026-06-20T14:00:00.000Z", price = "Free")

        // Only one non-AI event -> earliest AI event fills the remaining slot,
        // and the result is sorted by start time.
        assertEquals(
            listOf(ai1, tech1),
            MeetupPrioritizer.prioritizeMeetups(listOf(ai1, tech1, ai2), maxResults = 2),
        )
    }

    @Test
    fun prioritizeMeetupsPrefersPaidNonAiOverFreeAi() {
        val aiFree = meetup(name = "AI Builders Night", slug = "a1", start = "2026-06-20T10:00:00.000Z", price = "Free")
        val techPaid = meetup(name = "Kotlin Meetup", slug = "t1", start = "2026-06-20T12:00:00.000Z", price = "\u20ac25")

        // Topic beats price: a paid non-AI event outranks a free AI one.
        assertEquals(
            listOf(techPaid),
            MeetupPrioritizer.prioritizeMeetups(listOf(aiFree, techPaid), maxResults = 1),
        )
    }

    @Test
    fun urlIsDerivedFromSlug() {
        assertEquals("https://lu.ma/abc123", meetup(slug = "abc123").url)
    }
}
