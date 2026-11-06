package com.videokeeper.mobile

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DeepSea = Color(0xFF08111F)
private val DeepCard = Color(0xFF102238)
private val DeepCardAlt = Color(0xFF142B42)
private val SeaBlue = Color(0xFF77D8F4)
private val SeaBlueDark = Color(0xFF2D9AC5)
private val TextMain = Color(0xFFE9F4FC)
private val TextMuted = Color(0xFFA5B9CB)
private val SafeGreen = Color(0xFF9DE4CC)

object VideoKeeperPalette {
    val background = DeepSea
    val card = DeepCard
    val cardAlt = DeepCardAlt
    val accent = SeaBlue
    val accentDark = SeaBlueDark
    val text = TextMain
    val muted = TextMuted
    val safe = SafeGreen
}

private val VideoKeeperScheme = darkColorScheme(
    primary = SeaBlue,
    onPrimary = Color(0xFF062433),
    primaryContainer = Color(0xFF16445B),
    onPrimaryContainer = Color(0xFFD3F5FF),
    secondary = Color(0xFF8ED7D2),
    onSecondary = Color(0xFF063331),
    background = DeepSea,
    onBackground = TextMain,
    surface = DeepCard,
    onSurface = TextMain,
    surfaceVariant = DeepCardAlt,
    onSurfaceVariant = TextMuted,
    outline = Color(0xFF38536B),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005)
)

@Composable
fun VideoKeeperTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = VideoKeeperScheme,
        typography = Typography(),
        content = content
    )
}
