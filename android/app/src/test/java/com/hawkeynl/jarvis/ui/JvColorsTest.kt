package com.hawkeynl.jarvis.ui

import androidx.compose.ui.graphics.Color
import java.io.File
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every colour token of desktop/src/tokens.css exists in light and dark, and matches it. */
class JvColorsTest {
    private val tokens: Map<String, (JvColors) -> Color> = mapOf(
        "bg-page" to { it.bgPage },
        "bg-surface" to { it.bgSurface },
        "bg-raised" to { it.bgRaised },
        "bg-sunken" to { it.bgSunken },
        "bg-hover" to { it.bgHover },
        "bg-selected" to { it.bgSelected },
        "bg-scrim" to { it.bgScrim },
        "border-subtle" to { it.borderSubtle },
        "border" to { it.border },
        "border-strong" to { it.borderStrong },
        "separator" to { it.separator },
        "text-primary" to { it.textPrimary },
        "text-secondary" to { it.textSecondary },
        "text-disabled" to { it.textDisabled },
        "text-inverse" to { it.textInverse },
        "text-on-accent" to { it.textOnAccent },
        "accent-subtle" to { it.accentSubtle },
        "accent-border" to { it.accentBorder },
        "accent-solid" to { it.accentSolid },
        "accent-solid-hover" to { it.accentSolidHover },
        "accent-text" to { it.accentText },
        "focus-ring" to { it.focusRing },
        "positive-subtle" to { it.positiveSubtle },
        "positive-solid" to { it.positiveSolid },
        "positive-text" to { it.positiveText },
        "negative-subtle" to { it.negativeSubtle },
        "negative-solid" to { it.negativeSolid },
        "negative-text" to { it.negativeText },
        "danger-subtle" to { it.dangerSubtle },
        "danger-solid" to { it.dangerSolid },
        "danger-solid-hover" to { it.dangerSolidHover },
        "danger-text" to { it.dangerText },
        "chart-accent" to { it.chartAccent },
        "chart-ma" to { it.chartMa },
    )

    @Test
    fun everyCssColourTokenHasMatchingLightAndDarkValues() {
        val css = repoFile("desktop/src/tokens.css").readText()
        for ((selector, colors) in listOf(":root {" to JvLight, ":root[data-theme=\"dark\"] {" to JvDark)) {
            val block = css.substringAfter(selector, missingDelimiterValue = "").substringBefore("}")
            val declared = OKLCH.findAll(block).associateBy { it.groupValues[1] }
            assertEquals("tokens in $selector", tokens.keys, declared.keys)
            for ((name, match) in declared) {
                val (l, c, h) = match.groupValues.subList(2, 5).map(String::toDouble)
                val alpha = match.groupValues[5].ifEmpty { "1" }.toDouble()
                assertEquals("$name in $selector", oklchToArgb(l, c, h, alpha), argb(tokens.getValue(name)(colors)))
            }
        }
        assertFalse(JvLight.isDark)
        assertTrue(JvDark.isDark)
    }

    @Test
    fun startingWindowUsesThePageColourOfEachTheme() {
        assertEquals(argb(JvLight.bgPage) and 0xFFFFFF, windowBackground("values"))
        assertEquals(argb(JvDark.bgPage) and 0xFFFFFF, windowBackground("values-night"))
    }

    @Test
    fun conversionMatchesKnownSrgbValues() {
        assertEquals(0xFFFFFFFF.toInt(), oklchToArgb(1.0, 0.0, 0.0, 1.0))
        assertEquals(0xFF000000.toInt(), oklchToArgb(0.0, 0.0, 0.0, 1.0))
    }

    private fun windowBackground(dir: String): Int {
        val xml = repoFile("android/app/src/main/res/$dir/colors.xml").readText()
        val hex = Regex("""<color name="jarvis_background">#([0-9A-Fa-f]{6})</color>""").find(xml)!!.groupValues[1]
        return hex.toInt(16)
    }

    private fun argb(color: Color): Int {
        fun channel(v: Float) = (v * 255).roundToInt()
        return (channel(color.alpha) shl 24) or (channel(color.red) shl 16) or (channel(color.green) shl 8) or channel(color.blue)
    }

    private companion object {
        val OKLCH = Regex("""--([a-z-]+):\s*oklch\(([\d.]+) ([\d.]+) ([\d.]+)(?: / ([\d.]+))?\);""")

        /** CSS Color 4: OKLCH -> OKLab -> linear sRGB (Ottosson) -> sRGB, 8 bits per channel. */
        fun oklchToArgb(l: Double, c: Double, hDeg: Double, alpha: Double): Int {
            val h = Math.toRadians(hDeg)
            val a = c * cos(h)
            val b = c * sin(h)
            val l1 = (l + 0.3963377774 * a + 0.2158037573 * b).pow(3)
            val m1 = (l - 0.1055613458 * a - 0.0638541728 * b).pow(3)
            val s1 = (l - 0.0894841775 * a - 1.2914855480 * b).pow(3)
            val linear = doubleArrayOf(
                4.0767416621 * l1 - 3.3077115913 * m1 + 0.2309699292 * s1,
                -1.2684380046 * l1 + 2.6097574011 * m1 - 0.3413193965 * s1,
                -0.0041960863 * l1 - 0.7034186147 * m1 + 1.7076147010 * s1,
            )
            val rgb = linear.map { x ->
                val encoded = if (x <= 0.0031308) 12.92 * x else 1.055 * x.pow(1 / 2.4) - 0.055
                (encoded.coerceIn(0.0, 1.0) * 255).roundToInt()
            }
            return ((alpha * 255).roundToInt() shl 24) or (rgb[0] shl 16) or (rgb[1] shl 8) or rgb[2]
        }

        /** Unit tests run in android/app; walk up to the repository root. */
        fun repoFile(path: String): File {
            var dir: File? = File("").absoluteFile
            while (dir != null) {
                File(dir, path).takeIf { it.isFile }?.let { return it }
                dir = dir.parentFile
            }
            error("$path not found above ${File("").absolutePath}")
        }
    }
}
