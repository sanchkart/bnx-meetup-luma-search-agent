package com.bnx.meetup.domain

import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * A normalized representation of a Luma event that we care about.
 */
data class Meetup(
    val apiId: String,
    val name: String,
    val slug: String,
    val startAt: OffsetDateTime,
    val city: String?,
    val cityState: String?,
    val timezone: String?,
    /** Short, one-line summary of what the event is about (may be null). */
    val summary: String? = null,
    /** Human readable ticket price (e.g. "Free", "€25"); null when unknown. */
    val price: String? = null,
) {
    /** Public URL of the event on lu.ma. */
    val url: String
        get() = "https://lu.ma/$slug"

    /** Human readable start time in the event's own timezone (falls back to UTC). */
    val localStart: String
        get() {
            val zone = runCatching { ZoneId.of(timezone ?: "UTC") }.getOrDefault(ZoneId.of("UTC"))
            return startAt.atZoneSameInstant(zone).format(DISPLAY_FORMAT)
        }

    companion object {
        private val DISPLAY_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("EEE, d MMM yyyy 'at' HH:mm", Locale.ENGLISH)
    }
}