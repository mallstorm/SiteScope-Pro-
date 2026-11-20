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

/** Смысловые цвета, которых нет в Material: состояния, акценты разделов и градиент шапки. */
class Tones(
    val good: Color, val goodSoft: Color, val warn: Color, val warnSoft: Color,
    val bad: Color, val badSoft: Color, val info: Color, val infoSoft: Color,
    val site: Color, val siteSoft: Color, val ip: Color, val ipSoft: Color,
    val tv: Color, val tvSoft: Color, val net: Color, val netSoft: Color,
    val heroStart: Color, val heroEnd: Color,
    val headerStart: Color, val headerEnd: Color, val aqua: Color, val onAqua: Color,
    val tileStart: Color, val tileEnd: Color
)

private val LightTones = Tones(
    good = Color(0xFF1F9D63), goodSoft = Color(0xFFDDF4E6), warn = Color(0xFFB97A00), warnSoft = Color(0xFFFFF0CC),
    bad = Color(0xFFC0392B), badSoft = Color(0xFFFDE3E1), info = Color(0xFF1B4FA0), infoSoft = Color(0xFFE0ECFA),
    site = Color(0xFF0E7C86), siteSoft = Color(0xFFD3F1F3), ip = Color(0xFF2F6FB0), ipSoft = Color(0xFFDCE9F8),
    tv = Color(0xFF5A4FCF), tvSoft = Color(0xFFE6E3FA), net = Color(0xFF1F8F6E), netSoft = Color(0xFFD9F3EA),
    heroStart = Color(0xFF0B4651), heroEnd = Color(0xFF0E7C86),
    headerStart = Color(0xFF06222C), headerEnd = Color(0xFF0B4651), aqua = Color(0xFF22E6D2), onAqua = Color(0xFF04323A),
    tileStart = Color(0xFF0A2A38), tileEnd = Color(0xFF0E6B75)
)
private val DarkTones = Tones(
    good = Color(0xFF6FD3A3), goodSoft = Color(0xFF14382A), warn = Color(0xFFF2B95C), warnSoft = Color(0xFF3F2E10),
    bad = Color(0xFFFF8A7D), badSoft = Color(0xFF451C17), info = Color(0xFF9CC3E6), infoSoft = Color(0xFF1B2D3D),
    site = Color(0xFF6FD6DE), siteSoft = Color(0xFF0F3A3F), ip = Color(0xFF8FB8E8), ipSoft = Color(0xFF1A2E47),
    tv = Color(0xFFB7B0FF), tvSoft = Color(0xFF2A2650), net = Color(0xFF86D9B8), netSoft = Color(0xFF173A2E),
    heroStart = Color(0xFF08323B), heroEnd = Color(0xFF0E6B75),
    headerStart = Color(0xFF031319), headerEnd = Color(0xFF08323B), aqua = Color(0xFF22E6D2), onAqua = Color(0xFF04323A),
    tileStart = Color(0xFF0A2A38), tileEnd = Color(0xFF0E6B75)
)

val LocalTones = staticCompositionLocalOf { LightTones }

private val LightColors = lightColorScheme(
    primary = Color(0xFF0E7C86), onPrimary = Color.White, primaryContainer = Color(0xFFD3F1F3), onPrimaryContainer = Color(0xFF00363B),
    secondary = Color(0xFF4A6572), onSecondary = Color.White, secondaryContainer = Color(0xFFDDEBF2), onSecondaryContainer = Color(0xFF0D2530),
    tertiary = Color(0xFF2F6FB0), onTertiary = Color.White, tertiaryContainer = Color(0xFFDCE9F8), onTertiaryContainer = Color(0xFF0B2A4A),
    background = Color(0xFFEDF3F8), onBackground = Color(0xFF0E2330), surface = Color(0xFFEDF3F8), onSurface = Color(0xFF0E2330),
    surfaceVariant = Color(0xFFDDE7EE), onSurfaceVariant = Color(0xFF51636F), outline = Color(0xFF8FA1AD), outlineVariant = Color(0xFFD3DEE6),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF7FAFC), surfaceContainer = Color(0xFFE8F0F5),
    surfaceContainerHigh = Color(0xFFE0EAF0), surfaceContainerHighest = Color(0xFFD8E3EA),
    error = Color(0xFFC0392B), errorContainer = Color(0xFFFDE3E1), onErrorContainer = Color(0xFF410002)
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFF5FD3DC), onPrimary = Color(0xFF00363B), primaryContainer = Color(0xFF0B4F57), onPrimaryContainer = Color(0xFFD3F1F3),
    secondary = Color(0xFFA9C6D4), onSecondary = Color(0xFF0D2530), secondaryContainer = Color(0xFF2B4350), onSecondaryContainer = Color(0xFFDDEBF2),
    tertiary = Color(0xFF9CC3E6), onTertiary = Color(0xFF0B2A4A), tertiaryContainer = Color(0xFF1F4A78), onTertiaryContainer = Color(0xFFDCE9F8),
    background = Color(0xFF0A161D), onBackground = Color(0xFFE3EDF2), surface = Color(0xFF0A161D), onSurface = Color(0xFFE3EDF2),
    surfaceVariant = Color(0xFF28383F), onSurfaceVariant = Color(0xFFB4C4CC), outline = Color(0xFF7A8C96), outlineVariant = Color(0xFF2E4048),
    surfaceContainerLowest = Color(0xFF07111A), surfaceContainerLow = Color(0xFF0F1E27), surfaceContainer = Color(0xFF132731),
    surfaceContainerHigh = Color(0xFF1B313C), surfaceContainerHighest = Color(0xFF243C48),
    error = Color(0xFFFF8A7D), errorContainer = Color(0xFF5C1A14), onErrorContainer = Color(0xFFFFDAD5)
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp), small = RoundedCornerShape(14.dp), medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(22.dp), extraLarge = RoundedCornerShape(28.dp)
)

private val base = Typography()
/** Размеры подобраны по макетам: подписи не мельче 11 sp, основной текст 12–14 sp. */
private val AppTypography = Typography(
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Bold),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 27.sp),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold, fontSize = 18.sp, lineHeight = 23.sp),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = 17.sp, lineHeight = 22.sp),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 19.sp),
    titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 17.sp),
    bodyLarge = TextStyle(fontSize = 14.sp, lineHeight = 19.sp),
    bodyMedium = TextStyle(fontSize = 13.sp, lineHeight = 17.sp),
    bodySmall = base.bodySmall.copy(fontSize = 11.sp, lineHeight = 15.sp),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    labelMedium = base.labelMedium.copy(fontSize = 11.sp, lineHeight = 15.sp),
    labelSmall = base.labelSmall.copy(fontSize = 11.sp, lineHeight = 14.sp)
)

@Composable
fun AppTheme(dark: Boolean = when (ThemePrefs.mode) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }, content: @Composable () -> Unit) {
    val view = androidx.compose.ui.platform.LocalView.current
    if (!view.isInEditMode) androidx.compose.runtime.SideEffect {
        val window = (view.context as? android.app.Activity)?.window
        // Шапка тёмная в обеих темах, поэтому значки строки состояния всегда светлые.
        if (window != null) androidx.core.view.WindowCompat.getInsetsController(window, view).apply { isAppearanceLightStatusBars = false; isAppearanceLightNavigationBars = !dark }
    }
    // Плотность задаётся в настройках (по умолчанию 0,92): все dp масштабируются, а текст (sp) остаётся прежним, чтобы он не падал ниже 11 sp.
    val base = androidx.compose.ui.platform.LocalDensity.current
    val dense = androidx.compose.ui.unit.Density(base.density * UiPrefs.scale, base.fontScale / UiPrefs.scale)
    CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides dense, LocalTones provides if (dark) DarkTones else LightTones) {
        MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, shapes = AppShapes, typography = AppTypography, content = content)
    }
}

/** Масштаб размеров интерфейса относительно стандартного dp: ползунок в настройках, шаг 0,04. */
object UiPrefs {
    const val MIN = 0.88f
    const val MAX = 1.12f
    const val STEP = 0.04f
    const val DEFAULT = 0.92f
    private const val KEY = "ui_scale"

    var scale by androidx.compose.runtime.mutableFloatStateOf(DEFAULT)
        private set

    private fun snap(v: Float): Float = (Math.round(v.coerceIn(MIN, MAX) / STEP) * STEP).let { Math.round(it * 100f) / 100f }.coerceIn(MIN, MAX)

    fun load(context: android.content.Context) {
        scale = snap(context.getSharedPreferences(pro.sitescope.app.SettingsStore.PREFS, android.content.Context.MODE_PRIVATE).getFloat(KEY, DEFAULT))
    }

    fun set(context: android.content.Context, value: Float) {
        scale = snap(value)
        context.getSharedPreferences(pro.sitescope.app.SettingsStore.PREFS, android.content.Context.MODE_PRIVATE).edit().putFloat(KEY, scale).apply()
    }
}

val tones: Tones @Composable get() = LocalTones.current
