package com.bnx.meetup.client.luma.format

import com.bnx.meetup.client.luma.asStringOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Builds one-line event summaries from Luma's ProseMirror `description_mirror`. */
object EventSummarizer {
    /** Maximum length of a generated event summary, in characters. */
    private const val MAX_SUMMARY_LENGTH = 160

    /** Minimum number of words a sentence must have to count as descriptive. */
    private const val MIN_SUMMARY_WORDS = 4

    private val WHITESPACE: Regex = Regex("\\s+")
    private val SENTENCE: Regex = Regex("[^.!?]+[.!?]?")

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
        Regex(
            "\\b(rescheduled|postponed|cancelled|canceled|moved to|relocated|new (date|time|venue|location))\\b",
            RegexOption.IGNORE_CASE,
        ),
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
     * Flattens all text nodes, splits them into sentences, skips generic boilerplate
     * (event-week banners, dates, bare greetings) and picks the first sentence that
     * actually describes the event, trimmed to [MAX_SUMMARY_LENGTH] characters.
     * Returns null when there is no usable descriptive text.
     */
    internal fun summarize(descriptionMirror: JsonElement): String? {
        val text = collectText(descriptionMirror)
            .replace(WHITESPACE, " ")
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
    private fun splitSentences(text: String): List<String> = SENTENCE.findAll(text)
        .map { it.value.trim() }
        .filter { it.isNotEmpty() }
        .toList()

    /**
     * A sentence is "descriptive" if it has enough real words, isn't generic
     * boilerplate (event-week banners, greetings) or an "update"/announcement,
     * and doesn't reference a date/time (which is already shown in the digest).
     */
    private fun isDescriptive(sentence: String): Boolean {
        val wordCount = sentence.split(WHITESPACE).count { it.any(Char::isLetter) }
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
                    node["text"].asStringOrNull()?.let {
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
}
