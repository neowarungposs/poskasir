@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.neowarungposs.poskasir.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Store
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neowarungposs.poskasir.AppContainer
import com.neowarungposs.poskasir.core.friendlyError
import com.neowarungposs.poskasir.ui.ErrorText

@Composable
fun AdminHome(container: AppContainer) {
    val repo = remember { AdminRepo(container.supabase) }
    val tabs = listOf(
        "Dashboard" to Icons.Filled.Dashboard,
        "Pembayaran" to Icons.Filled.Payments,
        "Uang Masuk" to Icons.Filled.AccountBalanceWallet,
        "Tenant" to Icons.Filled.Store,
        "Lainnya" to Icons.Filled.MoreHoriz,
    )
    var selected by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Kasir Admin · ${tabs[selected].first}") }) },
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, (label, icon) ->
                    NavigationBarItem(
                        selected = selected == i, onClick = { selected = i },
                        icon = { Icon(icon, label) }, label = { Text(label, fontSize = 11.sp) },
                    )
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (selected) {
                0 -> DashboardScreen(repo) { selected = it }
                1 -> PaymentsScreen(repo)
                2 -> IncomingScreen(repo)
                3 -> TenantsScreen(repo)
                else -> MoreScreen(container, repo)
            }
        }
    }
}

@Composable
private fun DashboardScreen(repo: AdminRepo, go: (Int) -> Unit) {
    var stats by remember { mutableStateOf<List<Triple<String, Int, Int>>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(reload) {
        error = null
        try {
            stats = listOf(
                Triple("Klaim pembayaran pending", repo.count("payment_confirmations", "status=eq.PENDING"), 1),
                Triple("Uang masuk belum dicocokkan", repo.count("incoming_payments", "status=eq.UNMATCHED"), 2),
                Triple("Antrean WA hari ini", repo.count("wa_reminder_queue", "status=eq.PENDING"), 4),
                Triple("Tenant aktif (LIVE)", repo.count("tenants", "lifecycle=eq.LIVE"), 3),
                Triple("Tenant arsip (CHURNED)", repo.count("tenants", "lifecycle=eq.CHURNED"), 3),
            )
        } catch (e: Exception) {
            error = friendlyError(e)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ErrorText(error)
        stats?.forEach { (label, value, tab) ->
            Card(onClick = { go(tab) }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp)) {
                    Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("$value", fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
        }
        Button(onClick = { reload++ }) { Text("Muat ulang") }
    }
}
