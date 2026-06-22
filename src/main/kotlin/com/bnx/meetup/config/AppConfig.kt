package com.bnx.meetup.config

import org.yaml.snakeyaml.Yaml

/**
 * Application configuration loaded from `application.yml` on the classpath.
 *
 * Telegram credentials and meetup/LLM settings live in the YAML file. Values may
 * use `${ENV_VAR:default}` placeholders so secrets can be supplied via the
 * environment without being committed to version control.
 */
data class AppConfig(
    val telegram: TelegramConfig,
    val meetup: MeetupConfig,
    val llm: LlmConfig,
) {
    data class TelegramConfig(
        val botToken: String,
        val chatId: String,
    ) {
        val isConfigured: Boolean get() = botToken.isNotBlank() && chatId.isNotBlank()
    }

    data class MeetupConfig(
        val city: String,
        val maxResults: Int,
        val cacheFile: String,
    )

    data class LlmConfig(
        val baseUrl: String,
        val model: String,
    ) {
        /** Local LLM is usable as long as a model name is set; no API key is required. */
        val isConfigured: Boolean get() = model.isNotBlank() && baseUrl.isNotBlank()
    }

    companion object {
        private const val RESOURCE = "/application.yml"

        private const val DEFAULT_CITY = "Amsterdam"
        private const val DEFAULT_MAX_RESULTS = 15
        private const val DEFAULT_CACHE_FILE = ".meetup-cache/posted.txt"
        private const val DEFAULT_OLLAMA_BASE_URL = "http://localhost:11434"
        private const val DEFAULT_MODEL = "mistral-nemo"

        /** Loads and parses [RESOURCE] from the classpath, resolving env placeholders. */
        fun load(resourcePath: String = RESOURCE): AppConfig {
            val stream = AppConfig::class.java.getResourceAsStream(resourcePath)
                ?: error("Configuration resource not found on classpath: $resourcePath")
            val root: Map<String, Any?> = stream.use { Yaml().load(it) }
                ?: error("Configuration resource is empty: $resourcePath")

            val telegram = root.section("telegram")
            val meetup = root.section("meetup")
            val llm = root.section("llm")

            return AppConfig(
                telegram = TelegramConfig(
                    botToken = telegram.resolve("botToken"),
                    chatId = telegram.resolve("chatId"),
                ),
                meetup = MeetupConfig(
                    city = meetup.resolve("city").ifBlank { DEFAULT_CITY },
                    maxResults = meetup.resolve("maxResults").toIntOrNull() ?: DEFAULT_MAX_RESULTS,
                    cacheFile = meetup.resolve("cacheFile").ifBlank { DEFAULT_CACHE_FILE },
                ),
                llm = LlmConfig(
                    baseUrl = llm.resolve("baseUrl").ifBlank { DEFAULT_OLLAMA_BASE_URL },
                    model = llm.resolve("model").ifBlank { DEFAULT_MODEL },
                ),
            )
        }

        @Suppress("UNCHECKED_CAST")
        private fun Map<String, Any?>.section(name: String): Map<String, Any?> =
            (this[name] as? Map<String, Any?>) ?: emptyMap()

        /** Reads a key and resolves any `${ENV:default}` placeholder against the environment. */
        private fun Map<String, Any?>.resolve(key: String): String =
            resolvePlaceholders(this[key]?.toString() ?: "")

        private val PLACEHOLDER = Regex("""\$\{([^:}]+)(?::([^}]*))?}""")

        internal fun resolvePlaceholders(raw: String): String =
            PLACEHOLDER.replace(raw) { match ->
                val envName = match.groupValues[1].trim()
                val default = match.groupValues[2]
                System.getenv(envName)?.takeIf { it.isNotBlank() } ?: default
            }.trim()
    }
}