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
import com.neowarungposs.poskasir.pos.ServerOrder
import com.neowarungposs.poskasir.core.Outlet
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.saveable.rememberSaveable
import com.neowarungposs.poskasir.ui.Amber
import com.neowarungposs.poskasir.ui.AmberSoft
import com.neowarungposs.poskasir.ui.DashedDivider
import com.neowarungposs.poskasir.ui.GreenDark
import com.neowarungposs.poskasir.ui.GreenSoft
import com.neowarungposs.poskasir.ui.Pill
import kotlinx.coroutines.launch

@Composable
fun RiwayatScreen(repo: PosRepository, isAdmin: Boolean) {
    var server by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        if (isAdmin) {
            Row(Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !server, onClick = { server = false }, label = { Text("HP ini") })
                FilterChip(selected = server, onClick = { server = true }, label = { Text("Semua cabang (online)") })
            }
        }
        if (server) ServerHistory(repo) else LocalHistory(repo)
    }
}

@Composable
private fun LocalHistory(repo: PosRepository) {
    val orders by repo.dao.ordersFlow().collectAsState(initial = emptyList())
    var open by remember { mutableStateOf<Pair<OrderEntity, List<OrderItemEntity>>?>(null) }
    val scope = rememberCoroutineScope()
    val today = remember { repo.todayStartMs() }
    val todays = orders.filter { it.createdAtMs >= today && it.status != "VOIDED" }

    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Penjualan hari ini (HP ini)", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(rupiah(todays.sumOf { it.total }), fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
                    Text("${todays.size} transaksi" + if (todays.sumOf { it.discount } > 0) " · diskon ${rupiah(todays.sumOf { it.discount })}" else "")
                }
            }
        }
        if (orders.isEmpty()) item { Text("Belum ada transaksi.", modifier = Modifier.padding(12.dp)) }
        items(orders, key = { it.id }) { o ->
            OrderRow(o, if (o.synced) "Tersinkron" else "Belum sinkron", o.synced) { scope.launch { open = o to repo.dao.items(o.id) } }
        }
    }
    open?.let { (o, items) -> InvoiceDialog(repo, o, items) { open = null } }
}

@Composable
private fun ServerHistory(repo: PosRepository) {
    var outlets by remember { mutableStateOf<List<Outlet>>(emptyList()) }
    var outletId by rememberSaveable { mutableStateOf<String?>(null) }
    var days by rememberSaveable { mutableStateOf(0) }
    var list by remember { mutableStateOf<List<ServerOrder>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var open by remember { mutableStateOf<Pair<OrderEntity, List<OrderItemEntity>>?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(outletId, days) {
        loading = true
        try {
            if (outlets.isEmpty()) outlets = repo.outlets()
            list = repo.serverOrders(outletId, repo.todayStartMs() - days * PosRepository.DAY_MS)
            errorMsg = null
        } catch (e: Exception) { errorMsg = friendlyError(e) }
        loading = false
    }
    val paid = list.filter { it.status == "PAID" }

    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0 to "Hari ini", 6 to "7 hari", 29 to "30 hari").forEach { (d, label) ->
                        FilterChip(selected = days == d, onClick = { days = d }, label = { Text(label) })
                    }
                }
                if (outlets.size > 1) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = outletId == null, onClick = { outletId = null }, label = { Text("Semua") })
                    outlets.forEach { o -> FilterChip(selected = outletId == o.id, onClick = { outletId = o.id }, label = { Text(o.name) }) }
                }
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                ErrorText(errorMsg)
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(outlets.firstOrNull { it.id == outletId }?.name ?: "Semua cabang", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(rupiah(paid.sumOf { it.total }), fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
                        Text("${paid.size} transaksi · ${list.size - paid.size} void")
                    }
                }
            }
        }
        if (!loading && list.isEmpty()) item { Text("Belum ada transaksi tersinkron.", modifier = Modifier.padding(12.dp)) }
        items(list, key = { it.id }) { so ->
            val o = so.toEntity()
            OrderRow(o, outlets.firstOrNull { it.id == so.outletId }?.name ?: "", true) {
                scope.launch {
                    try { open = o to repo.serverOrderItems(so.id) } catch (e: Exception) { errorMsg = friendlyError(e) }
                }
            }
        }
    }
    open?.let { (o, items) -> InvoiceDialog(repo, o, items, allowVoid = false) { open = null } }
}

@Composable
private fun OrderRow(o: OrderEntity, tag: String, ok: Boolean, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(o.invoiceNo, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(
                    "${formatDateTime(o.createdAtMs)} · ${methodLabel(o.paymentMethod)}" +
                        (if (o.orderType == "TAKEAWAY") " · Bungkus" else o.tableNo?.let { " · Meja $it" } ?: ""),
                    fontSize = 12.sp,
                )
                if (o.discount > 0) Text("Diskon ${rupiah(o.discount)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.tertiary)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(rupiah(o.total), fontWeight = FontWeight.Bold)
                when {
                    o.status == "VOIDED" -> Pill("VOID", RedSoft, RedText)
                    ok -> Pill(tag.ifEmpty { "Tersinkron" }, GreenSoft, GreenDark)
                    else -> Pill(tag, AmberSoft, Amber)
                }
            }
        }
    }
}

fun methodLabel(m: String) = when (m) {
    "CASH" -> "Tunai"; "QRIS" -> "QRIS"; else -> "Lainnya"
}

/** Digital invoice: Canvas dashed separators, weight(1f) rows. */
@Composable
fun InvoiceDialog(repo: PosRepository, order: OrderEntity, items: List<OrderItemEntity>, allowVoid: Boolean = true, onClose: () -> Unit) {
    var business by remember { mutableStateOf("") }
    var outlet by remember { mutableStateOf<Outlet?>(null) }
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
        outlet = repo.cachedOutlets().firstOrNull { it.id == order.outletId } ?: repo.currentOutlet()
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
                outlet?.let { o ->
                    Text(o.name, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(), fontSize = 13.sp)
                    o.phone?.takeIf { it.isNotBlank() }?.let { Text("Telp $it", textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(), fontSize = 12.sp) }
                    o.receiptHeader?.takeIf { it.isNotBlank() }?.let { Text(it, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(), fontSize = 12.sp) }
                }
                DashedDivider()
                if (current.status == "VOIDED") Pill("VOID", RedSoft, RedText)
                Mono(order.invoiceNo)
                Mono(formatDateTime(order.createdAtMs))
                Mono(if (order.orderType == "TAKEAWAY") "Bungkus" else "Makan di tempat" + (order.tableNo?.let { " · Meja $it" } ?: ""))
                DashedDivider()
                items.forEach { i ->
                    Row(Modifier.fillMaxWidth()) {
                        Text("${i.productName} x${trimQty(i.qty)}", modifier = Modifier.weight(1f), fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                        Text(rupiah(i.subtotal), fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    }
                    i.note?.takeIf { it.isNotBlank() }?.let { Text("  * $it", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.tertiary) }
                }
                DashedDivider()
                if (order.discount > 0) {
                    Row(Modifier.fillMaxWidth()) {
                        Text("Subtotal", modifier = Modifier.weight(1f), fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                        Text(rupiah(order.subtotal), fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    }
                    val promoLines = order.promoSummary?.split("; ")?.map { it.substringBeforeLast(" -") to (it.substringAfterLast(" -").toLongOrNull() ?: 0L) }
                        ?: listOf("Diskon" to order.discount)
                    promoLines.forEach { (n, a) ->
                        Row(Modifier.fillMaxWidth()) {
                            Text(n, modifier = Modifier.weight(1f), fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                            Text("-${rupiah(a)}", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                        }
                    }
                }
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
                Text(outlet?.receiptFooter?.takeIf { it.isNotBlank() } ?: "Terima kasih!", textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                info?.let { Banner(it, GreenSoft, GreenDark) }
                ErrorText(error)
                if (voiding) {
                    OutlinedTextField(reason, { reason = it }, label = { Text("Alasan void") })
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (allowVoid && current.status != "VOIDED") {
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
