@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.neowarungposs.poskasir.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neowarungposs.poskasir.core.Plan
import com.neowarungposs.poskasir.core.Tenant
import com.neowarungposs.poskasir.core.formatDate
import com.neowarungposs.poskasir.core.formatDateTime
import com.neowarungposs.poskasir.core.friendlyError
import com.neowarungposs.poskasir.core.rupiah
import com.neowarungposs.poskasir.ui.Banner
import com.neowarungposs.poskasir.ui.ErrorText
import com.neowarungposs.poskasir.ui.GreenDark
import com.neowarungposs.poskasir.ui.GreenSoft
import com.neowarungposs.poskasir.ui.RedSoft
import com.neowarungposs.poskasir.ui.RedText
import kotlinx.coroutines.launch
import java.time.Instant

/** Money ledger (D7): record what really arrived, decode the amount, then approve. */
@Composable
fun IncomingScreen(repo: AdminRepo) {
    var list by remember { mutableStateOf<List<Incoming>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var recording by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf<Incoming?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(reload) {
        error = null
        try { list = repo.incoming("UNMATCHED") } catch (e: Exception) { error = friendlyError(e) }
    }

    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { recording = true }) { Text("Catat uang masuk") }
                OutlinedButton(onClick = { searching = true }) { Text("Cari nominal") }
            }
            Text(
                "Catat setiap uang yang benar-benar terlihat di mutasi bank / aplikasi merchant QRIS. Approve hanya bisa dari catatan ini.",
                fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp),
            )
            ErrorText(error)
            Text("Belum dicocokkan (${list.size})", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
        }
        items(list, key = { it.id }) { inc ->
            Card(onClick = { open = inc }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text(rupiah(inc.amount), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("${inc.channel} · ${inc.senderName ?: "-"} · ${formatDateTime(inc.receivedAt)}", fontSize = 13.sp)
                    inc.note?.let { Text(it, fontSize = 12.sp) }
                }
            }
        }
    }

    if (recording) RecordDialog(repo, onDismiss = { recording = false }) { recording = false; reload++ }
    if (searching) SearchDialog(repo) { searching = false }
    open?.let { ApproveDialog(repo, it, onClose = { open = null }, onDone = { open = null; reload++ }) }
}

@Composable
private fun RecordDialog(repo: AdminRepo, onDismiss: () -> Unit, onSaved: () -> Unit) {
    var channel by remember { mutableStateOf("BANK") }
    var amount by remember { mutableStateOf("") }
    var sender by remember { mutableStateOf("") }
    var ref by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var hoursAgo by remember { mutableStateOf("0") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Catat uang masuk") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("BANK" to "Transfer bank", "QRIS" to "QRIS").forEach { (k, l) ->
                        FilterChip(selected = channel == k, onClick = { channel = k }, label = { Text(l) })
                    }
                }
                OutlinedTextField(amount, { amount = it.filter(Char::isDigit) }, label = { Text("Nominal persis (Rp)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                OutlinedTextField(sender, { sender = it }, label = { Text("Nama pengirim") }, singleLine = true)
                OutlinedTextField(ref, { ref = it }, label = { Text("No. referensi (opsional)") }, singleLine = true)
                OutlinedTextField(hoursAgo, { hoursAgo = it.filter(Char::isDigit) }, label = { Text("Masuk berapa jam lalu") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                OutlinedTextField(note, { note = it }, label = { Text("Catatan (opsional)") })
                ErrorText(error)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && (amount.toLongOrNull() ?: 0) > 0, onClick = {
                busy = true; error = null
                scope.launch {
                    try {
                        val at = Instant.now().minusSeconds((hoursAgo.toLongOrNull() ?: 0) * 3600)
                        repo.recordIncoming(channel, amount.toLong(), at, sender.ifBlank { null }, ref.ifBlank { null }, note.ifBlank { null })
                        onSaved()
                    } catch (e: Exception) { error = friendlyError(e); busy = false }
                }
            }) { Text("Simpan") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Batal") } },
    )
}

@Composable
private fun SearchDialog(repo: AdminRepo, onDismiss: () -> Unit) {
    var amount by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<DecodeRow?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cari nominal") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(amount, { amount = it.filter(Char::isDigit) }, label = { Text("Nominal dari mutasi") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                TextButton(enabled = amount.isNotEmpty(), onClick = {
                    scope.launch {
                        try { result = repo.decode(amount.toLong()); error = null } catch (e: Exception) { error = friendlyError(e) }
                    }
                }) { Text("Urai") }
                result?.let { DecodeSummary(it) }
                ErrorText(error)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Tutup") } },
    )
}

@Composable
fun DecodeSummary(d: DecodeRow) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Kode unik: ${d.payCode.toString().padStart(3, '0')}  ·  Harga dasar: ${rupiah(d.baseAmount)}")
        if (d.tenantId != null) Banner("Tenant: ${d.businessName} (${d.customerCode})${if (d.lifecycle == "CHURNED") " · ARSIP" else ""}", GreenSoft, GreenDark)
        else Banner("Tidak ada tenant dengan kode ${d.payCode}.", RedSoft, RedText)
        Text(
            when (d.matchKind) {
                "PLAN_PRICE" -> "Paket: ${d.planMonths} bulan${if (d.planIsActive == false) " (harga lama)" else ""}"
                "MULTIPLE_OF_MONTHLY" -> "Kelipatan harga bulanan, pilih jumlah bulan manual."
                else -> "Tidak cocok dengan paket mana pun."
            },
            fontWeight = FontWeight.SemiBold,
        )
        Text("Hasil urai hanya saran. Cocokkan dengan klaim tenant atau konfirmasi WA sebelum approve.", fontSize = 12.sp)
    }
}

@Composable
private fun ApproveDialog(repo: AdminRepo, inc: Incoming, onClose: () -> Unit, onDone: () -> Unit) {
    var decode by remember { mutableStateOf<DecodeRow?>(null) }
    var plans by remember { mutableStateOf<List<Plan>>(emptyList()) }
    var tenant by remember { mutableStateOf<Tenant?>(null) }
    var codeInput by remember { mutableStateOf("") }
    var planId by remember { mutableStateOf<String?>(null) }
    var months by remember { mutableStateOf("") }
    var claims by remember { mutableStateOf<List<Claim>>(emptyList()) }
    var claimId by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun selectTenant(t: Tenant?) {
        tenant = t
        claims = t?.let { repo.claimsForTenant(it.id).filter { c -> c.status == "PENDING" } } ?: emptyList()
        claimId = claims.firstOrNull()?.id
    }

    LaunchedEffect(inc.id) {
        try {
            plans = repo.plans()
            val d = repo.decode(inc.amount)
            decode = d
            planId = d?.planId
            if (d != null && d.matchKind == "MULTIPLE_OF_MONTHLY") {
                val monthly = plans.firstOrNull { it.months == 1 }?.price ?: 29000L
                months = (d.baseAmount / monthly).toString()
            }
            selectTenant(d?.tenantId?.let { repo.tenant(it) })
        } catch (e: Exception) { error = friendlyError(e) }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text("Cocokkan ${rupiah(inc.amount)}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${inc.channel} · ${inc.senderName ?: "-"} · ${formatDateTime(inc.receivedAt)}", fontSize = 13.sp)
                decode?.let { DecodeSummary(it) }

                Text("Tenant", fontWeight = FontWeight.Bold)
                val t = tenant
                if (t != null) {
                    Text("${t.businessName} (${t.customerCode}) · aktif s/d ${t.accessUntil()?.let { formatDate(it) } ?: "-"}")
                    if (decode?.tenantId != null && decode?.tenantId != t.id) Banner("Kode di nominal bukan milik tenant ini!", RedSoft, RedText)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(codeInput, { codeInput = it.uppercase() }, label = { Text("Kode pelanggan (WRG-XXXX)") }, singleLine = true, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        scope.launch {
                            runCatching {
                                val found = repo.tenantByCode(codeInput.trim())
                                selectTenant(found)
                                if (found == null) error = "Kode pelanggan tidak ditemukan."
                            }
                        }
                    }) { Text("Pakai") }
                }

                Text("Paket", fontWeight = FontWeight.Bold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    plans.forEach { p ->
                        FilterChip(selected = planId == p.id, onClick = { planId = p.id; months = "" }, label = { Text("${p.months} bln ${rupiah(p.price)}") })
                    }
                }
                OutlinedTextField(months, { months = it.filter(Char::isDigit); if (months.isNotEmpty()) planId = null },
                    label = { Text("atau jumlah bulan manual") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))

                if (claims.isNotEmpty()) {
                    Text("Tautkan ke klaim tenant", fontWeight = FontWeight.Bold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = claimId == null, onClick = { claimId = null }, label = { Text("Tanpa klaim") })
                        claims.forEach { c ->
                            FilterChip(selected = claimId == c.id, onClick = { claimId = c.id },
                                label = { Text("${c.expectedAmount?.let { rupiah(it) } ?: "tanpa paket"} · ${formatDate(c.createdAt)}") })
                        }
                    }
                }
                ErrorText(error)
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && tenant != null && (planId != null || (months.toIntOrNull() ?: 0) in 1..24),
                onClick = {
                    busy = true; error = null
                    scope.launch {
                        try {
                            repo.approve(inc.id, tenant!!.id, planId, if (planId == null) months.toInt() else null, claimId, null)
                            onDone()
                        } catch (e: Exception) { error = friendlyError(e); busy = false }
                    }
                },
            ) { Text("Approve & perpanjang") }
        },
        dismissButton = { TextButton(onClick = onClose, enabled = !busy) { Text("Tutup") } },
    )
}

