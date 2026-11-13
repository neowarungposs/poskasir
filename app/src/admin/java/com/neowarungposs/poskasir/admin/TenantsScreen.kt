@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.neowarungposs.poskasir.admin

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.KeyboardType
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neowarungposs.poskasir.core.Tenant
import com.neowarungposs.poskasir.core.formatDate
import com.neowarungposs.poskasir.core.formatDateTime
import com.neowarungposs.poskasir.core.friendlyError
import com.neowarungposs.poskasir.core.parseInstant
import com.neowarungposs.poskasir.ui.ErrorText
import com.neowarungposs.poskasir.ui.RedText
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.temporal.ChronoUnit

@Composable
fun TenantsScreen(repo: AdminRepo) {
    var tab by remember { mutableIntStateOf(0) }
    var list by remember { mutableStateOf<List<Tenant>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var open by remember { mutableStateOf<Tenant?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(tab, reload) {
        error = null
        try { list = repo.tenants(if (tab == 0) "LIVE" else "CHURNED") } catch (e: Exception) { error = friendlyError(e) }
    }
    val shown = list.filter { query.isBlank() || it.businessName.contains(query, true) || it.customerCode.contains(query, true) }

    Column {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Aktif") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Arsip") })
        }
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                OutlinedTextField(query, { query = it }, label = { Text("Cari nama / kode") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                ErrorText(error)
            }
            items(shown, key = { it.id }) { t ->
                Card(onClick = { open = t }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text("${t.businessName} (${t.customerCode})", fontWeight = FontWeight.Bold)
                        Text(tenantStatusLine(t), fontSize = 13.sp)
                    }
                }
            }
        }
    }
    open?.let { TenantDetail(repo, it, onClose = { open = null }, onChanged = { open = null; reload++ }) }
}

fun tenantStatusLine(t: Tenant): String {
    val until = t.accessUntil() ?: return "-"
    val days = ChronoUnit.DAYS.between(Instant.now(), until)
    val kind = if (t.paidUntil == null && parseInstant(t.trialEndsAt)?.isAfter(Instant.now()) == true) "Trial" else "Langganan"
    return if (days >= 0) "$kind · aktif s/d ${formatDate(until)} ($days hari)" else "Berakhir ${formatDate(until)} (${-days} hari lalu)"
}

@Composable
private fun TenantDetail(repo: AdminRepo, t: Tenant, onClose: () -> Unit, onChanged: () -> Unit) {
    var churning by remember { mutableStateOf(false) }
    var reason by remember { mutableStateOf("TIDAK_LANJUT") }
    var history by remember { mutableStateOf<List<Claim>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var contacts by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var editContact by remember { mutableStateOf(false) }
    var phone by remember { mutableStateOf(t.phoneWa ?: "") }
    var optIn by remember { mutableStateOf(t.waOptIn) }
    LaunchedEffect(t.id) {
        runCatching { history = repo.claimsForTenant(t.id) }
        runCatching { contacts = repo.contactLog(t.id) }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text(t.businessName) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Kode pelanggan: ${t.customerCode}")
                Text("Kode bayar: ${t.payCode.toString().padStart(3, '0')}")
                Text(tenantStatusLine(t))
                Text("Email: ${t.email ?: "-"}")
                if (!editContact) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("WA: ${t.phoneWa ?: "-"} ${if (t.waOptIn) "(setuju diingatkan)" else "(pengingat OFF)"}", Modifier.weight(1f))
                        TextButton(enabled = !busy, onClick = { editContact = true }) { Text("Ubah") }
                    }
                } else {
                    OutlinedTextField(
                        phone, { phone = it.filter { c -> c.isDigit() }.take(15) },
                        label = { Text("Nomor WA (08xx / 62xx)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Kirim pengingat WA", Modifier.weight(1f))
                        Switch(checked = optIn, onCheckedChange = { optIn = it })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(enabled = !busy, onClick = { editContact = false }) { Text("Batal") }
                        TextButton(enabled = !busy && (phone.isEmpty() || phone.length >= 9), onClick = {
                            busy = true; error = null
                            scope.launch {
                                try { repo.updateContact(t.id, phone, optIn); onChanged() }
                                catch (e: Exception) { error = friendlyError(e); busy = false }
                            }
                        }) { Text("Simpan kontak") }
                    }
                }
                Text("Daftar: ${formatDate(t.createdAt)}")
                if (t.lifecycle == "CHURNED") Text("Diarsip ${formatDate(t.churnedAt)} · ${t.churnReason ?: ""}", color = RedText)
                if (contacts.isNotEmpty()) {
                    Text("Riwayat perubahan kontak", fontWeight = FontWeight.Bold)
                    contacts.take(5).forEach { (at, change) -> Text("${formatDateTime(at)} · $change", fontSize = 12.sp) }
                }
                Text("Riwayat klaim", fontWeight = FontWeight.Bold)
                if (history.isEmpty()) Text("-")
                history.take(10).forEach { Text("${formatDateTime(it.createdAt)} · ${it.status} · ${it.expectedAmount ?: "-"}", fontSize = 12.sp) }
                if (churning) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("TIDAK_LANJUT", "TIDAK_RESPON", "TUTUP_USAHA").forEach {
                            FilterChip(selected = reason == it, onClick = { reason = it }, label = { Text(it) })
                        }
                    }
                }
                ErrorText(error)
            }
        },
        confirmButton = {
            if (t.lifecycle == "LIVE") {
                TextButton(enabled = !busy, onClick = {
                    if (!churning) churning = true else {
                        busy = true
                        scope.launch {
                            try { repo.markChurned(t.id, reason); onChanged() } catch (e: Exception) { error = friendlyError(e); busy = false }
                        }
                    }
                }) { Text(if (churning) "Arsipkan" else "Tandai Tidak Lanjut", color = RedText) }
            }
        },
        dismissButton = {
            Column {
                t.phoneWa?.let { phone -> TextButton(onClick = { openWhatsApp(context, phone, "Halo ${t.businessName}, ") }) { Text("Kirim WA") } }
                TextButton(onClick = onClose, enabled = !busy) { Text("Tutup") }
            }
        },
    )
}

fun openWhatsApp(context: Context, phone: String, text: String) {
    val digits = phone.filter(Char::isDigit).let { if (it.startsWith("0")) "62" + it.drop(1) else it }
    val uri = Uri.parse("https://wa.me/$digits?text=" + Uri.encode(text))
    context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
