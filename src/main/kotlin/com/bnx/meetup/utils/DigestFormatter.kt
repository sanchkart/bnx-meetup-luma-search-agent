package com.bnx.meetup.utils

import com.bnx.meetup.domain.Meetup

/**
 * Builds the HTML-formatted digest message that is published to Telegram.
 *
 * Kept separate from the orchestration logic ([com.bnx.meetup.agent.MeetupAgent]) so the message
 * layout has a single, well-tested home and can evolve independently.
 */
object DigestFormatter {

    /** Builds an HTML-formatted digest message suitable for Telegram. */
    fun format(city: String, meetups: List<Meetup>): String {
        if (meetups.isEmpty()) {
            return "No upcoming tech meetups found in $city right now. \uD83D\uDD0D"
        }
        return buildString {
            append("\uD83D\uDDD3 <b>Upcoming tech meetups in ").append(escape(city)).append("</b>\n\n")
            meetups.forEachIndexed { index, meetup ->
                append(index + 1).append(". <a href=\"").append(escape(meetup.url)).append("\">")
                    .append(escape(meetup.name)).append("</a>\n")
                append("   \uD83D\uDD52 ").append(escape(meetup.localStart))
                meetup.city?.let { append("  \u2022  \uD83D\uDCCD ").append(escape(it)) }
                meetup.price?.takeIf { it.isNotBlank() }?.let {
                    append("  \u2022  \uD83D\uDCB6 ").append(escape(it))
                }
                append("\n")
                meetup.summary?.takeIf { it.isNotBlank() }?.let {
                    append("   \uD83D\uDCDD <i>").append(escape(it)).append("</i>\n")
                }
                append("\n")
            }
            append("Source: lu.ma")
        }.trimEnd()
    }

    private fun escape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}