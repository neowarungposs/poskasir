@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.neowarungposs.poskasir.pos.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neowarungposs.poskasir.AppContainer
import com.neowarungposs.poskasir.core.AppJson
import com.neowarungposs.poskasir.core.Plan
import com.neowarungposs.poskasir.core.SystemSettings
import com.neowarungposs.poskasir.core.rupiah

private val Ink = Color(0xFF101418)
private val Surface2 = Color(0xFF1A2027)
private val Lime = Color(0xFFC6F24E)
private val Muted = Color(0xFFA9B3BC)

/** Direct (sideload) build only: plan cards in the "Opsi B — Hitung Hemat" style (D13). */
@Composable
fun PricingSection(container: AppContainer, payCode: Int) {
    var plans by remember { mutableStateOf<List<Plan>>(emptyList()) }
    var settings by remember { mutableStateOf<SystemSettings?>(null) }
    var selected by remember { mutableStateOf<Plan?>(null) }
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        runCatching {
            plans = AppJson.decodeFromString<List<Plan>>(container.supabase.get("plans?select=*&is_active=eq.true&order=months.desc"))
            settings = AppJson.decodeFromString<List<SystemSettings>>(container.supabase.get("system_settings?select=*")).firstOrNull()
            selected = plans.firstOrNull()
        }
    }
    if (plans.isEmpty()) return
    val monthly = settings?.monthlyBasePrice ?: plans.minByOrNull { it.months }?.price ?: 0L

    Column(
        Modifier
            .fillMaxWidth()
            .background(Ink, RoundedCornerShape(20.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Makin lama,", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text("makin hemat.", color = Lime, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        plans.forEach { p ->
            val isSel = selected?.id == p.id
            val save = p.normalPrice - p.price
            Card(
                onClick = { selected = p },
                colors = CardDefaults.cardColors(containerColor = if (isSel) Lime else Surface2),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${p.months} bulan", fontWeight = FontWeight.Bold, color = if (isSel) Ink else Color.White, modifier = Modifier.weight(1f))
                        p.badge?.let { Text(it, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = if (isSel) Ink else Lime) }
                    }
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(rupiah(p.price), fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = if (isSel) Ink else Color.White)
                        if (save > 0) Text(rupiah(p.normalPrice), textDecoration = TextDecoration.LineThrough, color = if (isSel) Ink else Muted)
                    }
                    Text("≈ ${rupiah(p.price / p.months)} / bulan", color = if (isSel) Ink else Muted, fontSize = 13.sp)
                    if (save > 0) {
                        val free = if (monthly > 0) save / monthly else 0L
                        Text(
                            "Hemat ${rupiah(save)}" + if (free >= 1) " · setara $free bulan gratis" else "",
                            fontWeight = FontWeight.Bold, color = if (isSel) Ink else Lime, fontSize = 13.sp,
                        )
                    }
                }
            }
        }
        selected?.let { p ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Lime, RoundedCornerShape(16.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("TRANSFER PERSIS", color = Ink, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text(rupiah(p.price + payCode), color = Ink, fontWeight = FontWeight.ExtraBold, fontSize = 30.sp)
                Text("3 digit terakhir (${payCode.toString().padStart(3, '0')}) adalah kode unik Anda. Transfer sekali saja.", color = Ink, fontSize = 13.sp)
            }
        }
        settings?.bankDetailsText?.let { Text(it, color = Color.White, fontSize = 14.sp) }
        settings?.paymentPageUrl?.let { url ->
            Button(
                onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Buka halaman pembayaran") }
        }
        Text("Aktivasi maks. 1×24 jam hari kerja setelah dana diterima.", color = Muted, fontSize = 12.sp, style = MaterialTheme.typography.bodySmall)
    }
}
