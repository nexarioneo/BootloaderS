package dev.bootloaderstudio.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val LightColors = lightColorScheme(
    primary = Orange,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    primaryContainer = OrangeContainer,
    onPrimaryContainer = OnOrangeContainer,
    secondary = Teal,
    onSecondary = androidx.compose.ui.graphics.Color.White,
    secondaryContainer = TealContainer,
    onSecondaryContainer = androidx.compose.ui.graphics.Color(0xFF00201C),
    tertiary = Amber,
    onTertiary = Ink,
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFFF3EDF7),
    onSurfaceVariant = InkMuted,
    error = ErrorRed,
    onError = androidx.compose.ui.graphics.Color.White
)

private val DarkColors = darkColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFFFFB68A),
    onPrimary = androidx.compose.ui.graphics.Color(0xFF552000),
    primaryContainer = OrangeDark,
    onPrimaryContainer = OrangeContainer,
    secondary = androidx.compose.ui.graphics.Color(0xFF80CBC4),
    onSecondary = androidx.compose.ui.graphics.Color(0xFF003731),
    secondaryContainer = androidx.compose.ui.graphics.Color(0xFF005048),
    onSecondaryContainer = TealContainer,
    tertiary = androidx.compose.ui.graphics.Color(0xFFFFD180),
    onTertiary = androidx.compose.ui.graphics.Color(0xFF3E2E00),
    background = PaperDark,
    onBackground = androidx.compose.ui.graphics.Color(0xFFE6E1E5),
    surface = SurfaceDark,
    onSurface = androidx.compose.ui.graphics.Color(0xFFE6E1E5),
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFFCAC4D0),
    error = androidx.compose.ui.graphics.Color(0xFFFFB4AB),
    onError = androidx.compose.ui.graphics.Color(0xFF690005)
)

/**
 * Compact type scale: the default Material 3 sizes feel oversized on phones,
 * especially with dense technical information. Sizes are reduced by roughly
 * 15-25% while keeping the same hierarchy.
 */
private val CompactTypography = Typography(
    displayLarge = TextStyle(fontSize = 34.sp, lineHeight = 40.sp),
    displayMedium = TextStyle(fontSize = 28.sp, lineHeight = 34.sp),
    displaySmall = TextStyle(fontSize = 24.sp, lineHeight = 30.sp),
    headlineLarge = TextStyle(fontSize = 24.sp, lineHeight = 30.sp),
    headlineMedium = TextStyle(fontSize = 21.sp, lineHeight = 27.sp),
    headlineSmall = TextStyle(fontSize = 19.sp, lineHeight = 25.sp),
    titleLarge = TextStyle(fontSize = 18.sp, lineHeight = 23.sp),
    titleMedium = TextStyle(fontSize = 15.sp, lineHeight = 20.sp),
    titleSmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    bodyLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodyMedium = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    labelMedium = TextStyle(fontSize = 11.sp, lineHeight = 14.sp),
    labelSmall = TextStyle(fontSize = 10.sp, lineHeight = 13.sp)
)

private val CompactShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp)
)

@Composable
fun BootloaderStudioTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors

    MaterialTheme(
        colorScheme = colorScheme,
        typography = CompactTypography,
        shapes = CompactShapes,
        content = content
    )
}
