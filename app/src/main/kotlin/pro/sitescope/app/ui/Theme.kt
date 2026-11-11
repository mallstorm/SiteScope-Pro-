package pro.sitescope.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Смысловые цвета, которых нет в Material: состояния и акценты разделов. */
class Tones(
    val good: Color, val goodSoft: Color, val warn: Color, val warnSoft: Color,
    val bad: Color, val badSoft: Color, val info: Color, val infoSoft: Color,
    val site: Color, val siteSoft: Color, val ip: Color, val ipSoft: Color,
    val tv: Color, val tvSoft: Color, val net: Color, val netSoft: Color,
    val heroStart: Color, val heroEnd: Color
)

private val LightTones = Tones(
    good = Color(0xFF2E9E6B), goodSoft = Color(0xFFDDF4E8), warn = Color(0xFFD98A1F), warnSoft = Color(0xFFFFEFD3),
    bad = Color(0xFFD9483B), badSoft = Color(0xFFFFE2DE), info = Color(0xFF5B8DB8), infoSoft = Color(0xFFE2EEF8),
    site = Color(0xFFD9532B), siteSoft = Color(0xFFFFE3D6), ip = Color(0xFF1F8A8A), ipSoft = Color(0xFFD8F1EF),
    tv = Color(0xFF7A5AF5), tvSoft = Color(0xFFE9E2FF), net = Color(0xFF4C8C3F), netSoft = Color(0xFFE2F2DC),
    heroStart = Color(0xFFE8663C), heroEnd = Color(0xFFF29E5C)
)
private val DarkTones = Tones(
    good = Color(0xFF6FD3A3), goodSoft = Color(0xFF173A2A), warn = Color(0xFFF2B95C), warnSoft = Color(0xFF3F2E10),
    bad = Color(0xFFFF8A7D), badSoft = Color(0xFF451C17), info = Color(0xFF9CC3E6), infoSoft = Color(0xFF1B2D3D),
    site = Color(0xFFFFB59C), siteSoft = Color(0xFF4A2416), ip = Color(0xFF7FD6D2), ipSoft = Color(0xFF123634),
    tv = Color(0xFFC3B3FF), tvSoft = Color(0xFF2C2350), net = Color(0xFFA6D79A), netSoft = Color(0xFF1F3519),
    heroStart = Color(0xFF8A3A1E), heroEnd = Color(0xFFA85F2B)
)

val LocalTones = staticCompositionLocalOf { LightTones }

private val LightColors = lightColorScheme(
    primary = Color(0xFFD9532B), onPrimary = Color.White, primaryContainer = Color(0xFFFFDBCF), onPrimaryContainer = Color(0xFF3A0E00),
    secondary = Color(0xFF6B5B95), onSecondary = Color.White, secondaryContainer = Color(0xFFEBE3FF), onSecondaryContainer = Color(0xFF241848),
    tertiary = Color(0xFF1F8A8A), onTertiary = Color.White, tertiaryContainer = Color(0xFFD8F1EF), onTertiaryContainer = Color(0xFF00201F),
    background = Color(0xFFFBF7F2), onBackground = Color(0xFF2B2622), surface = Color(0xFFFBF7F2), onSurface = Color(0xFF2B2622),
    surfaceVariant = Color(0xFFF1E8DE), onSurfaceVariant = Color(0xFF6E6359), outline = Color(0xFFB9AC9F), outlineVariant = Color(0xFFE6DACD),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFFFCF9), surfaceContainer = Color(0xFFF6EFE7),
    surfaceContainerHigh = Color(0xFFF1E8DE), surfaceContainerHighest = Color(0xFFEBE1D6),
    error = Color(0xFFD9483B), errorContainer = Color(0xFFFFE2DE), onErrorContainer = Color(0xFF410002)
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB59C), onPrimary = Color(0xFF5C1A00), primaryContainer = Color(0xFF7F2C0E), onPrimaryContainer = Color(0xFFFFDBCF),
    secondary = Color(0xFFCFC2F2), onSecondary = Color(0xFF362B5E), secondaryContainer = Color(0xFF4D4276), onSecondaryContainer = Color(0xFFEBE3FF),
    tertiary = Color(0xFF7FD6D2), onTertiary = Color(0xFF003735), tertiaryContainer = Color(0xFF004F4D), onTertiaryContainer = Color(0xFFD8F1EF),
    background = Color(0xFF17130F), onBackground = Color(0xFFEDE3D9), surface = Color(0xFF17130F), onSurface = Color(0xFFEDE3D9),
    surfaceVariant = Color(0xFF3A322B), onSurfaceVariant = Color(0xFFCDBFB2), outline = Color(0xFF93877B), outlineVariant = Color(0xFF473E36),
    surfaceContainerLowest = Color(0xFF110E0B), surfaceContainerLow = Color(0xFF201B16), surfaceContainer = Color(0xFF25201A),
    surfaceContainerHigh = Color(0xFF302A23), surfaceContainerHighest = Color(0xFF3B342D),
    error = Color(0xFFFF8A7D), errorContainer = Color(0xFF5C1A14), onErrorContainer = Color(0xFFFFDAD5)
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp), small = RoundedCornerShape(14.dp), medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(26.dp), extraLarge = RoundedCornerShape(32.dp)
)

private val base = Typography()
private val AppTypography = Typography(
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Bold),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold)
)

@Composable
fun AppTheme(dark: Boolean = when (ThemePrefs.mode) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }, content: @Composable () -> Unit) {
    val view = androidx.compose.ui.platform.LocalView.current
    if (!view.isInEditMode) androidx.compose.runtime.SideEffect {
        val window = (view.context as? android.app.Activity)?.window
        if (window != null) androidx.core.view.WindowCompat.getInsetsController(window, view).apply { isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark }
    }
    CompositionLocalProvider(LocalTones provides if (dark) DarkTones else LightTones) {
        MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, shapes = AppShapes, typography = AppTypography, content = content)
    }
}

val tones: Tones @Composable get() = LocalTones.current
