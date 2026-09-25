package com.bnx.meetup.client.luma.format

import com.bnx.meetup.client.luma.asIntOrNull
import com.bnx.meetup.client.luma.asObjectOrNull
import com.bnx.meetup.client.luma.asStringOrNull
import kotlinx.serialization.json.JsonObject
import java.util.Locale

/** Turns Luma's `ticket_info` into a short display price. */
object PriceFormatter {
    private const val CENTS_PER_UNIT = 100

    /** Common currency codes mapped to their symbol for nicer display. */
    private val CURRENCY_SYMBOLS: Map<String, String> = mapOf(
        "usd" to "$",
        "eur" to "\u20ac",
        "gbp" to "\u00a3",
        "jpy" to "\u00a5",
    )

    /**
     * Returns a formatted amount (e.g. "\u20ac25") whenever a concrete price is
     * present, and "Free" otherwise. Luma marks many events as `is_free = false`
     * even though they expose no actual price (e.g. approval-based tickets); since
     * there is nothing to charge/display we treat those as "Free" rather than the
     * misleading "Paid" with no amount.
     */
    internal fun formatPrice(ticketInfo: JsonObject): String {
        val price = ticketInfo["price"].asObjectOrNull()
        val cents = price?.get("cents").asIntOrNull()
        val currency = price?.get("currency").asStringOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        if (cents != null && cents > 0 && currency != null) {
            val symbol = CURRENCY_SYMBOLS[currency.lowercase()]
            val amount = if (cents % CENTS_PER_UNIT == 0) {
                (cents / CENTS_PER_UNIT).toString()
            } else {
                String.format(Locale.US, "%.2f", cents / CENTS_PER_UNIT.toDouble())
            }
            return if (symbol != null) "$symbol$amount" else "$amount ${currency.uppercase()}"
        }

        // No concrete amount available -> the event is effectively free to attend.
        return "Free"
    }
}
