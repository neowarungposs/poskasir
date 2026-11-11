@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.neowarungposs.poskasir.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.neowarungposs.poskasir.core.formatDate
import com.neowarungposs.poskasir.core.formatDateTime
import com.neowarungposs.poskasir.core.friendlyError
import com.neowarungposs.poskasir.core.rupiah
import com.neowarungposs.poskasir.ui.Amber
import com.neowarungposs.poskasir.ui.AmberSoft
import com.neowarungposs.poskasir.ui.Banner
import com.neowarungposs.poskasir.ui.ErrorText
import com.neowarungposs.poskasir.ui.GreenDark
import com.neowarungposs.poskasir.ui.GreenSoft
import com.neowarungposs.poskasir.ui.RedSoft
import com.neowarungposs.poskasir.ui.RedText
import kotlinx.coroutines.launch

private val statuses = listOf("PENDING" to "Pending", "APPROVED" to "Disetujui", "REJECTED" to "Ditolak")

@Composable
fun PaymentsScreen(repo: AdminRepo) {
    var tab by remember { mutableIntStateOf(0) }
    var list by remember { mutableStateOf<List<Claim>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var open by remember { mutableStateOf<Claim?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(tab, reload) {
        error = null
        try { list = repo.claims(statuses[tab].first) } catch (e: Exception) { error = friendlyError(e) }
    }

    Column {
        TabRow(selectedTabIndex = tab) {
            statuses.forEachIndexed { i, (_, label) -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label) }) }
        }
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { ErrorText(error) }
            if (list.isEmpty()) item { Text("Tidak ada data.", modifier = Modifier.padding(8.dp)) }
            items(list, key = { it.id }) { c ->
                Card(onClick = { open = c }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("${c.tenants?.businessName ?: "-"} (${c.tenants?.customerCode ?: "-"})", fontWeight = FontWeight.Bold)
                        Text("Tagihan: ${c.expectedAmount?.let { rupiah(it) } ?: "tanpa paket"} · ${formatDateTime(c.createdAt)}", fontSize = 13.sp)
                        c.senderName?.let { Text("Pengirim: $it", fontSize = 13.sp) }
                        c.rejectionReason?.let { Text("Alasan: $it", fontSize = 13.sp, color = RedText) }
                    }
                }
            }
        }
    }
    open?.let { ClaimDetail(repo, it, onClose = { open = null }, onDone = { open = null; reload++ }) }
}

@Composable
private fun ClaimDetail(repo: AdminRepo, claim: Claim, onClose: () -> Unit, onDone: () -> Unit) {
    var proofUrl by remember { mutableStateOf<String?>(null) }
    var unmatched by remember { mutableStateOf<List<Incoming>>(emptyList()) }
    var accessUntil by remember { mutableStateOf<String?>(null) }
    var pendingCount by remember { mutableIntStateOf(0) }
    var mode by remember { mutableStateOf("view") }
    var reason by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val payCode = claim.tenants?.payCode

    LaunchedEffect(claim.id) {
        runCatching {
            proofUrl = claim.proofPath?.let { repo.signedProof(it) }
            unmatched = repo.incoming("UNMATCHED")
            accessUntil = repo.tenant(claim.tenantId)?.accessUntil()?.let { formatDate(it) }
            pendingCount = repo.claimsForTenant(claim.tenantId).count { it.status == "PENDING" }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text(claim.tenants?.businessName ?: "Klaim pembayaran") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Kode: ${claim.tenants?.customerCode} · kode bayar ${payCode?.toString()?.padStart(3, '0')}")
                Text("WA: ${claim.tenants?.phoneWa ?: "-"}")
                Text("Aktif s/d saat ini: ${accessUntil ?: "-"}")
                Text("Tagihan: ${claim.expectedAmount?.let { rupiah(it) } ?: "-"}", fontWeight = FontWeight.Bold)
                Text("Pengirim: ${claim.senderName ?: "-"} · transfer ${formatDateTime(claim.transferAt)}")
                Text("Diunggah: ${formatDateTime(claim.createdAt)}")
                if (pendingCount > 1) Banner("Tenant ini punya $pendingCount klaim pending.", AmberSoft, Amber)
                if (claim.expectedAmount != null && unmatched.none { it.amount == claim.expectedAmount }) {
                    Banner("Belum ada uang masuk dengan nominal ${rupiah(claim.expectedAmount)}. Catat dulu di tab Uang Masuk.", RedSoft, RedText)
                }
                proofUrl?.let {
                    AsyncImage(it, "Bukti transfer", contentScale = ContentScale.Fit, modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp))
                } ?: Text("Tanpa foto bukti.")

                if (claim.status == "PENDING" && mode == "approve") {
                    Text("Pilih uang masuk yang cocok:", fontWeight = FontWeight.Bold)
                    val sorted = unmatched.sortedByDescending { it.amount == claim.expectedAmount }
                    if (sorted.isEmpty()) Text("Tidak ada uang masuk yang belum dicocokkan.")
                    sorted.forEach { inc ->
                        val code = (inc.amount % 1000).toInt()
                        val match = inc.amount == claim.expectedAmount
                        Card(
                            onClick = {
                                busy = true; error = null
                                scope.launch {
                                    try {
                                        repo.approve(inc.id, claim.tenantId, claim.planId, null, claim.id, null)
                                        onDone()
                                    } catch (e: Exception) {
                                        error = friendlyError(e); busy = false
                                    }
                                }
                            },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(rupiah(inc.amount), fontWeight = FontWeight.Bold)
                                    Text("${inc.channel} · ${inc.senderName ?: "-"} · ${formatDateTime(inc.receivedAt)}", fontSize = 12.sp)
                                    if (payCode != null && code != payCode) Text("Kode $code ≠ kode tenant $payCode", color = RedText, fontSize = 12.sp)
                                }
                                if (match) Text("COCOK", color = GreenDark, fontWeight = FontWeight.ExtraBold)
                            }
                        }
                    }
                }
                if (claim.status == "PENDING" && mode == "reject") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("Nominal tidak sesuai", "Dana belum masuk", "Bukti tidak jelas", "Duplikat").forEach {
                            FilterChip(selected = reason == it, onClick = { reason = it }, label = { Text(it) })
                        }
                    }
                    OutlinedTextField(reason, { reason = it }, label = { Text("Alasan") })
                }
                ErrorText(error)
            }
        },
        confirmButton = {
            if (claim.status == "PENDING") {
                when (mode) {
                    "view" -> Row {
                        TextButton(onClick = { mode = "reject" }) { Text("Tolak", color = RedText) }
                        TextButton(onClick = { mode = "approve" }) { Text("Setujui") }
                    }
                    "reject" -> TextButton(enabled = reason.isNotBlank() && !busy, onClick = {
                        busy = true
                        scope.launch {
                            try { repo.reject(claim.id, reason); onDone() } catch (e: Exception) { error = friendlyError(e); busy = false }
                        }
                    }) { Text("Kirim penolakan", color = RedText) }
                    else -> {}
                }
            }
        },
        dismissButton = { TextButton(onClick = onClose, enabled = !busy) { Text("Tutup") } },
    )
}
