package com.bnx.meetup.utils

import com.bnx.meetup.domain.Meetup
import java.io.IOException
import java.lang.System.Logger.Level
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
 * file. A missing file is treated as an empty cache; an unreadable or unwritable
 * file is logged at WARNING level (and treated as empty / not persisted) so a
 * broken cache never aborts a run but is also never silently ignored.
 */
class PostedCache(private val file: Path) {

    private val seen: MutableSet<String> = loadFromDisk()

    /** Returns true if the given meetup id has already been posted. */
    fun contains(apiId: String): Boolean = seen.contains(apiId)

    /** Returns the subset of [meetups] that have not been posted yet. */
    fun filterNew(meetups: List<Meetup>): List<Meetup> = meetups.filterNot { seen.contains(it.apiId) }

    /**
     * Marks the given meetups as posted and persists the cache to disk.
     * No-op (no write) when [meetups] is empty.
     */
    fun markPosted(meetups: List<Meetup>) = markPostedIds(meetups.map { it.apiId })

    /**
     * Marks the given meetup ids as posted and persists the cache to disk.
     * Blank ids are ignored; no write happens when nothing changed.
     */
    fun markPostedIds(apiIds: Collection<String>) {
        var changed = false
        for (id in apiIds) {
            val trimmed = id.trim()
            if (trimmed.isNotEmpty() && seen.add(trimmed)) changed = true
        }
        if (changed) persist()
    }

    /** Number of cached (already posted) meetup ids. */
    val size: Int get() = seen.size

    private fun loadFromDisk(): MutableSet<String> {
        if (!Files.exists(file)) return mutableSetOf()
        return try {
            Files.readAllLines(file)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toMutableSet()
        } catch (e: IOException) {
            log.log(Level.WARNING, "Could not read posted-meetup cache $file; starting with an empty cache", e)
            mutableSetOf()
        }
    }

    private fun persist() {
        try {
            file.parent?.let { Files.createDirectories(it) }
            Files.write(file, seen.sorted())
        } catch (e: IOException) {
            log.log(
                Level.WARNING,
                "Could not write posted-meetup cache $file; already posted meetups may be re-posted next run",
                e,
            )
        }
    }

    companion object {
        private val log: System.Logger = System.getLogger(PostedCache::class.java.name)

        /**
         * Opens the cache at [path] (as configured via `meetup.cacheFile`, see
         * [com.bnx.meetup.config.AppConfig]).
         */
        fun at(path: String): PostedCache {
            require(path.isNotBlank()) { "cache file path must not be blank" }
            return PostedCache(Paths.get(path))
        }
    }
}
