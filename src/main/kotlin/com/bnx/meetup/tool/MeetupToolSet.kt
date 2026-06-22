package com.bnx.meetup.tool

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.bnx.meetup.client.LumaClient
import com.bnx.meetup.client.TelegramClient
import com.bnx.meetup.domain.Meetup
import com.bnx.meetup.utils.DigestFormatter
import com.bnx.meetup.utils.PostedCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Koog [ai.koog.agents.core.tools.reflect.ToolSet] that exposes the agent's real-world capabilities to the LLM:
 * discovering tech meetups on lu.ma and posting a digest to a Telegram channel.
 *
 * Each annotated method becomes a tool the agent can call autonomously.
 */
@LLMDescription("Tools for discovering tech meetups on lu.ma and posting them to Telegram.")
class MeetupToolSet(
    private val lumaClient: LumaClient,
    private val telegramClient: TelegramClient?,
    private val defaultCity: String,
    private val cache: PostedCache? = null,
) : ToolSet {

    /** Meetups returned by the most recent [findTechMeetups] call, pending a post. */
    @Volatile
    private var pending: List<Meetup> = emptyList()

    @Tool
    @LLMDescription(
        "Finds upcoming tech meetups in the given city using lu.ma and returns a " +
            "ready-to-send, HTML-formatted digest message. Meetups already posted in a " +
            "previous run are skipped to avoid duplicates. Returns a notice when none are found.",
    )
    suspend fun findTechMeetups(
        @LLMDescription("City to search for tech meetups in, e.g. 'Amsterdam'.") city: String,
        @LLMDescription("Maximum number of meetups to include in the digest.") maxResults: Int,
    ): String {
        val target = city.ifBlank { defaultCity }
        val found = withContext(Dispatchers.IO) {
            lumaClient.findEvents(
                city = target,
                keywords = LumaClient.DEFAULT_TECH_KEYWORDS,
                maxResults = if (maxResults > 0) maxResults else DEFAULT_MAX_RESULTS,
            )
        }
        val meetups = cache?.filterNew(found) ?: found
        pending = meetups
        return DigestFormatter.format(target, meetups)
    }

    @Tool
    @LLMDescription(
        "Posts the given message text to the configured Telegram channel using HTML " +
            "formatting. Returns the resulting Telegram message id.",
    )
    suspend fun postToTelegram(
        @LLMDescription("The HTML message text to publish to the Telegram channel.") message: String,
    ): String {
        val client = telegramClient
            ?: return "Telegram is not configured; message was not sent."
        val id = withContext(Dispatchers.IO) { client.sendMessage(message) }
        // Remember which meetups were just published so future runs skip them.
        cache?.markPosted(pending)
        pending = emptyList()
        return "Posted to Telegram (message_id=$id)."
    }

    private companion object {
        /** Fallback digest size when the model does not supply a positive limit. */
        const val DEFAULT_MAX_RESULTS = 15
    }
}