@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.neowarungposs.poskasir.pos.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.neowarungposs.poskasir.AppContainer
import com.neowarungposs.poskasir.core.ApiException
import com.neowarungposs.poskasir.core.friendlyError
import com.neowarungposs.poskasir.ui.ErrorText
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Composable
fun OnboardingScreen(container: AppContainer) {
    var business by remember { mutableStateOf("") }
    var outlet by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var waOptIn by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val client = container.supabase

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.height(32.dp)) {}
        Text("Buat POS Anda", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
        Text("Gratis 30 hari. Tidak perlu kartu kredit.")
        OutlinedTextField(business, { business = it }, label = { Text("Nama usaha *") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(outlet, { outlet = it }, label = { Text("Nama outlet utama (opsional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            phone, { phone = it.filter { c -> c.isDigit() } }, label = { Text("No. WhatsApp (contoh 6281234567890)") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = waOptIn, onCheckedChange = { waOptIn = it })
            Text("Saya bersedia menerima pengingat langganan lewat WhatsApp")
        }
        ErrorText(error)
        Button(
            enabled = !busy && business.trim().length >= 2,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            onClick = {
                busy = true; error = null
                scope.launch {
                    try {
                        try {
                            client.rpc("register_new_tenant", buildJsonObject {
                                put("p_business_name", business.trim())
                                put("p_outlet_name", outlet.trim().ifEmpty { null })
                                put("p_phone_wa", phone.ifEmpty { null })
                                put("p_wa_opt_in", waOptIn && phone.isNotEmpty())
                            })
                        } catch (e: ApiException) {
                            if (e.code != "ALREADY_REGISTERED") throw e
                        }
                        client.refreshSession()
                        if (client.session.claims()?.appRole == null) {
                            error = "Usaha sudah terdaftar, tetapi role belum terbaca. Pastikan JWT hook Supabase aktif, lalu keluar dan masuk lagi."
                        }
                    } catch (e: Exception) {
                        error = friendlyError(e)
                    } finally {
                        busy = false
                    }
                }
            },
        ) { Text(if (busy) "Membuat..." else "Buat POS Saya") }
        TextButton(onClick = { scope.launch { client.signOut() } }) { Text("Keluar") }
    }
}
