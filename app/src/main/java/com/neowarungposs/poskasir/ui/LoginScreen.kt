package com.neowarungposs.poskasir.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import com.neowarungposs.poskasir.core.BrandingStore
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.CardDefaults
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor

@Composable
fun LoginScreen(
    client: SupabaseClient,
    title: String,
    subtitle: String,
    allowSignUp: Boolean,
    onSignedIn: () -> Unit,
    branding: BrandingStore? = null,
) {
    val brandFlow: kotlinx.coroutines.flow.StateFlow<Long> =
        remember(branding) { branding?.version ?: kotlinx.coroutines.flow.MutableStateFlow(0L) }
    val brandVersion by brandFlow.collectAsState()
    val logo = remember(brandVersion) { branding?.logo() }
    val background = remember(brandVersion) { branding?.background() }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var signUpMode by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val glass = LocalPalette.current.glass
    val bgColor = MaterialTheme.colorScheme.background
    AppBackground {
    if (background != null) {
        AsyncImage(model = background, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(
            Modifier.fillMaxSize().background(
                if (glass) Brush.verticalGradient(0.25f to Color.Transparent, 0.62f to bgColor.copy(alpha = 0.85f), 0.8f to bgColor)
                else SolidColor(Color.Black.copy(alpha = 0.45f)),
            ),
        )
    }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
    if (glass) Spacer(Modifier.height(if (background != null) 200.dp else 80.dp))
    Card(
        modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth().padding(top = 32.dp),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = glassColor()),
        border = glassBorder(),
    ) {
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (logo != null) {
            AsyncImage(model = logo, contentDescription = "Logo", contentScale = ContentScale.Crop,
                modifier = Modifier.size(84.dp)
                    .then(if (glass) Modifier.shadow(20.dp, RoundedCornerShape(26.dp), spotColor = MaterialTheme.colorScheme.primary, ambientColor = MaterialTheme.colorScheme.primary) else Modifier)
                    .clip(RoundedCornerShape(26.dp)))
        }
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
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
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
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
    }
    }
}
