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

/** Paleta aplikacji: jeden akcent (zieleń szałwiowa), bursztyn i czerwień wyłącznie dla stanów wymagających uwagi. */
data class Palette(
    val bg: Color, val surface: Color, val sunken: Color,
    val ink: Color, val ink2: Color, val ink3: Color, val line: Color,
    val accent: Color, val accentInk: Color, val accentSoft: Color,
    val warn: Color, val warnBg: Color, val warnLine: Color, val danger: Color,
    val dark: Boolean,
    val dangerBg: Color = if (dark) Color(0xFF361A18) else Color(0xFFFCEAE8),
    val dangerLine: Color = if (dark) Color(0xFF692B27) else Color(0xFFF2B8B5),
)

val LightPalette = Palette(
    bg = Color(0xFFF3F6F4), surface = Color(0xFFFFFFFF), sunken = Color(0xFFE7EEEA),
    ink = Color(0xFF111E19), ink2 = Color(0xFF3E524A), ink3 = Color(0xFF5A6D65), line = Color(0xFFDCE5DF),
    accent = Color(0xFF0B6E59), accentInk = Color(0xFFFFFFFF), accentSoft = Color(0xFFDAEEE6),
    warn = Color(0xFF8A5200), warnBg = Color(0xFFFDF2DF), warnLine = Color(0xFFEBD09B), danger = Color(0xFFB3261E), dark = false,
)

val DarkPalette = Palette(
    bg = Color(0xFF0B1210), surface = Color(0xFF131E1A), sunken = Color(0xFF1B2924),
    ink = Color(0xFFE8F0EC), ink2 = Color(0xFFB5C5BD), ink3 = Color(0xFF8EA199), line = Color(0xFF23332E),
    accent = Color(0xFF4CC9AB), accentInk = Color(0xFF05211A), accentSoft = Color(0xFF16352D),
    warn = Color(0xFFF3B65E), warnBg = Color(0xFF2B2112), warnLine = Color(0xFF5C4621), danger = Color(0xFFF48982), dark = true,
)

val LocalPalette = staticCompositionLocalOf { LightPalette }

val pal: Palette @Composable @ReadOnlyComposable get() = LocalPalette.current

val Mono = FontFamily.Monospace

/** Cyfry o równej szerokości w kroju bezszeryfowym — czytelne wartości pomiarów, dawek i godzin bez surowego wyglądu maszynowego. */
const val TNUM = "tnum"

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
