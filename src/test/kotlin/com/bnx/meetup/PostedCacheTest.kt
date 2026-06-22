package com.bnx.meetup

import com.bnx.meetup.domain.Meetup
import com.bnx.meetup.utils.PostedCache
import java.nio.file.Files
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PostedCacheTest {

    private fun meetup(id: String) = Meetup(
        apiId = id,
        name = "Event $id",
        slug = id,
        startAt = OffsetDateTime.parse("2026-06-20T17:00:00.000Z"),
        city = "Amsterdam",
        cityState = "Amsterdam, Netherlands",
        timezone = "Europe/Amsterdam",
    )

    @Test
    fun filtersOutAlreadyPostedMeetups() {
        val file = Files.createTempFile("posted", ".txt")
        Files.deleteIfExists(file)
        val cache = PostedCache(file)

        val all = listOf(meetup("a"), meetup("b"), meetup("c"))
        // Nothing posted yet -> all new.
        assertEquals(all, cache.filterNew(all))

        cache.markPosted(listOf(meetup("a"), meetup("b")))

        val remaining = cache.filterNew(all)
        assertEquals(listOf("c"), remaining.map { it.apiId })
        assertTrue(cache.contains("a"))
        assertFalse(cache.contains("c"))
    }

    @Test
    fun cachePersistsAcrossInstances() {
        val file = Files.createTempFile("posted", ".txt")
        Files.deleteIfExists(file)

        PostedCache(file).markPosted(listOf(meetup("x"), meetup("y")))

        val reloaded = PostedCache(file)
        assertEquals(2, reloaded.size)
        assertTrue(reloaded.contains("x"))
        assertEquals(listOf("z"), reloaded.filterNew(listOf(meetup("x"), meetup("z"))).map { it.apiId })
    }

    @Test
    fun markPostedEmptyDoesNotCreateFile() {
        val file = Files.createTempFile("posted", ".txt")
        Files.deleteIfExists(file)
        val cache = PostedCache(file)

        cache.markPosted(emptyList())

        assertFalse(Files.exists(file))
        assertEquals(0, cache.size)
    }
}
