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
fun LanggananScreen(container: AppContainer, repo: PosRepository) {
    val ent by repo.ent.collectAsState()
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
                    val days = gate.daysLeft
                    Text(
                        if (days >= 0) "Sisa $days hari" else "Lewat ${-days} hari",
                        fontWeight = FontWeight.SemiBold,
                    )
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
}
