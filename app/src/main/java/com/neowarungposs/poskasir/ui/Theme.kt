package com.neowarungposs.poskasir.ui

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// Legacy constants kept for screens that use fixed status colors.
val Green = Color(0xFF0E7A4F)
val GreenDark = Color(0xFF0E3B2A)
val GreenSoft = Color(0xFFE3F3EA)
val Amber = Color(0xFF7A4F00)
val AmberSoft = Color(0xFFFFF4DB)
val RedText = Color(0xFFB3261E)
val RedSoft = Color(0xFFFDE7E7)

/** Selectable app themes (D20). BENTO = mockup Opsi B (default). */
enum class AppTheme(val label: String, val dark: Boolean) {
    BENTO("Bento Terang", false),
    NEON("Neon Glass", true),
    MIDNIGHT("Midnight Pro", true),
}

/** Extra colors not covered by Material roles. */
data class Palette(
    val hero: Color,        // big stats tile background
    val onHero: Color,
    val onHeroMuted: Color,
    val promo: Color,       // promo badge / card background
    val onPromo: Color,
    val tiles: List<Pair<Color, Color>>, // product placeholder tile (bg, ink)
)

private val bentoPalette = Palette(
    hero = Color(0xFF0E3B2A), onHero = Color.White, onHeroMuted = Color(0xFFBFE3CF),
    promo = Color(0xFFFFF1CC), onPromo = Color(0xFF4A3300),
    tiles = listOf(
        Color(0xFFFDE8D7) to Color(0xFF9A3F0A), Color(0xFFE3EEFB) to Color(0xFF1F4E9A),
        Color(0xFFFFF1CC) to Color(0xFF6B4A00), Color(0xFFF3E8FB) to Color(0xFF6A2E9A), Color(0xFFE3F3EA) to Color(0xFF0A5C3B),
    ),
)
private val neonPalette = Palette(
    hero = Color(0xFF11171E), onHero = Color(0xFFEEF2F5), onHeroMuted = Color(0xFF9AA6B2),
    promo = Color(0xFF2A3410), onPromo = Color(0xFFC6F24E),
    tiles = listOf(Color(0xFF141C24) to Color(0xFFC6F24E)),
)
private val midnightPalette = Palette(
    hero = Color(0xFF13203A), onHero = Color(0xFFE6EDF7), onHeroMuted = Color(0xFF93A3BA),
    promo = Color(0xFF3A2614), onPromo = Color(0xFFFDBA74),
    tiles = listOf(Color(0xFF16223A) to Color(0xFF7DD3FC)),
)

val LocalPalette = staticCompositionLocalOf { bentoPalette }

private val bentoScheme = lightColorScheme(
    primary = Color(0xFF0E7A4F), onPrimary = Color.White,
    primaryContainer = Color(0xFFE3F3EA), onPrimaryContainer = Color(0xFF0A3D28),
    secondary = Color(0xFF15201A), onSecondary = Color.White,
    tertiary = Color(0xFFF2A900), onTertiary = Color(0xFF2B1F00),
    background = Color(0xFFEEF0EA), onBackground = Color(0xFF15201A),
    surface = Color.White, onSurface = Color(0xFF15201A),
    surfaceVariant = Color(0xFFF3F5F0), onSurfaceVariant = Color(0xFF4A564F),
    outline = Color(0xFFDDE2D8), error = Color(0xFFB3261E),
)
private val neonScheme = darkColorScheme(
    primary = Color(0xFFC6F24E), onPrimary = Color(0xFF090D12),
    primaryContainer = Color(0xFF26310F), onPrimaryContainer = Color(0xFFE2FF8F),
    secondary = Color(0xFF222C36), onSecondary = Color(0xFFEEF2F5),
    tertiary = Color(0xFFFDBA74), onTertiary = Color(0xFF2B1600),
    background = Color(0xFF090D12), onBackground = Color(0xFFEEF2F5),
    surface = Color(0xFF10161D), onSurface = Color(0xFFEEF2F5),
    surfaceVariant = Color(0xFF141C24), onSurfaceVariant = Color(0xFF9AA6B2),
    outline = Color(0xFF222C36), error = Color(0xFFFF8A80),
)
private val midnightScheme = darkColorScheme(
    primary = Color(0xFF7DD3FC), onPrimary = Color(0xFF0B1220),
    primaryContainer = Color(0xFF13304A), onPrimaryContainer = Color(0xFFBAE6FD),
    secondary = Color(0xFF15223A), onSecondary = Color(0xFFE6EDF7),
    tertiary = Color(0xFFFDBA74), onTertiary = Color(0xFF2B1600),
    background = Color(0xFF0B1220), onBackground = Color(0xFFE6EDF7),
    surface = Color(0xFF111B2E), onSurface = Color(0xFFE6EDF7),
    surfaceVariant = Color(0xFF16223A), onSurfaceVariant = Color(0xFF93A3BA),
    outline = Color(0xFF22304A), error = Color(0xFFFCA5A5),
)

/** Current theme, persisted locally; tenant theme from the server overrides it on sync. */
object ThemeState {
    private val _current = MutableStateFlow(AppTheme.BENTO)
    val current: StateFlow<AppTheme> = _current

    fun load(context: Context) {
        val name = context.getSharedPreferences("ui", Context.MODE_PRIVATE).getString("theme", null)
        _current.value = AppTheme.entries.firstOrNull { it.name == name } ?: AppTheme.BENTO
    }

    fun set(context: Context, theme: AppTheme) {
        context.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putString("theme", theme.name).apply()
        _current.value = theme
    }
}

@Composable
fun PosTheme(content: @Composable () -> Unit) {
    val theme by ThemeState.current.collectAsState()
    val (scheme, palette) = when (theme) {
        AppTheme.BENTO -> bentoScheme to bentoPalette
        AppTheme.NEON -> neonScheme to neonPalette
        AppTheme.MIDNIGHT -> midnightScheme to midnightPalette
    }
    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
