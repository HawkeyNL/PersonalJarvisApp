package com.hawkeynl.jarvis.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hawkeynl.jarvis.R
import kotlin.math.roundToInt

/** `rgba(r, g, b, a)` as in the design export. */
private fun rgba(r: Int, g: Int, b: Int, a: Float) = Color(r, g, b, (a * 255).roundToInt())

/** Jarvis design tokens (dark only), mirrored from desktop/src/styles.css. */
object Jv {
    val Bg = Color(0xFF01090A)
    val Accent = Color(0xFF2EE6A2)
    val Accent2 = Color(0xFF9BFFD6)
    val OnAccent = Color(0xFF02150E)

    val Text0 = Color(0xFFF4FFFA)
    val Text1 = Color(0xFFE6F4EE)
    val Text2 = Color(0xFFD3E8DF)
    val Text3 = Color(0xFFC4DCD2)
    val Text4 = Color(0xFFA9C4B9)
    val Text5 = Color(0xFF8FB3A3)
    val Bright = Color(0xFFF1FAF6)
    val Muted = Color(0xFF9FB8AD)

    val Idle = Color(0xFF8995A0)
    val IdleLabel = Color(0xFF9AA7AD)
    val Warn = Color(0xFFF5B84A)
    val Danger = Color(0xFFF87171)

    val Line = rgba(160, 255, 215, 0.12f)
    val Line16 = rgba(160, 255, 215, 0.16f)
    fun accent(alpha: Float) = Accent.copy(alpha = alpha)

    val CardBrush = Brush.verticalGradient(listOf(rgba(10, 52, 40, 0.7f), rgba(4, 28, 22, 0.8f)))
    val SelectedBrush = Brush.verticalGradient(listOf(rgba(14, 78, 58, 0.8f), rgba(5, 40, 31, 0.88f)))
    val PanelBrush = Brush.verticalGradient(listOf(rgba(5, 36, 28, 0.9f), rgba(3, 22, 18, 0.92f)))
    val Satellite = rgba(3, 24, 19, 0.95f)
    val SatelliteSelected = rgba(14, 78, 58, 0.95f)
    val Tile = rgba(8, 40, 32, 0.6f)
    val ListBg = rgba(8, 40, 32, 0.45f)
    val IconBox = rgba(6, 30, 24, 0.9f)
    val Field = rgba(8, 26, 22, 0.7f)
    val Chip = rgba(4, 30, 24, 0.7f)
    val Dock = rgba(4, 28, 22, 0.88f)
    val Profile = rgba(5, 40, 30, 0.8f)

    val R8 = RoundedCornerShape(8.dp)
    val R10 = RoundedCornerShape(10.dp)
    val R12 = RoundedCornerShape(12.dp)
    val R14 = RoundedCornerShape(14.dp)
    val R16 = RoundedCornerShape(16.dp)
    val R20 = RoundedCornerShape(20.dp)
    val R22 = RoundedCornerShape(22.dp)
    val R26 = RoundedCornerShape(26.dp)
}

private fun exo(weight: Int, style: FontStyle = FontStyle.Normal) = Font(
    if (style == FontStyle.Italic) R.font.exo2_italic_variable else R.font.exo2_variable,
    FontWeight(weight),
    style,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

/** Exo 2 (variable font, 300–600 plus italic 300/400): body text. */
val Exo2 = FontFamily(
    exo(300), exo(400), exo(500), exo(600),
    exo(300, FontStyle.Italic), exo(400, FontStyle.Italic),
)

/** Rajdhani 500/600: wordmark and page titles. */
val Rajdhani = FontFamily(
    Font(R.font.rajdhani_medium, FontWeight.Medium),
    Font(R.font.rajdhani_semibold, FontWeight.SemiBold),
)

private val JarvisColors = darkColorScheme(
    primary = Jv.Accent,
    onPrimary = Jv.OnAccent,
    primaryContainer = Color(0xFF0E4E3A),
    onPrimaryContainer = Jv.Text0,
    secondary = Jv.Accent2,
    onSecondary = Jv.OnAccent,
    background = Jv.Bg,
    onBackground = Jv.Text1,
    surface = Color(0xFF041612),
    onSurface = Jv.Text1,
    surfaceVariant = Color(0xFF082820),
    onSurfaceVariant = Jv.Text5,
    surfaceContainerLowest = Jv.Bg,
    surfaceContainerLow = Color(0xFF03130F),
    surfaceContainer = Color(0xFF041A15),
    surfaceContainerHigh = Color(0xFF06221B),
    surfaceContainerHighest = Color(0xFF082A21),
    outline = Jv.accent(0.45f),
    outlineVariant = Jv.accent(0.18f),
    error = Jv.Danger,
    onError = Jv.Bg,
)

private val JarvisTypography = Typography().let { t ->
    Typography(
        displayLarge = t.displayLarge.copy(fontFamily = Rajdhani, fontWeight = FontWeight.SemiBold),
        displayMedium = t.displayMedium.copy(fontFamily = Rajdhani, fontWeight = FontWeight.SemiBold),
        displaySmall = t.displaySmall.copy(fontFamily = Rajdhani, fontWeight = FontWeight.SemiBold),
        headlineLarge = t.headlineLarge.copy(fontFamily = Rajdhani, fontWeight = FontWeight.SemiBold),
        headlineMedium = t.headlineMedium.copy(fontFamily = Rajdhani, fontWeight = FontWeight.SemiBold),
        headlineSmall = t.headlineSmall.copy(fontFamily = Rajdhani, fontWeight = FontWeight.SemiBold),
        titleLarge = t.titleLarge.copy(fontFamily = Exo2, fontWeight = FontWeight.Medium),
        titleMedium = t.titleMedium.copy(fontFamily = Exo2, fontWeight = FontWeight.Medium),
        titleSmall = t.titleSmall.copy(fontFamily = Exo2, fontWeight = FontWeight.Medium),
        bodyLarge = t.bodyLarge.copy(fontFamily = Exo2),
        bodyMedium = t.bodyMedium.copy(fontFamily = Exo2),
        bodySmall = t.bodySmall.copy(fontFamily = Exo2),
        labelLarge = t.labelLarge.copy(fontFamily = Exo2),
        labelMedium = t.labelMedium.copy(fontFamily = Exo2),
        labelSmall = t.labelSmall.copy(fontFamily = Exo2),
    )
}

private val JarvisShapes = Shapes(
    extraSmall = Jv.R8,
    small = Jv.R10,
    medium = Jv.R14,
    large = Jv.R22,
    extraLarge = Jv.R26,
)

@Composable
fun JarvisTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = JarvisColors, typography = JarvisTypography, shapes = JarvisShapes, content = content)
}
