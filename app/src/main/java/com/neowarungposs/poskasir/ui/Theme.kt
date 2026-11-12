package com.neowarungposs.poskasir.ui

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.neowarungposs.poskasir.R
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

/** Selectable app themes (D20). AURORA = "Aurora Glass" premium mockup (default since v1.2.1). */
enum class AppTheme(val label: String, val dark: Boolean) {
    AURORA("Aurora Glass", true),
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
    val heroGradient: List<Color>? = null,  // when set, hero tiles use this gradient + heroBorder
    val heroBorder: Color? = null,
    val glows: List<Color> = emptyList(),   // soft background light spots (Aurora)
    val glass: Boolean = false,             // translucent panels with hairline borders
    val accent2: Color = Color(0xFF6EE7F9),
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

private val auroraPalette = Palette(
    hero = Color(0xFF151D14), onHero = Color(0xFFEEF2F7), onHeroMuted = Color(0xFFB9C3D3),
    promo = Color(0xFF2A1E0D), onPromo = Color(0xFFFFCF85),
    tiles = listOf(
        Color(0xFF1B2A12) to Color(0xFFC8F65A), Color(0xFF10262B) to Color(0xFF6EE7F9),
        Color(0xFF2A1E0D) to Color(0xFFFFB547), Color(0xFF1E1631) to Color(0xFFB794F6),
    ),
    heroGradient = listOf(Color(0x2EC8F65A), Color(0x0F6EE7F9)),
    heroBorder = Color(0x47C8F65A),
    glows = listOf(Color(0xFFC8F65A), Color(0xFF6EE7F9)),
    glass = true,
)

val LocalPalette = staticCompositionLocalOf { auroraPalette }

/** Display face for headings and big numbers (Space Grotesk, bundled at build time). */
@OptIn(ExperimentalTextApi::class)
val Display = FontFamily(
    Font(R.font.space_grotesk, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.space_grotesk, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.space_grotesk, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
    Font(R.font.space_grotesk, FontWeight.ExtraBold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
)

private val typography = Typography().let { t ->
    t.copy(
        displayLarge = t.displayLarge.copy(fontFamily = Display),
        displayMedium = t.displayMedium.copy(fontFamily = Display),
        displaySmall = t.displaySmall.copy(fontFamily = Display),
        headlineLarge = t.headlineLarge.copy(fontFamily = Display),
        headlineMedium = t.headlineMedium.copy(fontFamily = Display),
        headlineSmall = t.headlineSmall.copy(fontFamily = Display),
        titleLarge = t.titleLarge.copy(fontFamily = Display, fontWeight = FontWeight.SemiBold),
    )
}

private val auroraScheme = darkColorScheme(
    primary = Color(0xFFC8F65A), onPrimary = Color(0xFF070A12),
    primaryContainer = Color(0xFF1F2A12), onPrimaryContainer = Color(0xFFE3FF9E),
    secondary = Color(0xFF6EE7F9), onSecondary = Color(0xFF04161A),
    secondaryContainer = Color(0xFF10262B), onSecondaryContainer = Color(0xFFBDF4FC),
    tertiary = Color(0xFFFFB547), onTertiary = Color(0xFF2A1700),
    tertiaryContainer = Color(0xFF2A1E0D), onTertiaryContainer = Color(0xFFFFCF85),
    background = Color(0xFF070A12), onBackground = Color(0xFFEEF2F7),
    surface = Color(0xFF0F141E), onSurface = Color(0xFFEEF2F7),
    surfaceVariant = Color(0xFF161C29), onSurfaceVariant = Color(0xFF8B95A7),
    surfaceContainer = Color(0xFF111723), surfaceContainerHigh = Color(0xFF161C29),
    surfaceContainerHighest = Color(0xFF1B2230), surfaceContainerLow = Color(0xFF0D121B),
    surfaceContainerLowest = Color(0xFF080B13),
    outline = Color(0xFF2A3242), outlineVariant = Color(0xFF1E2533), error = Color(0xFFFF8A80),
)

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
    private val _current = MutableStateFlow(AppTheme.AURORA)
    val current: StateFlow<AppTheme> = _current

    fun load(context: Context) {
        val name = context.getSharedPreferences("ui", Context.MODE_PRIVATE).getString("theme", null)
        _current.value = AppTheme.entries.firstOrNull { it.name == name } ?: AppTheme.AURORA
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
        AppTheme.AURORA -> auroraScheme to auroraPalette
        AppTheme.BENTO -> bentoScheme to bentoPalette
        AppTheme.NEON -> neonScheme to neonPalette
        AppTheme.MIDNIGHT -> midnightScheme to midnightPalette
    }
    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}

/** Screen background with the theme's soft light spots (Aurora). */
@Composable
fun AppBackground(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val palette = LocalPalette.current
    val bg = MaterialTheme.colorScheme.background
    Box(
        modifier
            .fillMaxSize()
            .background(bg)
            .drawBehind {
                if (palette.glows.size >= 2) {
                    val r1 = size.minDimension * 0.75f
                    drawCircle(Brush.radialGradient(listOf(palette.glows[0].copy(alpha = 0.16f), Color.Transparent), center = Offset(size.width * 0.08f, -r1 * 0.15f), radius = r1), radius = r1, center = Offset(size.width * 0.08f, -r1 * 0.15f))
                    val r2 = size.minDimension * 0.7f
                    drawCircle(Brush.radialGradient(listOf(palette.glows[1].copy(alpha = 0.12f), Color.Transparent), center = Offset(size.width * 0.82f, size.height + r2 * 0.1f), radius = r2), radius = r2, center = Offset(size.width * 0.82f, size.height + r2 * 0.1f))
                }
            },
    ) { content() }
}

/** Panel styling: glass (translucent + hairline border) on Aurora, plain surface otherwise. */
@Composable
fun glassColor(): Color = if (LocalPalette.current.glass) MaterialTheme.colorScheme.surface.copy(alpha = 0.72f) else MaterialTheme.colorScheme.surface

@Composable
fun glassBorder(): BorderStroke? = if (LocalPalette.current.glass) BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)) else null

/** Hero brush (gradient on Aurora). */
@Composable
fun heroBrush(): Brush {
    val p = LocalPalette.current
    return p.heroGradient?.let { Brush.linearGradient(it) } ?: SolidColor(p.hero)
}
