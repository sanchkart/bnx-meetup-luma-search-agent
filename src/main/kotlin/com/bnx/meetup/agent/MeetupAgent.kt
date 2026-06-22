package com.bnx.meetup.agent

import com.bnx.meetup.domain.Meetup
import com.bnx.meetup.utils.PostedCache
import com.bnx.meetup.client.LumaClient
import com.bnx.meetup.client.TelegramClient
import com.bnx.meetup.utils.DigestFormatter

/**
 * Orchestrates the flow: discover tech meetups in a city via Luma and publish a
 * digest to a Telegram channel.
 */
class MeetupAgent(
    private val lumaClient: LumaClient,
    private val telegramClient: TelegramClient,
    private val cache: PostedCache? = null,
) {
    /**
     * Finds matching meetups and posts a digest message.
     *
     * When a [PostedCache] is configured, meetups that were already posted in a
     * previous run are filtered out, so the channel never receives duplicates.
     * Successfully posted meetups are then recorded in the cache.
     *
     * @param dryRun when true, the message is only built and returned, not sent.
     * @return the [Result] describing what was found and (optionally) posted.
     */
    fun run(
        city: String = "Amsterdam",
        keywords: List<String> = LumaClient.DEFAULT_TECH_KEYWORDS,
        maxResults: Int = 15,
        dryRun: Boolean = false,
    ): RunResult {
        val found = lumaClient.findEvents(city = city, keywords = keywords, maxResults = maxResults)
        val meetups = cache?.filterNew(found) ?: found
        val message = DigestFormatter.format(city, meetups)
        var messageId: Long? = null
        if (!dryRun && meetups.isNotEmpty()) {
            messageId = telegramClient.sendMessage(message)
            cache?.markPosted(meetups)
        }
        return RunResult(meetups = meetups, message = message, messageId = messageId, posted = messageId != null)
    }

    data class RunResult(
        val meetups: List<Meetup>,
        val message: String,
        val messageId: Long?,
        val posted: Boolean,
    )

    companion object {
        /**
         * Builds an HTML-formatted digest message suitable for Telegram.
         *
         * Delegates to [DigestFormatter]; retained for backwards-compatible call sites.
         */
        fun formatDigest(city: String, meetups: List<Meetup>): String =
            DigestFormatter.format(city, meetups)
    }
}