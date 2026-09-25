package com.bnx.meetup

import com.bnx.meetup.agent.KoogMeetupAgent
import com.bnx.meetup.agent.MeetupAgent
import com.bnx.meetup.client.luma.LumaClient
import com.bnx.meetup.client.luma.filter.MeetupFilters
import com.bnx.meetup.client.telegram.TelegramClient
import com.bnx.meetup.config.AppConfig
import com.bnx.meetup.utils.DigestFormatter
import com.bnx.meetup.utils.PostedCache
import kotlinx.coroutines.runBlocking
import java.lang.System.Logger.Level
import kotlin.system.exitProcess

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
 * Exits with status 1 on a configuration error or a failed run so schedulers (cron, CI)
 * can detect failures.
 *
 * Run with Maven:
 *   mvn compile exec:java
 */
@Suppress("TooGenericExceptionCaught") // top-level guard: any failure must map to a non-zero exit code
fun main() {
    val exitCode = try {
        runApp()
    } catch (e: IllegalStateException) {
        System.err.println("Configuration error: ${e.message}")
        EXIT_FAILURE
    } catch (e: Exception) {
        System.err.println("Run failed: ${e.message}")
        log.log(Level.ERROR, "Run failed", e)
        EXIT_FAILURE
    }
    if (exitCode != EXIT_SUCCESS) exitProcess(exitCode)
}

private val log: System.Logger = System.getLogger("com.bnx.meetup.Main")

private const val EXIT_SUCCESS = 0
private const val EXIT_FAILURE = 1

/** Runs the configured flow and returns the process exit code. */
private fun runApp(): Int {
    val config = AppConfig.load()
    val dryRun = System.getenv("DRY_RUN")?.trim()?.equals("true", ignoreCase = true) ?: false

    val lumaClient = LumaClient()
    val cache = PostedCache.at(config.meetup.cacheFile)

    if (dryRun) {
        val found = lumaClient.findEvents(
            city = config.meetup.city,
            keywords = MeetupFilters.DEFAULT_TECH_KEYWORDS,
            maxResults = config.meetup.maxResults,
        )
        val meetups = cache.filterNew(found)
        println(DigestFormatter.format(config.meetup.city, meetups))
        println(
            "\n[DRY_RUN] Found ${found.size} meetup(s), ${meetups.size} new " +
                "(${cache.size} already cached); nothing was posted.",
        )
        return EXIT_SUCCESS
    }

    if (config.llm.isConfigured) {
        println("Running Koog AI agent (local model=${config.llm.model} @ ${config.llm.baseUrl})...")
        val result = runBlocking { KoogMeetupAgent(config, lumaClient, cache).run() }
        println(result)
        return EXIT_SUCCESS
    }

    // Fallback: no LLM model -> deterministic pipeline.
    check(config.telegram.isConfigured) {
        "No local LLM model and no Telegram credentials configured. " +
            "Set them in application.yml (or via env vars), or use DRY_RUN=true."
    }

    val agent = MeetupAgent(
        lumaClient,
        TelegramClient(config.telegram.botToken, config.telegram.chatId),
        cache,
    )
    val result = agent.run(city = config.meetup.city, maxResults = config.meetup.maxResults)

    if (result.posted) {
        println(
            "Posted ${result.meetups.size} tech meetup(s) in ${config.meetup.city} " +
                "to Telegram (message_id=${result.messageId}).",
        )
    } else {
        println("No tech meetups found in ${config.meetup.city}; nothing was posted.")
    }
    return EXIT_SUCCESS
}
