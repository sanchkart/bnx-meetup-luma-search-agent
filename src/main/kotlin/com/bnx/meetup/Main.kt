package com.bnx.meetup

import com.bnx.meetup.agent.KoogMeetupAgent
import com.bnx.meetup.agent.MeetupAgent
import com.bnx.meetup.client.LumaClient
import com.bnx.meetup.client.TelegramClient
import com.bnx.meetup.config.AppConfig
import com.bnx.meetup.utils.DigestFormatter
import com.bnx.meetup.utils.PostedCache

/**
 * Entry point.
 *
 * Configuration is loaded from `src/main/resources/application.yml`. Secrets can
 * be supplied either directly in that file or via the referenced environment
 * variables (`${ENV:default}` placeholders), e.g. `TELEGRAM_BOT_TOKEN`,
 * `TELEGRAM_CHAT_ID`, `OLLAMA_BASE_URL`, `LLM_MODEL`.
 *
 * Behaviour:
 *   - When a local LLM (e.g. qwen2.5 via Ollama) is configured, the work is driven by
 *     a Koog [com.bnx.meetup.agent.KoogMeetupAgent] that calls the meetup/Telegram tools autonomously.
 *   - Otherwise it falls back to a deterministic flow ([com.bnx.meetup.agent.MeetupAgent]).
 *
 * Set DRY_RUN=true to print the digest instead of posting to Telegram.
 *
 * Run with Maven:
 *   mvn compile exec:java
 */
fun main() {
    val config = AppConfig.load()
    val dryRun = System.getenv("DRY_RUN")?.trim()?.equals("true", ignoreCase = true) ?: false

    val lumaClient = LumaClient()
    val cache = PostedCache.default(config.meetup.cacheFile)

    if (dryRun) {
        val found = lumaClient.findEvents(
            city = config.meetup.city,
            keywords = LumaClient.DEFAULT_TECH_KEYWORDS,
            maxResults = config.meetup.maxResults,
        )
        val meetups = cache.filterNew(found)
        println(DigestFormatter.format(config.meetup.city, meetups))
        println(
            "\n[DRY_RUN] Found ${found.size} meetup(s), ${meetups.size} new " +
                "(${cache.size} already cached); nothing was posted.",
        )
        return
    }

    if (config.llm.isConfigured) {
        println("Running Koog AI agent (local model=${config.llm.model} @ ${config.llm.baseUrl})...")
        val result = KoogMeetupAgent(config, lumaClient, cache).run()
        println(result)
        return
    }

    // Fallback: no LLM model -> deterministic pipeline.
    if (!config.telegram.isConfigured) {
        error(
            "No local LLM model and no Telegram credentials configured. " +
                "Set them in application.yml (or via env vars), or use DRY_RUN=true.",
        )
    }

    val agent = MeetupAgent(
        lumaClient,
        TelegramClient(config.telegram.botToken, config.telegram.chatId),
        cache,
    )
    val result = agent.run(city = config.meetup.city, maxResults = config.meetup.maxResults)

    when {
        result.posted -> {
            println(
                "Posted ${result.meetups.size} tech meetup(s) in ${config.meetup.city} " +
                        "to Telegram (message_id=${result.messageId}).",
            )
        }
        else -> {
            println("No tech meetups found in ${config.meetup.city}; nothing was posted.")
        }
    }
}
