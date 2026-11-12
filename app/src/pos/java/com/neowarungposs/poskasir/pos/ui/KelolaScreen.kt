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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neowarungposs.poskasir.core.ApiException
import com.neowarungposs.poskasir.core.Outlet
import com.neowarungposs.poskasir.core.formatDateTime
import com.neowarungposs.poskasir.core.friendlyError
import com.neowarungposs.poskasir.core.rupiah
import com.neowarungposs.poskasir.pos.CashierRow
import com.neowarungposs.poskasir.pos.PosRepository
import com.neowarungposs.poskasir.pos.VoidRow
import com.neowarungposs.poskasir.ui.ConfirmDialog
import com.neowarungposs.poskasir.ui.ErrorText
import com.neowarungposs.poskasir.ui.RedText
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** TENANT_ADMIN tools: products, branches, cashiers, void approvals, branch report. */
@Composable
fun KelolaScreen(repo: PosRepository) {
    val tabs = listOf("Laporan", "Produk", "Promo", "Cabang", "Kasir", "Void")
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
            tabs.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
        }
        when (tab) {
            0 -> LaporanScreen(repo)
            1 -> ProdukScreen(repo)
            2 -> PromoScreen(repo)
            3 -> OutletsTab(repo)
            4 -> CashiersTab(repo)
            else -> VoidsTab(repo)
        }
    }
}

private fun cashierError(e: Throwable): String {
    val msg = (e as? ApiException)?.message ?: ""
    return when {
        msg.contains("EMAIL_EXISTS") -> "Email ini sudah dipakai akun lain."
        msg.contains("INVALID_INPUT") -> "Email atau password tidak valid (password min. 6 karakter)."
        msg.contains("OUTLET_NOT_FOUND") -> "Cabang tidak ditemukan."
        else -> friendlyError(e)
    }
}

// ------------------------------------------------------------------ branches
@Composable
private fun OutletsTab(repo: PosRepository) {
    var list by remember { mutableStateOf<List<Outlet>>(emptyList()) }
    var editing by remember { mutableStateOf<Outlet?>(null) }
    var creating by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf<Outlet?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(reload) { try { list = repo.outlets(); error = null } catch (e: Exception) { error = friendlyError(e) } }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { ErrorText(error) }
            items(list, key = { it.id }) { o ->
                Card(onClick = { editing = o }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${o.name}${if (o.isMain) " (pusat)" else ""}", fontWeight = FontWeight.Bold)
                            Text("Kode ${o.code}${o.address?.let { " · $it" } ?: ""}", fontSize = 13.sp)
                        }
                        if (!o.isMain) TextButton(onClick = { closing = o }) { Text("Tutup", color = RedText) }
                    }
                }
            }
            item { Box(Modifier.padding(40.dp)) }
        }
        ExtendedFloatingActionButton(
            onClick = { creating = true }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Cabang") },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        )
    }

    if (creating || editing != null) {
        val o = editing
        var code by remember(o) { mutableStateOf(o?.code ?: "") }
        var name by remember(o) { mutableStateOf(o?.name ?: "") }
        var address by remember(o) { mutableStateOf(o?.address ?: "") }
        var busy by remember(o) { mutableStateOf(false) }
        var dialogError by remember(o) { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { creating = false; editing = null },
            title = { Text(if (o == null) "Tambah cabang" else "Ubah cabang") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(code, { code = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(8) }, label = { Text("Kode (dipakai di no. struk)") }, singleLine = true)
                    OutlinedTextField(name, { name = it }, label = { Text("Nama cabang") }, singleLine = true)
                    OutlinedTextField(address, { address = it }, label = { Text("Alamat (opsional)") })
                    ErrorText(dialogError)
                }
            },
            confirmButton = {
                TextButton(enabled = !busy && code.isNotBlank() && name.isNotBlank(), onClick = {
                    busy = true
                    scope.launch {
                        try {
                            repo.saveOutlet(o?.id, code, name, address)
                            creating = false; editing = null; reload++
                        } catch (e: Exception) {
                            dialogError = if ((e as? ApiException)?.code == "23505") "Kode cabang sudah dipakai." else friendlyError(e)
                            busy = false
                        }
                    }
                }) { Text("Simpan") }
            },
            dismissButton = { TextButton(onClick = { creating = false; editing = null }) { Text("Batal") } },
        )
    }
    closing?.let { o ->
        ConfirmDialog(
            title = "Tutup cabang ${o.name}?",
            text = "Cabang disembunyikan. Riwayat penjualannya tetap tersimpan. Pindahkan dulu kasir di cabang ini.",
            confirmLabel = "Tutup cabang",
            onConfirm = {
                scope.launch {
                    try { repo.closeOutlet(o.id); reload++ } catch (e: Exception) { error = friendlyError(e) }
                    closing = null
                }
            },
            onDismiss = { closing = null },
        )
    }
}

// ------------------------------------------------------------------ cashiers
@Composable
private fun CashiersTab(repo: PosRepository) {
    var cashiers by remember { mutableStateOf<List<CashierRow>>(emptyList()) }
    var outlets by remember { mutableStateOf<List<Outlet>>(emptyList()) }
    var inviting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<CashierRow?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(reload) {
        try { outlets = repo.outlets(); cashiers = repo.cashiers(); error = null } catch (e: Exception) { error = friendlyError(e) }
    }
    fun outletName(id: String?) = outlets.firstOrNull { it.id == id }?.name ?: "-"

    Box(Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text("Kasir login di aplikasi Kasir Warung dengan email & password yang Anda buat di sini.", fontSize = 13.sp)
                ErrorText(error)
            }
            if (cashiers.isEmpty()) item { Text("Belum ada kasir.", modifier = Modifier.padding(8.dp)) }
            items(cashiers, key = { it.userId }) { c ->
                Card(onClick = { selected = c }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text(c.email ?: c.userId, fontWeight = FontWeight.Bold)
                        Text("Cabang: ${outletName(c.outletId)}", fontSize = 13.sp)
                    }
                }
            }
            item { Box(Modifier.padding(40.dp)) }
        }
        ExtendedFloatingActionButton(
            onClick = { inviting = true }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Kasir") },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        )
    }

    if (inviting) {
        var email by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        var outletId by remember { mutableStateOf(outlets.firstOrNull()?.id) }
        var busy by remember { mutableStateOf(false) }
        var dialogError by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { if (!busy) inviting = false },
            title = { Text("Tambah kasir") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(email, { email = it.trim() }, label = { Text("Email kasir") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                    OutlinedTextField(password, { password = it }, label = { Text("Password awal (min. 6)") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation())
                    Text("Cabang", fontWeight = FontWeight.Bold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        outlets.forEach { o -> FilterChip(selected = outletId == o.id, onClick = { outletId = o.id }, label = { Text(o.name) }) }
                    }
                    ErrorText(dialogError)
                }
            },
            confirmButton = {
                TextButton(enabled = !busy && email.contains("@") && password.length >= 6 && outletId != null, onClick = {
                    busy = true
                    scope.launch {
                        try {
                            repo.manageCashier(buildJsonObject {
                                put("action", "invite"); put("email", email); put("password", password); put("outlet_id", outletId)
                            })
                            inviting = false; reload++
                        } catch (e: Exception) { dialogError = cashierError(e); busy = false }
                    }
                }) { Text("Buat akun") }
            },
            dismissButton = { TextButton(onClick = { inviting = false }, enabled = !busy) { Text("Batal") } },
        )
    }

    selected?.let { c ->
        var newPassword by remember(c) { mutableStateOf("") }
        var moveTo by remember(c) { mutableStateOf(c.outletId) }
        var confirmRemove by remember(c) { mutableStateOf(false) }
        var busy by remember(c) { mutableStateOf(false) }
        var dialogError by remember(c) { mutableStateOf<String?>(null) }
        fun run(body: kotlinx.serialization.json.JsonObject) {
            busy = true
            scope.launch {
                try { repo.manageCashier(body); selected = null; reload++ } catch (e: Exception) { dialogError = cashierError(e); busy = false }
            }
        }
        AlertDialog(
            onDismissRequest = { if (!busy) selected = null },
            title = { Text(c.email ?: "Kasir") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Pindah cabang", fontWeight = FontWeight.Bold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        outlets.forEach { o -> FilterChip(selected = moveTo == o.id, onClick = { moveTo = o.id }, label = { Text(o.name) }) }
                    }
                    OutlinedButton(enabled = !busy && moveTo != c.outletId, onClick = {
                        run(buildJsonObject { put("action", "move"); put("user_id", c.userId); put("outlet_id", moveTo) })
                    }) { Text("Simpan cabang") }
                    OutlinedTextField(newPassword, { newPassword = it }, label = { Text("Password baru") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation())
                    OutlinedButton(enabled = !busy && newPassword.length >= 6, onClick = {
                        run(buildJsonObject { put("action", "reset_password"); put("user_id", c.userId); put("password", newPassword) })
                    }) { Text("Ganti password") }
                    TextButton(enabled = !busy, onClick = {
                        if (!confirmRemove) confirmRemove = true
                        else run(buildJsonObject { put("action", "remove"); put("user_id", c.userId) })
                    }) { Text(if (confirmRemove) "Tekan lagi untuk hapus permanen" else "Hapus akun kasir", color = RedText) }
                    ErrorText(dialogError)
                }
            },
            confirmButton = { TextButton(onClick = { selected = null }, enabled = !busy) { Text("Tutup") } },
        )
    }
}

// ------------------------------------------------------------------ void approvals
@Composable
private fun VoidsTab(repo: PosRepository) {
    var list by remember { mutableStateOf<List<VoidRow>>(emptyList()) }
    var outlets by remember { mutableStateOf<List<Outlet>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(reload) {
        try { list = repo.pendingVoids(); outlets = repo.outlets(); error = null } catch (e: Exception) { error = friendlyError(e) }
    }

    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { ErrorText(error) }
        if (list.isEmpty()) item { Text("Tidak ada permintaan void.", modifier = Modifier.padding(8.dp)) }
        items(list, key = { it.id }) { v ->
            val o = v.salesOrders
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${o?.invoiceNo ?: "-"} · ${o?.total?.let { rupiah(it) } ?: "-"}", fontWeight = FontWeight.Bold)
                    Text("Cabang: ${outlets.firstOrNull { it.id == o?.outletId }?.name ?: "-"} · ${formatDateTime(o?.createdAt)}", fontSize = 13.sp)
                    Text("Alasan: ${v.reason}")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled = !busy, onClick = {
                            busy = true
                            scope.launch { try { repo.decideVoid(v.id, false); reload++ } catch (e: Exception) { error = friendlyError(e) }; busy = false }
                        }) { Text("Tolak") }
                        OutlinedButton(enabled = !busy, onClick = {
                            busy = true
                            scope.launch { try { repo.decideVoid(v.id, true); reload++ } catch (e: Exception) { error = friendlyError(e) }; busy = false }
                        }) { Text("Setujui void", color = RedText) }
                    }
                }
            }
        }
    }
}

