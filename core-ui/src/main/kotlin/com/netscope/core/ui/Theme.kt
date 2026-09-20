package com.netscope.core.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Dark-first palette.
 *
 * The instrument look comes from a near-black surface with a cool accent, so that the
 * semantic status colours are the brightest thing on screen.
 */
private val NetScopeDarkColors = darkColorScheme(
    primary = Color(0xFF5CC8FF),
    onPrimary = Color(0xFF00344A),
    primaryContainer = Color(0xFF004B69),
    onPrimaryContainer = Color(0xFFC4E7FF),
    secondary = Color(0xFF7FD1AE),
    onSecondary = Color(0xFF00382A),
    secondaryContainer = Color(0xFF00513D),
    onSecondaryContainer = Color(0xFF9BEDC9),
    tertiary = Color(0xFFD0BCFF),
    background = Color(0xFF0B0F13),
    onBackground = Color(0xFFE2E6EA),
    surface = Color(0xFF0B0F13),
    onSurface = Color(0xFFE2E6EA),
    surfaceVariant = Color(0xFF1A2027),
    onSurfaceVariant = Color(0xFFBFC7D0),
    surfaceContainer = Color(0xFF151A20),
    surfaceContainerHigh = Color(0xFF1C222A),
    surfaceContainerHighest = Color(0xFF232A33),
    outline = Color(0xFF5A6472),
    outlineVariant = Color(0xFF333B45),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val NetScopeLightColors = lightColorScheme(
    primary = Color(0xFF00658E),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFC4E7FF),
    onPrimaryContainer = Color(0xFF001E2C),
    secondary = Color(0xFF006B52),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFF9BEDC9),
    onSecondaryContainer = Color(0xFF002117),
    background = Color(0xFFFBFCFE),
    onBackground = Color(0xFF191C1E),
    surface = Color(0xFFFBFCFE),
    onSurface = Color(0xFF191C1E),
    surfaceVariant = Color(0xFFDCE3E9),
    onSurfaceVariant = Color(0xFF40484D),
    outline = Color(0xFF70787D),
    outlineVariant = Color(0xFFC0C8CD),
)

/**
 * Semantic status colours.
 *
 * These are always paired with a label or icon in the UI: colour alone excludes anyone
 * with a colour vision deficiency, and an engineer reading a diagnostic must never have
 * to guess what a green dot meant.
 */
data class StatusColors(
    val reachable: Color,
    val onReachable: Color,
    val warning: Color,
    val onWarning: Color,
    val failed: Color,
    val onFailed: Color,
    val informational: Color,
    val onInformational: Color,
    val unknown: Color,
    val onUnknown: Color,
)

private val DarkStatusColors = StatusColors(
    reachable = Color(0xFF1B5E3F), onReachable = Color(0xFF8FF3C2),
    warning = Color(0xFF5C4813), onWarning = Color(0xFFFFD98A),
    failed = Color(0xFF6B1F23), onFailed = Color(0xFFFFB4AB),
    informational = Color(0xFF14415C), onInformational = Color(0xFF9EDBFF),
    unknown = Color(0xFF2A313A), onUnknown = Color(0xFFB4BDC7),
)

private val LightStatusColors = StatusColors(
    reachable = Color(0xFFCDF0DD), onReachable = Color(0xFF0B3D27),
    warning = Color(0xFFFFEBC2), onWarning = Color(0xFF503A00),
    failed = Color(0xFFFFDAD6), onFailed = Color(0xFF7A1B1F),
    informational = Color(0xFFCDE9FA), onInformational = Color(0xFF00344A),
    unknown = Color(0xFFE3E8ED), onUnknown = Color(0xFF3B434B),
)

val LocalStatusColors = staticCompositionLocalOf { DarkStatusColors }

/**
 * Typography tuned for dense technical content.
 *
 * Addresses and other fixed-width data use [MonoTextStyle] so columns of IPs line up
 * and a 1 can never be mistaken for an l.
 */
private val NetScopeTypography = Typography(
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 15.sp),
)

val MonoTextStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp)
val MonoSmallTextStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)

@Composable
fun NetScopeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) NetScopeDarkColors else NetScopeLightColors
    val statusColors = if (darkTheme) DarkStatusColors else LightStatusColors
    CompositionLocalProvider(LocalStatusColors provides statusColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = NetScopeTypography,
            content = content,
        )
    }
}
