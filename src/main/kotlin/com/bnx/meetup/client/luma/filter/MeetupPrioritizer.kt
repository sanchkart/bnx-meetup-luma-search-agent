package com.bnx.meetup.client.luma.filter

import com.bnx.meetup.client.luma.filter.MeetupFilters.isAiFocused
import com.bnx.meetup.client.luma.filter.MeetupFilters.isFree
import com.bnx.meetup.domain.Meetup
import kotlin.math.roundToInt

/** Chooses which of the collected candidates end up in the digest. */
object MeetupPrioritizer {
    /**
     * Fraction of `maxResults` slots reserved for AI-focused events, so the digest
     * still carries a few AI meetups without being dominated by them. The rest
     * of the slots go to non-AI events first.
     */
    const val AI_SHARE: Double = 0.3

    /**
     * Selects up to [maxResults] meetups. Roughly [aiShare] of the slots are
     * reserved for AI-focused events and the remaining slots go to non-AI events;
     * within each group free events come before paid ones. Slots that cannot be
     * filled by their group are topped up from the other group (non-AI leftovers
     * first). The final list is sorted by start time.
     */
    internal fun prioritizeMeetups(meetups: List<Meetup>, maxResults: Int, aiShare: Double = AI_SHARE): List<Meetup> {
        require(maxResults >= 0) { "maxResults must not be negative, was $maxResults" }
        require(aiShare in 0.0..1.0) { "aiShare must be within 0.0..1.0, was $aiShare" }

        val (ai, nonAi) = meetups.sortedBy { it.startAt }.partition { isAiFocused(it) }
        val rankedAi = ai.sortedBy { !isFree(it) }
        val rankedNonAi = nonAi.sortedBy { !isFree(it) }

        val aiSlots = (maxResults * aiShare).roundToInt().coerceIn(0, minOf(maxResults, rankedAi.size))
        val nonAiSlots = (maxResults - aiSlots).coerceAtMost(rankedNonAi.size)

        val selected = rankedNonAi.take(nonAiSlots) + rankedAi.take(aiSlots)
        val topUp = (rankedNonAi.drop(nonAiSlots) + rankedAi.drop(aiSlots)).take(maxResults - selected.size)
        return (selected + topUp).sortedBy { it.startAt }
    }
}
