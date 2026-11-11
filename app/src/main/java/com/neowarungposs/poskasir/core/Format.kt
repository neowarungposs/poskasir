package com.neowarungposs.poskasir.core

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val idLocale = Locale("id", "ID")
private val dateFmt = DateTimeFormatter.ofPattern("d MMM yyyy", idLocale)
private val dateTimeFmt = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm", idLocale)

fun rupiah(value: Long): String {
    val abs = kotlin.math.abs(value)
    val grouped = abs.toString().reversed().chunked(3).joinToString(".").reversed()
    return (if (value < 0) "-Rp" else "Rp") + grouped
}

fun parseInstant(ts: String?): Instant? = ts?.let {
    runCatching { OffsetDateTime.parse(it).toInstant() }.getOrElse { runCatching { Instant.parse(it) }.getOrNull() }
}

fun formatDate(ts: String?): String = parseInstant(ts)?.atZone(ZoneId.systemDefault())?.format(dateFmt) ?: "-"
fun formatDate(i: Instant): String = i.atZone(ZoneId.systemDefault()).format(dateFmt)
fun formatDateTime(epochMs: Long): String = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(dateTimeFmt)
fun formatDateTime(ts: String?): String = parseInstant(ts)?.atZone(ZoneId.systemDefault())?.format(dateTimeFmt) ?: "-"

/** Subscription status label in Indonesian. */
fun statusLabel(status: String): String = when (status) {
    "TRIAL" -> "Trial"
    "ACTIVE" -> "Aktif"
    "GRACE" -> "Masa tenggang"
    "EXPIRED" -> "Berakhir"
    else -> status
}
