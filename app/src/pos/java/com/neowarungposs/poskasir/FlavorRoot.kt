package com.neowarungposs.poskasir

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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.neowarungposs.poskasir.core.FcmRegistrar
import com.neowarungposs.poskasir.pos.PosModule
import com.neowarungposs.poskasir.pos.SyncScheduler
import com.neowarungposs.poskasir.pos.ui.OnboardingScreen
import com.neowarungposs.poskasir.pos.ui.PosHome
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

    if (!loaded) {
        Loading(); return
    }
    val current = session
    if (current == null) {
        LoginScreen(
            client = container.supabase,
            title = "Kasir Warung",
            subtitle = "Masuk untuk mulai berjualan. Data tersimpan aman dan tetap jalan saat offline.",
            allowSignUp = true,
            onSignedIn = {},
            branding = container.branding,
        )
        return
    }

    LaunchedEffect(current.userId) {
        runCatching { FcmRegistrar.register(container.supabase, context) }
        SyncScheduler.syncNow(context)
    }

    val claims = remember(current.accessToken) { sm.claims() }
    when (claims?.appRole) {
        null -> OnboardingScreen(container)
        "SUPER_ADMIN" -> AdminAccountNotice(container)
        else -> PosHome(container, PosModule.repo(context), claims)
    }
}

@Composable
private fun AdminAccountNotice(container: AppContainer) {
    val scope = rememberCoroutineScope()
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Akun SUPER_ADMIN", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Akun ini khusus admin sistem. Gunakan aplikasi Kasir Admin, atau keluar lalu masuk dengan akun warung.")
        Button(onClick = { scope.launch { container.supabase.signOut() } }) { Text("Keluar") }
    }
}
