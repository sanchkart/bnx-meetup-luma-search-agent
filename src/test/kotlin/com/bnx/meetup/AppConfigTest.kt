package com.bnx.meetup

import com.bnx.meetup.config.AppConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppConfigTest {

    @Test
    fun resolvesDefaultWhenEnvMissing() {
        // Use an env var name that is virtually guaranteed not to be set.
        assertEquals("Amsterdam", AppConfig.resolvePlaceholders("\${DEFINITELY_UNSET_VAR_XYZ:Amsterdam}"))
    }

    @Test
    fun resolvesEnvValueWhenPresent() {
        val entry = System.getenv().entries.firstOrNull { it.value.isNotBlank() }
        if (entry != null) {
            assertEquals(entry.value, AppConfig.resolvePlaceholders("\${${entry.key}:fallback}"))
        }
    }

    @Test
    fun emptyDefaultYieldsEmptyString() {
        assertEquals("", AppConfig.resolvePlaceholders("\${DEFINITELY_UNSET_VAR_XYZ:}"))
    }

    @Test
    fun loadsBundledApplicationYml() {
        val config = AppConfig.load()
        assertEquals("Amsterdam", config.meetup.city)
        assertEquals(15, config.meetup.maxResults)
        assertEquals("mistral-nemo", config.llm.model)
        assertEquals("http://localhost:11434", config.llm.baseUrl)
    }

    @Test
    fun unconfiguredCredentialsReportFalse() {
        val telegram = AppConfig.TelegramConfig(botToken = "", chatId = "")
        assertFalse(telegram.isConfigured)
        assertTrue(AppConfig.TelegramConfig(botToken = "t", chatId = "c").isConfigured)
    }
}
