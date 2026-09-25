package com.bnx.meetup.tool.model

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable

/** Result of `postToTelegram`. */
@Serializable
data class PostResult(
    @property:LLMDescription("True when the message was delivered to Telegram.")
    val posted: Boolean,
    @property:LLMDescription("Telegram message id when posted, otherwise null.")
    val messageId: Long? = null,
    @property:LLMDescription("Human-readable outcome, including the reason when nothing was posted.")
    val detail: String,
)
