package com.hawkeynl.jarvis.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** Exo 2 text style in the design's scale. */
fun exo(size: Float, weight: FontWeight = FontWeight.Normal, color: Color = Jv.Text1, spacing: Float = 0f, italic: Boolean = false) =
    TextStyle(
        fontFamily = Exo2, fontWeight = weight, fontSize = size.sp, color = color, letterSpacing = spacing.em,
        fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
    )

fun toneColor(tone: Tone): Color = when (tone) {
    Tone.OK -> Jv.Accent
    Tone.WARN -> Jv.Warn
    Tone.ERROR -> Jv.Danger
    Tone.IDLE -> Jv.Idle
}

private fun toneLabelColor(tone: Tone): Color = if (tone == Tone.IDLE) Jv.IdleLabel else toneColor(tone)

// Stars of the export's backdrop: x as a fraction of the width, y in dp, size, alpha, glow.
private val STARS = listOf(
    floatArrayOf(0.07f, 140f, 3f, 0.8f, 1f), floatArrayOf(0.9f, 112f, 2f, 0.6f, 0f), floatArrayOf(0.1f, 420f, 2f, 0.5f, 0f),
    floatArrayOf(0.93f, 380f, 3f, 0.7f, 1f), floatArrayOf(0.05f, 700f, 2f, 0.5f, 0f), floatArrayOf(0.95f, 640f, 2f, 0.5f, 0f),
)

/** Page background: Jarvis black, the soft green glow behind the orb, a few
 *  stars and the glowing horizon at the bottom of the screen. */
fun Modifier.jvBackdrop(glowY: Dp = 260.dp): Modifier = background(Jv.Bg).drawBehind {
    val w = size.width
    val glowCenter = Offset(w / 2, glowY.toPx())
    drawCircle(
        Brush.radialGradient(0f to Color(20, 150, 95, 71), 0.7f to Color.Transparent, center = glowCenter, radius = 330.dp.toPx()),
        330.dp.toPx(), glowCenter,
    )
    for (s in STARS) {
        val at = Offset(s[0] * w, s[1].dp.toPx())
        if (at.y > size.height) continue
        if (s[4] > 0f) drawCircle(Jv.Accent, s[2].dp.toPx() * 1.8f, at, alpha = 0.25f)
        drawCircle(Jv.Accent2, s[2].dp.toPx() / 2, at, alpha = s[3])
    }
    // Horizon: a very wide ellipse whose top edge glows just above the bottom.
    val horizon = Size(w * 2.33f, 500.dp.toPx())
    val topLeft = Offset((w - horizon.width) / 2, size.height - 96.dp.toPx())
    drawOval(
        Brush.radialGradient(
            0f to Color(20, 120, 80, 128), 0.2f to Color(0xFF04160F), 0.6f to Color(0xFF010A07),
            center = Offset(w / 2, topLeft.y), radius = horizon.height,
        ),
        topLeft, horizon,
    )
    drawOval(Jv.Accent.copy(alpha = 0.12f), topLeft - Offset(0f, 6.dp.toPx()), horizon, style = Stroke(12.dp.toPx()))
    drawOval(Color(110, 255, 200, 140), topLeft, horizon, style = Stroke(2.dp.toPx()))
}

/** A status dot; the label carries the meaning. */
@Composable
fun StatusDot(tone: Tone, label: String? = null, size: Dp = 8.dp, fontSize: Float = 12f, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier.size(size).drawBehind {
                if (tone != Tone.IDLE) drawCircle(toneColor(tone), this.size.minDimension, alpha = 0.25f)
                drawCircle(toneColor(tone))
            },
        )
        if (label != null) Text(label, style = exo(fontSize, color = toneLabelColor(tone)))
    }
}

/** Uppercase section label ("YOUR SYSTEM", "AT A GLANCE"). */
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), modifier.semantics { heading() }, style = exo(11f, color = Jv.Text5, spacing = 0.3f))
}

@Composable
fun MutedText(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = exo(12.5f, color = Jv.Text4))
}

@Composable
fun ErrorText(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = exo(12.5f, color = Jv.Danger))
}

/** Round icon button (back, profile, satellites). */
@Composable
fun CircleIconButton(
    icon: JvIcon,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    selected: Boolean = false,
    tint: Color = Jv.Accent,
    background: Color = Jv.Satellite,
    enabled: Boolean = true,
) {
    Box(
        modifier
            .size(size)
            .drawBehind { if (selected) drawCircle(Jv.Accent, this.size.minDimension * 0.75f, alpha = 0.18f) }
            .clip(CircleShape)
            .background(if (selected) Jv.SatelliteSelected else background)
            .border(if (selected) 2.dp else 1.5.dp, if (selected) Jv.Accent else Jv.accent(0.6f), CircleShape)
            .clickable(enabled = enabled, onClickLabel = contentDescription, role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        JvIconView(icon, tint = if (selected) Color(0xFFF8FFFB) else tint, size = size * 0.45f)
    }
}

@Composable
fun ProfileButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    CircleIconButton(JvIcon.USER, "Settings", onClick, modifier, tint = Jv.Bright, background = Jv.Profile)
}

/** Full-width call to action inside a panel. */
@Composable
fun ActionButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, arrow: Boolean = false) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(Jv.R12)
            .background(Jv.accent(0.1f))
            .border(1.dp, Jv.accent(if (enabled) 0.55f else 0.2f), Jv.R12)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = exo(13.5f, color = if (enabled) Jv.Bright else Jv.Text5, spacing = 0.03f))
        if (arrow) JvIconView(JvIcon.ARROW_RIGHT, tint = Jv.Accent, size = 16.dp)
    }
}

/** Small outlined button for list rows. */
@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, danger: Boolean = false) {
    val color = if (danger) Jv.Danger else Jv.Accent
    Box(
        modifier
            .heightIn(min = 36.dp)
            .clip(Jv.R10)
            .border(1.dp, color.copy(alpha = if (enabled) 0.45f else 0.15f), Jv.R10)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = exo(12f, color = if (!enabled) Jv.Text5 else if (danger) Jv.Danger else Jv.Text2))
    }
}

data class ChipItem(val id: String, val label: String, val icon: JvIcon? = null)

/** Horizontally scrolling chips: node selector (large) or sub-tabs (small). */
@Composable
fun ChipRow(items: List<ChipItem>, selected: String, onSelect: (String) -> Unit, small: Boolean = false, modifier: Modifier = Modifier) {
    val shape = if (small) Jv.R10 else RoundedCornerShape(19.dp)
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (item in items) {
            val on = item.id == selected
            Row(
                Modifier
                    .height(if (small) 36.dp else 40.dp)
                    .clip(shape)
                    .background(if (on) Jv.accent(if (small) 0.1f else 0.14f) else if (small) Color.Transparent else Jv.Chip)
                    .border(if (on) 1.5.dp else 1.dp, if (on) Jv.Accent else Jv.accent(if (small) 0.2f else 0.3f), shape)
                    .selectable(selected = on, role = Role.Tab, onClick = { onSelect(item.id) })
                    .padding(horizontal = if (small) 12.dp else 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                item.icon?.let { JvIconView(it, tint = if (on) Jv.Accent else Jv.Text5, size = 16.dp) }
                Text(item.label, style = exo(if (small) 12f else 13f, if (on) FontWeight.Medium else FontWeight.Normal, if (on) Color(0xFFF8FFFB) else Jv.Text3))
            }
        }
    }
}

/** A hub module card: icon, name and two lines of live summary. */
@Composable
fun ModuleCard(icon: JvIcon, title: String, summary: CardSummary, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val description = listOf(title, summary.line1, summary.line2).filter { it.isNotEmpty() }.joinToString(", ")
    Box(
        modifier
            .heightIn(min = 72.dp)
            .clip(Jv.R16)
            .background(Jv.CardBrush)
            .border(1.dp, Jv.accent(0.35f), Jv.R16)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description },
    ) {
        Row(Modifier.padding(start = 12.dp, end = 18.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).border(1.5.dp, Jv.accent(0.6f), CircleShape), contentAlignment = Alignment.Center) {
                JvIconView(icon, size = 18.dp)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = exo(12.5f, FontWeight.SemiBold, Jv.Bright, 0.06f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                for (line in listOf(summary.line1, summary.line2).filter { it.isNotEmpty() }) {
                    Text(line, Modifier.padding(top = 2.dp), style = exo(11f, color = Jv.Text5), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        StatusDot(summary.tone, size = 5.dp, modifier = Modifier.align(Alignment.TopEnd).padding(top = 12.dp, end = 12.dp))
    }
}

/** Detail panel of a node page: header with icon, title, status, description,
 *  italic detail line and optional action, then the body. */
@Composable
fun Panel(
    icon: JvIcon,
    title: String,
    tone: Tone,
    status: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    quote: String? = null,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(Jv.R20)
            .background(Jv.PanelBrush)
            .border(1.5.dp, Jv.accent(0.55f), Jv.R20)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).border(1.5.dp, Jv.accent(0.75f), CircleShape), contentAlignment = Alignment.Center) {
                JvIconView(icon, size = 24.dp)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, Modifier.semantics { heading() }, style = exo(17f, FontWeight.Medium, Color(0xFFF8FFFB), 0.03f))
                StatusDot(tone, status, modifier = Modifier.padding(top = 4.dp))
            }
        }
        if (description != null || quote != null) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                description?.let { Text(it, style = exo(13.5f, color = Jv.Text2).copy(lineHeight = 19.sp)) }
                quote?.let { Text(it, style = exo(12f, FontWeight.Light, Jv.Muted, italic = true)) }
            }
        }
        action?.invoke()
        content()
    }
}

data class TileData(val icon: JvIcon, val label: String, val value: String)

/** Two-column grid of vitals under an eyebrow. */
@Composable
fun TileGrid(title: String, tiles: List<TileData>, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Eyebrow(title)
        for (row in tiles.chunked(2)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (tile in row) Tile(tile, Modifier.weight(1f))
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun Tile(tile: TileData, modifier: Modifier = Modifier) {
    Column(
        modifier
            .heightIn(min = 92.dp)
            .clip(Jv.R14)
            .background(Jv.Tile)
            .border(1.dp, Jv.accent(0.3f), Jv.R14)
            .semantics(mergeDescendants = true) {}
            .padding(12.dp),
    ) {
        JvIconView(tile.icon, size = 22.dp)
        Text(tile.label, Modifier.padding(top = 8.dp), style = exo(12.5f, FontWeight.Medium, Jv.Bright))
        Text(tile.value, Modifier.padding(top = 3.dp), style = exo(11f, color = Jv.Text5))
    }
}

data class ActivityItem(
    val id: String,
    val icon: JvIcon,
    val title: String,
    val detail: String? = null,
    val time: String? = null,
    val tone: Tone,
    val toneLabel: String,
)

/** Recent items: icon, title, detail, time, status; optional row actions. */
@Composable
fun ActivityList(
    title: String,
    items: List<ActivityItem>,
    empty: String,
    modifier: Modifier = Modifier,
    fullDetail: Boolean = false,
    action: (@Composable () -> Unit)? = null,
    rowActions: (@Composable RowScope.(ActivityItem) -> Unit)? = null,
) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth().heightIn(min = 32.dp).padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Eyebrow(title, Modifier.weight(1f))
            action?.invoke()
        }
        if (items.isEmpty()) {
            Text(empty, Modifier.fillMaxWidth().dashedBorder().padding(16.dp), style = exo(12f, color = Jv.Text4))
            return@Column
        }
        Column(Modifier.fillMaxWidth().clip(Jv.R14).background(Jv.ListBg).border(1.dp, Jv.accent(0.3f), Jv.R14)) {
            items.forEachIndexed { index, item ->
                if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Jv.accent(0.16f)))
                ActivityRow(item, fullDetail, rowActions)
            }
        }
    }
}

@Composable
private fun ActivityRow(item: ActivityItem, fullDetail: Boolean, rowActions: (@Composable RowScope.(ActivityItem) -> Unit)?) {
    Column(Modifier.fillMaxWidth().heightIn(min = 58.dp).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Row(Modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(36.dp).clip(Jv.R10).background(Jv.IconBox).border(1.dp, Jv.accent(0.2f), Jv.R10),
                contentAlignment = Alignment.Center,
            ) { JvIconView(item.icon, tint = Jv.Text1, size = 18.dp) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title, style = exo(12.5f, FontWeight.Medium, Jv.Bright), maxLines = if (fullDetail) 3 else 1, overflow = TextOverflow.Ellipsis)
                item.detail?.takeIf { it.isNotEmpty() && !fullDetail }?.let {
                    Text(it, Modifier.padding(top = 3.dp), style = exo(11f, color = Jv.Text5), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            item.time?.takeIf { it.isNotEmpty() }?.let { Text(it, Modifier.padding(start = 8.dp), style = exo(11f, color = Jv.Text5)) }
            Spacer(Modifier.width(10.dp))
            StatusDot(item.tone, size = 8.dp, modifier = Modifier.semantics { contentDescription = item.toneLabel })
        }
        if (fullDetail && !item.detail.isNullOrEmpty()) {
            Text(
                item.detail,
                Modifier.padding(top = 8.dp).fillMaxWidth().clip(Jv.R8).background(Jv.IconBox).padding(horizontal = 10.dp, vertical = 8.dp),
                style = exo(11.5f, color = Jv.Text3),
            )
        }
        if (rowActions != null) {
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) { rowActions(item) }
        }
    }
}

enum class UnavailableKind { PLANNED, CORE_UPDATE, ERROR }

/** Honest empty state for what Core does not offer (yet); never sample data. */
@Composable
fun Unavailable(title: String, icon: JvIcon, modifier: Modifier = Modifier, kind: UnavailableKind = UnavailableKind.PLANNED, detail: String? = null) {
    Row(
        modifier.fillMaxWidth().dashedBorder().background(Color(8, 40, 32, 77), Jv.R12).padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(40.dp).border(1.5.dp, Jv.accent(0.3f), CircleShape), contentAlignment = Alignment.Center) {
            JvIconView(icon, tint = Jv.Text5, size = 20.dp)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = exo(13f, FontWeight.Medium, Jv.Text1))
            Text(
                when (kind) {
                    UnavailableKind.CORE_UPDATE -> "REQUIRES NEWER CORE"
                    UnavailableKind.ERROR -> "COULD NOT LOAD"
                    UnavailableKind.PLANNED -> "NOT YET AVAILABLE"
                },
                Modifier.padding(top = 3.dp),
                style = exo(11f, color = Jv.Text5, spacing = 0.2f),
            )
            detail?.let { Text(it, Modifier.padding(top = 8.dp), style = exo(12f, color = Jv.Text4).copy(lineHeight = 17.sp)) }
        }
    }
}

private fun Modifier.dashedBorder(): Modifier = drawBehind {
    val stroke = 1.dp.toPx()
    drawRoundRect(
        Jv.accent(0.3f),
        topLeft = Offset(stroke / 2, stroke / 2),
        size = Size(size.width - stroke, size.height - stroke),
        cornerRadius = CornerRadius(12.dp.toPx()),
        style = Stroke(stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
    )
}

/** JARVIS wordmark with its ring, as in the hub's top-left corner. */
@Composable
fun Wordmark(modifier: Modifier = Modifier) {
    Row(modifier.semantics(mergeDescendants = true) { contentDescription = "Jarvis" }, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(28.dp)
                .drawBehind { drawCircle(Jv.Accent, this.size.minDimension * 0.75f, alpha = 0.15f) }
                .border(3.dp, Jv.Accent, CircleShape),
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text("JARVIS", style = TextStyle(fontFamily = Rajdhani, fontWeight = FontWeight.Medium, fontSize = 18.sp, letterSpacing = 0.42.em, color = Color(0xFFEAF7F1)))
            Text("YOUR SECOND MIND", style = exo(9f, color = Jv.Text5, spacing = 0.3f))
        }
    }
}

/** Centered page title (Rajdhani) with an eyebrow subtitle. */
@Composable
fun PageTitle(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            title,
            Modifier.semantics { heading() },
            style = TextStyle(fontFamily = Rajdhani, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, letterSpacing = 0.3.em, color = Jv.Text0),
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
        Text(subtitle, Modifier.padding(top = 4.dp), style = exo(9.5f, color = Jv.Muted, spacing = 0.22f), maxLines = 1, textAlign = TextAlign.Center)
    }
}
