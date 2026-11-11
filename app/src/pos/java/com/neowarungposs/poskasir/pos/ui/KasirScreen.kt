@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.neowarungposs.poskasir.pos.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.neowarungposs.poskasir.core.friendlyError
import com.neowarungposs.poskasir.core.rupiah
import com.neowarungposs.poskasir.pos.CartLine
import com.neowarungposs.poskasir.pos.Gate
import com.neowarungposs.poskasir.pos.OrderEntity
import com.neowarungposs.poskasir.pos.OrderItemEntity
import com.neowarungposs.poskasir.pos.PosRepository
import com.neowarungposs.poskasir.pos.ProductEntity
import com.neowarungposs.poskasir.pos.SyncScheduler
import com.neowarungposs.poskasir.ui.ErrorText
import com.neowarungposs.poskasir.ui.GreenDark
import com.neowarungposs.poskasir.ui.GreenSoft
import kotlinx.coroutines.launch

@Composable
fun KasirScreen(repo: PosRepository, gate: Gate, isAdmin: Boolean) {
    val products by repo.dao.productsFlow().collectAsState(initial = emptyList())
    val active = products.filter { it.isActive }
    val cart = remember { mutableStateMapOf<String, Int>() }
    var query by rememberSaveable { mutableStateOf("") }
    var grid by rememberSaveable { mutableStateOf(true) }
    var paying by remember { mutableStateOf(false) }
    var receipt by remember { mutableStateOf<Pair<OrderEntity, List<OrderItemEntity>>?>(null) }

    val shown = active.filter { query.isBlank() || it.name.contains(query, true) || (it.category ?: "").contains(query, true) }
    val lines = active.mapNotNull { p -> cart[p.id]?.let { CartLine(p, it) } }
    val total = lines.sumOf { it.product.price * it.qty }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                placeholder = { Text("Cari menu") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { grid = !grid }) {
                Icon(if (grid) Icons.Filled.ViewList else Icons.Filled.GridView, contentDescription = "Ganti tampilan")
            }
        }

        Box(Modifier.weight(1f)) {
            if (active.isEmpty()) {
                Text(
                    if (isAdmin) "Belum ada produk. Tambahkan di tab Produk." else "Belum ada produk. Minta pemilik menambahkan menu.",
                    modifier = Modifier.padding(24.dp),
                )
            } else if (grid) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(150.dp),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(shown, key = { it.id }) { p -> ProductCard(p, cart[p.id] ?: 0, { cart[p.id] = (cart[p.id] ?: 0) + 1 }, { dec(cart, p.id) }) }
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(shown, key = { it.id }) { p -> ProductRow(p, cart[p.id] ?: 0, { cart[p.id] = (cart[p.id] ?: 0) + 1 }, { dec(cart, p.id) }) }
                }
            }
        }

        Surface(color = GreenDark, contentColor = MaterialTheme.colorScheme.onPrimary) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("${lines.sumOf { it.qty }} item", fontSize = 13.sp)
                    Text(rupiah(total), fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                }
                if (cart.isNotEmpty()) TextButton(onClick = { cart.clear() }) { Text("Kosongkan", color = GreenSoft) }
                Button(enabled = lines.isNotEmpty() && gate.canSell, onClick = { paying = true }) {
                    Text(if (gate.canSell) "Bayar" else "Terkunci")
                }
            }
        }
    }

    if (paying) {
        PaymentDialog(
            repo = repo,
            total = total,
            onDismiss = { paying = false },
            onPaid = { method, cash, ctx ->
                val r = repo.checkout(lines, method, cash)
                cart.clear()
                paying = false
                receipt = r
                SyncScheduler.syncNow(ctx)
            },
        )
    }
    receipt?.let { (o, items) -> InvoiceDialog(repo, o, items) { receipt = null } }
}

private fun dec(cart: MutableMap<String, Int>, id: String) {
    val q = (cart[id] ?: 0) - 1
    if (q <= 0) cart.remove(id) else cart[id] = q
}

@Composable
private fun ProductCard(p: ProductEntity, qty: Int, onAdd: () -> Unit, onRemove: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onAdd),
        colors = CardDefaults.cardColors(containerColor = if (qty > 0) GreenSoft else MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.6f)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(p.name.take(1).uppercase(), fontSize = 28.sp, fontWeight = FontWeight.Bold, color = GreenDark)
            }
            Text(p.name, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(rupiah(p.price), modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                if (qty > 0) QtyBadge(qty, onRemove)
            }
        }
    }
}

@Composable
private fun ProductRow(p: ProductEntity, qty: Int, onAdd: () -> Unit, onRemove: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onAdd),
        colors = CardDefaults.cardColors(containerColor = if (qty > 0) GreenSoft else MaterialTheme.colorScheme.surface),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(p.name, fontWeight = FontWeight.SemiBold)
                p.category?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Text(rupiah(p.price), fontWeight = FontWeight.Bold)
            if (qty > 0) {
                Spacer(Modifier.size(8.dp)); QtyBadge(qty, onRemove)
            }
        }
    }
}

@Composable
private fun QtyBadge(qty: Int, onRemove: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) { Icon(Icons.Filled.Remove, "Kurangi") }
        Text("$qty", fontWeight = FontWeight.ExtraBold, modifier = Modifier.widthIn(min = 20.dp))
    }
}

@Composable
private fun PaymentDialog(
    repo: PosRepository,
    total: Long,
    onDismiss: () -> Unit,
    onPaid: suspend (method: String, cash: Long?, ctx: Context) -> Unit,
) {
    var method by remember { mutableStateOf("CASH") }
    var cashText by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var qrisUrl by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val cash = cashText.toLongOrNull()

    LaunchedEffect(method) {
        if (method == "QRIS" && qrisUrl == null) {
            val path = repo.currentOutlet()?.merchantQrisPath
            if (path != null) qrisUrl = repo.container.supabase.signedUrl("outlet-assets", path)
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Pembayaran ${rupiah(total)}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("CASH" to "Tunai", "QRIS" to "QRIS", "OTHER" to "Lainnya").forEach { (k, label) ->
                        FilterChip(selected = method == k, onClick = { method = k }, label = { Text(label) })
                    }
                }
                when (method) {
                    "CASH" -> {
                        OutlinedTextField(
                            value = cashText, onValueChange = { cashText = it.filter(Char::isDigit) },
                            label = { Text("Uang diterima") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(total, 20000L, 50000L, 100000L).distinct().filter { it >= total }.forEach { v ->
                                FilterChip(selected = cash == v, onClick = { cashText = v.toString() }, label = { Text(if (v == total) "Uang pas" else rupiah(v)) })
                            }
                        }
                        if (cash != null && cash >= total) Text("Kembalian: ${rupiah(cash - total)}", fontWeight = FontWeight.Bold)
                    }
                    "QRIS" -> {
                        if (qrisUrl != null) {
                            AsyncImage(
                                model = qrisUrl, contentDescription = "QRIS outlet",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 280.dp),
                            )
                        } else {
                            Text("QRIS outlet belum diunggah (Akun > QRIS Outlet) atau sedang offline. Tunjukkan QRIS fisik Anda.")
                        }
                        Text("Tekan \"Sudah diterima\" setelah notifikasi pembayaran masuk di HP Anda.", fontSize = 13.sp)
                    }
                    else -> Text("Catat pembayaran dengan metode lain.")
                }
                ErrorText(error)
            }
        },
        confirmButton = {
            Button(
                enabled = !busy && (method != "CASH" || (cash != null && cash >= total)),
                onClick = {
                    busy = true; error = null
                    scope.launch {
                        try {
                            onPaid(method, if (method == "CASH") cash else null, ctx)
                        } catch (e: Exception) {
                            error = friendlyError(e); busy = false
                        }
                    }
                },
            ) { Text(if (method == "QRIS") "Sudah diterima" else "Simpan") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Batal") } },
    )
}
