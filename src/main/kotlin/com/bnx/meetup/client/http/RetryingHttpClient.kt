package com.bnx.meetup.client.http

import com.bnx.meetup.client.luma.LumaClientException
import java.io.IOException
import java.lang.System.Logger.Level
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * Issues JSON `GET` requests with shared headers and timeout, retrying transient
 * failures (I/O errors, HTTP 408/429/5xx) with capped exponential backoff and
 * honouring `Retry-After`. Failures surviving the retries raise [LumaClientException].
 */
internal class RetryingHttpClient(
    private val httpClient: HttpClient,
    private val requestTimeout: Duration,
    private val maxRetries: Int,
    private val retryBackoff: Duration,
    private val sleeper: (Duration) -> Unit,
) {
    init {
        require(!requestTimeout.isNegative && !requestTimeout.isZero) { "requestTimeout must be positive" }
        require(maxRetries >= 0) { "maxRetries must be >= 0" }
        require(!retryBackoff.isNegative) { "retryBackoff must not be negative" }
    }

    /** Non-retryable responses are returned as-is for the caller to interpret. */
    fun get(url: String): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create(url))
            .header("accept", "application/json")
            .header("user-agent", USER_AGENT)
            .timeout(requestTimeout)
            .GET()
            .build()

        var attempt = 0
        while (true) {
            val response = try {
                httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw LumaClientException("Interrupted while calling $url", e)
            } catch (e: IOException) {
                if (attempt >= maxRetries) {
                    throw LumaClientException("Luma request to $url failed after ${attempt + 1} attempt(s)", e)
                }
                log.log(Level.WARNING, "Luma request to {0} failed (attempt {1}/{2}): {3}", url, attempt + 1, maxRetries + 1, e.toString())
                backoff(attempt, retryAfter = null)
                attempt++
                continue
            }

            if (response.statusCode() in RETRYABLE_STATUS && attempt < maxRetries) {
                log.log(
                    Level.WARNING,
                    "Luma request to {0} returned HTTP {1} (attempt {2}/{3}); retrying",
                    url,
                    response.statusCode(),
                    attempt + 1,
                    maxRetries + 1,
                )
                backoff(attempt, retryAfter = response.retryAfter())
                attempt++
                continue
            }
            return response
        }
    }

    /** Waits before retry number [attempt] (0-based), honouring a server-provided `Retry-After` when present. */
    private fun backoff(attempt: Int, retryAfter: Duration?) {
        val exponential = retryBackoff.multipliedBy(1L shl attempt.coerceAtMost(MAX_BACKOFF_SHIFT))
        val delay = (retryAfter ?: exponential).coerceAtMost(MAX_RETRY_BACKOFF)
        if (!delay.isZero) sleeper(delay)
    }

    companion object {
        private val log: System.Logger = System.getLogger(RetryingHttpClient::class.java.name)

        private const val USER_AGENT = "meetup-agent/1.0"

        /** Upper bound for a single retry delay, regardless of backoff or `Retry-After`. */
        private val MAX_RETRY_BACKOFF: Duration = Duration.ofSeconds(30)

        /** Caps the exponent used for exponential backoff so the shift can never overflow. */
        private const val MAX_BACKOFF_SHIFT = 10

        /** HTTP statuses that indicate a transient condition worth retrying. */
        @Suppress("MagicNumber")
        private val RETRYABLE_STATUS: Set<Int> = setOf(408, 429, 500, 502, 503, 504)

        /** Reads a `Retry-After` header expressed in seconds, ignoring HTTP-date forms. */
        private fun HttpResponse<*>.retryAfter(): Duration? = headers().firstValue("retry-after").orElse(null)?.trim()?.toLongOrNull()
            ?.takeIf { it >= 0 }?.let(Duration::ofSeconds)
    }
}
