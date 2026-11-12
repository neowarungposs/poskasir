@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.neowarungposs.poskasir.pos.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.neowarungposs.poskasir.core.ImageCompressor
import com.neowarungposs.poskasir.core.Outlet
import com.neowarungposs.poskasir.core.friendlyError
import com.neowarungposs.poskasir.pos.PosRepository
import com.neowarungposs.poskasir.ui.AppTheme
import com.neowarungposs.poskasir.ui.ErrorText
import com.neowarungposs.poskasir.ui.ThemeState
import kotlinx.coroutines.launch

/** TENANT_ADMIN: theme, logo and login background (D20, D21). Saved tenant-wide, cached on each device. */
@Composable
fun AppearanceCard(repo: PosRepository, onInfo: (String) -> Unit) {
    val ctx = LocalContext.current
    val theme by ThemeState.current.collectAsState()
    val version by repo.container.branding.version.collectAsState()
    val logo = remember(version) { repo.container.branding.logo() }
    val bg = remember(version) { repo.container.branding.background() }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun run(block: suspend () -> Unit) {
        busy = true; errorMsg = null
        scope.launch {
            try { block() } catch (e: Exception) { errorMsg = friendlyError(e) }
            busy = false
        }
    }
    val logoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) run {
            val bytes = ImageCompressor.toWebp(ctx, uri, maxPx = 512, maxBytes = 150_000)
            repo.updateBranding(bytes, null, null)
            onInfo("Logo tersimpan. Tampil di layar login & kasir.")
        }
    }
    val bgPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) run {
            val bytes = ImageCompressor.toWebp(ctx, uri, maxPx = 1280, maxBytes = 350_000)
            repo.updateBranding(null, bytes, null)
            onInfo("Latar login tersimpan. Tampil setelah login berikutnya di HP ini & HP kasir.")
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Tampilan & merek", fontWeight = FontWeight.Bold)
            Text("Tema berlaku untuk semua HP di usaha Anda.", fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppTheme.entries.forEach { t ->
                    val (a, b) = when (t) {
                        AppTheme.AURORA -> Color(0xFF070A12) to Color(0xFFC8F65A)
                        AppTheme.BENTO -> Color(0xFFEEF0EA) to Color(0xFF0E7A4F)
                        AppTheme.NEON -> Color(0xFF090D12) to Color(0xFFC6F24E)
                        AppTheme.MIDNIGHT -> Color(0xFF0B1220) to Color(0xFF7DD3FC)
                    }
                    Surface(
                        onClick = {
                            if (!busy && t != theme) {
                                ThemeState.set(ctx, t)
                                run { repo.updateBranding(null, null, t) }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        color = a,
                        border = if (t == theme) androidx.compose.foundation.BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else null,
                    ) {
                        Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.size(width = 48.dp, height = 10.dp).clip(RoundedCornerShape(5.dp)).background(b))
                            Spacer(Modifier.height(6.dp))
                            Text(t.label, fontSize = 12.sp, color = if (t.dark) Color.White else Color(0xFF15201A), textAlign = TextAlign.Center)
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                    if (logo != null) AsyncImage(logo, "Logo", Modifier.fillMaxSize(), contentScale = ContentScale.Crop) else Text("Logo", fontSize = 11.sp)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Logo usaha", fontWeight = FontWeight.SemiBold)
                    Text("Login, kasir, dan struk (opsional)", fontSize = 12.sp)
                }
                if (logo != null) TextButton(enabled = !busy, onClick = { run { repo.updateBranding(null, null, null, clearLogo = true) } }) { Text("Hapus") }
                OutlinedButton(enabled = !busy, onClick = { logoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text("Pilih") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(width = 56.dp, height = 80.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                    if (bg != null) AsyncImage(bg, "Latar login", Modifier.fillMaxSize(), contentScale = ContentScale.Crop) else Text("Latar", fontSize = 11.sp)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Latar layar login", fontWeight = FontWeight.SemiBold)
                    Text("Foto warung/menu, tampil setelah login pertama", fontSize = 12.sp)
                }
                if (bg != null) TextButton(enabled = !busy, onClick = { run { repo.updateBranding(null, null, null, clearBg = true) } }) { Text("Hapus") }
                OutlinedButton(enabled = !busy, onClick = { bgPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text("Pilih") }
            }
            if (busy) Text("Menyimpan...", fontSize = 12.sp)
            ErrorText(errorMsg)
        }
    }
}

/** TENANT_ADMIN: receipt header/footer per outlet (feedback #3). */
@Composable
fun ReceiptCard(repo: PosRepository, onInfo: (String) -> Unit) {
    var outlets by remember { mutableStateOf<List<Outlet>>(emptyList()) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }
    LaunchedEffect(reload) {
        outlets = runCatching { repo.outlets() }.getOrElse { repo.cachedOutlets() }
        if (selectedId == null) selectedId = repo.currentOutlet()?.id ?: outlets.firstOrNull()?.id
    }
    val o = outlets.firstOrNull { it.id == selectedId } ?: return
    val business = repo.tenant.collectAsState().value?.businessName ?: ""
    var header by remember(o) { mutableStateOf(o.receiptHeader ?: "") }
    var footer by remember(o) { mutableStateOf(o.receiptFooter ?: "") }
    var phone by remember(o) { mutableStateOf(o.phone ?: "") }
    var showLogo by remember(o) { mutableStateOf(o.receiptShowLogo) }
    var showPreview by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Struk", fontWeight = FontWeight.Bold)
            if (outlets.size > 1) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                outlets.forEach { x -> FilterChip(selected = x.id == o.id, onClick = { selectedId = x.id }, label = { Text(x.name) }) }
            }
            OutlinedTextField(header, { header = it.take(160) }, label = { Text("Teks atas (mis. alamat, jam buka)") }, minLines = 2, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(phone, { phone = it.take(20) }, label = { Text("Telepon cabang") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(footer, { footer = it.take(160) }, label = { Text("Teks bawah (mis. Terima kasih, IG, wifi)") }, minLines = 2, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Cetak logo di struk", Modifier.weight(1f))
                Switch(checked = showLogo, onCheckedChange = { showLogo = it })
            }
            ErrorText(errorMsg)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { showPreview = true }) { Text("Pratinjau") }
            OutlinedButton(enabled = !busy, onClick = {
                busy = true; errorMsg = null
                scope.launch {
                    try { repo.saveReceiptSettings(o.id, header, footer, phone, showLogo); reload++; onInfo("Pengaturan struk ${o.name} tersimpan.") }
                    catch (e: Exception) { errorMsg = friendlyError(e) }
                    busy = false
                }
            }) { Text("Simpan struk") }
            }
        }
    }
    if (showPreview) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showPreview = false },
            title = { Text("Pratinjau struk") },
            text = {
                Surface(color = Color(0xFFFFFDF7), contentColor = Color(0xFF222222), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        val mono = FontFamily.Monospace
                        val logoFile = repo.container.branding.logo()
                        if (showLogo && logoFile != null) AsyncImage(logoFile, "Logo", Modifier.size(64.dp).padding(bottom = 4.dp), contentScale = ContentScale.Fit)
                        Text(business, fontFamily = mono, fontWeight = FontWeight.Bold)
                        if (o.name != business) Text(o.name, fontFamily = mono, fontSize = 12.sp)
                        if (phone.isNotBlank()) Text("Telp $phone", fontFamily = mono, fontSize = 12.sp)
                        if (header.isNotBlank()) Text(header, fontFamily = mono, fontSize = 12.sp, textAlign = TextAlign.Center)
                        Text("--------------------------------", fontFamily = mono, fontSize = 12.sp)
                        Text("1 x Es Teh            5.000", fontFamily = mono, fontSize = 12.sp)
                        Text("TOTAL                 5.000", fontFamily = mono, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text("--------------------------------", fontFamily = mono, fontSize = 12.sp)
                        Text(footer.ifBlank { "Terima kasih!" }, fontFamily = mono, fontSize = 12.sp, textAlign = TextAlign.Center)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showPreview = false }) { Text("Tutup") } },
        )
    }
}
