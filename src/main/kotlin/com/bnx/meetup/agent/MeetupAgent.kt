package com.bnx.meetup.agent

import com.bnx.meetup.client.luma.LumaClient
import com.bnx.meetup.client.luma.filter.MeetupFilters
import com.bnx.meetup.client.telegram.TelegramClient
import com.bnx.meetup.domain.Meetup
import com.bnx.meetup.utils.DigestFormatter
import com.bnx.meetup.utils.PostedCache

/**
 * Deterministic (LLM-free) orchestration: discover tech meetups in a city via Luma
 * and publish a digest to a Telegram channel. Used as the fallback when no local
 * LLM is configured.
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
     * @param city city to search, as configured in `meetup.city`.
     * @param maxResults digest size, as configured in `meetup.maxResults`.
     * @param dryRun when true, the message is only built and returned, not sent.
     * @return the [RunResult] describing what was found and (optionally) posted.
     */
    fun run(
        city: String,
        maxResults: Int,
        keywords: List<String> = MeetupFilters.DEFAULT_TECH_KEYWORDS,
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
}
