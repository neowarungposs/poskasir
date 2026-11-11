@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.neowarungposs.poskasir.pos.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neowarungposs.poskasir.core.friendlyError
import com.neowarungposs.poskasir.core.rupiah
import com.neowarungposs.poskasir.pos.PosRepository
import com.neowarungposs.poskasir.pos.ProductEntity
import com.neowarungposs.poskasir.ui.ConfirmDialog
import com.neowarungposs.poskasir.ui.ErrorText
import kotlinx.coroutines.launch

@Composable
fun ProdukScreen(repo: PosRepository) {
    val products by repo.dao.productsFlow().collectAsState(initial = emptyList())
    var editing by remember { mutableStateOf<ProductEntity?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<ProductEntity?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Box(Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text("Perubahan produk butuh koneksi internet.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ErrorText(error)
            }
            items(products, key = { it.id }) { p ->
                Card(onClick = { editing = p }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name, fontWeight = FontWeight.SemiBold)
                            Text(
                                listOfNotNull(p.category, if (p.isActive) null else "nonaktif").joinToString(" · "),
                                fontSize = 12.sp,
                            )
                        }
                        Text(rupiah(p.price), fontWeight = FontWeight.Bold)
                        IconButton(onClick = { deleting = p }) { Icon(Icons.Filled.Delete, "Hapus") }
                    }
                }
            }
            item { Box(Modifier.padding(40.dp)) }
        }
        ExtendedFloatingActionButton(
            onClick = { creating = true },
            icon = { Icon(Icons.Filled.Add, null) },
            text = { Text("Produk") },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        )
    }

    if (creating || editing != null) {
        ProductDialog(
            initial = editing,
            onDismiss = { creating = false; editing = null },
            onSave = { name, cat, price, active ->
                repo.saveProduct(editing?.id, name, cat, price, active)
                creating = false; editing = null
            },
        )
    }
    deleting?.let { p ->
        ConfirmDialog(
            title = "Hapus produk?",
            text = "\"${p.name}\" dihapus dari menu. Riwayat penjualan tetap menyimpan nama dan harganya.",
            confirmLabel = "Hapus",
            onConfirm = {
                scope.launch {
                    try { repo.deleteProduct(p.id); error = null } catch (e: Exception) { error = friendlyError(e) }
                    deleting = null
                }
            },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun ProductDialog(
    initial: ProductEntity?,
    onDismiss: () -> Unit,
    onSave: suspend (String, String?, Long, Boolean) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var category by remember { mutableStateOf(initial?.category ?: "") }
    var price by remember { mutableStateOf(initial?.price?.toString() ?: "") }
    var active by remember { mutableStateOf(initial?.isActive ?: true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (initial == null) "Tambah produk" else "Ubah produk") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Nama") }, singleLine = true)
                OutlinedTextField(category, { category = it }, label = { Text("Kategori (opsional)") }, singleLine = true)
                OutlinedTextField(
                    price, { price = it.filter(Char::isDigit) }, label = { Text("Harga (Rp)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Aktif dijual", modifier = Modifier.weight(1f))
                    Switch(checked = active, onCheckedChange = { active = it })
                }
                ErrorText(error)
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && name.isNotBlank() && price.toLongOrNull() != null,
                onClick = {
                    busy = true
                    scope.launch {
                        try {
                            onSave(name, category, price.toLong(), active)
                        } catch (e: Exception) {
                            error = friendlyError(e); busy = false
                        }
                    }
                },
            ) { Text("Simpan") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Batal") } },
    )
}
