// SPDX-License-Identifier: GPL-3.0-or-later
package dev.may.taskmanager.ui.theme

import android.app.WallpaperManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7FD1E0),
    onPrimary = Color(0xFF00363F),
    primaryContainer = Color(0xFF004F5B),
    onPrimaryContainer = Color(0xFF9EEFFE),
    background = Color(0xFF0E1318),
    surface = Color(0xFF1B2733),
    onSurface = Color(0xFFE3E8EC),
    surfaceVariant = Color(0xFF22303E),
    onSurfaceVariant = Color(0xFFB0BCC7),
    surfaceContainer = Color(0xFF1F2C39),
    surfaceContainerHigh = Color(0xFF263544),
    surfaceContainerHighest = Color(0xFF2C3E4F),
    surfaceContainerLow = Color(0xFF18232F),
    surfaceContainerLowest = Color(0xFF121B24),
    outline = Color(0xFF6F8191),
    outlineVariant = Color(0xFF384755),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF10707F),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFB8EAFF),
    onPrimaryContainer = Color(0xFF001F25),
    background = Color(0xFFF3F6F8),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1B2733),
    surfaceVariant = Color(0xFFDBE4E8),
    onSurfaceVariant = Color(0xFF404A50),
    surfaceContainer = Color(0xFFE8EEF1),
    surfaceContainerHigh = Color(0xFFE1E8EC),
    surfaceContainerHighest = Color(0xFFDBE2E6),
    surfaceContainerLow = Color(0xFFEFF4F7),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    outline = Color(0xFF707B82),
    outlineVariant = Color(0xFFC0CCD2),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

val LocalGlassThemeActive = compositionLocalOf { false }
val LocalActiveThemeId = compositionLocalOf { "system_dynamic" }

@Composable
fun MayTheme(
    activeThemeId: String = "system_dynamic",
    content: @Composable () -> Unit,
) {
    val isGlassThemeActive = activeThemeId == "glass"
    val darkTheme = when (activeThemeId) {
        "oled_black", "dark_modern" -> true
        "light_clean" -> false
        "glass" -> isSystemInDarkTheme()
        else -> isSystemInDarkTheme()
    }
    val context = LocalContext.current
    var colorTick by remember { mutableIntStateOf(0) }

    DisposableEffect(context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) {
            return@DisposableEffect onDispose { }
        }
        val wm = WallpaperManager.getInstance(context)
        val listener = WallpaperManager.OnColorsChangedListener { _, which ->
            if (which and WallpaperManager.FLAG_SYSTEM != 0) {
                colorTick++
            }
        }
        runCatching { wm.addOnColorsChangedListener(listener, Handler(Looper.getMainLooper())) }
        onDispose { runCatching { wm.removeOnColorsChangedListener(listener) } }
    }

    val wallpaperPrimary: Color? = remember(context, colorTick) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return@remember null
        val wm = WallpaperManager.getInstance(context)
        runCatching {
            val colors = wm.getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
            colors?.primaryColor?.toArgb()?.let { Color(it) }
        }.getOrNull()
    }

    val baseColors = remember(darkTheme, activeThemeId, wallpaperPrimary) {
        when (activeThemeId) {
            "oled_black" -> {
                DarkColors.copy(
                    background = Color.Black,
                    surface = Color.Black,
                    surfaceVariant = Color(0xFF111111),
                    surfaceContainer = Color(0xFF0A0A0A),
                    surfaceContainerHigh = Color(0xFF141414),
                    surfaceContainerHighest = Color(0xFF1C1C1C),
                    surfaceContainerLow = Color.Black,
                    surfaceContainerLowest = Color.Black,
                    outline = Color(0xFF333333),
                    outlineVariant = Color(0xFF222222),
                    primary = wallpaperPrimary ?: Color(0xFF7FD1E0),
                    primaryContainer = (wallpaperPrimary ?: Color(0xFF7FD1E0)).copy(alpha = 0.22f),
                )
            }
            "dark_modern" -> {
                if (wallpaperPrimary != null) {
                    DarkColors.copy(
                        primary = wallpaperPrimary,
                        primaryContainer = wallpaperPrimary.copy(alpha = 0.35f),
                    )
                } else DarkColors
            }
            "light_clean" -> {
                if (wallpaperPrimary != null) {
                    LightColors.copy(
                        primary = wallpaperPrimary,
                        primaryContainer = wallpaperPrimary.copy(alpha = 0.35f),
                    )
                } else LightColors
            }
            "glass" -> {
                val base = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
                } else {
                    if (darkTheme) DarkColors else LightColors
                }
                val tinted = if (wallpaperPrimary != null) {
                    base.copy(
                        primary = wallpaperPrimary,
                        primaryContainer = wallpaperPrimary.copy(alpha = 0.35f),
                        onPrimaryContainer = if (darkTheme) Color.White else Color.Black,
                    )
                } else base
                tinted.copy(
                    background = if (darkTheme) Color(0x33101520) else Color(0x33F0F4F8),
                    surface = if (darkTheme) Color(0x33101520) else Color(0x33F0F4F8),
                    surfaceContainer = if (darkTheme) Color(0x33141B26) else Color(0x33E5EDF4),
                    surfaceContainerHigh = if (darkTheme) Color(0x441F2937) else Color(0x44D9E2EC),
                    surfaceContainerHighest = if (darkTheme) Color(0x552D3748) else Color(0x55CBD5E1),
                    surfaceContainerLow = if (darkTheme) Color(0x220B0F17) else Color(0x22F1F5F9),
                    outline = if (darkTheme) Color(0x4DFFFFFF) else Color(0x4D000000),
                    outlineVariant = if (darkTheme) Color(0x33FFFFFF) else Color(0x33000000),
                )
            }
            else -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val systemColors = if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
                    if (wallpaperPrimary != null) {
                        systemColors.copy(
                            primary = wallpaperPrimary,
                            primaryContainer = wallpaperPrimary.copy(alpha = 0.35f),
                            onPrimaryContainer = if (darkTheme) Color.White else Color.Black,
                        )
                    } else {
                        systemColors
                    }
                } else {
                    val fallback = if (darkTheme) DarkColors else LightColors
                    if (wallpaperPrimary != null) {
                        fallback.copy(
                            primary = wallpaperPrimary,
                            primaryContainer = wallpaperPrimary.copy(alpha = 0.35f),
                        )
                    } else fallback
                }
            }
        }
    }

    CompositionLocalProvider(
        LocalGlassThemeActive provides isGlassThemeActive,
        LocalActiveThemeId provides activeThemeId,
    ) {
        MaterialTheme(colorScheme = baseColors, content = content)
    }
}

@Composable
fun dynamicSurfaceColor(
    alpha: Float = 0.90f,
    tintFraction: Float = 0.12f,
): Color {
    val colorScheme = MaterialTheme.colorScheme
    return lerp(colorScheme.surfaceContainerHigh, colorScheme.primary, tintFraction).copy(alpha = alpha)
}

@Composable
fun dynamicBorderColor(
    alpha: Float = 0.35f,
    tintFraction: Float = 0.40f,
): Color {
    val colorScheme = MaterialTheme.colorScheme
    return lerp(colorScheme.outlineVariant, colorScheme.primary, tintFraction).copy(alpha = alpha)
}
