@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.neowarungposs.poskasir.pos.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CardMembership
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.PointOfSale
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.neowarungposs.poskasir.AppContainer
import com.neowarungposs.poskasir.core.Claims
import com.neowarungposs.poskasir.pos.Gate
import com.neowarungposs.poskasir.pos.GateState
import com.neowarungposs.poskasir.pos.PosRepository
import com.neowarungposs.poskasir.ui.Amber
import com.neowarungposs.poskasir.ui.AmberSoft
import com.neowarungposs.poskasir.ui.Banner
import com.neowarungposs.poskasir.ui.RedSoft
import com.neowarungposs.poskasir.ui.RedText
import com.neowarungposs.poskasir.ui.AppBackground
import com.neowarungposs.poskasir.ui.LocalPalette
import com.neowarungposs.poskasir.ui.glassBorder
import com.neowarungposs.poskasir.ui.glassColor
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private data class Tab(val label: String, val icon: ImageVector)

@Composable
fun PosHome(container: AppContainer, repo: PosRepository, claims: Claims) {
    val isAdmin = claims.appRole == "TENANT_ADMIN"
    val tabs = buildList {
        add(Tab("Kasir", Icons.Filled.PointOfSale))
        add(Tab("Riwayat", Icons.Filled.ReceiptLong))
        if (isAdmin) add(Tab("Kelola", Icons.Filled.Inventory2))
        add(Tab("Langganan", Icons.Filled.CardMembership))
        add(Tab("Akun", Icons.Filled.AccountCircle))
    }
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val ent by repo.ent.collectAsState()
    var gate by remember { mutableStateOf(repo.gate()) }

    LaunchedEffect(Unit) {
        repo.loadCaches()
        repo.loadEntitlement()
        runCatching { repo.syncAll() }
    }
    // Re-evaluate the gate every 30 s and whenever entitlement changes.
    LaunchedEffect(ent) {
        while (true) {
            gate = repo.gate()
            repo.persistTrustedTime()
            delay(30_000)
        }
    }

    val content: @Composable (Modifier) -> Unit = { m ->
        Column(m.fillMaxSize()) {
            GateBanner(gate)
            when (tabs.getOrNull(selected)?.label) {
                "Kasir" -> KasirScreen(repo, gate, isAdmin)
                "Kelola" -> KelolaScreen(repo)
                "Riwayat" -> RiwayatScreen(repo, isAdmin)
                "Langganan" -> LanggananScreen(container, repo, isAdmin)
                "Akun" -> AkunScreen(container, repo, claims)
            }
        }
    }

    AppBackground {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            if (maxWidth >= 840.dp) {
                // Tablet: slim glass rail on the left (Aurora mockup).
                Row(Modifier.fillMaxSize().systemBarsPadding()) {
                    Surface(
                        modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 12.dp).fillMaxHeight().width(80.dp),
                        shape = RoundedCornerShape(26.dp), color = glassColor(), border = glassBorder(),
                    ) {
                        NavigationRail(containerColor = Color.Transparent, modifier = Modifier.padding(vertical = 8.dp)) {
                            tabs.forEachIndexed { i, t ->
                                NavigationRailItem(
                                    selected = selected == i, onClick = { selected = i },
                                    icon = { Icon(t.icon, contentDescription = t.label) },
                                    label = { Text(t.label, fontSize = 11.sp) },
                                )
                            }
                        }
                    }
                    content(Modifier.weight(1f))
                }
            } else {
                Scaffold(
                    containerColor = Color.Transparent,
                    bottomBar = {
                        NavigationBar(containerColor = if (LocalPalette.current.glass) MaterialTheme.colorScheme.surface.copy(alpha = 0.9f) else NavigationBarDefaults.containerColor) {
                            tabs.forEachIndexed { i, t ->
                                NavigationBarItem(
                                    selected = selected == i,
                                    onClick = { selected = i },
                                    icon = { Icon(t.icon, contentDescription = t.label) },
                                    label = { Text(t.label) },
                                )
                            }
                        }
                    },
                ) { padding -> content(Modifier.padding(padding)) }
            }
        }
    }
}

@Composable
private fun GateBanner(gate: Gate) {
    val m = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
    when (gate.state) {
        GateState.WARNING -> Banner("Langganan berakhir ${gate.daysLeft.coerceAtLeast(0)} hari lagi. Lihat tab Langganan.", AmberSoft, Amber, m)
        GateState.GRACE -> Banner("Masa tenggang: langganan sudah lewat. Kasir masih bisa dipakai sebentar.", RedSoft, RedText, m)
        GateState.EXPIRED -> Banner("Langganan berakhir. Mode baca saja: transaksi baru dikunci.", RedSoft, RedText, m)
        GateState.NEED_ONLINE -> Banner("Sudah terlalu lama offline. Sambungkan internet sekali untuk lanjut berjualan.", RedSoft, RedText, m)
        GateState.UNKNOWN -> Banner("Menunggu status langganan. Sambungkan internet untuk pertama kali.", AmberSoft, Amber, m)
        GateState.OK -> {}
    }
}
