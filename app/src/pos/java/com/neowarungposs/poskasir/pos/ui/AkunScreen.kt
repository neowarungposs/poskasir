@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.neowarungposs.poskasir.pos.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.neowarungposs.poskasir.AppContainer
import com.neowarungposs.poskasir.core.Claims
import com.neowarungposs.poskasir.core.FcmRegistrar
import com.neowarungposs.poskasir.core.friendlyError
import com.neowarungposs.poskasir.pos.BluetoothPrinter
import com.neowarungposs.poskasir.pos.OrderEntity
import com.neowarungposs.poskasir.pos.PairedPrinter
import com.neowarungposs.poskasir.pos.PosRepository
import com.neowarungposs.poskasir.pos.SyncScheduler
import com.neowarungposs.poskasir.ui.Banner
import com.neowarungposs.poskasir.ui.ConfirmDialog
import com.neowarungposs.poskasir.ui.ErrorText
import com.neowarungposs.poskasir.ui.GreenDark
import com.neowarungposs.poskasir.ui.GreenSoft
import com.neowarungposs.poskasir.ui.RedText
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Composable
fun AkunScreen(container: AppContainer, repo: PosRepository, claims: Claims) {
    val client = container.supabase
    val session by client.session.state.collectAsState()
    val pending by repo.dao.pendingCountFlow().collectAsState(initial = 0)
    var business by remember { mutableStateOf("") }
    var outletName by remember { mutableStateOf("") }
    var qrisUrl by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var deleteText by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val isAdmin = claims.appRole == "TENANT_ADMIN"

    suspend fun loadInfo() {
        business = repo.businessName()
        val o = repo.currentOutlet()
        outletName = o?.name ?: ""
        qrisUrl = o?.merchantQrisPath?.let { client.signedUrl("outlet-assets", it) }
    }
    LaunchedEffect(Unit) { loadInfo() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true; errorMsg = null
            try {
                val outlet = repo.currentOutlet() ?: throw IllegalStateException("Data outlet belum tersedia")
                val type = context.contentResolver.getType(uri) ?: "image/png"
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IllegalStateException("Gagal membaca gambar")
                if (bytes.size > 2 * 1024 * 1024) throw IllegalStateException("Gambar terlalu besar (maks 2 MB)")
                val ext = if (type.contains("png")) "png" else if (type.contains("webp")) "webp" else "jpg"
                // QRIS is uploaded lossless/as-is: recompressing can make it unscannable.
                val path = "${outlet.tenantId}/${outlet.id}/qris.$ext"
                client.upload("outlet-assets", path, bytes, type)
                client.patch("outlets?id=eq.${outlet.id}", buildJsonObject { put("merchant_qris_path", path) }.toString())
                repo.refreshTenantInfo()
                loadInfo()
                info = "QRIS outlet tersimpan."
            } catch (e: Exception) {
                errorMsg = friendlyError(e)
            }
            busy = false
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(business.ifEmpty { "-" }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Outlet: ${outletName.ifEmpty { "-" }}")
                Text(session?.email ?: "")
                Text(if (isAdmin) "Pemilik (Tenant Admin)" else "Kasir cabang")
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Sinkronisasi", fontWeight = FontWeight.Bold)
                Text(if (pending == 0) "Semua transaksi sudah tersinkron." else "$pending transaksi menunggu dikirim.")
                OutlinedButton(onClick = { SyncScheduler.syncNow(context); info = "Sinkronisasi dijalankan." }) { Text("Sinkronkan sekarang") }
            }
        }

        if (isAdmin) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("QRIS Outlet", fontWeight = FontWeight.Bold)
                    Text("Ditampilkan ke pembeli saat memilih bayar QRIS.")
                    qrisUrl?.let {
                        AsyncImage(
                            model = it, contentDescription = "QRIS outlet", contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 240.dp),
                        )
                    }
                    OutlinedButton(
                        enabled = !busy,
                        onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    ) { Text(if (qrisUrl == null) "Unggah QRIS" else "Ganti QRIS") }
                }
            }
        }

        PrinterCard(repo) { info = it }

        info?.let { Banner(it, GreenSoft, GreenDark) }
        ErrorText(errorMsg)

        Button(onClick = { confirmLogout = true }, modifier = Modifier.fillMaxWidth()) { Text("Keluar") }
        OutlinedButton(
            onClick = { confirmDelete = true },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = RedText),
        ) { Text(if (isAdmin) "Hapus Akun & Semua Data Usaha" else "Hapus Akun Saya") }
    }

    if (confirmLogout) {
        ConfirmDialog(
            title = "Keluar?",
            text = if (pending > 0) "Masih ada $pending transaksi belum tersinkron. Sambungkan internet dan sinkronkan dulu, atau data itu akan hilang dari HP ini."
            else "Data lokal di HP ini akan dibersihkan.",
            confirmLabel = if (pending > 0) "Tetap keluar" else "Keluar",
            onConfirm = {
                confirmLogout = false
                scope.launch {
                    runCatching { FcmRegistrar.unregister(client) }
                    repo.clearLocal()
                    client.signOut()
                }
            },
            onDismiss = { confirmLogout = false },
        )
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = if (isAdmin) "Hapus akun & semua data usaha?" else "Hapus akun?",
            text = if (isAdmin) "Semua data usaha (produk, penjualan, outlet, akun kasir, file) dihapus permanen dan tidak bisa dikembalikan. Ketik HAPUS untuk lanjut."
            else "Akun kasir Anda dihapus permanen. Ketik HAPUS untuk lanjut.",
            confirmLabel = "Hapus permanen",
            confirmEnabled = deleteText == "HAPUS" && !busy,
            content = { OutlinedTextField(deleteText, { deleteText = it }, singleLine = true, label = { Text("Ketik HAPUS") }) },
            onConfirm = {
                busy = true
                scope.launch {
                    try {
                        client.function("delete_account")
                        repo.clearLocal()
                        client.session.clear()
                    } catch (e: Exception) {
                        errorMsg = friendlyError(e)
                    }
                    busy = false
                    confirmDelete = false
                }
            },
            onDismiss = { confirmDelete = false; deleteText = "" },
        )
    }
}

@Composable
private fun PrinterCard(repo: PosRepository, onInfo: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf<String?>(null) }
    var width by remember { mutableStateOf(32) }
    var choosing by remember { mutableStateOf(false) }
    var printers by remember { mutableStateOf<List<PairedPrinter>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) { printers = BluetoothPrinter.pairedPrinters(context); choosing = true }
        else error = "Izin Bluetooth ditolak. Printer tidak bisa dipakai."
    }
    LaunchedEffect(Unit) { name = repo.printerName(); width = repo.paperWidth() }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Printer Bluetooth", fontWeight = FontWeight.Bold)
            Text("Printer: ${name ?: "belum dipilih"}. Pasangkan (pair) printer dulu di Pengaturan Bluetooth HP.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = width == 32, onClick = { width = 32; scope.launch { repo.setPaperWidth(32) } }, label = { Text("58 mm") })
                FilterChip(selected = width == 48, onClick = { width = 48; scope.launch { repo.setPaperWidth(48) } }, label = { Text("80 mm") })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    error = null
                    if (Build.VERSION.SDK_INT >= 31 && !BluetoothPrinter.hasPermission(context)) {
                        permission.launch(Manifest.permission.BLUETOOTH_CONNECT)
                    } else {
                        printers = BluetoothPrinter.pairedPrinters(context)
                        choosing = true
                    }
                }) { Text("Pilih printer") }
                OutlinedButton(enabled = name != null, onClick = {
                    error = null
                    scope.launch {
                        try {
                            val test = OrderEntity("test", "", "TES-PRINT", "CASH", 0, null, System.currentTimeMillis(), true)
                            repo.printReceipt(test, emptyList())
                            onInfo("Tes cetak terkirim.")
                        } catch (e: Exception) { error = e.message }
                    }
                }) { Text("Tes cetak") }
            }
            ErrorText(error)
        }
    }

    if (choosing) {
        AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text("Printer yang sudah dipasangkan") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (printers.isEmpty()) Text("Tidak ada. Nyalakan Bluetooth dan pasangkan printer di pengaturan HP.")
                    printers.forEach { p ->
                        TextButton(onClick = {
                            scope.launch { repo.setPrinter(p) }
                            name = p.name; choosing = false
                        }) { Text("${p.name} (${p.address})") }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choosing = false }) { Text("Tutup") } },
        )
    }
}
