@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.neowarungposs.poskasir.admin

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neowarungposs.poskasir.AppContainer
import com.neowarungposs.poskasir.core.FcmRegistrar
import com.neowarungposs.poskasir.core.Plan
import com.neowarungposs.poskasir.core.formatDate
import com.neowarungposs.poskasir.core.friendlyError
import com.neowarungposs.poskasir.core.rupiah
import com.neowarungposs.poskasir.ui.Banner
import com.neowarungposs.poskasir.ui.ErrorText
import com.neowarungposs.poskasir.ui.GreenDark
import com.neowarungposs.poskasir.ui.GreenSoft
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.math.floor

@Composable
fun MoreScreen(container: AppContainer, repo: AdminRepo) {
    var page by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    BackHandler(enabled = page != null) { page = null }

    when (page) {
        "queue" -> QueueScreen(repo)
        "plans" -> PlansScreen(repo)
        "settings" -> SettingsScreen(repo)
        else -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf("queue" to "Antrean WA", "plans" to "Paket & Diskon", "settings" to "Pengaturan").forEach { (k, label) ->
                Card(onClick = { page = k }, modifier = Modifier.fillMaxWidth()) {
                    Text(label, modifier = Modifier.padding(18.dp), fontWeight = FontWeight.SemiBold)
                }
            }
            OutlinedButton(onClick = {
                scope.launch {
                    runCatching { FcmRegistrar.unregister(container.supabase) }
                    container.supabase.signOut()
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("Keluar") }
        }
    }
}

// ------------------------------------------------------------------ WA queue (D10)
@Composable
private fun QueueScreen(repo: AdminRepo) {
    var list by remember { mutableStateOf<List<QueueItem>>(emptyList()) }
    var template by remember { mutableStateOf("") }
    var payUrl by remember { mutableStateOf("") }
    var promo by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(reload) {
        try {
            list = repo.queue()
            template = repo.reminderSettings().waTemplate
            payUrl = repo.settings().paymentPageUrl ?: ""
            val best = repo.plans().maxByOrNull { it.months }
            promo = best?.let {
                val save = it.normalPrice - it.price
                if (save > 0) "Perpanjang ${it.months} bulan hemat ${rupiah(save)}." else ""
            } ?: ""
        } catch (e: Exception) { error = friendlyError(e) }
    }

    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("Pengingat perpanjangan hari ini (${list.size})", fontWeight = FontWeight.Bold)
            ErrorText(error)
        }
        items(list, key = { it.id }) { q ->
            val t = q.tenants
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${t?.businessName} (${t?.customerCode})", fontWeight = FontWeight.Bold)
                    Text("${q.kind} · berakhir ${formatDate(q.periodEnd)} · ${q.amount?.let { rupiah(it) } ?: "-"}", fontSize = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = t?.phoneWa != null, onClick = {
                            val text = template
                                .replace("{nama_usaha}", t?.businessName ?: "")
                                .replace("{kode}", t?.customerCode ?: "")
                                .replace("{tanggal}", formatDate(q.periodEnd))
                                .replace("{nominal}", q.amount?.let { rupiah(it).removePrefix("Rp") } ?: "")
                                .replace("{promo_terbaik}", promo)
                                .replace("{link}", payUrl)
                            openWhatsApp(context, t?.phoneWa ?: "", text)
                            scope.launch { runCatching { repo.setQueueStatus(q.id, "SENT") }; reload++ }
                        }) { Text("Kirim WA") }
                        OutlinedButton(onClick = { scope.launch { runCatching { repo.setQueueStatus(q.id, "SKIPPED") }; reload++ } }) { Text("Lewati") }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Plans & discounts (D12)
@Composable
private fun PlansScreen(repo: AdminRepo) {
    var plans by remember { mutableStateOf<List<Plan>>(emptyList()) }
    var monthly by remember { mutableStateOf(29000L) }
    var editing by remember { mutableStateOf<Int?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(reload) {
        try { plans = repo.plans(); monthly = repo.settings().monthlyBasePrice } catch (e: Exception) { error = friendlyError(e) }
    }

    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("Harga dasar: ${rupiah(monthly)}/bulan (ubah di Pengaturan)", fontSize = 13.sp)
            ErrorText(error)
        }
        items(listOf(1, 3, 6, 12)) { m ->
            val p = plans.firstOrNull { it.months == m }
            Card(onClick = { editing = m }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("$m bulan ${p?.badge?.let { "· $it" } ?: ""}", fontWeight = FontWeight.Bold)
                    if (p == null) Text("Nonaktif", fontSize = 13.sp)
                    else Text(
                        "${rupiah(p.normalPrice)} → ${rupiah(p.price)} · diskon ${p.discountPct}%" +
                            (p.promoEndsAt?.let { " · promo s/d ${formatDate(it)}" } ?: ""),
                        fontSize = 13.sp,
                    )
                }
            }
        }
    }
    editing?.let { m ->
        PlanDialog(repo, m, plans.firstOrNull { it.months == m }, monthly, onDismiss = { editing = null }) { editing = null; reload++ }
    }
}

@Composable
private fun PlanDialog(repo: AdminRepo, months: Int, current: Plan?, monthly: Long, onDismiss: () -> Unit, onSaved: () -> Unit) {
    var discount by remember { mutableStateOf(current?.discountPct?.let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() } ?: "0") }
    var badge by remember { mutableStateOf(current?.badge) }
    var promoDays by remember { mutableStateOf("") }
    var active by remember { mutableStateOf(current != null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val d = discount.toDoubleOrNull() ?: 0.0
    val normal = monthly * months
    val price = (floor(normal * (1 - d / 100.0) / 1000) * 1000).toLong()

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Paket $months bulan") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Aktif", modifier = Modifier.weight(1f))
                    Switch(checked = active, onCheckedChange = { active = it })
                }
                OutlinedTextField(discount, { discount = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("Diskon %") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(null to "Tanpa badge", "PALING HEMAT" to "PALING HEMAT", "POPULER" to "POPULER").forEach { (k, l) ->
                        FilterChip(selected = badge == k, onClick = { badge = k }, label = { Text(l) })
                    }
                }
                OutlinedTextField(promoDays, { promoDays = it.filter(Char::isDigit) }, label = { Text("Promo berakhir dalam (hari, kosong = permanen)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                Banner(
                    "${rupiah(normal)} → ${rupiah(price)} · hemat ${rupiah(normal - price)} · ≈${rupiah(price / months)}/bln",
                    GreenSoft, GreenDark,
                )
                ErrorText(error)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && d in 0.0..90.0, onClick = {
                busy = true; error = null
                scope.launch {
                    try {
                        val promo = promoDays.toLongOrNull()?.let { Instant.now().plus(it, ChronoUnit.DAYS) }
                        repo.upsertPlan(months, d, badge, promo, active)
                        onSaved()
                    } catch (e: Exception) { error = friendlyError(e); busy = false }
                }
            }) { Text("Simpan") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Batal") } },
    )
}

// ------------------------------------------------------------------ Settings
@Composable
private fun SettingsScreen(repo: AdminRepo) {
    var monthly by remember { mutableStateOf("") }
    var bank by remember { mutableStateOf("") }
    var payUrl by remember { mutableStateOf("") }
    var grace by remember { mutableStateOf("") }
    var offline by remember { mutableStateOf("") }
    var rs by remember { mutableStateOf<ReminderSettings?>(null) }
    var days by remember { mutableStateOf("") }
    var hour by remember { mutableStateOf("") }
    var churn by remember { mutableStateOf("") }
    var template by remember { mutableStateOf("") }
    var enabled by remember { mutableStateOf(true) }
    var grace2 by remember { mutableStateOf(true) }
    var info by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var qrisUrl by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true; error = null
            try {
                val type = context.contentResolver.getType(uri) ?: "image/png"
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalArgumentException("Gagal membaca gambar")
                if (bytes.size > 2 * 1024 * 1024) throw IllegalArgumentException("Gambar maks 2 MB")
                val ext = if (type.contains("png")) "png" else if (type.contains("webp")) "webp" else "jpg"
                val path = repo.uploadGlobalQris(bytes, type, ext)
                qrisUrl = repo.globalQrisUrl(path)
                info = "QRIS langganan tersimpan."
            } catch (e: Exception) { error = if (e is IllegalArgumentException) e.message else friendlyError(e) }
            busy = false
        }
    }

    LaunchedEffect(Unit) {
        try {
            val s = repo.settings()
            qrisUrl = s.globalQrisPath?.let { repo.globalQrisUrl(it) }
            monthly = s.monthlyBasePrice.toString(); bank = s.bankDetailsText ?: ""; payUrl = s.paymentPageUrl ?: ""
            grace = s.graceDays.toString(); offline = s.maxOfflineDays.toString()
            val r = repo.reminderSettings()
            rs = r; days = r.daysBefore.joinToString(","); hour = r.runHour.toString(); churn = r.autoChurnAfterDays.toString()
            template = r.waTemplate; enabled = r.enabled; grace2 = r.remindOnGrace
        } catch (e: Exception) { error = friendlyError(e) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("QRIS pembayaran langganan", fontWeight = FontWeight.Bold)
        Text("Tampil di halaman bayar web. Diunggah apa adanya (tanpa kompresi) supaya tetap bisa di-scan.", fontSize = 12.sp)
        qrisUrl?.let {
            AsyncImage(it, "QRIS langganan", contentScale = ContentScale.Fit, modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 260.dp))
        }
        OutlinedButton(enabled = !busy, onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
            Text(if (qrisUrl == null) "Unggah QRIS" else "Ganti QRIS")
        }

        Text("Sistem", fontWeight = FontWeight.Bold)
        OutlinedTextField(monthly, { monthly = it.filter(Char::isDigit) }, label = { Text("Harga dasar per bulan (kelipatan 1000)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(bank, { bank = it }, label = { Text("Info rekening bank") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(payUrl, { payUrl = it }, label = { Text("URL halaman bayar") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(grace, { grace = it.filter(Char::isDigit) }, label = { Text("Masa tenggang (hari)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(offline, { offline = it.filter(Char::isDigit) }, label = { Text("Maks. offline (hari)") }, singleLine = true, modifier = Modifier.fillMaxWidth())

        Text("Pengingat WA", fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Aktif", Modifier.weight(1f)); Switch(enabled, { enabled = it }) }
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Ingatkan saat masa tenggang", Modifier.weight(1f)); Switch(grace2, { grace2 = it }) }
        OutlinedTextField(days, { days = it.filter { c -> c.isDigit() || c == ',' } }, label = { Text("H-berapa (contoh 7,3,1)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(hour, { hour = it.filter(Char::isDigit) }, label = { Text("Jam kirim antrean (WIB, 0-23)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(churn, { churn = it.filter(Char::isDigit) }, label = { Text("Arsip otomatis setelah expired (hari)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(template, { template = it }, label = { Text("Template WA") }, modifier = Modifier.fillMaxWidth())
        Text("Placeholder: {nama_usaha} {kode} {tanggal} {nominal} {promo_terbaik} {link}", fontSize = 12.sp)

        info?.let { Banner(it, GreenSoft, GreenDark) }
        ErrorText(error)
        Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
            busy = true; error = null; info = null
            scope.launch {
                try {
                    val m = monthly.toLongOrNull() ?: throw IllegalArgumentException("Harga dasar tidak valid")
                    if (m % 1000 != 0L) throw IllegalArgumentException("Harga dasar harus kelipatan 1000")
                    repo.saveSettings(
                        mapOf(
                            "monthly_base_price" to JsonPrimitive(m),
                            "bank_details_text" to JsonPrimitive(bank.ifBlank { null }),
                            "payment_page_url" to JsonPrimitive(payUrl.ifBlank { null }),
                            "grace_days" to JsonPrimitive(grace.toIntOrNull() ?: 3),
                            "max_offline_days" to JsonPrimitive(offline.toIntOrNull() ?: 7),
                        )
                    )
                    val base = rs ?: ReminderSettings()
                    repo.saveReminderSettings(
                        base.copy(
                            enabled = enabled,
                            remindOnGrace = grace2,
                            daysBefore = days.split(",").mapNotNull { it.trim().toIntOrNull() },
                            runHour = (hour.toIntOrNull() ?: 9).coerceIn(0, 23),
                            autoChurnAfterDays = (churn.toIntOrNull() ?: 30).coerceIn(1, 365),
                            waTemplate = template,
                        )
                    )
                    info = "Tersimpan. Jika harga dasar berubah, simpan ulang tiap paket di Paket & Diskon."
                } catch (e: Exception) { error = if (e is IllegalArgumentException) e.message else friendlyError(e) }
                busy = false
            }
        }) { Text("Simpan") }
    }
}
