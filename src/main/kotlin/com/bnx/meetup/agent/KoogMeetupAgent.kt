package com.bnx.meetup.agent

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.features.eventHandler.feature.handleEvents
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.ollama.client.OllamaClient
import ai.koog.prompt.executor.ollama.client.OllamaModels
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.utils.io.use
import com.bnx.meetup.client.luma.LumaClient
import com.bnx.meetup.client.telegram.TelegramClient
import com.bnx.meetup.config.AppConfig
import com.bnx.meetup.tool.MeetupToolSet
import com.bnx.meetup.utils.PostedCache
import java.lang.System.Logger.Level

/**
 * Koog-powered agent. It registers [MeetupToolSet] as tools and lets an LLM decide
 * how to fulfil the goal: find tech meetups in the configured city and publish them
 * to Telegram.
 *
 * Uses a local, tool-capable LLM (e.g. qwen2.5) served by Ollama; no API key is
 * required. See the `llm.baseUrl` / `llm.model` settings in `application.yml`.
 *
 * [run] is a `suspend` function: the single `runBlocking` bridge lives in `main`,
 * as recommended by the coroutines guidelines. Every run creates and closes its own
 * Ollama client and agent, so no HTTP resources leak between runs.
 */
class KoogMeetupAgent(
    private val config: AppConfig,
    private val lumaClient: LumaClient = LumaClient(),
    private val cache: PostedCache? = null,
) {
    /**
     * Runs the agent once and returns the model's final report.
     *
     * Tool calls, tool failures and agent errors are logged through the JDK
     * [System.Logger] via Koog's `EventHandler` feature.
     */
    suspend fun run(): String {
        val telegramClient = if (config.telegram.isConfigured) {
            TelegramClient(config.telegram.botToken, config.telegram.chatId)
        } else {
            null
        }

        val toolRegistry = ToolRegistry {
            tools(
                MeetupToolSet(
                    lumaClient = lumaClient,
                    telegramClient = telegramClient,
                    defaultCity = config.meetup.city,
                    cache = cache,
                ),
            )
        }

        val task = buildString {
            append("Find up to ${config.meetup.maxResults} upcoming tech meetups in ")
            append(config.meetup.city)
            append(" and post the digest to the Telegram channel. ")
            append("Use the findTechMeetups tool to build the digest, then call postToTelegram ")
            append("with that exact message and its meetupIds. Do not invent events.")
        }

        // The executor owns the Ollama client and closes it; the agent is closed via Koog's `use`.
        return MultiLLMPromptExecutor(LLMProvider.Ollama to OllamaClient(baseUrl = config.llm.baseUrl)).use { executor ->
            AIAgent(
                promptExecutor = executor,
                llmModel = resolveModel(config.llm.model),
                toolRegistry = toolRegistry,
                systemPrompt = SYSTEM_PROMPT,
                temperature = TEMPERATURE,
                maxIterations = MAX_ITERATIONS,
                installFeatures = {
                    handleEvents {
                        onToolCallStarting { log.log(Level.INFO, "Tool call: {0}({1})", it.toolName, it.toolArgs) }
                        onToolCallCompleted { log.log(Level.INFO, "Tool {0} completed", it.toolName) }
                        onToolValidationFailed {
                            log.log(Level.WARNING, "Tool {0} rejected arguments {1}: {2}", it.toolName, it.toolArgs, it.message)
                        }
                        onToolCallFailed { log.log(Level.WARNING, "Tool ${it.toolName} failed: ${it.message}", it.error) }
                        onAgentExecutionFailed { log.log(Level.ERROR, "Agent run ${it.runId} failed", it.error) }
                        onAgentCompleted { log.log(Level.INFO, "Agent run {0} completed", it.runId) }
                    }
                },
            ).use { agent -> agent.run(task) }
        }
    }

    companion object {
        private val log: System.Logger = System.getLogger(KoogMeetupAgent::class.java.name)

        /** The task needs ~3 LLM turns (plan, find, post, report); this cap prevents runaway loops. */
        private const val MAX_ITERATIONS = 10

        /** Deterministic tool use matters more than creativity here. */
        private const val TEMPERATURE = 0.0

        private const val CUSTOM_MODEL_CONTEXT_LENGTH = 16_384L

        private val SYSTEM_PROMPT = """
            You are a meetup-publishing assistant. Your goal is to discover upcoming tech
            meetups in a given city using the available tools and publish a digest to a
            Telegram channel. Always rely on tool results for event data; never fabricate
            events. First call findTechMeetups to obtain the digest, then pass its message
            verbatim together with its meetupIds to postToTelegram. If the digest contains
            no meetups (count is 0), skip posting. Finally, briefly report what you did.
        """.trimIndent()

        /**
         * Resolves the configured model name against Koog's [OllamaModels] catalogue so the
         * correct capabilities / context length are used; falls back to a generic
         * tool-capable [LLModel] for models Koog does not know about.
         */
        internal fun resolveModel(name: String): LLModel {
            val id = name.trim()
            require(id.isNotEmpty()) { "llm.model must not be blank" }
            val known = OllamaModels.models.firstOrNull { it.id == id }
                ?: OllamaModels.models.firstOrNull { it.id.substringBefore(':') == id.substringBefore(':') }
            if (known != null) {
                return if (known.id == id) known else known.copy(id = id)
            }
            log.log(Level.INFO, "Model ''{0}'' is not in Koog''s Ollama catalogue; assuming tool support", id)
            return LLModel(
                provider = LLMProvider.Ollama,
                id = id,
                capabilities = listOf(
                    LLMCapability.Completion,
                    LLMCapability.Tools,
                    LLMCapability.Temperature,
                ),
                contextLength = CUSTOM_MODEL_CONTEXT_LENGTH,
            )
        }
    }
}
