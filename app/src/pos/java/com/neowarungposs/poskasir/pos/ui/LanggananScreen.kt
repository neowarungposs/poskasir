package com.neowarungposs.poskasir.pos.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.input.KeyboardType
import com.neowarungposs.poskasir.ui.Banner
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neowarungposs.poskasir.AppContainer
import com.neowarungposs.poskasir.core.formatDate
import com.neowarungposs.poskasir.core.formatDateTime
import com.neowarungposs.poskasir.core.friendlyError
import com.neowarungposs.poskasir.core.statusLabel
import com.neowarungposs.poskasir.pos.GateState
import com.neowarungposs.poskasir.pos.PosRepository
import com.neowarungposs.poskasir.ui.Amber
import com.neowarungposs.poskasir.ui.AmberSoft
import com.neowarungposs.poskasir.ui.ErrorText
import com.neowarungposs.poskasir.ui.GreenDark
import com.neowarungposs.poskasir.ui.GreenSoft
import com.neowarungposs.poskasir.ui.Pill
import com.neowarungposs.poskasir.ui.RedSoft
import com.neowarungposs.poskasir.ui.RedText
import kotlinx.coroutines.launch
import java.time.Instant

/** Subscription status only. No price, payment method or payment link in the Play build (D1). */
@Composable
fun LanggananScreen(container: AppContainer, repo: PosRepository, isAdmin: Boolean) {
    val ent by repo.ent.collectAsState()
    val tenant by repo.tenant.collectAsState()
    var editContact by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        val s = ent
        if (s == null) {
            Text("Status langganan belum tersedia. Sambungkan internet lalu tekan Cek Status.")
        } else {
            val gate = repo.gate()
            val label = when (gate.state) {
                GateState.GRACE -> "GRACE"
                GateState.EXPIRED -> "EXPIRED"
                else -> s.status
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Status", modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        when (label) {
                            "GRACE" -> Pill(statusLabel(label), AmberSoft, Amber)
                            "EXPIRED" -> Pill(statusLabel(label), RedSoft, RedText)
                            else -> Pill(statusLabel(label), GreenSoft, GreenDark)
                        }
                    }
                    Text("Aktif sampai", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(formatDate(Instant.ofEpochMilli(s.accessUntilMs)), fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
                    Text(
                        if (days >= 0) "Sisa $days hari" else "Lewat ${-days} hari",
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            val days = gate.daysLeft
            when {
                gate.state == GateState.EXPIRED -> Banner(
                    "Langganan sudah berakhir. Data Anda aman: riwayat dan laporan tetap bisa dibuka dan diekspor ke Excel. " +
                        "Transaksi baru dikunci sampai langganan diperpanjang.", RedSoft, RedText,
                )
                gate.state == GateState.GRACE -> Banner(
                    "Masa tenggang. Kasir masih bisa dipakai beberapa hari lagi. Segera perpanjang agar tidak terkunci.", RedSoft, RedText,
                )
                days in 0L..7L -> Banner(
                    "Langganan berakhir $days hari lagi. Info perpanjangan dikirim ke WhatsApp/email terdaftar.", AmberSoft, Amber,
                )
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Cara perpanjang", fontWeight = FontWeight.Bold)
                    if (isAdmin) {
                        Text("1. Info perpanjangan dikirim ke WhatsApp/email terdaftar di bawah.", fontSize = 14.sp)
                        Text("2. Sebutkan kode pelanggan Anda bila diminta.", fontSize = 14.sp)
                        Text("3. Setelah diverifikasi, tekan Cek Status Langganan.", fontSize = 14.sp)
                    } else {
                        Text("Hubungi pemilik usaha untuk perpanjangan langganan.", fontSize = 14.sp)
                    }
                }
            }
            if (isAdmin) {
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Kontak terdaftar", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("WhatsApp: ${tenant?.phoneWa?.ifBlank { null } ?: "belum diisi"}", fontWeight = FontWeight.SemiBold)
                            Text("Email: ${tenant?.email ?: "-"}", fontSize = 13.sp)
                            if (tenant != null && tenant?.waOptIn != true) Text("Pengingat WA dimatikan", fontSize = 12.sp, color = RedText)
                        }
                        TextButton(onClick = { editContact = true }) { Text("Ubah") }
                    }
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Kode pelanggan", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(s.customerCode, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    }
                    IconButton(onClick = { clipboard.setText(AnnotatedString(s.customerCode)) }) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "Salin kode pelanggan")
                    }
                }
            }
            Text("Terakhir dicek: ${formatDateTime(s.serverNowMs)}", fontSize = 13.sp)
        }
        ErrorText(error)
        Button(
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                busy = true; error = null
                scope.launch {
                    try { repo.refreshEntitlement() } catch (e: Exception) { error = friendlyError(e) }
                    busy = false
                }
            },
        ) { Text(if (busy) "Mengecek..." else "Cek Status Langganan") }

        ent?.let { PricingSection(container, it.payCode) }
    }

    if (editContact) {
        var phone by remember { mutableStateOf(tenant?.phoneWa ?: "") }
        var optIn by remember { mutableStateOf(tenant?.waOptIn ?: true) }
        var saving by remember { mutableStateOf(false) }
        var dialogError by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { if (!saving) editContact = false },
            title = { Text("Kontak perpanjangan") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        phone, { phone = it.filter { c -> c.isDigit() || c == '+' }.take(16) },
                        label = { Text("Nomor WhatsApp (08xx / 62xx)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Terima pengingat via WhatsApp", Modifier.weight(1f))
                        Switch(checked = optIn, onCheckedChange = { optIn = it })
                    }
                    ErrorText(dialogError)
                }
            },
            confirmButton = {
                TextButton(enabled = !saving && phone.length >= 9, onClick = {
                    saving = true
                    scope.launch {
                        try { repo.updateContact(phone, optIn); editContact = false }
                        catch (e: Exception) { dialogError = friendlyError(e); saving = false }
                    }
                }) { Text("Simpan") }
            },
            dismissButton = { TextButton(onClick = { editContact = false }, enabled = !saving) { Text("Batal") } },
        )
    }
}
