package com.bnx.meetup.client.luma

import com.bnx.meetup.domain.Meetup
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.time.OffsetDateTime

// Null-safe accessors: Luma frequently sends explicit JSON `null`s, and the stock
// `.jsonObject` / `.jsonPrimitive` casts throw on those.
internal fun JsonElement?.asObjectOrNull(): JsonObject? = this as? JsonObject

internal fun JsonElement?.asStringOrNull(): String? = (this as? JsonPrimitive)?.contentOrNull

internal fun JsonElement?.asBooleanOrNull(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull

internal fun JsonElement?.asIntOrNull(): Int? = (this as? JsonPrimitive)?.intOrNull

/** Maps one discover-feed entry to a [Meetup], or null when mandatory fields are missing/invalid. */
internal fun parseDiscoverEntry(entry: JsonObject): Meetup? {
    val event = entry["event"].asObjectOrNull() ?: return null
    val apiId = event["api_id"].asStringOrNull() ?: return null
    val name = event["name"].asStringOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val slug = event["url"].asStringOrNull()?.takeIf { it.isNotBlank() } ?: return null
    val startRaw = event["start_at"].asStringOrNull() ?: return null
    val startAt = runCatching { OffsetDateTime.parse(startRaw) }.getOrNull() ?: return null

    val geo = event["geo_address_info"].asObjectOrNull()
    return Meetup(
        apiId = apiId,
        name = name,
        slug = slug,
        startAt = startAt,
        city = geo?.get("city").asStringOrNull(),
        cityState = geo?.get("city_state").asStringOrNull(),
        timezone = event["timezone"].asStringOrNull(),
    )
}
