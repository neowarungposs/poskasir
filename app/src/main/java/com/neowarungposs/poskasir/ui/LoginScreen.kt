package com.neowarungposs.poskasir.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.neowarungposs.poskasir.core.SupabaseClient
import com.neowarungposs.poskasir.core.friendlyError
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(
    client: SupabaseClient,
    title: String,
    subtitle: String,
    allowSignUp: Boolean,
    onSignedIn: () -> Unit,
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var signUpMode by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.height(48.dp)) {}
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
        Text(subtitle, style = MaterialTheme.typography.bodyLarge)
        OutlinedTextField(
            value = email, onValueChange = { email = it }, label = { Text("Email") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        )
        OutlinedTextField(
            value = password, onValueChange = { password = it }, label = { Text("Password (min. 6 karakter)") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        ErrorText(error)
        info?.let { Banner(it, GreenSoft, GreenDark) }
        Button(
            enabled = !busy && email.contains("@") && password.length >= 6,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            onClick = {
                busy = true; error = null; info = null
                scope.launch {
                    try {
                        if (signUpMode) {
                            val hasSession = client.signUp(email, password)
                            if (hasSession) onSignedIn()
                            else {
                                info = "Akun dibuat. Buka email Anda untuk konfirmasi, lalu masuk."
                                signUpMode = false
                            }
                        } else {
                            client.signIn(email, password)
                            onSignedIn()
                        }
                    } catch (e: Exception) {
                        error = friendlyError(e)
                    } finally {
                        busy = false
                    }
                }
            },
        ) { Text(if (busy) "Memproses..." else if (signUpMode) "Daftar" else "Masuk") }
        if (allowSignUp) {
            TextButton(onClick = { signUpMode = !signUpMode; error = null }, modifier = Modifier.fillMaxWidth()) {
                Text(if (signUpMode) "Sudah punya akun? Masuk" else "Belum punya akun? Daftar gratis 30 hari")
            }
        }
    }
}
