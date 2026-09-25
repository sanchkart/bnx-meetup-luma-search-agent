package com.bnx.meetup.client.luma

/**
 * Raised when the Luma API cannot be reached, keeps failing after retries, or
 * returns a response we cannot interpret.
 */
class LumaClientException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
