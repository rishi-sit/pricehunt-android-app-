package com.pricehunt.presentation.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// App Colors
val Primary = Color(0xFF00D4AA)
val PrimaryVariant = Color(0xFF00B894)
val Secondary = Color(0xFF9B5DE5)
val Background = Color(0xFF0A0A0F)
val Surface = Color(0xFF16161F)
val SurfaceVariant = Color(0xFF1A1A25)
val OnPrimary = Color(0xFF0A0A0F)
val OnBackground = Color(0xFFF5F5F7)
val OnSurface = Color(0xFFF5F5F7)
val TextSecondary = Color(0xFFA1A1AA)
val TextTertiary = Color(0xFF71717A)
val Error = Color(0xFFEF4444)

// Platform Colors
val AmazonColor = Color(0xFFFF9900)
val AmazonFreshColor = Color(0xFF5EA03E)
val FlipkartColor = Color(0xFF2874F0)
val FlipkartMinutesColor = Color(0xFFFFCE00)
val JioMartColor = Color(0xFF0078AD)
val BigBasketColor = Color(0xFF84C225)
val ZeptoColor = Color(0xFF8B5CF6)
val BlinkitColor = Color(0xFFF8CB46)
val InstamartColor = Color(0xFFFC8019)

private val DarkColorScheme = darkColorScheme(
    primary = Primary,
    secondary = Secondary,
    tertiary = PrimaryVariant,
    background = Background,
    surface = Surface,
    surfaceVariant = SurfaceVariant,
    onPrimary = OnPrimary,
    onSecondary = OnPrimary,
    onTertiary = OnPrimary,
    onBackground = OnBackground,
    onSurface = OnSurface,
    onSurfaceVariant = TextSecondary,
    error = Error
)

@Composable
fun PriceHuntTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}

