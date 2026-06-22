package com.bnx.meetup.agent

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.ollama.client.OllamaClient
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import com.bnx.meetup.tool.MeetupToolSet
import com.bnx.meetup.utils.PostedCache
import com.bnx.meetup.client.LumaClient
import com.bnx.meetup.client.TelegramClient
import com.bnx.meetup.config.AppConfig
import kotlinx.coroutines.runBlocking

/**
 * Koog-powered agent. It registers [MeetupToolSet] as tools and lets an LLM decide
 * how to fulfil the goal: find tech meetups in the configured city and publish them
 * to Telegram.
 *
 * Uses a local, tool-capable LLM (e.g. qwen2.5) served by Ollama; no API key is
 * required. See the
 * `llm.baseUrl` / `llm.model` settings in `application.yml`.
 */
class KoogMeetupAgent(
    private val config: AppConfig,
    private val lumaClient: LumaClient = LumaClient(),
    private val cache: PostedCache? = null,
) {
    fun run(): String {
        val telegramClient = if (config.telegram.isConfigured) {
            TelegramClient(config.telegram.botToken, config.telegram.chatId)
        } else {
            null
        }

        val toolSet = MeetupToolSet(
            lumaClient = lumaClient,
            telegramClient = telegramClient,
            defaultCity = config.meetup.city,
            cache = cache,
        )

        val toolRegistry = ToolRegistry {
            tools(toolSet)
        }

        val ollamaClient = OllamaClient(baseUrl = config.llm.baseUrl)
        val executor = MultiLLMPromptExecutor(
            LLMProvider.Ollama to ollamaClient,
        )

        val llmModel = LLModel(
            provider = LLMProvider.Ollama,
            id = config.llm.model,
            capabilities = listOf(
                LLMCapability.Completion,
                LLMCapability.Tools,
                LLMCapability.Temperature,
            ),
            contextLength = 16_384L,
        )

        val agent = AIAgent(
            promptExecutor = executor,
            llmModel = llmModel,
            systemPrompt = SYSTEM_PROMPT,
            toolRegistry = toolRegistry,
        )

        val task = buildString {
            append("Find up to ${config.meetup.maxResults} upcoming tech meetups in ")
            append(config.meetup.city)
            append(" and post the digest to the Telegram channel. ")
            append("Use the findTechMeetups tool to build the digest, then call postToTelegram ")
            append("with that exact digest. Do not invent events.")
        }

        return runBlocking { agent.run(task) }
    }

    companion object {
        private val SYSTEM_PROMPT = """
            You are a meetup-publishing assistant. Your goal is to discover upcoming tech
            meetups in a given city using the available tools and publish a digest to a
            Telegram channel. Always rely on tool results for event data; never fabricate
            events. First call findTechMeetups to obtain the HTML digest, then pass that
            digest verbatim to postToTelegram. Finally, briefly report what you did.
        """.trimIndent()
    }
}