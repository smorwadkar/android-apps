package com.mobildroid.cloudshelf.app.core.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = CloudShelfTeal,
    onPrimary = Color.White,
    secondary = CloudShelfIndigo,
    tertiary = CloudShelfAmber,
    error = CloudShelfError
)

private val DarkColors = darkColorScheme(
    primary = CloudShelfTeal,
    onPrimary = Color.Black,
    secondary = CloudShelfIndigoDark,
    tertiary = CloudShelfAmber,
    error = CloudShelfError
)

private val VibeColors = darkColorScheme(
    primary = VibeMagenta,
    onPrimary = Color.White,
    secondary = VibeCyan,
    tertiary = VibeNeonGreen,
    error = CloudShelfError,
    surface = VibeSurfaceDark,
    onSurface = VibeOnDark,
    background = VibeSurfaceLight,
    onBackground = VibeOnDark,
    surfaceVariant = VibeSurfaceLight,
    onSurfaceVariant = VibeOnDark,
    inverseSurface = Color.White,
    inverseOnSurface = VibeSurfaceDark,
    outline = VibeHotPink,
    outlineVariant = VibePurple
)

@Composable
fun CloudShelfTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    vibe: Boolean = false,
    // Material You dynamic colors on Android 12+; falls back to our brand palette otherwise.
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        vibe -> VibeColors
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = MaterialTheme.typography,
        content = content
    )
}
