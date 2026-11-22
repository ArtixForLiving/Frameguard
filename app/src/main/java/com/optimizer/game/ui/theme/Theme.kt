package com.optimizer.game.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val DarkColorScheme = darkColorScheme(
    primary = AccentPurple,
    onPrimary = BoneWhite,
    primaryContainer = ObsidianSurface,
    onPrimaryContainer = BoneWhite,
    secondary = ZincMuted,
    onSecondary = BoneWhite,
    background = CanvasDark,
    onBackground = BoneWhite,
    surface = ObsidianSurface,
    onSurface = BoneWhite,
    error = SignalRed,
    onError = BoneWhite,
    outline = HairlineBorder
)

val UtilitarianShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(3.dp),
    medium = RoundedCornerShape(4.dp),
    large = RoundedCornerShape(4.dp),
    extraLarge = RoundedCornerShape(4.dp)
)

@Composable
fun FrameGuardTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        shapes = UtilitarianShapes,
        content = content
    )
}
