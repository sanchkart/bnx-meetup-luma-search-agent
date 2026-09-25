package com.bnx.meetup.tool.model

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable

/** Result of `findTechMeetups`: the digest plus the ids needed to mark it as posted later. */
@Serializable
data class Digest(
    @property:LLMDescription("Ready-to-send, HTML-formatted digest message.")
    val message: String,
    @property:LLMDescription("Ids of the meetups included in the message; pass them unchanged to postToTelegram.")
    val meetupIds: List<String>,
    @property:LLMDescription("Number of meetups in the digest; 0 means nothing new was found.")
    val count: Int,
)
