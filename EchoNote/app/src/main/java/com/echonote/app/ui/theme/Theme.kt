package com.echonote.app.ui.theme

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

/** EchoNote brand palette — a deep "recording studio" green with a warm accent. */
private val BrandGreen = Color(0xFF1F7A5C)
private val BrandGreenDark = Color(0xFF0E3A2B)
private val BrandMint = Color(0xFF7FE3C0)
private val BrandSand = Color(0xFFE8DFD0)
private val RecordingRed = Color(0xFFD32F2F)
private val RecordingAmber = Color(0xFFE08A00)
private val UnknownGrey = Color(0xFF7A8894)

/** Stable per-speaker colours. Index order matters: it is what the diarizer writes
 *  into [com.echonote.app.data.db.SpeakerEntity.colorIndex]. */
val SpeakerPalette: List<Color> = listOf(
    BrandGreen,
    Color(0xFF2F6FB5),
    Color(0xFFB5622F),
    Color(0xFF6B4FA8),
    Color(0xFF2F9E8F),
    Color(0xFFA83F6B),
)

val UnknownSpeakerColor: Color = UnknownGrey
val RecordingAccent: Color = RecordingRed
val PausedAccent: Color = RecordingAmber

private val LightColors = lightColorScheme(
    primary = BrandGreen,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB8EBD6),
    onPrimaryContainer = BrandGreenDark,
    secondary = Color(0xFF4C6358),
    onSecondary = Color.White,
    tertiary = BrandSand,
    error = RecordingRed,
    background = Color(0xFFF7FAF8),
    surface = Color(0xFFF7FAF8),
    surfaceVariant = Color(0xFFDCE5E0),
)

private val DarkColors = darkColorScheme(
    primary = BrandMint,
    onPrimary = BrandGreenDark,
    primaryContainer = Color(0xFF17553E),
    onPrimaryContainer = Color(0xFFB8EBD6),
    secondary = Color(0xFFB3CCC0),
    onSecondary = Color(0xFF1E352C),
    tertiary = Color(0xFF4C4639),
    error = Color(0xFFFF6B6B),
    background = Color(0xFF101418),
    surface = Color(0xFF101418),
    surfaceVariant = Color(0xFF2A3238),
)

@Composable
fun EchoNoteTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    /** Material You on Android 12+. Falls back to the brand palette. */
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
