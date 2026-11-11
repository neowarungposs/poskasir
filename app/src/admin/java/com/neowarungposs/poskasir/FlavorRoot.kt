package com.neowarungposs.poskasir

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.neowarungposs.poskasir.admin.AdminHome
import com.neowarungposs.poskasir.core.FcmRegistrar
import com.neowarungposs.poskasir.ui.Loading
import com.neowarungposs.poskasir.ui.LoginScreen
import kotlinx.coroutines.launch

@Composable
fun FlavorRoot(container: AppContainer, activity: FragmentActivity) {
    val sm = container.session
    val context = LocalContext.current
    LaunchedEffect(Unit) { sm.load() }
    val loaded by sm.loaded.collectAsState()
    val session by sm.state.collectAsState()
    var unlocked by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    if (!loaded) {
        Loading(); return
    }
    val current = session
    if (current == null) {
        LoginScreen(
            client = container.supabase,
            title = "Kasir Admin",
            subtitle = "Khusus SUPER_ADMIN. Jangan bagikan aplikasi ini.",
            allowSignUp = false,
            onSignedIn = { unlocked = true },
        )
        return
    }

    val claims = remember(current.accessToken) { sm.claims() }
    if (claims?.appRole != "SUPER_ADMIN") {
        Column(
            Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Akses ditolak", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                if (claims?.appRole == null) "Role belum terbaca dari token. Pastikan JWT hook Supabase aktif dan akun ini sudah diberi role SUPER_ADMIN, lalu masuk lagi."
                else "Akun ini bukan SUPER_ADMIN."
            )
            Button(onClick = { scope.launch { container.supabase.signOut() } }) { Text("Keluar") }
        }
        return
    }

    if (!unlocked) {
        LaunchedEffect(current.userId) {
            promptUnlock(activity) { unlocked = true }
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Terkunci", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Verifikasi sidik jari / kunci layar untuk membuka Kasir Admin.")
            Button(onClick = { promptUnlock(activity) { unlocked = true } }) { Text("Buka kunci") }
        }
        return
    }

    LaunchedEffect(current.userId) { runCatching { FcmRegistrar.register(container.supabase, context) } }
    AdminHome(container)
}

private fun promptUnlock(activity: FragmentActivity, onSuccess: () -> Unit) {
    val authenticators = BIOMETRIC_WEAK or DEVICE_CREDENTIAL
    if (BiometricManager.from(activity).canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
        onSuccess() // no screen lock configured on this phone
        return
    }
    val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
        },
    )
    prompt.authenticate(
        BiometricPrompt.PromptInfo.Builder()
            .setTitle("Buka Kasir Admin")
            .setAllowedAuthenticators(authenticators)
            .build()
    )
}
