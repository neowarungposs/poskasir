@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)

package com.neowarungposs.poskasir.pos.ui

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.neowarungposs.poskasir.core.Outlet
import com.neowarungposs.poskasir.core.friendlyError
import com.neowarungposs.poskasir.core.rupiah
import com.neowarungposs.poskasir.pos.AppliedPromo
import com.neowarungposs.poskasir.pos.CartLine
import com.neowarungposs.poskasir.pos.Gate
import com.neowarungposs.poskasir.pos.OrderEntity
import com.neowarungposs.poskasir.pos.OrderItemEntity
import com.neowarungposs.poskasir.pos.PosRepository
import com.neowarungposs.poskasir.pos.PricedLine
import com.neowarungposs.poskasir.pos.ProductEntity
import com.neowarungposs.poskasir.pos.PromoDto
import com.neowarungposs.poskasir.pos.PromoEngine
import com.neowarungposs.poskasir.pos.SyncScheduler
import com.neowarungposs.poskasir.pos.TodayTotals
import com.neowarungposs.poskasir.ui.ErrorText
import com.neowarungposs.poskasir.ui.LocalPalette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

private data class Item(val product: ProductEntity, val price: Long, val promo: Boolean)

/** Cashier screen, Opsi B "Bento" layout (D20). Wide screens get a fixed cart panel, phones a bottom sheet. */
@Composable
fun KasirScreen(repo: PosRepository, gate: Gate, isAdmin: Boolean) {
    val products by repo.dao.productsFlow().collectAsState(initial = emptyList())
    val outlet by repo.outlet.collectAsState()
    val promos by repo.promos.collectAsState()
    val productOutlets by repo.productOutlets.collectAsState()
    val outletId = outlet?.id

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { repo.loadCaches(); while (true) { delay(60_000); now = System.currentTimeMillis() } }

    val activePromos = remember(promos, outletId, now) { PromoEngine.activeNow(promos, outletId, now) }
    val promoAll = activePromos.any { (it.type == "PERCENT" || it.type == "AMOUNT") && it.ids("product_ids").isEmpty() }
    val promoIds = activePromos.flatMap { it.productIds() }.toSet()
    val items = remember(products, productOutlets, outletId, activePromos) {
        products.mapNotNull { p ->
            val (ok, price) = repo.effective(p, outletId)
            if (!ok) null else Item(p, price, promoAll || p.id in promoIds)
        }
    }

    val cart = remember { mutableStateMapOf<String, Int>() }
    val notes = remember { mutableStateMapOf<String, String>() }
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("Semua") }
    var orderType by rememberSaveable { mutableStateOf("DINE_IN") }
    var tableNo by rememberSaveable { mutableStateOf("") }
    var paying by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<Item?>(null) }
    var sheet by remember { mutableStateOf(false) }
    var receipt by remember { mutableStateOf<Pair<OrderEntity, List<OrderItemEntity>>?>(null) }

    val categories = listOf("Semua") + (if (activePromos.isNotEmpty()) listOf("Promo") else emptyList()) +
        items.mapNotNull { it.product.category?.trim()?.ifEmpty { null } }.distinct().sorted()
    val shown = items.filter {
        (query.isBlank() || it.product.name.contains(query, true) || (it.product.category ?: "").contains(query, true)) &&
            when (category) { "Semua" -> true; "Promo" -> it.promo; else -> it.product.category?.trim() == category }
    }
    val lines = items.mapNotNull { i -> cart[i.product.id]?.let { CartLine(i.product, it, i.price, notes[i.product.id]) } }
    val applied = remember(lines, activePromos) {
        PromoEngine.apply(lines.map { PricedLine(it.product.id, it.unitPrice, it.qty) }, activePromos)
    }
    val subtotal = lines.sumOf { it.unitPrice * it.qty }
    val discount = applied.sumOf { it.discount }.coerceAtMost(subtotal)
    val total = subtotal - discount

    val add: (String, Int) -> Unit = { id, d ->
        val q = (cart[id] ?: 0) + d
        if (q <= 0) { cart.remove(id); notes.remove(id) } else cart[id] = q
    }
    val cartState = CartState(lines, applied, subtotal, discount, total, orderType, tableNo, notes, gate.canSell,
        onQty = add, onType = { orderType = it }, onTable = { tableNo = it.take(6) },
        onClear = { cart.clear(); notes.clear() }, onPay = { paying = true; sheet = false })

    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val wide = maxWidth >= 840.dp
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                HeaderCard(repo, outlet, isAdmin, query) { query = it }
                StatsRow(repo, outletId, isAdmin, activePromos)
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    categories.forEach { c ->
                        FilterChip(
                            selected = category == c, onClick = { category = c },
                            label = { Text(c) },
                            leadingIcon = if (c == "Promo") ({ Icon(Icons.Filled.LocalOffer, null, Modifier.size(16.dp)) }) else null,
                        )
                    }
                }
                Box(Modifier.weight(1f)) {
                    if (items.isEmpty()) {
                        Text(
                            if (isAdmin) "Belum ada produk untuk cabang ini. Tambahkan di Kelola > Produk." else "Belum ada produk. Minta pemilik menambahkan menu.",
                            modifier = Modifier.padding(24.dp),
                        )
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(if (wide) 150.dp else 132.dp),
                            contentPadding = PaddingValues(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(shown, key = { it.product.id }) { i ->
                                ProductTile(repo, i, cart[i.product.id] ?: 0, onAdd = { add(i.product.id, 1) }, onLong = { detail = i })
                            }
                        }
                    }
                }
                if (!wide) PhoneCartBar(cartState) { sheet = true }
            }
            if (wide) {
                Surface(
                    Modifier.width(372.dp).fillMaxHeight().padding(12.dp),
                    shape = RoundedCornerShape(22.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 1.dp,
                ) { CartPanel(cartState, Modifier.fillMaxSize()) }
            }
        }
    }

    if (sheet) {
        ModalBottomSheet(onDismissRequest = { sheet = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            CartPanel(cartState, Modifier.fillMaxWidth().heightIn(max = 640.dp))
        }
    }
    detail?.let { d ->
        ProductDetailDialog(repo, d, cart[d.product.id] ?: 0, notes[d.product.id] ?: "", activePromos,
            onDismiss = { detail = null },
            onSave = { qty, note ->
                if (qty <= 0) { cart.remove(d.product.id); notes.remove(d.product.id) }
                else { cart[d.product.id] = qty; if (note.isBlank()) notes.remove(d.product.id) else notes[d.product.id] = note.trim() }
                detail = null
            })
    }
    if (paying) {
        PaymentDialog(
            repo = repo,
            total = total,
            onDismiss = { paying = false },
            onPaid = { method, cash, ctx ->
                val r = repo.checkout(lines, method, cash, applied, orderType, if (orderType == "DINE_IN") tableNo else null)
                cart.clear(); notes.clear(); tableNo = ""
                paying = false
                receipt = r
                SyncScheduler.syncNow(ctx)
            },
        )
    }
    receipt?.let { (o, its) -> InvoiceDialog(repo, o, its) { receipt = null } }
}

private class CartState(
    val lines: List<CartLine>,
    val applied: List<AppliedPromo>,
    val subtotal: Long,
    val discount: Long,
    val total: Long,
    val orderType: String,
    val tableNo: String,
    val notes: Map<String, String>,
    val canSell: Boolean,
    val onQty: (String, Int) -> Unit,
    val onType: (String) -> Unit,
    val onTable: (String) -> Unit,
    val onClear: () -> Unit,
    val onPay: () -> Unit,
)

@Composable
private fun HeaderCard(repo: PosRepository, outlet: Outlet?, isAdmin: Boolean, query: String, onQuery: (String) -> Unit) {
    val palette = LocalPalette.current
    val version by repo.container.branding.version.collectAsState()
    val logo = remember(version) { repo.container.branding.logo() }
    val tenant by repo.tenant.collectAsState()
    val pending by repo.dao.pendingCountFlow().collectAsState(initial = 0)
    var outlets by remember { mutableStateOf<List<Outlet>>(emptyList()) }
    var menu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(outlet?.id) { outlets = repo.cachedOutlets() }

    Surface(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 8.dp),
        shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(palette.hero),
                    contentAlignment = Alignment.Center,
                ) {
                    if (logo != null) AsyncImage(logo, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    else Text((tenant?.businessName ?: "K").take(1).uppercase(), color = palette.onHero, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(tenant?.businessName ?: "Kasir Warung", fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Box {
                        Row(
                            Modifier.clickable(enabled = isAdmin && outlets.size > 1) { menu = true },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(outlet?.name ?: "-", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            if (isAdmin && outlets.size > 1) Icon(Icons.Filled.ArrowDropDown, "Pilih cabang", Modifier.size(18.dp))
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            outlets.forEach { o ->
                                DropdownMenuItem(text = { Text(o.name) }, onClick = { menu = false; scope.launch { repo.setActiveOutlet(o) } })
                            }
                        }
                    }
                }
                val synced = pending == 0
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (synced) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.tertiaryContainer,
                ) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(if (synced) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary))
                        Spacer(Modifier.width(6.dp))
                        Text(if (synced) "Tersinkron" else "$pending antre", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            OutlinedTextField(
                value = query, onValueChange = onQuery, singleLine = true,
                placeholder = { Text("Cari menu atau kategori") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = if (query.isNotEmpty()) ({ IconButton(onClick = { onQuery("") }) { Icon(Icons.Filled.Close, "Hapus") } }) else null,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun StatsRow(repo: PosRepository, outletId: String?, isAdmin: Boolean, activePromos: List<PromoDto>) {
    val palette = LocalPalette.current
    val from = remember { LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }
    val key = outletId ?: ""
    val totals by remember(key) { repo.dao.todayTotals(from, key) }.collectAsState(initial = TodayTotals(0, 0))
    val cost by remember(key) { repo.dao.todayCost(from, key) }.collectAsState(initial = 0.0)
    val top by remember(key) { repo.dao.topToday(from, key) }.collectAsState(initial = null)

    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp).padding(bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StatTile("Omzet hari ini", rupiah(totals.omzet), "${totals.trx} transaksi", palette.hero, palette.onHero, palette.onHeroMuted, 190.dp)
        if (isAdmin) {
            val margin = totals.omzet - cost.toLong()
            val pct = if (totals.omzet > 0) margin * 100 / totals.omzet else 0
            StatTile("Laba kotor", rupiah(margin), "Margin $pct%", MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.onSurface, MaterialTheme.colorScheme.onSurfaceVariant)
        }
        StatTile("Terlaris", top?.name ?: "-", top?.let { "${it.qty.toLong()} terjual" } ?: "Belum ada",
            MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.onSurface, MaterialTheme.colorScheme.onSurfaceVariant)
        StatTile("Promo aktif", "${activePromos.size}", activePromos.firstOrNull()?.name ?: "Tidak ada",
            palette.promo, palette.onPromo, palette.onPromo.copy(alpha = 0.75f))
    }
}

@Composable
private fun StatTile(label: String, value: String, sub: String, bg: Color, fg: Color, muted: Color, width: androidx.compose.ui.unit.Dp = 150.dp) {
    Surface(Modifier.width(width), shape = RoundedCornerShape(18.dp), color = bg, contentColor = fg) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(label, fontSize = 12.sp, color = muted)
            Text(value, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, fontSize = 12.sp, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ProductTile(repo: PosRepository, i: Item, qty: Int, onAdd: () -> Unit, onLong: () -> Unit) {
    val p = i.product
    val palette = LocalPalette.current
    val photo = remember(p.id, p.updatedAt) { repo.localImage(p.id).takeIf { p.imagePath != null && it.exists() } }
    val tile = palette.tiles[(p.name.hashCode() and 0x7fffffff) % palette.tiles.size]
    val selected = qty > 0
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(18.dp)) else Modifier)
            .clip(RoundedCornerShape(18.dp))
            .combinedClickable(onClick = onAdd, onLongClick = onLong),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(8.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(1.25f).clip(RoundedCornerShape(14.dp)).background(tile.first)) {
                if (photo != null) AsyncImage(photo, p.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Text(p.name.take(2).uppercase(), Modifier.align(Alignment.Center), fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = tile.second)
                if (i.promo) {
                    Surface(Modifier.padding(6.dp).align(Alignment.TopStart), shape = RoundedCornerShape(8.dp), color = palette.promo, contentColor = palette.onPromo) {
                        Text("PROMO", Modifier.padding(horizontal = 6.dp, vertical = 2.dp), fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                    }
                }
                if (selected) {
                    Box(
                        Modifier.padding(6.dp).align(Alignment.TopEnd).size(28.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center,
                    ) { Text("$qty", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp) }
                }
            }
            Text(p.name, Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp), fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 14.sp, lineHeight = 17.sp)
            Row(Modifier.padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(rupiah(i.price), Modifier.weight(1f), fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
                if (i.price != p.price) Text(rupiah(p.price), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun PhoneCartBar(s: CartState, onOpen: () -> Unit) {
    val palette = LocalPalette.current
    val count = s.lines.sumOf { it.qty }
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).clickable(enabled = count > 0, onClick = onOpen),
        shape = RoundedCornerShape(20.dp), color = palette.hero, contentColor = palette.onHero,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            if (count > 0) {
                Text(
                    s.lines.take(3).joinToString(" · ") { "${it.qty}× ${it.product.name}" } + if (s.lines.size > 3) " +${s.lines.size - 3}" else "",
                    fontSize = 12.sp, color = palette.onHeroMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.ShoppingCart, null)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (count == 0) "Keranjang kosong" else "$count item · lihat detail", fontSize = 12.sp, color = palette.onHeroMuted)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(rupiah(s.total), fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                        if (s.discount > 0) Text("  hemat ${rupiah(s.discount)}", fontSize = 12.sp, color = palette.onHeroMuted)
                    }
                }
                Button(enabled = count > 0 && s.canSell, onClick = s.onPay) { Text(if (s.canSell) "Bayar" else "Terkunci") }
            }
        }
    }
}

@Composable
private fun CartPanel(s: CartState, modifier: Modifier) {
    val palette = LocalPalette.current
    Column(modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Pesanan", fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, modifier = Modifier.weight(1f))
            if (s.lines.isNotEmpty()) TextButton(onClick = s.onClear) { Text("Kosongkan") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = s.orderType == "DINE_IN", onClick = { s.onType("DINE_IN") }, label = { Text("Makan di sini") })
            FilterChip(selected = s.orderType == "TAKEAWAY", onClick = { s.onType("TAKEAWAY") }, label = { Text("Bungkus") })
        }
        if (s.orderType == "DINE_IN") {
            OutlinedTextField(
                value = s.tableNo, onValueChange = s.onTable, singleLine = true, label = { Text("No. meja (opsional)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        if (s.lines.isEmpty()) {
            Box(Modifier.weight(1f, fill = false).fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                Text("Ketuk menu untuk menambah.\nTahan lama untuk lihat detail & catatan.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            }
        } else {
            LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(s.lines, key = { it.product.id }) { l ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(l.product.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${rupiah(l.unitPrice)} × ${l.qty} = ${rupiah(l.unitPrice * l.qty)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            l.note?.let { Text("• $it", fontSize = 12.sp, color = MaterialTheme.colorScheme.tertiary) }
                        }
                        IconButton(onClick = { s.onQty(l.product.id, -1) }, Modifier.size(34.dp)) { Icon(Icons.Filled.Remove, "Kurangi") }
                        Text("${l.qty}", fontWeight = FontWeight.ExtraBold, modifier = Modifier.widthIn(min = 22.dp))
                        IconButton(onClick = { s.onQty(l.product.id, 1) }, Modifier.size(34.dp)) { Icon(Icons.Filled.Add, "Tambah") }
                    }
                }
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        s.applied.forEach { a ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(6.dp), color = palette.promo, contentColor = palette.onPromo) {
                    Icon(Icons.Filled.LocalOffer, null, Modifier.padding(3.dp).size(14.dp))
                }
                Text(a.name, Modifier.weight(1f).padding(start = 6.dp), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("-${rupiah(a.discount)}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        if (s.discount > 0) {
            Row(Modifier.fillMaxWidth()) { Text("Subtotal", Modifier.weight(1f), fontSize = 13.sp); Text(rupiah(s.subtotal), fontSize = 13.sp) }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Total", Modifier.weight(1f), fontWeight = FontWeight.Bold)
            Text(rupiah(s.total), fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
        }
        Button(
            enabled = s.lines.isNotEmpty() && s.canSell, onClick = s.onPay,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(52.dp), shape = RoundedCornerShape(16.dp),
        ) { Text(if (s.canSell) "Bayar ${rupiah(s.total)}" else "Terkunci", fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun ProductDetailDialog(
    repo: PosRepository, i: Item, qty0: Int, note0: String, promos: List<PromoDto>,
    onDismiss: () -> Unit, onSave: (Int, String) -> Unit,
) {
    val p = i.product
    var qty by remember { mutableStateOf(if (qty0 == 0) 1 else qty0) }
    var note by remember { mutableStateOf(note0) }
    val photo = remember(p.id) { repo.localImage(p.id).takeIf { p.imagePath != null && it.exists() } }
    val related = promos.filter { pr ->
        pr.productIds().contains(p.id) || ((pr.type == "PERCENT" || pr.type == "AMOUNT") && pr.ids("product_ids").isEmpty())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(p.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (photo != null) AsyncImage(photo, p.name, Modifier.fillMaxWidth().aspectRatio(1.6f).clip(RoundedCornerShape(14.dp)), contentScale = ContentScale.Crop)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(rupiah(i.price), fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
                        p.category?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    IconButton(onClick = { qty = (qty - 1).coerceAtLeast(0) }) { Icon(Icons.Filled.Remove, "Kurangi") }
                    Text("$qty", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                    IconButton(onClick = { qty++ }) { Icon(Icons.Filled.Add, "Tambah") }
                }
                related.forEach { pr ->
                    Text("Promo: ${pr.name} · ${pr.describe()}", fontSize = 13.sp, color = MaterialTheme.colorScheme.tertiary)
                }
                OutlinedTextField(
                    value = note, onValueChange = { note = it.take(80) },
                    label = { Text("Catatan (mis. tanpa es, pedas)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { Button(onClick = { onSave(qty, note) }) { Text(if (qty == 0) "Hapus" else "Simpan") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal") } },
    )
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
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var qrisUrl by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val cash = cashText.toLongOrNull()

    LaunchedEffect(method) {
        if (method == "QRIS" && qrisUrl == null) {
            val path = repo.currentOutlet()?.merchantQrisPath
            if (path != null) qrisUrl = runCatching { repo.container.supabase.signedUrl("outlet-assets", path) }.getOrNull()
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
                                model = qrisUrl, contentDescription = "QRIS outlet", contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp),
                            )
                        } else {
                            Text("QRIS outlet belum diunggah (Akun > QRIS Outlet) atau sedang offline. Tunjukkan QRIS fisik Anda.")
                        }
                        Text("Tekan \"Sudah diterima\" setelah notifikasi pembayaran masuk di HP Anda.", fontSize = 13.sp)
                    }
                    else -> Text("Catat pembayaran dengan metode lain.")
                }
                ErrorText(errorMsg)
            }
        },
        confirmButton = {
            Button(
                enabled = !busy && (method != "CASH" || (cash != null && cash >= total)),
                onClick = {
                    busy = true; errorMsg = null
                    scope.launch {
                        try {
                            onPaid(method, if (method == "CASH") cash else null, ctx)
                        } catch (e: Exception) {
                            errorMsg = friendlyError(e); busy = false
                        }
                    }
                },
            ) { Text(if (method == "QRIS") "Sudah diterima" else "Simpan") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Batal") } },
    )
}
