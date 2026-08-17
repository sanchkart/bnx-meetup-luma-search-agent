package com.bnx.meetup.client

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * Tiny wrapper around the Telegram Bot API `sendMessage` method.
 *
 * Create a bot with @BotFather to obtain [botToken], then add the bot as an
 * administrator of your channel and use the channel's @username or numeric id
 * as [chatId].
 */
class TelegramClient(
    private val botToken: String,
    private val chatId: String,
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build(),
    private val baseUrl: String = "https://api.telegram.org",
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Sends a single message to the configured channel using HTML parse mode.
     * Returns the message id reported by Telegram.
     */
    fun sendMessage(text: String, disableWebPagePreview: Boolean = true): Long {
        val form = buildString {
            append("chat_id=").append(enc(chatId))
            append("&parse_mode=HTML")
            append("&disable_web_page_preview=").append(disableWebPagePreview)
            append("&text=").append(enc(text))
        }
        val request = HttpRequest.newBuilder(URI.create("$baseUrl/bot$botToken/sendMessage"))
            .header("content-type", "application/x-www-form-urlencoded; charset=UTF-8")
            .timeout(Duration.ofSeconds(20))
            .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        val body = runCatching { json.parseToJsonElement(response.body()).jsonObject }.getOrNull()
        val ok = body?.get("ok")?.jsonPrimitive?.content == "true"
        check(response.statusCode() == 200 && ok) {
            "Telegram sendMessage failed (HTTP ${response.statusCode()}): ${response.body().take(300)}"
        }
        return body["result"]?.jsonObject?.get("message_id")?.jsonPrimitive?.content?.toLongOrNull() ?: -1L
    }

    private fun enc(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
}