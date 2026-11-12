@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.neowarungposs.poskasir.pos.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neowarungposs.poskasir.core.Outlet
import com.neowarungposs.poskasir.core.friendlyError
import com.neowarungposs.poskasir.core.parseInstant
import com.neowarungposs.poskasir.pos.PosRepository
import com.neowarungposs.poskasir.pos.ProductEntity
import com.neowarungposs.poskasir.pos.PromoDto
import com.neowarungposs.poskasir.pos.PromoEngine
import com.neowarungposs.poskasir.ui.ConfirmDialog
import com.neowarungposs.poskasir.ui.ErrorText
import com.neowarungposs.poskasir.ui.LocalPalette
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TYPES = listOf(
    "BUNDLE" to "Paket (mix)",
    "BXGY" to "Beli X gratis Y",
    "PERCENT" to "Diskon %",
    "AMOUNT" to "Potongan Rp",
    "MIN_SPEND" to "Min. belanja",
)
private val DAYS = listOf("Sen", "Sel", "Rab", "Kam", "Jum", "Sab", "Min")

/** TENANT_ADMIN: create and edit promotions (D22). Applied offline in the cashier screen. */
@Composable
fun PromoScreen(repo: PosRepository) {
    val promos by repo.promos.collectAsState()
    val products by repo.dao.productsFlow().collectAsState(initial = emptyList())
    var outlets by remember { mutableStateOf<List<Outlet>>(emptyList()) }
    var editing by remember { mutableStateOf<PromoDto?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<PromoDto?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val palette = LocalPalette.current

    LaunchedEffect(Unit) {
        repo.loadCaches()
        outlets = repo.cachedOutlets()
        runCatching { repo.pullPromos() }.onFailure { error = friendlyError(it) }
    }
    val now = System.currentTimeMillis()

    Box(Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text("Promo otomatis terpasang di layar kasir sesuai jadwal & cabang. Ubah promo butuh internet.", fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                ErrorText(error)
            }
            if (promos.isEmpty()) item { Text("Belum ada promo.", Modifier.padding(8.dp)) }
            items(promos, key = { it.id }) { p ->
                val live = PromoEngine.activeNow(listOf(p), p.outletIds?.firstOrNull(), now).isNotEmpty()
                Card(
                    onClick = { editing = p }, modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = if (p.isActive) palette.promo else MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name, fontWeight = FontWeight.Bold, color = if (p.isActive) palette.onPromo else MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(p.describe(), fontSize = 13.sp)
                            Text(schedule(p, outlets) + if (!p.isActive) " · nonaktif" else if (!live) " · di luar jadwal" else "", fontSize = 12.sp)
                        }
                        IconButton(onClick = { deleting = p }) { Icon(Icons.Filled.Delete, "Hapus") }
                    }
                }
            }
            item { Box(Modifier.padding(40.dp)) }
        }
        ExtendedFloatingActionButton(
            onClick = { creating = true }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Promo") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }

    if (creating || editing != null) {
        PromoDialog(repo, editing, products.filter { !it.deleted }, outlets) { creating = false; editing = null }
    }
    deleting?.let { p ->
        ConfirmDialog(
            title = "Hapus promo?",
            text = "\"${p.name}\" berhenti tampil di kasir. Transaksi lama tetap mencatat promonya.",
            confirmLabel = "Hapus",
            onConfirm = {
                scope.launch {
                    try { repo.deletePromo(p.id); error = null } catch (e: Exception) { error = friendlyError(e) }
                    deleting = null
                }
            },
            onDismiss = { deleting = null },
        )
    }
}

private val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")

private fun schedule(p: PromoDto, outlets: List<Outlet>): String {
    val parts = mutableListOf<String>()
    val s = parseInstant(p.startsAt)?.atZone(ZoneId.systemDefault())?.toLocalDate()
    val e = parseInstant(p.endsAt)?.atZone(ZoneId.systemDefault())?.toLocalDate()?.minusDays(1)
    if (s != null || e != null) parts += "${s?.format(dateFmt) ?: "…"} s/d ${e?.format(dateFmt) ?: "…"}"
    if (!p.days.isNullOrEmpty() && p.days.size < 7) parts += p.days.sorted().joinToString(",") { DAYS[it - 1] }
    if (p.startMinute != null && p.endMinute != null) parts += "${hm(p.startMinute)}–${hm(p.endMinute)}"
    parts += if (p.outletIds.isNullOrEmpty()) "semua cabang" else p.outletIds.mapNotNull { id -> outlets.firstOrNull { it.id == id }?.name }.joinToString(", ")
    return parts.joinToString(" · ")
}

private fun hm(m: Int) = "%02d:%02d".format(m / 60, m % 60)
private fun parseHm(s: String): Int? {
    val parts = s.trim().split(":", ".")
    if (parts.size != 2) return null
    val h = parts[0].toIntOrNull() ?: return null
    val m = parts[1].toIntOrNull() ?: return null
    return if (h in 0..24 && m in 0..59 && h * 60 + m <= 1440) h * 60 + m else null
}

@Composable
private fun PromoDialog(repo: PosRepository, initial: PromoDto?, products: List<ProductEntity>, outlets: List<Outlet>, onClose: () -> Unit) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var type by remember { mutableStateOf(initial?.type ?: "PERCENT") }
    val productIds = remember { mutableStateListOf<String>().apply { addAll(initial?.ids("product_ids") ?: emptySet()) } }
    val buyIds = remember { mutableStateListOf<String>().apply { addAll(initial?.ids("buy_ids") ?: emptySet()) } }
    val getIds = remember { mutableStateListOf<String>().apply { addAll(initial?.ids("get_ids") ?: emptySet()) } }
    var qty by remember { mutableStateOf(initial?.num("qty")?.takeIf { it > 0 }?.toString() ?: "2") }
    var bundlePrice by remember { mutableStateOf(initial?.num("price")?.takeIf { it > 0 }?.toString() ?: "") }
    var buyQty by remember { mutableStateOf(initial?.num("buy_qty")?.takeIf { it > 0 }?.toString() ?: "1") }
    var getQty by remember { mutableStateOf(initial?.num("get_qty")?.takeIf { it > 0 }?.toString() ?: "1") }
    var pct by remember { mutableStateOf(initial?.num("pct")?.takeIf { it > 0 }?.toString() ?: "") }
    var amount by remember { mutableStateOf(initial?.num("amount")?.takeIf { it > 0 }?.toString() ?: "") }
    var min by remember { mutableStateOf(initial?.num("min")?.takeIf { it > 0 }?.toString() ?: "") }
    val zone = ZoneId.systemDefault()
    var startDate by remember { mutableStateOf(parseInstant(initial?.startsAt)?.atZone(zone)?.toLocalDate()?.format(dateFmt) ?: LocalDate.now().format(dateFmt)) }
    var endDate by remember { mutableStateOf(parseInstant(initial?.endsAt)?.atZone(zone)?.toLocalDate()?.minusDays(1)?.format(dateFmt) ?: "") }
    val days = remember { mutableStateListOf<Int>().apply { addAll(initial?.days ?: emptyList()) } }
    var fromTime by remember { mutableStateOf(initial?.startMinute?.let { hm(it) } ?: "") }
    var toTime by remember { mutableStateOf(initial?.endMinute?.let { hm(it) } ?: "") }
    val outletIds = remember { mutableStateListOf<String>().apply { addAll(initial?.outletIds ?: emptyList()) } }
    var active by remember { mutableStateOf(initial?.isActive ?: true) }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun validate(): String? {
        if (name.isBlank()) return "Isi nama promo."
        when (type) {
            "BUNDLE" -> if (productIds.isEmpty() || (qty.toIntOrNull() ?: 0) < 2 || (bundlePrice.toLongOrNull() ?: 0) <= 0) return "Pilih menu, jumlah (min. 2) dan harga paket."
            "BXGY" -> if (buyIds.isEmpty() || getIds.isEmpty() || (buyQty.toIntOrNull() ?: 0) < 1 || (getQty.toIntOrNull() ?: 0) < 1) return "Pilih menu beli & gratis serta jumlahnya."
            "PERCENT" -> if ((pct.toIntOrNull() ?: 0) !in 1..100) return "Diskon harus 1–100%."
            "AMOUNT" -> if ((amount.toLongOrNull() ?: 0) <= 0) return "Isi potongan per item."
            "MIN_SPEND" -> if ((min.toLongOrNull() ?: 0) <= 0 || (amount.toLongOrNull() ?: 0) <= 0) return "Isi minimum belanja dan potongan."
        }
        if (startDate.isNotBlank() && runCatching { LocalDate.parse(startDate, dateFmt) }.isFailure) return "Format tanggal mulai: 2026-12-31"
        if (endDate.isNotBlank() && runCatching { LocalDate.parse(endDate, dateFmt) }.isFailure) return "Format tanggal selesai: 2026-12-31"
        if ((fromTime.isBlank()) != (toTime.isBlank())) return "Isi jam mulai dan jam selesai, atau kosongkan keduanya."
        if (fromTime.isNotBlank() && (parseHm(fromTime) == null || parseHm(toTime) == null || parseHm(fromTime)!! >= parseHm(toTime)!!)) return "Jam tidak valid (contoh 14:00 s/d 17:00)."
        return null
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text(if (initial == null) "Promo baru" else "Ubah promo") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.take(60) }, label = { Text("Nama promo (tampil di kasir & struk)") }, singleLine = true)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TYPES.forEach { (k, label) -> FilterChip(selected = type == k, onClick = { type = k }, label = { Text(label) }) }
                }
                when (type) {
                    "BUNDLE" -> {
                        Text("Pelanggan pilih sejumlah menu dari daftar, dibayar harga paket.", fontSize = 12.sp)
                        NumField(qty, "Jumlah item per paket") { qty = it }
                        NumField(bundlePrice, "Harga paket (Rp)") { bundlePrice = it }
                        ProductPicker("Menu yang boleh dipilih", products, productIds)
                    }
                    "BXGY" -> {
                        NumField(buyQty, "Beli berapa") { buyQty = it }
                        ProductPicker("Menu yang dibeli", products, buyIds)
                        NumField(getQty, "Gratis berapa") { getQty = it }
                        ProductPicker("Menu gratis (yang termurah digratiskan)", products, getIds)
                    }
                    "PERCENT" -> {
                        NumField(pct, "Diskon (%)") { pct = it }
                        ProductPicker("Berlaku untuk menu (kosong = semua)", products, productIds)
                    }
                    "AMOUNT" -> {
                        NumField(amount, "Potongan per item (Rp)") { amount = it }
                        ProductPicker("Berlaku untuk menu (kosong = semua)", products, productIds)
                    }
                    "MIN_SPEND" -> {
                        NumField(min, "Minimum belanja (Rp)") { min = it }
                        NumField(amount, "Potongan (Rp)") { amount = it }
                    }
                }
                Text("Periode", fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(startDate, { startDate = it.take(10) }, label = { Text("Mulai (yyyy-mm-dd)") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(endDate, { endDate = it.take(10) }, label = { Text("Sampai (kosong = terus)") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                Text("Hari (kosong = setiap hari)", fontWeight = FontWeight.Bold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    DAYS.forEachIndexed { i, d ->
                        val n = i + 1
                        FilterChip(selected = n in days, onClick = { if (n in days) days.remove(n) else days.add(n) }, label = { Text(d) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(fromTime, { fromTime = it.take(5) }, label = { Text("Jam mulai") }, placeholder = { Text("14:00") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(toTime, { toTime = it.take(5) }, label = { Text("Jam selesai") }, placeholder = { Text("17:00") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                if (outlets.size > 1) {
                    Text("Cabang (kosong = semua)", fontWeight = FontWeight.Bold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        outlets.forEach { o ->
                            FilterChip(selected = o.id in outletIds, onClick = { if (o.id in outletIds) outletIds.remove(o.id) else outletIds.add(o.id) }, label = { Text(o.name) })
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Aktif", Modifier.weight(1f))
                    Switch(checked = active, onCheckedChange = { active = it })
                }
                ErrorText(errorMsg)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                val v = validate()
                if (v != null) { errorMsg = v; return@TextButton }
                busy = true
                scope.launch {
                    try {
                        val body = buildJsonObject {
                            put("name", name.trim())
                            put("type", type)
                            put("config", buildJsonObject {
                                fun ids(key: String, list: List<String>) = put(key, buildJsonArray { list.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
                                when (type) {
                                    "BUNDLE" -> { ids("product_ids", productIds); put("qty", qty.toInt()); put("price", bundlePrice.toLong()) }
                                    "BXGY" -> { ids("buy_ids", buyIds); ids("get_ids", getIds); put("buy_qty", buyQty.toInt()); put("get_qty", getQty.toInt()) }
                                    "PERCENT" -> { ids("product_ids", productIds); put("pct", pct.toInt()) }
                                    "AMOUNT" -> { ids("product_ids", productIds); put("amount", amount.toLong()) }
                                    "MIN_SPEND" -> { put("min", min.toLong()); put("amount", amount.toLong()) }
                                }
                            })
                            if (outletIds.isEmpty()) put("outlet_ids", JsonNull)
                            else put("outlet_ids", buildJsonArray { outletIds.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
                            put("starts_at", startDate.takeIf { it.isNotBlank() }?.let { LocalDate.parse(it, dateFmt).atStartOfDay(zone).toInstant().toString() })
                            put("ends_at", endDate.takeIf { it.isNotBlank() }?.let { LocalDate.parse(it, dateFmt).plusDays(1).atStartOfDay(zone).toInstant().toString() })
                            if (days.isEmpty() || days.size == 7) put("days", JsonNull)
                            else put("days", buildJsonArray { days.sorted().forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
                            put("start_minute", parseHm(fromTime))
                            put("end_minute", parseHm(toTime))
                            put("is_active", active)
                        }
                        repo.savePromo(initial?.id, body)
                        onClose()
                    } catch (e: Exception) { errorMsg = friendlyError(e); busy = false }
                }
            }) { Text("Simpan") }
        },
        dismissButton = { TextButton(onClick = onClose, enabled = !busy) { Text("Batal") } },
    )
}

@Composable
private fun NumField(value: String, label: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, { onChange(it.filter(Char::isDigit).take(9)) }, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ProductPicker(title: String, products: List<ProductEntity>, selected: MutableList<String>) {
    Text(title, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        products.forEach { p ->
            FilterChip(
                selected = p.id in selected,
                onClick = { if (p.id in selected) selected.remove(p.id) else selected.add(p.id) },
                label = { Text(p.name, fontSize = 12.sp) },
            )
        }
    }
}
