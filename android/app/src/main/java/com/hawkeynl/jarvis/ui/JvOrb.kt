package com.hawkeynl.jarvis.ui

import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max

// The living Jarvis orb from the design export, drawn on one Canvas: halo,
// two rotating spark rings, three membranes, the beating sphere with drifting
// light, neural veins with travelling signals, nucleus and gloss. The mood
// speeds up heartbeat, nucleus and signals (idle x1, listening x0.7,
// thinking x0.4). With Android animations turned off (animator duration
// scale 0) the orb is drawn once, still.

/** True when the user turned animations off in the Android settings. */
@Composable
fun rememberReduceMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
}

private val SPARKS_A = arrayOf(
    floatArrayOf(50f, 0f, 4f, 0f), floatArrayOf(85f, 15f, 3f, -1.1f), floatArrayOf(99f, 58f, 4f, -2.3f),
    floatArrayOf(70f, 96f, 3f, -0.6f), floatArrayOf(22f, 91f, 4f, -1.8f), floatArrayOf(1f, 44f, 3f, -2.7f),
    floatArrayOf(16f, 12f, 2f, -0.3f),
)
private val SPARKS_B = arrayOf(
    floatArrayOf(30f, 4f, 2f, -1.4f), floatArrayOf(96f, 34f, 3f, -2.1f),
    floatArrayOf(80f, 90f, 2f, -0.9f), floatArrayOf(4f, 68f, 3f, -2.9f),
)
private val VEINS = listOf(
    "M50 50C42 40 35 38 24 30", "M50 50C58 41 66 36 77 28", "M50 50C60 55 70 62 80 70",
    "M50 50C41 58 32 64 22 72", "M50 50C50 62 47 74 49 88", "M50 50C51 38 54 24 50 12",
)
private val BRANCHES = listOf(
    "M35 38C30 46 22 48 14 52", "M66 36C72 44 82 46 88 48", "M70 62C66 72 62 80 60 88",
    "M32 64C28 56 20 58 12 60", "M54 24C62 20 68 16 74 12", "M42 40C40 30 36 22 30 16",
    "M47 74C40 78 34 84 30 90",
)
private val SIGNAL_TIMING = arrayOf(
    floatArrayOf(3.4f, 0f), floatArrayOf(4.1f, -1.3f), floatArrayOf(3.8f, -2.2f),
    floatArrayOf(4.6f, -0.7f), floatArrayOf(3.1f, -1.9f), floatArrayOf(4.3f, -2.8f),
)
private val NODES = arrayOf(
    floatArrayOf(35f, 38f, 1.1f, 0f), floatArrayOf(66f, 36f, 1.1f, -0.4f), floatArrayOf(70f, 62f, 1.1f, -0.9f),
    floatArrayOf(32f, 64f, 1.1f, -1.3f), floatArrayOf(54f, 24f, 1f, -1.7f), floatArrayOf(47f, 74f, 1f, -2.1f),
    floatArrayOf(24f, 30f, 0.8f, -0.6f), floatArrayOf(77f, 28f, 0.8f, -1.1f), floatArrayOf(80f, 70f, 0.8f, -1.5f),
    floatArrayOf(22f, 72f, 0.8f, -1.9f), floatArrayOf(14f, 52f, 0.7f, -2.4f), floatArrayOf(88f, 48f, 0.7f, -0.2f),
)

private val SPARK = Color(0xFFB8FFDF)
private val MINT = Color(0xFF96FFD2)

/** Position 0..1 within a repeating [period] (seconds), shifted by [delay]. */
private fun cycle(t: Float, period: Float, delay: Float = 0f): Float {
    val x = (t - delay) / period
    return x - floor(x)
}

private fun easeInOut(p: Float) = ((1 - cos(PI * p)) / 2).toFloat()

/** 0 → 1 → 0 over one cycle, eased (CSS 0%/50%/100% keyframes). */
private fun pulse(p: Float) = easeInOut(1 - abs(2 * p - 1))

/** Linear interpolation through keyframes ([keys] ascending 0..1). */
private fun keyframes(p: Float, keys: FloatArray, values: FloatArray): Float {
    for (i in 1 until keys.size) {
        if (p <= keys[i]) return values[i - 1] + (values[i] - values[i - 1]) * (p - keys[i - 1]) / (keys[i] - keys[i - 1])
    }
    return values.last()
}

private val BEAT_KEYS = floatArrayOf(0f, 0.1f, 0.2f, 0.3f, 0.45f, 1f)
private val BEAT_VALUES = floatArrayOf(1f, 1.03f, 0.995f, 1.018f, 1f, 1f)
private val DRIFT_KEYS = floatArrayOf(0f, 0.5f, 1f)

@Composable
fun JvOrb(size: Dp, mood: Mood, modifier: Modifier = Modifier, label: Boolean = true) {
    val reduceMotion = rememberReduceMotion()
    val clock = remember { mutableLongStateOf(1_500L) }
    if (!reduceMotion) {
        LaunchedEffect(Unit) {
            val start = withFrameMillis { it } - clock.longValue
            while (true) withFrameMillis { clock.longValue = it - start }
        }
    }
    val factor = moodFactor(mood)
    val veins = remember { VEINS.map { PathParser().parsePathString(it).toPath() } }
    val branches = remember { BRANCHES.map { PathParser().parsePathString(it).toPath() } }

    Box(
        modifier.size(size).drawWithCache {
            val d = this.size.width
            val c = this.size.center
            val r = d / 2
            val sr = d * 0.435f // sphere radius (87 %)
            val dp = density
            val accent = Jv.Accent
            val halo = Brush.radialGradient(
                0f to accent.copy(alpha = 0.34f), 0.36f to accent.copy(alpha = 0.12f), 0.62f to Color.Transparent,
                center = c, radius = 2.26f * r,
            )
            val glowR = r + 22 * dp
            val membraneGlow = Brush.radialGradient(
                (r - 22 * dp) / glowR to Color.Transparent, r / glowR to accent.copy(alpha = 0.3f), 1f to Color.Transparent,
                center = c, radius = glowR,
            )
            val innerFill = Brush.radialGradient(0.6f to Color.Transparent, 1f to accent.copy(alpha = 0.12f), center = c, radius = 0.47f * d)
            val sphereGlowR = sr + 70 * dp
            val sphereGlow = Brush.radialGradient(sr / sphereGlowR to accent.copy(alpha = 0.55f), 1f to Color.Transparent, center = c, radius = sphereGlowR)
            val sphereTopLeft = Offset(c.x - sr, c.y - sr)
            val sphere = Brush.radialGradient(
                0f to Color(0xFF3FF0A2), 0.28f to Color(0xFF14A868), 0.56f to Color(0xFF0A6744),
                0.84f to Color(0xFF05301F), 1f to Color(0xFF03190F),
                center = sphereTopLeft + Offset(0.84f * sr, 0.76f * sr), radius = 1.7f * sr,
            )
            val rim = Brush.radialGradient(
                (sr - 40 * dp).coerceAtLeast(0f) / sr to Color.Transparent, 1f to MINT.copy(alpha = 0.35f), center = c, radius = sr,
            )
            val shade = Brush.radialGradient(
                0.7f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.45f),
                center = c - Offset(20 * dp, 30 * dp), radius = sr,
            )
            val sphereClip = Path().apply { addOval(Rect(c, sr)) }
            fun blob(color: Color, x: Float, y: Float, w: Float, strong: Float, soft: Float): Pair<Offset, Brush> {
                val bc = sphereTopLeft + Offset((x + w / 2) * 2 * sr, (y + w / 2) * 2 * sr)
                return bc to Brush.radialGradient(
                    0f to color.copy(alpha = strong), 0.45f to color.copy(alpha = soft), 0.7f to Color.Transparent,
                    center = bc, radius = w * sr * 1.414f,
                )
            }
            val blobA = blob(Color(0xFFAAFFD7), 0.06f, 0.10f, 0.56f, 0.5f, 0.16f)
            val blobB = blob(Color(0xFF5AFFAF), 0.44f, 0.46f, 0.50f, 0.42f, 0.12f)
            val blobC = blob(Color(0xFFCDFFE8), 0.14f, 0.52f, 0.40f, 0.34f, 0.10f)
            val nucleusR = 0.44f * sr
            val nucleus = Brush.radialGradient(
                0f to Color(0xFFC8FFE4).copy(alpha = 0.7f), 0.32f to Color(0xFF50F0A5).copy(alpha = 0.3f),
                0.52f to Color(0xFF50F0A5).copy(alpha = 0.08f), 0.68f to Color.Transparent,
                center = c, radius = nucleusR * 1.414f,
            )
            val glossCenter = sphereTopLeft + Offset(0.34f * 2 * sr, 0.21f * 2 * sr)
            val glossW = 0.36f * 2 * sr
            val gloss = Brush.radialGradient(0f to Color.White.copy(alpha = 0.32f), 0.7f to Color.Transparent, center = glossCenter, radius = glossW / 2 * 1.414f)
            val unit = 2 * sr / 100f

            onDrawBehind {
                val t = clock.longValue / 1000f

                // Halo breathes.
                val breathe = pulse(cycle(t, 6f))
                scale(0.97f + 0.08f * breathe, c) { drawCircle(halo, 1.6f * r, c, alpha = 0.6f + 0.4f * breathe) }

                // Spark rings.
                rotate(360f * cycle(t, 60f), c) { sparks(SPARKS_A, 1.22f, t, dp, 1f) }
                rotate(-360f * cycle(t, 95f), c) { sparks(SPARKS_B, 1.44f, t, dp, 0.7f) }

                // Membranes: slightly irregular ovals that rotate.
                rotate(360f * easeInOut(cycle(t, 14f)), c) {
                    membrane(c, 0.52f * d, 0.975f, MINT.copy(alpha = 0.3f), 1 * dp)
                    drawCircle(membraneGlow, glowR, c)
                    membrane(c, 0.5f * d, 0.985f, MINT.copy(alpha = 0.5f), 1.5f * dp)
                }
                rotate(-360f * easeInOut(cycle(t, 19f)), c) {
                    drawCircle(innerFill, 0.47f * d, c)
                    membrane(c, 0.47f * d, 0.98f, accent.copy(alpha = 0.38f), 1 * dp)
                }

                // The beating sphere.
                scale(keyframes(cycle(t, 3.2f * factor), BEAT_KEYS, BEAT_VALUES), c) {
                    drawCircle(sphereGlow, sphereGlowR, c)
                    drawCircle(sphere, sr, c)
                    clipPath(sphereClip) {
                        drift(blobA, t, 9f, floatArrayOf(0f, 0.30f, -0.08f), floatArrayOf(0f, 0.20f, 0.34f), floatArrayOf(1f, 1.15f, 0.9f), 0.56f * 2 * sr)
                        drift(blobB, t, 12f, floatArrayOf(0f, -0.34f, -0.10f), floatArrayOf(0f, -0.18f, -0.38f), floatArrayOf(1f, 0.85f, 1.1f), 0.5f * 2 * sr)
                        drift(blobC, t, 15f, floatArrayOf(0f, 0.40f, 0.20f), floatArrayOf(0f, -0.26f, 0.10f), floatArrayOf(0.9f, 1.2f, 1f), 0.4f * 2 * sr)

                        // Veins, travelling signals and nodes in the export's 100-unit box.
                        withTransform({ translate(sphereTopLeft.x, sphereTopLeft.y); scale(unit, unit, Offset.Zero) }) {
                            val thin = Stroke(width = 0.45f, cap = StrokeCap.Round)
                            for (path in veins) drawPath(path, Color(0xFFD2FFEB).copy(alpha = 0.26f), style = thin)
                            for (path in branches) drawPath(path, Color(0xFFD2FFEB).copy(alpha = 0.26f), style = thin)
                            veins.forEachIndexed { i, path ->
                                val p = cycle(t, SIGNAL_TIMING[i][0] * factor, SIGNAL_TIMING[i][1])
                                val dash = PathEffect.dashPathEffect(floatArrayOf(4f, 60f), 64f * (1 - p))
                                drawPath(
                                    path, Color(0xFFEAFFF5), alpha = if (reduceMotion) 0.5f else 1f,
                                    style = Stroke(width = 1f, cap = StrokeCap.Round, pathEffect = dash),
                                )
                            }
                            for (n in NODES) {
                                drawCircle(Color(0xFFDFFFEE), n[2], Offset(n[0], n[1]), alpha = 0.3f + 0.7f * pulse(cycle(t, 2.6f, n[3])))
                            }
                        }

                        val nuc = pulse(cycle(t, 4f * factor))
                        scale(0.88f + 0.26f * nuc, c) { drawCircle(nucleus, nucleusR * 1.414f, c, alpha = 0.5f + 0.45f * nuc) }
                        drawCircle(rim, sr, c)
                        drawCircle(shade, sr * 1.6f, c)
                        rotate(-22f, glossCenter) {
                            scale(1f, 0.22f / 0.36f, glossCenter) { drawCircle(gloss, glossW / 2, glossCenter) }
                        }
                    }
                }
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        if (label) {
            val labelSize = max(13f, size.value * 0.088f).sp
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "JARVIS",
                    // Leading padding balances the trailing letter spacing.
                    modifier = Modifier.padding(start = (labelSize.value * 0.4f).dp),
                    style = TextStyle(
                        fontFamily = Rajdhani, fontWeight = FontWeight.SemiBold, fontSize = labelSize,
                        letterSpacing = 0.4.em, color = Jv.Text0,
                        shadow = Shadow(Color(0xD9001E14), Offset.Zero, 12f),
                    ),
                )
                Box(
                    Modifier.padding(top = (labelSize.value * 0.47f).dp).width((labelSize.value * 2.2f).dp).height(2.dp)
                        .background(Jv.Text0.copy(alpha = 0.85f)),
                )
            }
        }
    }
}

private fun DrawScope.membrane(c: Offset, radius: Float, aspect: Float, color: Color, width: Float) {
    val size = Size(2 * radius, 2 * radius * aspect)
    drawOval(color, topLeft = Offset(c.x - size.width / 2, c.y - size.height / 2), size = size, style = Stroke(width))
}

/** A ring of twinkling sparks laid out in a box [box]× the orb size. */
private fun DrawScope.sparks(sparks: Array<FloatArray>, box: Float, t: Float, dp: Float, alpha: Float) {
    val d = size.width * box
    val origin = center - Offset(d / 2, d / 2)
    for (s in sparks) {
        val at = origin + Offset(s[0] / 100f * d, s[1] / 100f * d)
        val twinkle = (0.2f + 0.8f * pulse(cycle(t, 3.2f, s[3]))) * alpha
        if (s[2] >= 3f) drawCircle(Jv.Accent, s[2] * dp * 1.6f, at, alpha = 0.25f * twinkle)
        drawCircle(SPARK, s[2] / 2 * dp, at, alpha = twinkle)
    }
}

/** A light blob drifting back and forth (CSS `alternate`) within the sphere. */
private fun DrawScope.drift(
    blob: Pair<Offset, Brush>, t: Float, period: Float,
    xs: FloatArray, ys: FloatArray, scales: FloatArray, blobSize: Float,
) {
    val p = cycle(t, 2 * period).let { easeInOut(if (it < 0.5f) it * 2 else 2 - it * 2) }
    val (bc, brush) = blob
    translate(keyframes(p, DRIFT_KEYS, xs) * blobSize, keyframes(p, DRIFT_KEYS, ys) * blobSize) {
        scale(keyframes(p, DRIFT_KEYS, scales), bc) { drawCircle(brush, blobSize * 0.75f, bc) }
    }
}
