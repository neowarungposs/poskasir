@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.neowarungposs.poskasir.pos.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.platform.LocalContext
import com.neowarungposs.poskasir.core.ApiException
import com.neowarungposs.poskasir.core.formatDateTime
import com.neowarungposs.poskasir.core.friendlyError
import com.neowarungposs.poskasir.pos.BluetoothPrinter
import com.neowarungposs.poskasir.ui.Banner
import com.neowarungposs.poskasir.ui.ErrorText
import com.neowarungposs.poskasir.ui.RedSoft
import com.neowarungposs.poskasir.ui.RedText
import com.neowarungposs.poskasir.core.rupiah
import com.neowarungposs.poskasir.pos.OrderEntity
import com.neowarungposs.poskasir.pos.OrderItemEntity
import com.neowarungposs.poskasir.pos.PosRepository
import com.neowarungposs.poskasir.ui.Amber
import com.neowarungposs.poskasir.ui.AmberSoft
import com.neowarungposs.poskasir.ui.DashedDivider
import com.neowarungposs.poskasir.ui.GreenDark
import com.neowarungposs.poskasir.ui.GreenSoft
import com.neowarungposs.poskasir.ui.Pill
import kotlinx.coroutines.launch

@Composable
fun RiwayatScreen(repo: PosRepository) {
    val orders by repo.dao.ordersFlow().collectAsState(initial = emptyList())
    var open by remember { mutableStateOf<Pair<OrderEntity, List<OrderItemEntity>>?>(null) }
    val scope = rememberCoroutineScope()
    val today = remember { repo.todayStartMs() }
    val todays = orders.filter { it.createdAtMs >= today && it.status != "VOIDED" }

    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Penjualan hari ini", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(rupiah(todays.sumOf { it.total }), fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
                    Text("${todays.size} transaksi")
                }
            }
        }
        if (orders.isEmpty()) item { Text("Belum ada transaksi.", modifier = Modifier.padding(12.dp)) }
        items(orders, key = { it.id }) { o ->
            Card(
                Modifier
                    .fillMaxWidth()
                    .clickable { scope.launch { open = o to repo.dao.items(o.id) } }
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(o.invoiceNo, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text("${formatDateTime(o.createdAtMs)} · ${methodLabel(o.paymentMethod)}", fontSize = 12.sp)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(rupiah(o.total), fontWeight = FontWeight.Bold)
                        when {
                            o.status == "VOIDED" -> Pill("VOID", RedSoft, RedText)
                            o.synced -> Pill("Tersinkron", GreenSoft, GreenDark)
                            else -> Pill("Belum sinkron", AmberSoft, Amber)
                        }
                    }
                }
            }
        }
    }
    open?.let { (o, items) -> InvoiceDialog(repo, o, items) { open = null } }
}

fun methodLabel(m: String) = when (m) {
    "CASH" -> "Tunai"; "QRIS" -> "QRIS"; else -> "Lainnya"
}

/** Digital invoice: Canvas dashed separators, weight(1f) rows. */
@Composable
fun InvoiceDialog(repo: PosRepository, order: OrderEntity, items: List<OrderItemEntity>, onClose: () -> Unit) {
    var business by remember { mutableStateOf("") }
    var outlet by remember { mutableStateOf("") }
    var current by remember { mutableStateOf(order) }
    var info by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var voiding by remember { mutableStateOf(false) }
    var reason by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val btPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(order.id) {
        business = repo.businessName()
        outlet = repo.currentOutlet()?.name ?: ""
        current = repo.dao.order(order.id) ?: order
    }
    Dialog(onDismissRequest = onClose) {
        Card {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(business, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                if (outlet.isNotEmpty()) Text(outlet, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(), fontSize = 13.sp)
                DashedDivider()
                if (current.status == "VOIDED") Pill("VOID", RedSoft, RedText)
                Mono(order.invoiceNo)
                Mono(formatDateTime(order.createdAtMs))
                DashedDivider()
                items.forEach { i ->
                    Row(Modifier.fillMaxWidth()) {
                        Text("${i.productName} x${trimQty(i.qty)}", modifier = Modifier.weight(1f), fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                        Text(rupiah(i.subtotal), fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    }
                }
                DashedDivider()
                Row(Modifier.fillMaxWidth()) {
                    Text("TOTAL", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    Text(rupiah(order.total), fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                }
                Row(Modifier.fillMaxWidth()) {
                    Text(methodLabel(order.paymentMethod), modifier = Modifier.weight(1f), fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    order.cashReceived?.let { Text(rupiah(it), fontFamily = FontFamily.Monospace, fontSize = 13.sp) }
                }
                order.cashReceived?.let {
                    Row(Modifier.fillMaxWidth()) {
                        Text("Kembali", modifier = Modifier.weight(1f), fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                        Text(rupiah(it - order.total), fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    }
                }
                DashedDivider()
                Text("Terima kasih!", textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                info?.let { Banner(it, GreenSoft, GreenDark) }
                ErrorText(error)
                if (voiding) {
                    OutlinedTextField(reason, { reason = it }, label = { Text("Alasan void") })
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (current.status != "VOIDED") {
                        TextButton(enabled = !busy && (!voiding || reason.isNotBlank()), onClick = {
                            if (!voiding) { voiding = true; return@TextButton }
                            busy = true; error = null
                            scope.launch {
                                try {
                                    repo.requestVoid(current, reason.trim())
                                    info = "Permintaan void dikirim ke pemilik."
                                    voiding = false
                                } catch (e: Exception) {
                                    error = if ((e as? ApiException)?.code == "VOID_ALREADY_PENDING") "Void untuk struk ini sudah diajukan." else friendlyError(e)
                                }
                                busy = false
                            }
                        }) { Text(if (voiding) "Kirim void" else "Ajukan void", color = RedText) }
                    }
                    TextButton(enabled = !busy, onClick = {
                        if (Build.VERSION.SDK_INT >= 31 && !BluetoothPrinter.hasPermission(context)) {
                            btPermission.launch(Manifest.permission.BLUETOOTH_CONNECT); return@TextButton
                        }
                        busy = true; error = null
                        scope.launch {
                            try { repo.printReceipt(current, items); info = "Struk dicetak." } catch (e: Exception) { error = e.message ?: friendlyError(e) }
                            busy = false
                        }
                    }) { Text("Cetak") }
                    TextButton(onClick = onClose) { Text("Tutup") }
                }
            }
        }
    }
}

@Composable
private fun Mono(text: String) {
    Text(text, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
}

private fun trimQty(q: Double): String = if (q % 1.0 == 0.0) q.toLong().toString() else q.toString()
