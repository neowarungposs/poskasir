@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.neowarungposs.poskasir.pos.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.neowarungposs.poskasir.core.Outlet
import com.neowarungposs.poskasir.core.XlsxWriter
import com.neowarungposs.poskasir.core.formatDateTime
import com.neowarungposs.poskasir.core.friendlyError
import com.neowarungposs.poskasir.core.rupiah
import com.neowarungposs.poskasir.pos.PosRepository
import com.neowarungposs.poskasir.pos.PosRepository.Companion.DAY_MS
import com.neowarungposs.poskasir.ui.ErrorText
import com.neowarungposs.poskasir.ui.LocalPalette
import com.neowarungposs.poskasir.ui.Display
import com.neowarungposs.poskasir.ui.heroBrush
import androidx.compose.foundation.border
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private fun JsonObject.d(k: String): Double = runCatching { this[k]!!.jsonPrimitive.doubleOrNull ?: 0.0 }.getOrDefault(0.0)
private fun JsonObject.l(k: String): Long = d(k).toLong()
private fun JsonObject.s(k: String): String = runCatching { this[k]!!.jsonPrimitive.contentOrNull ?: "" }.getOrDefault("")
private fun JsonObject.arr(k: String): List<JsonObject> =
    runCatching { (this[k] as JsonArray).map { it.jsonObject } }.getOrDefault(emptyList())

private fun payLabel(m: String) = when (m) { "CASH" -> "Tunai"; "QRIS" -> "QRIS"; "OTHER" -> "Lainnya"; else -> m }

/** TENANT_ADMIN report (D24): omzet, HPP, margin, per branch/product/cashier/method/hour, XLSX export. */
@Composable
fun LaporanScreen(repo: PosRepository) {
    val ranges = listOf("Hari ini" to 0, "Kemarin" to -1, "7 hari" to 6, "30 hari" to 29, "Bulan ini" to -2)
    var range by rememberSaveable { mutableIntStateOf(0) }
    var outletId by rememberSaveable { mutableStateOf<String?>(null) }
    var outlets by remember { mutableStateOf<List<Outlet>>(emptyList()) }
    var data by remember { mutableStateOf<JsonObject?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val palette = LocalPalette.current

    val today = repo.todayStartMs()
    val (from, to) = when (ranges[range].second) {
        0 -> today to today + DAY_MS
        -1 -> today - DAY_MS to today
        -2 -> {
            val first = LocalDate.now().withDayOfMonth(1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            first to today + DAY_MS
        }
        else -> today - ranges[range].second * DAY_MS to today + DAY_MS
    }

    LaunchedEffect(range, outletId) {
        loading = true
        try {
            if (outlets.isEmpty()) outlets = repo.outlets()
            data = repo.report(from, to, outletId)
            error = null
        } catch (e: Exception) { error = friendlyError(e) }
        loading = false
    }

    val fileName = "Laporan-${ranges[range].first.replace(" ", "")}-${LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)}.xlsx"
    val saver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    ) { uri ->
        val d = data
        if (uri != null && d != null) scope.launch {
            try {
                withContext(Dispatchers.IO) { ctx.contentResolver.openOutputStream(uri)?.use { buildXlsx(d, ranges[range].first, outletName(outlets, outletId)).write(it) } }
                info = "Tersimpan: $fileName"
            } catch (e: Exception) { error = friendlyError(e) }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ranges.forEachIndexed { i, (label, _) -> FilterChip(selected = range == i, onClick = { range = i }, label = { Text(label) }) }
        }
        if (outlets.size > 1) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = outletId == null, onClick = { outletId = null }, label = { Text("Semua cabang") })
            outlets.forEach { o -> FilterChip(selected = outletId == o.id, onClick = { outletId = o.id }, label = { Text(o.name) }) }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        ErrorText(error)
        val d = data ?: return@Column

        val omzet = d.l("omzet"); val hpp = d.l("hpp"); val laba = omzet - hpp
        val marginPct = if (omzet > 0) laba * 100.0 / omzet else 0.0
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(heroBrush())
                .then(palette.heroBorder?.let { Modifier.border(1.dp, it, RoundedCornerShape(24.dp)) } ?: Modifier),
        ) {
            Column(Modifier.padding(18.dp)) {
                Text("Omzet · ${outletName(outlets, outletId)}", color = palette.onHeroMuted, fontSize = 13.sp)
                Text(rupiah(omzet), color = palette.onHero, fontFamily = Display, fontSize = 32.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp)
                Text("${d.l("trx")} transaksi · rata-rata ${rupiah(if (d.l("trx") > 0) omzet / d.l("trx") else 0)}", color = palette.onHeroMuted, fontSize = 13.sp)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Kpi("Laba kotor", rupiah(laba), "dari omzet − HPP", Modifier.weight(1f))
            Kpi("Margin", "${"%.1f".format(marginPct)}%", "laba ÷ omzet", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val top = d.arr("by_product").firstOrNull()
            Kpi("Terlaris", top?.s("name") ?: "-", top?.let { "${qtyText(it.d("qty"))} terjual · ${rupiah(it.l("omzet"))}" } ?: "Belum ada", Modifier.weight(1f))
            Kpi("HPP / modal", rupiah(hpp), "dari harga modal produk", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Kpi("Diskon promo", rupiah(d.l("discount")), "dari ${rupiah(d.l("subtotal"))}", Modifier.weight(1f))
            Kpi("Void", "${d.l("void_count")}", rupiah(d.l("void_total")), Modifier.weight(1f))
        }
        if (hpp == 0L && omzet > 0) Text("Isi harga modal di Kelola > Produk agar laba & margin akurat.", fontSize = 12.sp, color = MaterialTheme.colorScheme.tertiary)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { info = null; saver.launch(fileName) }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Download, null); Spacer(Modifier.width(6.dp)); Text("Simpan Excel")
            }
            OutlinedButton(onClick = {
                info = null
                scope.launch {
                    try { shareXlsx(ctx, buildXlsx(d, ranges[range].first, outletName(outlets, outletId)), fileName) }
                    catch (e: Exception) { error = friendlyError(e) }
                }
            }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Share, null); Spacer(Modifier.width(6.dp)); Text("Kirim WA")
            }
        }
        info?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary) }

        val hours = d.arr("by_hour")
        if (hours.isNotEmpty()) Section("Jam ramai") {
            val maxO = hours.maxOf { it.l("omzet") }.coerceAtLeast(1)
            Row(Modifier.fillMaxWidth().height(110.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                (0..23).forEach { h ->
                    val v = hours.firstOrNull { it.l("hour") == h.toLong() }?.l("omzet") ?: 0
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Bottom, horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier.fillMaxWidth().fillMaxHeight((v.toFloat() / maxO).coerceAtLeast(0.02f) * 0.85f)
                                .clip(RoundedCornerShape(3.dp))
                                .background(if (v == maxO) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                        )
                        if (h % 3 == 0) Text("$h", fontSize = 9.sp) else Spacer(Modifier.height(12.dp))
                    }
                }
            }
        }
        if (outletId == null && outlets.size > 1) Section("Per cabang") {
            d.arr("by_outlet").forEach { o ->
                RowLine(o.s("name"), "${o.l("trx")} trx · laba ${rupiah(o.l("omzet") - o.l("hpp"))}", rupiah(o.l("omzet")), o.l("omzet"), omzet)
            }
        }
        Section("Produk terlaris") {
            d.arr("by_product").take(15).forEach { p ->
                val m = p.l("omzet") - p.l("hpp")
                RowLine(p.s("name"), "${qtyText(p.d("qty"))} terjual · margin ${if (p.l("omzet") > 0 && p.l("hpp") > 0) "${m * 100 / p.l("omzet")}%" else "-"}", rupiah(p.l("omzet")), p.l("omzet"), omzet)
            }
        }
        Section("Per kasir") { d.arr("by_cashier").forEach { c -> RowLine(c.s("name"), "${c.l("trx")} trx", rupiah(c.l("omzet")), c.l("omzet"), omzet) } }
        Section("Laba & margin per metode bayar") {
            Row(Modifier.fillMaxWidth()) {
                listOf("Metode" to 1.3f, "Omzet" to 1.2f, "Laba" to 1.2f, "Margin" to 0.8f).forEach { (h, w) ->
                    Text(h, Modifier.weight(w), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = if (h == "Metode") androidx.compose.ui.text.style.TextAlign.Start else androidx.compose.ui.text.style.TextAlign.End)
                }
            }
            val rows = d.arr("by_method")
            rows.forEach { c ->
                MethodRow(payLabel(c.s("name")) + " · ${c.l("trx")}", c.l("omzet"), c.l("omzet") - c.l("hpp"), bold = false)
            }
            androidx.compose.material3.HorizontalDivider()
            MethodRow("Total · ${rows.sumOf { it.l("trx") }}", rows.sumOf { it.l("omzet") }, rows.sumOf { it.l("omzet") - it.l("hpp") }, bold = true)
        }
        Text("Hanya transaksi yang sudah tersinkron dari HP kasir. Detail per transaksi ada di file Excel.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
    }
}

private fun outletName(outlets: List<Outlet>, id: String?) = outlets.firstOrNull { it.id == id }?.name ?: "Semua cabang"
private fun qtyText(q: Double) = if (q % 1.0 == 0.0) q.toLong().toString() else "%.2f".format(q)

@Composable
private fun Kpi(label: String, value: String, sub: String, modifier: Modifier) {
    Card(modifier) {
        Column(Modifier.padding(14.dp)) {
            Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, fontFamily = Display, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun MethodRow(label: String, omzet: Long, laba: Long, bold: Boolean) {
    val w = if (bold) FontWeight.Bold else FontWeight.Normal
    val end = androidx.compose.ui.text.style.TextAlign.End
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1.3f), fontWeight = w, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(rupiah(omzet), Modifier.weight(1.2f), fontWeight = w, fontSize = 14.sp, textAlign = end)
        Text(rupiah(laba), Modifier.weight(1.2f), fontWeight = w, fontSize = 14.sp, textAlign = end)
        Text(if (omzet > 0) "${"%.1f".format(laba * 100.0 / omzet)}%" else "-", Modifier.weight(0.8f), fontWeight = w, fontSize = 14.sp, textAlign = end,
            color = MaterialTheme.colorScheme.secondary)
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun RowLine(title: String, sub: String, value: String, part: Long, whole: Long) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(sub, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(value, fontWeight = FontWeight.Bold)
        }
        LinearProgressIndicator(
            progress = { if (whole > 0) (part.toFloat() / whole).coerceIn(0f, 1f) else 0f },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(4.dp),
            trackColor = Color.Transparent,
        )
    }
}

private fun buildXlsx(d: JsonObject, rangeLabel: String, outlet: String): XlsxWriter {
    val omzet = d.l("omzet"); val hpp = d.l("hpp")
    return XlsxWriter()
        .sheet("Ringkasan", listOf(
            listOf("Laporan Kasir Warung", ""),
            listOf("Periode", rangeLabel),
            listOf("Cabang", outlet),
            listOf("Dibuat", formatDateTime(System.currentTimeMillis())),
            listOf("Omzet", omzet),
            listOf("Subtotal sebelum diskon", d.l("subtotal")),
            listOf("Diskon promo", d.l("discount")),
            listOf("HPP / modal", hpp),
            listOf("Laba kotor", omzet - hpp),
            listOf("Margin %", if (omzet > 0) Math.round((omzet - hpp) * 1000.0 / omzet) / 10.0 else 0.0),
            listOf("Transaksi", d.l("trx")),
            listOf("Void (jumlah)", d.l("void_count")),
            listOf("Void (nilai)", d.l("void_total")),
        ))
        .sheet("Per Cabang", listOf(listOf("Cabang", "Transaksi", "Omzet", "HPP", "Laba kotor")) +
            d.arr("by_outlet").map { listOf(it.s("name"), it.l("trx"), it.l("omzet"), it.l("hpp"), it.l("omzet") - it.l("hpp")) })
        .sheet("Per Produk", listOf(listOf("Produk", "Qty", "Omzet", "HPP", "Laba kotor", "Margin %")) +
            d.arr("by_product").map {
                val o = it.l("omzet"); val h = it.l("hpp")
                listOf(it.s("name"), it.d("qty"), o, h, o - h, if (o > 0 && h > 0) Math.round((o - h) * 1000.0 / o) / 10.0 else null)
            })
        .sheet("Per Kasir", listOf(listOf("Kasir", "Transaksi", "Omzet")) + d.arr("by_cashier").map { listOf(it.s("name"), it.l("trx"), it.l("omzet")) })
        .sheet("Per Metode", d.arr("by_method").let { rows ->
            fun m(o: Long, h: Long): Double? = if (o > 0) Math.round((o - h) * 1000.0 / o) / 10.0 else null
            listOf(listOf("Metode", "Transaksi", "Omzet", "HPP", "Laba kotor", "Margin %")) +
                rows.map { listOf(payLabel(it.s("name")), it.l("trx"), it.l("omzet"), it.l("hpp"), it.l("omzet") - it.l("hpp"), m(it.l("omzet"), it.l("hpp"))) } +
                listOf(listOf("TOTAL", rows.sumOf { it.l("trx") }, rows.sumOf { it.l("omzet") }, rows.sumOf { it.l("hpp") },
                    rows.sumOf { it.l("omzet") - it.l("hpp") }, m(rows.sumOf { it.l("omzet") }, rows.sumOf { it.l("hpp") })))
        })
        .sheet("Per Jam", listOf(listOf("Jam", "Transaksi", "Omzet")) + d.arr("by_hour").map { listOf("%02d:00".format(it.l("hour")), it.l("trx"), it.l("omzet")) })
        .sheet("Transaksi", listOf(listOf("No. struk", "Waktu", "Cabang", "Kasir", "Metode", "Subtotal", "Diskon", "Total", "Status", "Promo")) +
            d.arr("transactions").map {
                listOf(it.s("invoice_no"), formatDateTime(it.s("created_at")), it.s("outlet"), it.s("cashier"), payLabel(it.s("payment_method")),
                    it.l("subtotal"), it.l("discount"), it.l("total"), if (it.s("status") == "VOIDED") "VOID" else "LUNAS", it.s("promo_summary"))
            })
}

private suspend fun shareXlsx(ctx: Context, x: XlsxWriter, name: String) {
    val file = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        File(dir, name).also { f -> f.outputStream().use { x.write(it) } }
    }
    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".exports", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TEXT, "Laporan penjualan ($name)")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val wa = Intent(send).setPackage("com.whatsapp")
    try {
        ctx.startActivity(wa.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        ctx.startActivity(Intent.createChooser(send, "Kirim laporan").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
