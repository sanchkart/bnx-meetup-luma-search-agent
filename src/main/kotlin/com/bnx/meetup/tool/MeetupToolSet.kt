package com.bnx.meetup.tool

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.bnx.meetup.client.luma.LumaClient
import com.bnx.meetup.client.luma.filter.MeetupFilters
import com.bnx.meetup.client.telegram.TelegramClient
import com.bnx.meetup.tool.model.Digest
import com.bnx.meetup.tool.model.PostResult
import com.bnx.meetup.utils.DigestFormatter
import com.bnx.meetup.utils.PostedCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Koog [ai.koog.agents.core.tools.reflect.ToolSet] that exposes the agent's real-world capabilities to the LLM:
 * discovering tech meetups on lu.ma and posting a digest to a Telegram channel.
 *
 * Each annotated method becomes a tool the agent can call autonomously. The tool set
 * holds no per-run state: everything a later tool call needs (the digest and the ids
 * of the meetups it contains) is returned by [findTechMeetups] and passed back
 * explicitly by the model, so calls can be reordered or repeated without corrupting
 * the [PostedCache].
 */
@LLMDescription("Tools for discovering tech meetups on lu.ma and posting them to Telegram.")
class MeetupToolSet(
    private val lumaClient: LumaClient,
    private val telegramClient: TelegramClient?,
    private val defaultCity: String,
    private val cache: PostedCache? = null,
) : ToolSet {

    @Tool
    @LLMDescription(
        "Finds upcoming tech meetups in the given city using lu.ma and returns a " +
            "ready-to-send, HTML-formatted digest message together with the ids of the included meetups. " +
            "Meetups already posted in a previous run are skipped to avoid duplicates. " +
            "The message contains a notice when none are found.",
    )
    suspend fun findTechMeetups(
        @LLMDescription("City to search for tech meetups in, e.g. 'Amsterdam'.") city: String,
        @LLMDescription("Maximum number of meetups to include in the digest.") maxResults: Int,
    ): Digest {
        val target = city.ifBlank { defaultCity }
        val found = withContext(Dispatchers.IO) {
            lumaClient.findEvents(
                city = target,
                keywords = MeetupFilters.DEFAULT_TECH_KEYWORDS,
                maxResults = if (maxResults > 0) maxResults else DEFAULT_MAX_RESULTS,
            )
        }
        val meetups = cache?.filterNew(found) ?: found
        return Digest(
            message = DigestFormatter.format(target, meetups),
            meetupIds = meetups.map { it.apiId },
            count = meetups.size,
        )
    }

    @Tool
    @LLMDescription(
        "Posts the given digest message to the configured Telegram channel using HTML formatting " +
            "and remembers the given meetup ids so they are not posted again in future runs. " +
            "Returns whether the message was posted and the resulting Telegram message id.",
    )
    suspend fun postToTelegram(
        @LLMDescription("The HTML message text to publish, exactly as returned by findTechMeetups.") message: String,
        @LLMDescription("The meetupIds returned by findTechMeetups for this message.") meetupIds: List<String>,
    ): PostResult {
        if (message.isBlank()) {
            return PostResult(posted = false, detail = "Message is empty; nothing was sent.")
        }
        val client = telegramClient
            ?: return PostResult(posted = false, detail = "Telegram is not configured; message was not sent.")
        val id = withContext(Dispatchers.IO) { client.sendMessage(message) }
        // Remember which meetups were just published so future runs skip them.
        cache?.markPostedIds(meetupIds)
        return PostResult(posted = true, messageId = id, detail = "Posted to Telegram (message_id=$id).")
    }

    private companion object {
        /** Fallback digest size when the model does not supply a positive limit. */
        const val DEFAULT_MAX_RESULTS = 15
    }
}
