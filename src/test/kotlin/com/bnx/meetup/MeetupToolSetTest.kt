package com.bnx.meetup

import ai.koog.agents.core.tools.ToolRegistry
import com.bnx.meetup.agent.KoogMeetupAgent
import com.bnx.meetup.client.luma.LumaClient
import com.bnx.meetup.tool.MeetupToolSet
import com.bnx.meetup.utils.PostedCache
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MeetupToolSetTest {

    private fun toolSet(cache: PostedCache? = null) = MeetupToolSet(
        lumaClient = LumaClient(baseUrl = "http://127.0.0.1:9/", maxRetries = 0),
        telegramClient = null,
        defaultCity = "Amsterdam",
        cache = cache,
    )

    @Test
    fun toolsAreDiscoverableByKoogWithStructuredResults() {
        val registry = ToolRegistry { tools(toolSet()) }

        val names = registry.tools.map { it.name }.toSet()
        assertEquals(setOf("findTechMeetups", "postToTelegram"), names)

        val post = registry.getTool("postToTelegram")
        assertEquals(setOf("message", "meetupIds"), post.descriptor.requiredParameters.map { it.name }.toSet())
    }

    @Test
    fun postToTelegramWithoutClientDoesNotTouchCache() = runBlocking {
        val file = Files.createTempFile("posted", ".txt").also { Files.deleteIfExists(it) }
        val cache = PostedCache(file)

        val result = toolSet(cache).postToTelegram("<b>digest</b>", listOf("evt-a", "evt-b"))

        assertFalse(result.posted)
        assertTrue(result.detail.contains("not configured"))
        assertEquals(0, cache.size)
        assertFalse(Files.exists(file))
    }

    @Test
    fun postToTelegramRejectsBlankMessage() = runBlocking {
        val result = toolSet().postToTelegram("   ", emptyList())
        assertFalse(result.posted)
    }

    @Test
    fun resolveModelPrefersKoogCatalogueAndFallsBackToCustomModel() {
        val known = KoogMeetupAgent.resolveModel("llama3.2:latest")
        assertEquals("llama3.2:latest", known.id)
        assertEquals(131_072L, known.contextLength)

        // A different tag of a known family keeps the catalogue capabilities but the requested id.
        val tagged = KoogMeetupAgent.resolveModel("llama3.2:1b")
        assertEquals("llama3.2:1b", tagged.id)
        assertEquals(known.capabilities, tagged.capabilities)

        val custom = KoogMeetupAgent.resolveModel("mistral-nemo")
        assertEquals("mistral-nemo", custom.id)
        assertTrue(custom.capabilities.orEmpty().any { it.id == "tools" })

        assertFailsWith<IllegalArgumentException> { KoogMeetupAgent.resolveModel("  ") }
    }
}
