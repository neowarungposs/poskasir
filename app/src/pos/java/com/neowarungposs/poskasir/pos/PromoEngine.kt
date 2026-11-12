package com.neowarungposs.poskasir.pos

import com.neowarungposs.poskasir.core.parseInstant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.time.ZoneId

/**
 * Promotions (D22). Evaluated offline on the device.
 * config by type:
 *  BUNDLE    {product_ids:[..], qty:3, price:10000}          pick N eligible items for a fixed price (mix allowed)
 *  BXGY      {buy_ids:[..], buy_qty:2, get_ids:[..], get_qty:1}  cheapest "get" items become free
 *  PERCENT   {product_ids:[..] (empty = all), pct:10}
 *  AMOUNT    {product_ids:[..] (empty = all), amount:2000}     per unit
 *  MIN_SPEND {min:50000, amount:5000}
 */
@Serializable
data class PromoDto(
    val id: String,
    val name: String,
    val type: String,
    val config: JsonObject = JsonObject(emptyMap()),
    val outletIds: List<String>? = null,
    val startsAt: String? = null,
    val endsAt: String? = null,
    val days: List<Int>? = null,
    val startMinute: Int? = null,
    val endMinute: Int? = null,
    val isActive: Boolean = true,
    val deletedAt: String? = null,
) {
    fun ids(key: String): Set<String> =
        runCatching { config[key]!!.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }.toSet() }.getOrDefault(emptySet())

    fun num(key: String): Long = runCatching { config[key]!!.jsonPrimitive.longOrNull ?: 0L }.getOrDefault(0L)

    fun productIds(): Set<String> = ids("product_ids") + ids("buy_ids") + ids("get_ids")

    fun describe(): String = when (type) {
        "BUNDLE" -> "Pilih ${num("qty")} menu cukup Rp${"%,d".format(num("price")).replace(',', '.')}"
        "BXGY" -> "Beli ${num("buy_qty")} gratis ${num("get_qty")}"
        "PERCENT" -> "Diskon ${num("pct")}%"
        "AMOUNT" -> "Potongan Rp${"%,d".format(num("amount")).replace(',', '.')} per item"
        "MIN_SPEND" -> "Belanja min Rp${"%,d".format(num("min")).replace(',', '.')} potong Rp${"%,d".format(num("amount")).replace(',', '.')}"
        else -> type
    }
}

data class PricedLine(val productId: String, val unitPrice: Long, val qty: Int)
data class AppliedPromo(val promoId: String, val name: String, val discount: Long)

object PromoEngine {

    fun activeNow(promos: List<PromoDto>, outletId: String?, nowMs: Long): List<PromoDto> {
        val zoned = Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault())
        val minute = zoned.hour * 60 + zoned.minute
        val day = zoned.dayOfWeek.value
        return promos.filter { p ->
            p.isActive && p.deletedAt == null &&
                (p.outletIds.isNullOrEmpty() || outletId in p.outletIds) &&
                (p.startsAt == null || (parseInstant(p.startsAt)?.toEpochMilli() ?: 0) <= nowMs) &&
                (p.endsAt == null || (parseInstant(p.endsAt)?.toEpochMilli() ?: Long.MAX_VALUE) > nowMs) &&
                (p.days.isNullOrEmpty() || day in p.days) &&
                (p.startMinute == null || p.endMinute == null || minute in p.startMinute until p.endMinute)
        }
    }

    /** Applies promos in a fixed order: bundles, buy-x-get-y, item discounts, then minimum spend. */
    fun apply(lines: List<PricedLine>, promos: List<PromoDto>): List<AppliedPromo> {
        class Slot(val productId: String, val price: Long, var used: Boolean = false)
        val units = lines.flatMap { l -> List(l.qty) { Slot(l.productId, l.unitPrice) } }
        val subtotal = units.sumOf { it.price }
        val out = mutableListOf<AppliedPromo>()
        fun add(p: PromoDto, d: Long) { if (d > 0) out += AppliedPromo(p.id, p.name, d) }

        promos.filter { it.type == "BUNDLE" }.forEach { p ->
            val ids = p.ids("product_ids"); val n = p.num("qty").toInt(); val price = p.num("price")
            if (n <= 0) return@forEach
            var disc = 0L
            while (true) {
                val pick = units.filter { !it.used && it.productId in ids }.sortedByDescending { it.price }.take(n)
                if (pick.size < n) break
                val saving = pick.sumOf { it.price } - price
                if (saving <= 0) break
                pick.forEach { it.used = true }
                disc += saving
            }
            add(p, disc)
        }

        promos.filter { it.type == "BXGY" }.forEach { p ->
            val buyIds = p.ids("buy_ids"); val getIds = p.ids("get_ids")
            val bq = p.num("buy_qty").toInt(); val gq = p.num("get_qty").toInt()
            if (bq <= 0 || gq <= 0) return@forEach
            var disc = 0L
            while (true) {
                val buy = units.filter { !it.used && it.productId in buyIds }.sortedByDescending { it.price }.take(bq)
                if (buy.size < bq) break
                val get = units.filter { !it.used && it.productId in getIds && buy.none { b -> b === it } }.sortedBy { it.price }.take(gq)
                if (get.size < gq) break
                buy.forEach { it.used = true }; get.forEach { it.used = true }
                disc += get.sumOf { it.price }
            }
            add(p, disc)
        }

        promos.filter { it.type == "PERCENT" || it.type == "AMOUNT" }.forEach { p ->
            val ids = p.ids("product_ids")
            val eligible = units.filter { !it.used && (ids.isEmpty() || it.productId in ids) }
            val disc = if (p.type == "PERCENT") eligible.sumOf { it.price } * p.num("pct") / 100
            else eligible.sumOf { minOf(it.price, p.num("amount")) }
            if (disc > 0) eligible.forEach { it.used = true }
            add(p, disc)
        }

        promos.filter { it.type == "MIN_SPEND" }.forEach { p ->
            val after = subtotal - out.sumOf { it.discount }
            if (after >= p.num("min")) add(p, p.num("amount"))
        }

        // never discount more than the subtotal
        var remaining = subtotal
        return out.map { a -> val d = minOf(a.discount, remaining); remaining -= d; a.copy(discount = d) }.filter { it.discount > 0 }
    }
}
