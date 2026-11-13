@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.neowarungposs.poskasir.pos.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
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

    val doShare: () -> Unit = {
        info = null
        data?.let { d ->
            scope.launch {
                try { shareXlsx(ctx, buildXlsx(d, ranges[range].first, outletName(outlets, outletId)), fileName) }
                catch (e: Exception) { error = friendlyError(e) }
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 720.dp
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp).padding(bottom = if (wide) 0.dp else 84.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---- header: title, period, outlet, export
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("KELOLA · LAPORAN", fontSize = 11.sp, letterSpacing = 0.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (wide) "Laporan penjualan" else "Laporan", fontFamily = Display, fontSize = if (wide) 28.sp else 24.sp, fontWeight = FontWeight.SemiBold)
                }
                if (wide) PeriodSegment(ranges.map { it.first }, range) { range = it }
                OutletPicker(outlets, outletId) { outletId = it }
                if (wide) {
                    Button(onClick = { info = null; saver.launch(fileName) }, enabled = data != null, shape = RoundedCornerShape(14.dp)) { Text("Simpan Excel", fontWeight = FontWeight.Bold) }
                    OutlinedButton(onClick = doShare, enabled = data != null, shape = RoundedCornerShape(14.dp)) { Text("Kirim WA") }
                }
            }
            if (!wide) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ranges.forEachIndexed { i, (label, _) -> FilterChip(selected = range == i, onClick = { range = i }, label = { Text(label) }) }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            ErrorText(error)
            info?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary) }
            val d = data ?: return@Column

            val omzet = d.l("omzet"); val hpp = d.l("hpp"); val laba = omzet - hpp
            val trx = d.l("trx")
            val margin = if (omzet > 0) laba * 100.0 / omzet else 0.0
            val top = d.arr("by_product").firstOrNull()
            val methods = d.arr("by_method")
            val hours = d.arr("by_hour")
            val products = d.arr("by_product").take(if (wide) 8 else 6)
            val cashiers = d.arr("by_cashier")
            val rangeLabel = ranges[range].first.lowercase()

            val hero: @Composable (Modifier) -> Unit = { m ->
                Box(
                    m.clip(RoundedCornerShape(22.dp)).background(heroBrush())
                        .then(palette.heroBorder?.let { Modifier.border(1.dp, it, RoundedCornerShape(22.dp)) } ?: Modifier)
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                ) {
                    Column {
                        Text("Omzet $rangeLabel · $trx transaksi", fontSize = 12.sp, color = palette.onHeroMuted)
                        Text(rupiah(omzet), color = palette.onHero, fontFamily = Display, fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp, maxLines = 1)
                        Text("Subtotal ${rupiah(d.l("subtotal"))} · diskon −${rupiah(d.l("discount"))}", fontSize = 12.sp, color = palette.onHeroMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            val kLaba: @Composable (Modifier) -> Unit = { m -> Kpi("Laba kotor", rupiah(laba), "HPP ${rupiah(hpp)}", m) }
            val kMargin: @Composable (Modifier) -> Unit = { m -> MarginKpi(margin, m) }
            val kTop: @Composable (Modifier) -> Unit = { m ->
                Kpi("Terlaris", top?.s("name") ?: "-", top?.let { "${qtyText(it.d("qty"))} terjual · ${rupiah(it.l("omzet"))}" } ?: "Belum ada", m, small = true)
            }
            val kTrx: @Composable (Modifier) -> Unit = { m ->
                Kpi(if (wide) "Transaksi" else "Rata-rata / struk", if (wide) "$trx" else rupiah(if (trx > 0) omzet / trx else 0),
                    (if (wide) "rata-rata ${rupiah(if (trx > 0) omzet / trx else 0)} · " else "") + "void ${d.l("void_count")}", m)
            }

            if (hpp == 0L && omzet > 0) Text("Isi harga modal di Kelola › Produk agar laba & margin akurat.", fontSize = 12.sp, color = MaterialTheme.colorScheme.tertiary)

            if (wide) {
                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    hero(Modifier.weight(1.5f).fillMaxHeight()); kLaba(Modifier.weight(1f).fillMaxHeight()); kMargin(Modifier.weight(1f).fillMaxHeight())
                    kTop(Modifier.weight(1f).fillMaxHeight()); kTrx(Modifier.weight(1f).fillMaxHeight())
                }
                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    HoursCard(hours, Modifier.weight(1f).fillMaxHeight(), barHeight = 150.dp)
                    MethodCard(methods, wide = true, modifier = Modifier.weight(1.25f).fillMaxHeight())
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ProductCard(products, d.l("discount"), wide = true, modifier = Modifier.weight(1.6f))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        CashierCard(cashiers, Modifier.fillMaxWidth())
                        PromoVoidCard(d.l("discount"), d.l("void_count"), d.l("void_total"), Modifier.fillMaxWidth())
                    }
                }
                if (outletId == null && outlets.size > 1) OutletCard(d.arr("by_outlet"), omzet, Modifier.fillMaxWidth())
            } else {
                hero(Modifier.fillMaxWidth())
                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    kLaba(Modifier.weight(1f).fillMaxHeight()); kMargin(Modifier.weight(1f).fillMaxHeight())
                }
                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    kTop(Modifier.weight(1f).fillMaxHeight()); kTrx(Modifier.weight(1f).fillMaxHeight())
                }
                MethodCard(methods, wide = false, modifier = Modifier.fillMaxWidth())
                HoursCard(hours, Modifier.fillMaxWidth(), barHeight = 110.dp)
                ProductCard(products, d.l("discount"), wide = false, modifier = Modifier.fillMaxWidth())
                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CashierCard(cashiers, Modifier.weight(1f).fillMaxHeight())
                    PromoVoidCard(d.l("discount"), d.l("void_count"), d.l("void_total"), Modifier.weight(1f).fillMaxHeight())
                }
                if (outletId == null && outlets.size > 1) OutletCard(d.arr("by_outlet"), omzet, Modifier.fillMaxWidth())
            }
            Text("Hanya transaksi yang sudah tersinkron dari HP kasir. Detail per struk ada di file Excel.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (!wide) {
            Surface(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp),
                shape = RoundedCornerShape(22.dp),
                color = if (palette.glass) Color(0xF0141A26) else MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)), shadowElevation = 12.dp,
            ) {
                Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { info = null; saver.launch(fileName) }, enabled = data != null, modifier = Modifier.weight(1f).height(50.dp), shape = RoundedCornerShape(16.dp)) {
                        Icon(Icons.Filled.Download, null); Spacer(Modifier.width(6.dp)); Text("Simpan Excel", fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(onClick = doShare, enabled = data != null, modifier = Modifier.weight(1f).height(50.dp), shape = RoundedCornerShape(16.dp)) {
                        Icon(Icons.Filled.Share, null); Spacer(Modifier.width(6.dp)); Text("Kirim WA")
                    }
                }
            }
        }
    }
}

private fun outletName(outlets: List<Outlet>, id: String?) = outlets.firstOrNull { it.id == id }?.name ?: "Semua cabang"
private fun qtyText(q: Double) = if (q % 1.0 == 0.0) q.toLong().toString() else "%.2f".format(q)

@Composable
private fun Panel(modifier: Modifier, tint: Color? = null, content: @Composable () -> Unit) {
    val glass = LocalPalette.current.glass
    val bg = tint?.copy(alpha = 0.10f) ?: if (glass) Color.White.copy(alpha = 0.04f) else MaterialTheme.colorScheme.surface
    val line = tint?.copy(alpha = 0.32f) ?: Color.White.copy(alpha = if (glass) 0.08f else 0.0f)
    Box(modifier.clip(RoundedCornerShape(22.dp)).background(bg).border(1.dp, line, RoundedCornerShape(22.dp)).padding(horizontal = 16.dp, vertical = 14.dp)) { content() }
}

@Composable
private fun Kpi(label: String, value: String, sub: String, modifier: Modifier, small: Boolean = false) {
    Panel(modifier) {
        Column {
            Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, fontFamily = Display, fontSize = if (small) 17.sp else 21.sp, fontWeight = if (small) FontWeight.SemiBold else FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            Text(sub, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun MarginKpi(margin: Double, modifier: Modifier) {
    val ring = MaterialTheme.colorScheme.secondary
    Panel(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(50.dp)) {
                val stroke = 7.dp.toPx()
                drawArc(Color.White.copy(alpha = 0.1f), 0f, 360f, false, style = Stroke(stroke))
                drawArc(ring, -90f, (margin.coerceIn(0.0, 100.0) * 3.6).toFloat(), false, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Margin", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${"%.1f".format(margin)}%", fontFamily = Display, fontSize = 21.sp, fontWeight = FontWeight.Bold, color = ring)
            }
        }
    }
}

@Composable
private fun CardTitle(title: String, trailing: String? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        trailing?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun methodColor(i: Int): Color = when (i % 3) {
    0 -> MaterialTheme.colorScheme.primary
    1 -> MaterialTheme.colorScheme.secondary
    else -> MaterialTheme.colorScheme.tertiary
}

private fun pct(laba: Long, omzet: Long) = if (omzet > 0) "${"%.1f".format(laba * 100.0 / omzet)}%" else "-"

@Composable
private fun MethodCard(rows: List<JsonObject>, wide: Boolean, modifier: Modifier) {
    val total = rows.sumOf { it.l("omzet") }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val margin = MaterialTheme.colorScheme.secondary
    Panel(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CardTitle("Laba & margin per metode bayar")
            if (total > 0) Row(Modifier.fillMaxWidth().height(9.dp).clip(RoundedCornerShape(5.dp)), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                rows.forEachIndexed { i, r ->
                    val w = r.l("omzet").toFloat() / total
                    if (w > 0f) Box(Modifier.weight(w).fillMaxHeight().background(methodColor(i)))
                }
            }
            if (rows.isEmpty()) Text("Belum ada transaksi.", fontSize = 13.sp, color = muted)
            if (wide) {
                val end = TextAlign.End
                Row { listOf("Metode" to 1.4f, "Trx" to 0.6f, "Omzet" to 1.2f, "HPP" to 1.1f, "Laba" to 1.1f, "Margin" to 0.8f).forEach { (h, w) ->
                    Text(h, Modifier.weight(w), fontSize = 12.sp, color = muted, textAlign = if (h == "Metode") TextAlign.Start else end) } }
                rows.forEachIndexed { i, r ->
                    HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1.4f), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(methodColor(i))); Spacer(Modifier.width(8.dp))
                            Text(payLabel(r.s("name")), fontSize = 14.sp)
                        }
                        Text("${r.l("trx")}", Modifier.weight(0.6f), fontSize = 14.sp, color = muted, textAlign = end)
                        Text(rupiah(r.l("omzet")), Modifier.weight(1.2f), fontSize = 14.sp, textAlign = end)
                        Text(rupiah(r.l("hpp")), Modifier.weight(1.1f), fontSize = 14.sp, color = muted, textAlign = end)
                        Text(rupiah(r.l("omzet") - r.l("hpp")), Modifier.weight(1.1f), fontSize = 14.sp, textAlign = end)
                        Text(pct(r.l("omzet") - r.l("hpp"), r.l("omzet")), Modifier.weight(0.8f), fontSize = 14.sp, color = margin, textAlign = end)
                    }
                }
                HorizontalDivider(color = Color.White.copy(alpha = 0.22f))
                val tH = rows.sumOf { it.l("hpp") }
                Row {
                    Text("Total", Modifier.weight(1.4f), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text("${rows.sumOf { it.l("trx") }}", Modifier.weight(0.6f), fontWeight = FontWeight.Bold, fontSize = 14.sp, textAlign = end)
                    Text(rupiah(total), Modifier.weight(1.2f), fontWeight = FontWeight.Bold, fontSize = 14.sp, textAlign = end)
                    Text(rupiah(tH), Modifier.weight(1.1f), fontWeight = FontWeight.Bold, fontSize = 14.sp, textAlign = end)
                    Text(rupiah(total - tH), Modifier.weight(1.1f), fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary, textAlign = end)
                    Text(pct(total - tH, total), Modifier.weight(0.8f), fontWeight = FontWeight.Bold, fontSize = 14.sp, color = margin, textAlign = end)
                }
            } else {
                rows.forEachIndexed { i, r ->
                    HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(methodColor(i))); Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(payLabel(r.s("name")), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text("${r.l("trx")} trx · omzet ${rupiah(r.l("omzet"))} · HPP ${rupiah(r.l("hpp"))}", fontSize = 11.sp, color = muted)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(rupiah(r.l("omzet") - r.l("hpp")), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text("margin ${pct(r.l("omzet") - r.l("hpp"), r.l("omzet"))}", fontSize = 11.sp, color = margin)
                        }
                    }
                }
                HorizontalDivider(color = Color.White.copy(alpha = 0.22f))
                val tH = rows.sumOf { it.l("hpp") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Total", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Text("${rows.sumOf { it.l("trx") }} trx · omzet ${rupiah(total)} · HPP ${rupiah(tH)}", fontSize = 11.sp, color = muted)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(rupiah(total - tH), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Text("margin ${pct(total - tH, total)}", fontSize = 11.sp, color = margin)
                    }
                }
            }
        }
    }
}

@Composable
private fun HoursCard(hours: List<JsonObject>, modifier: Modifier, barHeight: androidx.compose.ui.unit.Dp) {
    val peak = hours.maxByOrNull { it.l("omzet") }
    val maxO = (peak?.l("omzet") ?: 0L).coerceAtLeast(1)
    val first = hours.minOfOrNull { it.l("hour") }?.toInt() ?: 7
    val last = hours.maxOfOrNull { it.l("hour") }?.toInt() ?: 21
    val primary = MaterialTheme.colorScheme.primary
    Panel(modifier) {
        Column {
            CardTitle("Omzet per jam", peak?.let { "ramai ${"%02d".format(it.l("hour"))}:00 · ${rupiah(it.l("omzet"))}" })
            if (hours.isEmpty()) Text("Belum ada transaksi.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            else Row(Modifier.fillMaxWidth().height(barHeight).padding(top = 12.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                (minOf(first, 7)..maxOf(last, 21)).forEach { h ->
                    val v = hours.firstOrNull { it.l("hour") == h.toLong() }?.l("omzet") ?: 0
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Bottom, horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier.fillMaxWidth().fillMaxHeight((v.toFloat() / maxO).coerceAtLeast(0.03f) * 0.82f)
                                .clip(RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp, bottomStart = 2.dp, bottomEnd = 2.dp))
                                .background(if (v == maxO) primary else primary.copy(alpha = 0.35f)),
                        )
                        Text(if (h % 3 == 0) "$h" else "", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun ProductCard(rows: List<JsonObject>, discount: Long, wide: Boolean, modifier: Modifier) {
    val maxO = (rows.maxOfOrNull { it.l("omzet") } ?: 0L).coerceAtLeast(1)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val margin = MaterialTheme.colorScheme.secondary
    val primary = MaterialTheme.colorScheme.primary
    Panel(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CardTitle("Produk terlaris")
            if (rows.isEmpty()) Text("Belum ada transaksi.", fontSize = 13.sp, color = muted)
            if (wide && rows.isNotEmpty()) Row { listOf("Produk" to 2f, "Qty" to 0.6f, "Omzet" to 1.1f, "Laba" to 1.1f, "Margin" to 0.8f).forEach { (h, w) ->
                Text(h, Modifier.weight(w), fontSize = 12.sp, color = muted, textAlign = if (h == "Produk") TextAlign.Start else TextAlign.End) } }
            rows.forEach { p ->
                val o = p.l("omzet"); val lb = o - p.l("hpp")
                val mg = if (p.l("hpp") > 0) pct(lb, o) else "-"
                HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
                if (wide) Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(2f)) {
                        Text(p.s("name"), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Box(Modifier.padding(top = 5.dp).fillMaxWidth(0.7f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.07f))) {
                            Box(Modifier.fillMaxWidth(o.toFloat() / maxO).fillMaxHeight().background(primary))
                        }
                    }
                    Text(qtyText(p.d("qty")), Modifier.weight(0.6f), fontSize = 14.sp, color = muted, textAlign = TextAlign.End)
                    Text(rupiah(o), Modifier.weight(1.1f), fontSize = 14.sp, textAlign = TextAlign.End)
                    Text(rupiah(lb), Modifier.weight(1.1f), fontSize = 14.sp, textAlign = TextAlign.End)
                    Text(mg, Modifier.weight(0.8f), fontSize = 14.sp, color = margin, textAlign = TextAlign.End)
                } else Column {
                    Row { Text(p.s("name"), Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(rupiah(o), fontSize = 14.sp) }
                    Row { Text("${qtyText(p.d("qty"))} terjual · laba ${rupiah(lb)}", Modifier.weight(1f), fontSize = 11.sp, color = muted); Text("margin $mg", fontSize = 11.sp, color = margin) }
                    Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.07f))) {
                        Box(Modifier.fillMaxWidth(o.toFloat() / maxO).fillMaxHeight().background(primary))
                    }
                }
            }
            if (discount > 0) Text("Omzet produk dihitung sebelum diskon promo (${rupiah(discount)}).", fontSize = 11.sp, color = muted)
        }
    }
}

@Composable
private fun CashierCard(rows: List<JsonObject>, modifier: Modifier) {
    Panel(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            CardTitle("Per kasir")
            if (rows.isEmpty()) Text("-", fontSize = 13.sp)
            rows.forEach { c ->
                Row {
                    Text(c.s("name").substringBefore("@"), Modifier.weight(1f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${c.l("trx")} trx · ${rupiah(c.l("omzet"))}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun PromoVoidCard(discount: Long, voidCount: Long, voidTotal: Long, modifier: Modifier) {
    val c = MaterialTheme.colorScheme.tertiary
    Panel(modifier, tint = c) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Promo & void", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Row { Text("Diskon promo", Modifier.weight(1f), fontSize = 13.sp, color = c); Text("−${rupiah(discount)}", fontSize = 13.sp, color = c) }
            Row { Text("Void ($voidCount struk)", Modifier.weight(1f), fontSize = 13.sp, color = c); Text(rupiah(voidTotal), fontSize = 13.sp, color = c) }
        }
    }
}

@Composable
private fun OutletCard(rows: List<JsonObject>, omzet: Long, modifier: Modifier) {
    Panel(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            CardTitle("Per cabang")
            rows.forEach { o ->
                Row {
                    Text(o.s("name"), Modifier.weight(1f), fontSize = 13.sp)
                    Text("${o.l("trx")} trx · ${rupiah(o.l("omzet"))} · laba ${rupiah(o.l("omzet") - o.l("hpp"))}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun PeriodSegment(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.05f)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        labels.forEachIndexed { i, l ->
            val on = i == selected
            Surface(
                onClick = { onSelect(i) }, shape = RoundedCornerShape(10.dp),
                color = if (on) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                contentColor = if (on) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurfaceVariant,
            ) { Text(l, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), fontSize = 13.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal) }
        }
    }
}

@Composable
private fun OutletPicker(outlets: List<Outlet>, selected: String?, onSelect: (String?) -> Unit) {
    if (outlets.size <= 1) return
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, shape = RoundedCornerShape(14.dp)) { Text(outletName(outlets, selected) + " ▾", maxLines = 1) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Semua cabang") }, onClick = { open = false; onSelect(null) })
            outlets.forEach { o -> DropdownMenuItem(text = { Text(o.name) }, onClick = { open = false; onSelect(o.id) }) }
        }
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
