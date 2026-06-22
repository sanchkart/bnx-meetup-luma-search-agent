package com.bnx.meetup.utils

import com.bnx.meetup.domain.Meetup
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * A small, file-backed cache of meetups that have already been posted to Telegram.
 *
 * It persists the stable Luma [com.bnx.meetup.domain.Meetup.apiId] of every published event (one id per
 * line) so subsequent runs can skip events that were posted before, avoiding
 * duplicate messages in the channel.
 *
 * The cache is intentionally simple and dependency-free: a newline-separated text
 * file. Missing/unreadable files are treated as an empty cache.
 */
class PostedCache(private val file: Path) {

    private val seen: MutableSet<String> = loadFromDisk()

    /** Returns true if the given meetup id has already been posted. */
    fun contains(apiId: String): Boolean = seen.contains(apiId)

    /** Returns the subset of [meetups] that have not been posted yet. */
    fun filterNew(meetups: List<Meetup>): List<Meetup> =
        meetups.filterNot { seen.contains(it.apiId) }

    /**
     * Marks the given meetups as posted and persists the cache to disk.
     * No-op (no write) when [meetups] is empty.
     */
    fun markPosted(meetups: List<Meetup>) {
        if (meetups.isEmpty()) return
        var changed = false
        for (m in meetups) {
            if (seen.add(m.apiId)) changed = true
        }
        if (changed) persist()
    }

    /** Number of cached (already posted) meetup ids. */
    val size: Int get() = seen.size

    private fun loadFromDisk(): MutableSet<String> = runCatching {
        if (Files.exists(file)) {
            Files.readAllLines(file)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toMutableSet()
        } else {
            mutableSetOf()
        }
    }.getOrDefault(mutableSetOf())

    private fun persist() {
        runCatching {
            file.parent?.let { Files.createDirectories(it) }
            Files.write(file, seen.sorted())
        }
    }

    companion object {
        /** Default cache file path, overridable via the `MEETUP_CACHE_FILE` env var. */
        fun default(path: String? = null): PostedCache {
            val configured = path?.takeIf { it.isNotBlank() }
                ?: System.getenv("MEETUP_CACHE_FILE")?.takeIf { it.isNotBlank() }
                ?: ".meetup-cache/posted.txt"
            return PostedCache(Paths.get(configured))
        }
    }
}