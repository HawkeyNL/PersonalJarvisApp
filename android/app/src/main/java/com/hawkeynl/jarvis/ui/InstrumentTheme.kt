package com.hawkeynl.jarvis.ui

import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.hawkeynl.jarvis.R

/**
 * Jarvis Instrument colour tokens, named 1:1 after the CSS custom properties in
 * desktop/src/tokens.css (`--bg-page` is [bgPage], `--text-on-accent` is [textOnAccent]).
 *
 * The CSS defines every colour in OKLCH. Each value here is that OKLCH colour converted
 * once to 8-bit sRGB: OKLCH to OKLab (a = C cos h, b = C sin h), OKLab to linear sRGB with
 * Björn Ottosson's matrices, sRGB transfer curve, round to 0..255. Every token lies inside
 * sRGB, so nothing is clipped. The CSS value is kept next to each colour, and
 * JvColorsTest re-derives all of them from tokens.css, so the two cannot drift apart.
 *
 * The `--shadow-*` tokens are not colours; [shadowBorder] draws them.
 */
@Immutable
data class JvColors(
    val isDark: Boolean,
    val bgPage: Color,
    val bgSurface: Color,
    val bgRaised: Color,
    val bgSunken: Color,
    val bgHover: Color,
    val bgSelected: Color,
    val bgScrim: Color,
    val borderSubtle: Color,
    val border: Color,
    val borderStrong: Color,
    val separator: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textDisabled: Color,
    val textInverse: Color,
    val textOnAccent: Color,
    val accentSubtle: Color,
    val accentBorder: Color,
    val accentSolid: Color,
    val accentSolidHover: Color,
    val accentText: Color,
    val focusRing: Color,
    val positiveSubtle: Color,
    val positiveSolid: Color,
    val positiveText: Color,
    val negativeSubtle: Color,
    val negativeSolid: Color,
    val negativeText: Color,
    val dangerSubtle: Color,
    val dangerSolid: Color,
    val dangerSolidHover: Color,
    val dangerText: Color,
    val chartAccent: Color,
    val chartMa: Color,
)

val JvLight = JvColors(
    isDark = false,
    bgPage = Color(0xFFF9FAFC), // oklch(0.985 0.003 255)
    bgSurface = Color(0xFFFFFFFF), // oklch(1 0 0)
    bgRaised = Color(0xFFFFFFFF), // oklch(1 0 0)
    bgSunken = Color(0xFFF2F4F6), // oklch(0.965 0.004 255)
    bgHover = Color(0xFFECEFF2), // oklch(0.95 0.005 255)
    bgSelected = Color(0xFFE3ECF9), // oklch(0.94 0.02 258)
    bgScrim = Color(0x52181B1F), // oklch(0.22 0.01 255 / 0.32)
    borderSubtle = Color(0xFFE4E6E9), // oklch(0.925 0.005 255)
    border = Color(0xFFD5D8DB), // oklch(0.88 0.006 255)
    borderStrong = Color(0xFF82878C), // oklch(0.62 0.01 255)
    separator = Color(0xFFE4E6E9), // oklch(0.925 0.005 255)
    textPrimary = Color(0xFF181B1F), // oklch(0.22 0.01 255)
    textSecondary = Color(0xFF565B62), // oklch(0.47 0.012 255)
    textDisabled = Color(0xFFA1A5AA), // oklch(0.72 0.008 255)
    textInverse = Color(0xFFFBFCFD), // oklch(0.99 0.002 255)
    textOnAccent = Color(0xFFFFFFFF), // oklch(1 0 0)
    accentSubtle = Color(0xFFEDF4FF), // oklch(0.965 0.016 258)
    accentBorder = Color(0xFFACC6EC), // oklch(0.82 0.06 258)
    accentSolid = Color(0xFF1C65C8), // oklch(0.52 0.17 258)
    accentSolidHover = Color(0xFF1256B2), // oklch(0.47 0.16 258)
    accentText = Color(0xFF1659B5), // oklch(0.48 0.16 258)
    focusRing = Color(0xFF3D7EDE), // oklch(0.6 0.16 258)
    positiveSubtle = Color(0xFFE3F6F6), // oklch(0.96 0.02 195)
    positiveSolid = Color(0xFF0A8282), // oklch(0.55 0.092 195)
    positiveText = Color(0xFF046C6C), // oklch(0.48 0.081 195)
    negativeSubtle = Color(0xFFFFF0E7), // oklch(0.965 0.02 55)
    negativeSolid = Color(0xFFC9690C), // oklch(0.62 0.15 55)
    negativeText = Color(0xFF9F5102), // oklch(0.52 0.128 55)
    dangerSubtle = Color(0xFFFFEDEB), // oklch(0.96 0.019 27)
    dangerSolid = Color(0xFFC22826), // oklch(0.53 0.19 27)
    dangerSolidHover = Color(0xFFAC1A1C), // oklch(0.48 0.18 27)
    dangerText = Color(0xFFB02B27), // oklch(0.5 0.17 27)
    chartAccent = Color(0xFF1C65C8), // oklch(0.52 0.17 258)
    chartMa = Color(0xFF7347AF), // oklch(0.5 0.16 300)
)

val JvDark = JvColors(
    isDark = true,
    bgPage = Color(0xFF0C0F12), // oklch(0.165 0.008 255)
    bgSurface = Color(0xFF13161A), // oklch(0.2 0.009 255)
    bgRaised = Color(0xFF1C2024), // oklch(0.24 0.01 255)
    bgSunken = Color(0xFF080A0D), // oklch(0.145 0.007 255)
    bgHover = Color(0xFF22262A), // oklch(0.265 0.01 255)
    bgSelected = Color(0xFF1C293D), // oklch(0.28 0.04 258)
    bgScrim = Color(0x99010202), // oklch(0.08 0.005 255 / 0.6)
    borderSubtle = Color(0xFF23272B), // oklch(0.27 0.01 255)
    border = Color(0xFF32363B), // oklch(0.33 0.011 255)
    borderStrong = Color(0xFF6D7279), // oklch(0.55 0.012 255)
    separator = Color(0xFF23272B), // oklch(0.27 0.01 255)
    textPrimary = Color(0xFFEDEFF1), // oklch(0.95 0.004 255)
    textSecondary = Color(0xFFB3B8BE), // oklch(0.78 0.01 255)
    textDisabled = Color(0xFF5F6469), // oklch(0.5 0.01 255)
    textInverse = Color(0xFF13161A), // oklch(0.2 0.009 255)
    textOnAccent = Color(0xFFFFFFFF), // oklch(1 0 0)
    accentSubtle = Color(0xFF16273F), // oklch(0.27 0.05 258)
    accentBorder = Color(0xFF345687), // oklch(0.45 0.09 258)
    accentSolid = Color(0xFF3370C7), // oklch(0.55 0.15 258)
    accentSolidHover = Color(0xFF2460B7), // oklch(0.5 0.15 258)
    accentText = Color(0xFF92BDFB), // oklch(0.79 0.1 258)
    focusRing = Color(0xFF70A6F5), // oklch(0.72 0.13 258)
    positiveSubtle = Color(0xFF0E2C2C), // oklch(0.27 0.035 195)
    positiveSolid = Color(0xFF339797), // oklch(0.62 0.09 195)
    positiveText = Color(0xFF72D0D0), // oklch(0.8 0.09 195)
    negativeSubtle = Color(0xFF3A2312), // oklch(0.28 0.045 55)
    negativeSolid = Color(0xFFD58042), // oklch(0.68 0.13 55)
    negativeText = Color(0xFFF5AB77), // oklch(0.8 0.11 55)
    dangerSubtle = Color(0xFF421C18), // oklch(0.28 0.06 27)
    dangerSolid = Color(0xFFC13C36), // oklch(0.55 0.17 27)
    dangerSolidHover = Color(0xFFA82E29), // oklch(0.49 0.16 27)
    dangerText = Color(0xFFF98F84), // oklch(0.76 0.13 27)
    chartAccent = Color(0xFF70A6F5), // oklch(0.72 0.13 258)
    chartMa = Color(0xFFB699EB), // oklch(0.74 0.12 300)
)

val LocalJvColors = staticCompositionLocalOf { JvLight }

/** The current Instrument tokens: `JvTheme.colors.textSecondary`. */
object JvTheme {
    val colors: JvColors
        @Composable @ReadOnlyComposable get() = LocalJvColors.current
}

/** Material3 roles filled from the same tokens, so stock components match. In dark mode
 *  primary and error are the light text tokens, as Material does: stock text buttons and
 *  focused field labels draw in primary on raised surfaces and need AA contrast there. */
internal fun JvColors.toColorScheme(): ColorScheme = (if (isDark) darkColorScheme() else lightColorScheme()).copy(
    primary = if (isDark) accentText else accentSolid,
    onPrimary = if (isDark) textInverse else textOnAccent,
    primaryContainer = accentSubtle,
    onPrimaryContainer = accentText,
    inversePrimary = accentText,
    secondary = accentSolid,
    onSecondary = textOnAccent,
    secondaryContainer = bgSelected,
    onSecondaryContainer = accentText,
    tertiary = positiveSolid,
    onTertiary = textOnAccent,
    tertiaryContainer = positiveSubtle,
    onTertiaryContainer = positiveText,
    background = bgPage,
    onBackground = textPrimary,
    surface = bgSurface,
    onSurface = textPrimary,
    surfaceVariant = bgSunken,
    onSurfaceVariant = textSecondary,
    surfaceTint = bgSurface, // equal to surface: tonal elevation adds no tint
    inverseSurface = textPrimary,
    inverseOnSurface = textInverse,
    error = if (isDark) dangerText else dangerSolid,
    onError = if (isDark) textInverse else textOnAccent,
    errorContainer = dangerSubtle,
    onErrorContainer = dangerText,
    outline = borderStrong,
    outlineVariant = border,
    scrim = bgScrim,
    surfaceBright = bgRaised,
    surfaceDim = bgSunken,
    surfaceContainerLowest = bgSurface,
    surfaceContainerLow = bgSurface,
    surfaceContainer = bgRaised, // menus: must stand out from panels in dark mode
    surfaceContainerHigh = bgRaised,
    surfaceContainerHighest = bgRaised,
)

/** Corner radii: --r-sm/md/lg/xl/pill. */
object JvShapes {
    val sm = RoundedCornerShape(6.dp)
    val md = RoundedCornerShape(10.dp)
    val lg = RoundedCornerShape(14.dp)
    val xl = RoundedCornerShape(20.dp)
    val pill = CircleShape
}

/** Spacing scale (dp); 16 is the phone side gutter. */
object JvSpace {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    val xxxl = 32.dp
}

/** Easings (--ease-*) and durations in ms (--d-*). */
object JvMotion {
    val easeOut = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)
    val easeInOut = CubicBezierEasing(0.77f, 0f, 0.175f, 1f)
    val easeDrawer = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)
    val easeIcon = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    const val PRESS = 150
    const val HOVER = 150
    const val POP = 200
    const val MODAL = 240
    const val EXIT = 150
    const val ENTER = 300
}

/** IBM Plex Sans 400/500/600 and Plex Mono 400/500 (OFL, see assets/fonts/OFL.txt). */
val PlexSans = FontFamily(
    Font(R.font.ibm_plex_sans_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_sans_medium, FontWeight.Medium),
    Font(R.font.ibm_plex_sans_semibold, FontWeight.SemiBold),
)
val PlexMono = FontFamily(
    Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_mono_medium, FontWeight.Medium),
)

/** Tabular figures, so changing numbers do not shift. Plex already draws its digits at
 *  one width; the feature keeps that true for fallback fonts too. */
fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = "tnum")

private fun plex(size: Int, line: Int, weight: FontWeight = FontWeight.Normal, tracking: Double = 0.0) = TextStyle(
    fontFamily = PlexSans, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp, letterSpacing = tracking.em,
)

/** The tokens.css type scale on Material3 roles. Display roles are the big numbers (tnum). */
val JvTypography = Typography(
    displayLarge = plex(40, 46, FontWeight.SemiBold, -0.02).tabular(),
    displayMedium = plex(34, 40, FontWeight.SemiBold, -0.02).tabular(),
    displaySmall = plex(28, 32, FontWeight.SemiBold, -0.02).tabular(), // .big
    headlineLarge = plex(30, 36, FontWeight.SemiBold, -0.02), // phone page title
    headlineMedium = plex(28, 34, FontWeight.SemiBold, -0.02), // h1
    headlineSmall = plex(24, 30, FontWeight.SemiBold, -0.02),
    titleLarge = plex(18, 24, FontWeight.SemiBold, -0.01), // h2
    titleMedium = plex(15, 21, FontWeight.SemiBold), // h3
    titleSmall = plex(14, 20, FontWeight.Medium),
    bodyLarge = plex(16, 24), // phone body
    bodyMedium = plex(15, 22),
    bodySmall = plex(13, 19), // .t-sm
    labelLarge = plex(14, 20, FontWeight.Medium), // buttons
    labelMedium = plex(13, 18, FontWeight.Medium), // .label, section labels
    labelSmall = plex(12, 16, FontWeight.Medium), // .t-xs, badges, tabs
)

private val JvMaterialShapes = Shapes(
    extraSmall = JvShapes.sm,
    small = JvShapes.md,
    medium = JvShapes.lg,
    large = JvShapes.xl,
    extraLarge = JvShapes.xl,
)

private val LightScheme = JvLight.toColorScheme()
private val DarkScheme = JvDark.toColorScheme()

@Composable
fun JarvisTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (darkTheme) JvDark else JvLight
    CompositionLocalProvider(LocalJvColors provides colors) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkScheme else LightScheme,
            typography = JvTypography,
            shapes = JvMaterialShapes,
        ) {
            CompositionLocalProvider(LocalContentColor provides colors.textPrimary, content = content)
        }
    }
}

/** True when the user turned animations off in the Android settings. */
@Composable
fun rememberReduceMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
}

/**
 * Shrinks the element to [scale] while a finger is down (--d-press, --ease-out). Draws only;
 * the click itself stays with the element's own clickable. With animations turned off
 * (animator duration scale 0, which "Remove animations" also sets) it never scales.
 */
fun Modifier.pressScale(scale: Float = 0.96f, enabled: Boolean = true): Modifier = if (!enabled) this else composed {
    val reduceMotion = rememberReduceMotion()
    var pressed by remember { mutableStateOf(false) }
    val current by animateFloatAsState(
        if (pressed && !reduceMotion) scale else 1f,
        tween(JvMotion.PRESS, easing = JvMotion.easeOut),
        label = "pressScale",
    )
    graphicsLayer { scaleX = current; scaleY = current }
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                pressed = true
                waitForUpOrCancellation()
                pressed = false
            }
        }
}

/** --shadow-border: a hairline ring plus, in light mode, two soft drop shadows. */
fun Modifier.shadowBorder(shape: Shape = JvShapes.lg): Modifier = composed {
    if (LocalJvColors.current.isDark) {
        border(1.dp, Color.White.copy(alpha = 0.08f), shape)
    } else {
        dropShadow(shape, Shadow(4.dp, Color.Black, offset = DpOffset(0.dp, 2.dp), alpha = 0.04f))
            .dropShadow(shape, Shadow(2.dp, Color.Black, spread = (-1).dp, offset = DpOffset(0.dp, 1.dp), alpha = 0.06f))
            .border(1.dp, Color.Black.copy(alpha = 0.06f), shape)
    }
}
