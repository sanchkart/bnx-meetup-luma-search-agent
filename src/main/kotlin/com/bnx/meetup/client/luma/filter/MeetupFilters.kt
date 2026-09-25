package com.bnx.meetup.client.luma.filter

import com.bnx.meetup.domain.Meetup
import java.time.OffsetDateTime
import java.util.concurrent.ConcurrentHashMap

/** Predicates deciding whether a Luma event is relevant (city, keywords, timing, registration, AI, price). */
object MeetupFilters {
    /** Reasonable default keywords identifying "tech" meetups. */
    val DEFAULT_TECH_KEYWORDS: List<String> = listOf(
        "tech", "technology", "developer", "dev", "engineering", "engineer",
        "software", "coding", "code", "programming", "ai", "ml",
        "machine learning", "data", "startup", "cloud", "devops", "web3",
        "blockchain", "crypto", "cyber", "security", "hackathon",
        "kotlin", "java", "python", "javascript", "react", "rust", "golang",
        "api", "saas", "product", "design system", "llm", "gpt", "agent",
    )

    /**
     * Keywords whose presence in an event title marks the event as AI-focused.
     * These events get a reserved share of the digest ([MeetupPrioritizer.AI_SHARE]) and
     * otherwise only top up when there are not enough other tech meetups.
     */
    val AI_KEYWORDS: List<String> = listOf(
        "ai", "ml", "machine learning", "artificial intelligence", "deep learning",
        "llm", "gpt", "genai", "agent", "agentic", "chatgpt", "neural",
    )

    /** Keywords up to this length are matched on word boundaries instead of as plain substrings. */
    private const val SHORT_KEYWORD_LENGTH = 4

    /** Compiled word-boundary matchers for short keywords, built lazily and reused across calls. */
    private val KEYWORD_PATTERNS = ConcurrentHashMap<String, Regex>()

    /** True if the event has not started yet relative to [now]. */
    internal fun isUpcoming(meetup: Meetup, now: OffsetDateTime): Boolean = meetup.startAt.isAfter(now)

    /** True when the event title marks it as AI-focused (see [AI_KEYWORDS]). */
    internal fun isAiFocused(meetup: Meetup): Boolean = titleMatches(meetup.name, AI_KEYWORDS)

    /** True when the event costs nothing to attend (an unknown price is treated as paid). */
    internal fun isFree(meetup: Meetup): Boolean = meetup.price?.equals("Free", ignoreCase = true) == true

    /**
     * True if Luma's `registration_availability` value indicates registration is
     * open (i.e. people can still sign up), as opposed to `waitlist`/`sold-out`/
     * `closed` or an unknown/missing value.
     */
    internal fun isRegistrationOpen(availability: String?): Boolean = availability?.trim()?.lowercase() == "open"

    internal fun cityMatches(meetup: Meetup, city: String): Boolean {
        val target = city.trim().lowercase()
        if (target.isEmpty()) return false
        return meetup.city?.trim()?.lowercase() == target ||
            meetup.cityState?.lowercase()?.contains(target) == true
    }

    internal fun titleMatches(title: String, keywords: List<String>): Boolean {
        val lower = title.lowercase()
        return keywords.any { kw ->
            val k = kw.trim().lowercase()
            if (k.isEmpty()) return@any false
            // Short alphanumeric tokens (e.g. "ai", "dev") use word-boundary matching so
            // they don't match inside larger words; longer/multi-word keywords use a plain
            // substring match.
            if (k.length <= SHORT_KEYWORD_LENGTH && k.all { it.isLetterOrDigit() }) {
                KEYWORD_PATTERNS
                    .computeIfAbsent(k) { Regex("(?<![a-z0-9])${Regex.escape(it)}(?![a-z0-9])") }
                    .containsMatchIn(lower)
            } else {
                lower.contains(k)
            }
        }
    }
}
