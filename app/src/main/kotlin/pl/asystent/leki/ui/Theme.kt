package pl.asystent.leki.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import pl.asystent.leki.core.ThemeMode

/** Paleta aplikacji: jeden akcent (zieleń), bursztyn i czerwień tylko dla stanów. */
data class Palette(
    val bg: Color, val surface: Color, val sunken: Color,
    val ink: Color, val ink2: Color, val ink3: Color, val line: Color,
    val accent: Color, val accentInk: Color, val accentSoft: Color,
    val warn: Color, val warnBg: Color, val warnLine: Color, val danger: Color,
    val dark: Boolean,
)

val LightPalette = Palette(
    bg = Color(0xFFF1F4F2), surface = Color(0xFFFFFFFF), sunken = Color(0xFFE6ECE8),
    ink = Color(0xFF13201B), ink2 = Color(0xFF44564E), ink3 = Color(0xFF5E6F67), line = Color(0xFFD8E0DB),
    accent = Color(0xFF0B6B58), accentInk = Color(0xFFFFFFFF), accentSoft = Color(0xFFDDEEE8),
    warn = Color(0xFF8A5200), warnBg = Color(0xFFFBF0DC), warnLine = Color(0xFFE8CD94), danger = Color(0xFFB3261E), dark = false,
)

val DarkPalette = Palette(
    bg = Color(0xFF0C1311), surface = Color(0xFF141E1A), sunken = Color(0xFF1C2924),
    ink = Color(0xFFE7EFEB), ink2 = Color(0xFFB4C3BB), ink3 = Color(0xFF8FA199), line = Color(0xFF263631),
    accent = Color(0xFF4DC2A5), accentInk = Color(0xFF06201A), accentSoft = Color(0xFF17332B),
    warn = Color(0xFFF0B35A), warnBg = Color(0xFF2A2112), warnLine = Color(0xFF5A4620), danger = Color(0xFFF2867F), dark = true,
)

val LocalPalette = staticCompositionLocalOf { LightPalette }

val pal: Palette @Composable @ReadOnlyComposable get() = LocalPalette.current

val Mono = FontFamily.Monospace

@Composable
fun LekiTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) { ThemeMode.Auto -> isSystemInDarkTheme(); ThemeMode.Light -> false; ThemeMode.Dark -> true }
    val p = if (dark) DarkPalette else LightPalette
    val scheme = if (dark) {
        darkColorScheme(
            primary = p.accent, onPrimary = p.accentInk, primaryContainer = p.accentSoft, onPrimaryContainer = p.ink,
            secondary = p.accent, onSecondary = p.accentInk, secondaryContainer = p.accentSoft, onSecondaryContainer = p.ink,
            background = p.bg, onBackground = p.ink, surface = p.surface, onSurface = p.ink,
            surfaceVariant = p.sunken, onSurfaceVariant = p.ink2, outline = p.line, outlineVariant = p.line,
            error = p.danger, surfaceContainerHigh = p.surface, surfaceContainerHighest = p.sunken, surfaceContainer = p.surface,
            surfaceContainerLow = p.surface, surfaceContainerLowest = p.surface, surfaceTint = p.accent,
        )
    } else {
        lightColorScheme(
            primary = p.accent, onPrimary = p.accentInk, primaryContainer = p.accentSoft, onPrimaryContainer = p.ink,
            secondary = p.accent, onSecondary = p.accentInk, secondaryContainer = p.accentSoft, onSecondaryContainer = p.ink,
            background = p.bg, onBackground = p.ink, surface = p.surface, onSurface = p.ink,
            surfaceVariant = p.sunken, onSurfaceVariant = p.ink2, outline = p.line, outlineVariant = p.line,
            error = p.danger, surfaceContainerHigh = p.surface, surfaceContainerHighest = p.sunken, surfaceContainer = p.surface,
            surfaceContainerLow = p.surface, surfaceContainerLowest = p.surface, surfaceTint = p.accent,
        )
    }
    // bez stałego koloru: tekst bierze kolor z kontenera (LocalContentColor), dzięki czemu np. komunikat na jasnym pasku w ciemnym motywie jest czytelny
    val base = TextStyle(fontSize = 15.sp, lineHeight = 21.sp)
    val typography = Typography(
        bodyLarge = base, bodyMedium = base.copy(fontSize = 14.sp, lineHeight = 20.sp), bodySmall = base.copy(fontSize = 12.5.sp, lineHeight = 17.sp),
        titleLarge = base.copy(fontSize = 20.sp, fontWeight = FontWeight.SemiBold), titleMedium = base.copy(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
        titleSmall = base.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
        labelLarge = base.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold), labelMedium = base.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium),
        labelSmall = base.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium),
    )
    CompositionLocalProvider(LocalPalette provides p, LocalContentColor provides p.ink) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}
