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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Dot colour of a status. */
@Composable
@ReadOnlyComposable
fun toneColor(tone: Tone): Color = with(JvTheme.colors) {
    when (tone) {
        Tone.OK -> positiveSolid
        Tone.WARN -> negativeSolid
        Tone.ERROR -> dangerSolid
        Tone.IDLE -> borderStrong
    }
}

/** Text colour of a status label (AA on surfaces). */
@Composable
@ReadOnlyComposable
fun toneTextColor(tone: Tone): Color = with(JvTheme.colors) {
    when (tone) {
        Tone.OK -> positiveText
        Tone.WARN -> negativeText
        Tone.ERROR -> dangerText
        Tone.IDLE -> textSecondary
    }
}

/** A status dot; the label carries the meaning. */
@Composable
fun StatusDot(tone: Tone, label: String? = null, size: Dp = 8.dp, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(size).background(toneColor(tone), CircleShape))
        if (label != null) Text(label, color = toneTextColor(tone), style = MaterialTheme.typography.labelSmall)
    }
}

/** Sentence-case section heading ("Your system", "At a glance"). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.semantics { heading() }, color = JvTheme.colors.textSecondary, style = MaterialTheme.typography.labelMedium)
}

@Composable
fun MutedText(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, color = JvTheme.colors.textSecondary, style = MaterialTheme.typography.bodySmall)
}

@Composable
fun ErrorText(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, color = JvTheme.colors.dangerText, style = MaterialTheme.typography.bodySmall)
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
    tint: Color = JvTheme.colors.accentText,
    background: Color = JvTheme.colors.bgSurface,
    enabled: Boolean = true,
) {
    val c = JvTheme.colors
    Box(
        modifier
            .size(size)
            .pressScale(enabled = enabled)
            .then(if (selected) Modifier.border(2.dp, c.accentSolid, CircleShape) else Modifier.shadowBorder(CircleShape))
            .clip(CircleShape)
            .background(if (selected) c.bgSelected else background)
            .clickable(enabled = enabled, onClickLabel = contentDescription, role = Role.Button, onClick = onClick)
            .semantics {
                this.contentDescription = contentDescription
                if (selected) this.selected = true
            },
        contentAlignment = Alignment.Center,
    ) {
        JvIconView(icon, tint = if (!enabled) c.textDisabled else if (selected) c.accentText else tint, size = size * 0.45f)
    }
}

@Composable
fun ProfileButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    CircleIconButton(JvIcon.USER, "Settings", onClick, modifier, tint = JvTheme.colors.textPrimary)
}

/** Primary full-width button. Disabled it keeps a readable label ("Verifying…") and an outline. */
@Composable
fun ActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    arrow: Boolean = false,
    style: TextStyle = MaterialTheme.typography.labelLarge,
) {
    val c = JvTheme.colors
    val content = if (enabled) c.textOnAccent else c.textSecondary
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .pressScale(enabled = enabled)
            .then(if (enabled) Modifier else Modifier.border(1.dp, c.border, JvShapes.md))
            .clip(JvShapes.md)
            .background(if (enabled) c.accentSolid else c.bgSunken)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = JvSpace.lg),
        horizontalArrangement = Arrangement.spacedBy(JvSpace.sm, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = content, style = style)
        if (arrow) JvIconView(JvIcon.ARROW_RIGHT, tint = content, size = 16.dp)
    }
}

/** Secondary button; [danger] makes it the quiet destructive variant. */
@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, danger: Boolean = false) {
    val c = JvTheme.colors
    val shape = JvShapes.md
    Box(
        modifier
            .heightIn(min = 44.dp)
            .pressScale(enabled = enabled)
            .then(
                when {
                    !enabled -> Modifier
                    danger -> Modifier.border(1.dp, c.dangerText, shape)
                    else -> Modifier.shadowBorder(shape)
                },
            )
            .clip(shape)
            .background(if (!enabled) c.bgSunken else if (danger) Color.Transparent else c.bgSurface)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = JvSpace.md),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (!enabled) c.textDisabled else if (danger) c.dangerText else c.textPrimary,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

data class ChipItem(val id: String, val label: String, val icon: JvIcon? = null)

/** Horizontally scrolling chips: node selector (large) or sub-tabs (small). */
@Composable
fun ChipRow(items: List<ChipItem>, selected: String, onSelect: (String) -> Unit, small: Boolean = false, modifier: Modifier = Modifier) {
    val c = JvTheme.colors
    val shape = if (small) JvShapes.md else JvShapes.pill
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = JvSpace.lg, vertical = JvSpace.xxs),
        horizontalArrangement = Arrangement.spacedBy(JvSpace.sm),
    ) {
        for (item in items) {
            val on = item.id == selected
            Row(
                Modifier
                    .heightIn(min = 44.dp)
                    .pressScale()
                    .then(
                        when {
                            on -> Modifier.border(1.dp, c.accentSolid, shape)
                            small -> Modifier
                            else -> Modifier.shadowBorder(shape)
                        },
                    )
                    .clip(shape)
                    .background(if (on) c.bgSelected else if (small) Color.Transparent else c.bgSurface)
                    .selectable(selected = on, role = Role.Tab, onClick = { onSelect(item.id) })
                    .padding(horizontal = if (small) JvSpace.md else 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item.icon?.let { JvIconView(it, tint = if (on) c.accentText else c.textSecondary, size = 16.dp) }
                Text(
                    item.label,
                    color = if (on) c.accentText else if (small) c.textSecondary else c.textPrimary,
                    style = if (small) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

/** A hub module card: icon, name and two lines of live summary. */
@Composable
fun ModuleCard(icon: JvIcon, title: String, summary: CardSummary, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = JvTheme.colors
    val description = listOf(title, summary.line1, summary.line2).filter { it.isNotEmpty() }.joinToString(", ")
    Box(
        modifier
            .heightIn(min = 72.dp)
            .pressScale()
            .shadowBorder(JvShapes.lg)
            .clip(JvShapes.lg)
            .background(c.bgSurface)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description },
    ) {
        Row(Modifier.padding(start = JvSpace.md, end = 18.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(JvShapes.md).background(c.accentSubtle), contentAlignment = Alignment.Center) {
                JvIconView(icon, tint = c.accentText, size = 18.dp)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                for (line in listOf(summary.line1, summary.line2).filter { it.isNotEmpty() }) {
                    Text(
                        line, Modifier.padding(top = JvSpace.xxs), color = c.textSecondary,
                        style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        StatusDot(summary.tone, size = 6.dp, modifier = Modifier.align(Alignment.TopEnd).padding(top = JvSpace.md, end = JvSpace.md))
    }
}

/** Detail panel of a node page: header with icon, title, status, description,
 *  secondary detail line and optional action, then the body. */
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
    val c = JvTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            .shadowBorder(JvShapes.lg)
            .clip(JvShapes.lg)
            .background(c.bgSurface)
            .padding(JvSpace.lg),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(JvShapes.md).background(c.accentSubtle), contentAlignment = Alignment.Center) {
                JvIconView(icon, tint = c.accentText, size = 22.dp)
            }
            Spacer(Modifier.width(JvSpace.md))
            Column(Modifier.weight(1f)) {
                Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                StatusDot(tone, status, modifier = Modifier.padding(top = JvSpace.xs))
            }
        }
        if (description != null || quote != null) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                description?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                quote?.let { Text(it, color = c.textSecondary, style = MaterialTheme.typography.bodySmall) }
            }
        }
        action?.invoke()
        content()
    }
}

data class TileData(val icon: JvIcon, val label: String, val value: String)

/** Two-column grid of vitals under a section label. */
@Composable
fun TileGrid(title: String, tiles: List<TileData>, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel(title)
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
    val c = JvTheme.colors
    Column(
        modifier
            .heightIn(min = 92.dp)
            .shadowBorder(JvShapes.lg)
            .clip(JvShapes.lg)
            .background(c.bgSurface)
            .semantics(mergeDescendants = true) {}
            .padding(JvSpace.md),
    ) {
        JvIconView(tile.icon, tint = c.accentText, size = 22.dp)
        Text(tile.label, Modifier.padding(top = JvSpace.sm), style = MaterialTheme.typography.titleSmall)
        Text(tile.value, Modifier.padding(top = 3.dp), color = c.textSecondary, style = MaterialTheme.typography.bodySmall.tabular())
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
    val c = JvTheme.colors
    Column(modifier) {
        Row(Modifier.fillMaxWidth().heightIn(min = 32.dp).padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(title, Modifier.weight(1f))
            action?.invoke()
        }
        if (items.isEmpty()) {
            Text(
                empty, Modifier.fillMaxWidth().background(c.bgSunken, JvShapes.md).padding(JvSpace.lg),
                color = c.textSecondary, style = MaterialTheme.typography.bodySmall,
            )
            return@Column
        }
        Column(Modifier.fillMaxWidth().shadowBorder(JvShapes.lg).clip(JvShapes.lg).background(c.bgSurface)) {
            items.forEachIndexed { index, item ->
                if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(c.separator))
                ActivityRow(item, fullDetail, rowActions)
            }
        }
    }
}

@Composable
private fun ActivityRow(item: ActivityItem, fullDetail: Boolean, rowActions: (@Composable RowScope.(ActivityItem) -> Unit)?) {
    val c = JvTheme.colors
    Column(Modifier.fillMaxWidth().heightIn(min = 58.dp).padding(horizontal = JvSpace.md, vertical = 10.dp)) {
        Row(Modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(JvShapes.md).background(c.bgSunken), contentAlignment = Alignment.Center) {
                JvIconView(item.icon, tint = c.textSecondary, size = 18.dp)
            }
            Spacer(Modifier.width(JvSpace.md))
            Column(Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = if (fullDetail) 3 else 1, overflow = TextOverflow.Ellipsis)
                item.detail?.takeIf { it.isNotEmpty() && !fullDetail }?.let {
                    Text(
                        it, Modifier.padding(top = 3.dp), color = c.textSecondary,
                        style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            item.time?.takeIf { it.isNotEmpty() }?.let {
                Text(it, Modifier.padding(start = JvSpace.sm), color = c.textSecondary, style = MaterialTheme.typography.labelSmall.tabular())
            }
            Spacer(Modifier.width(10.dp))
            StatusDot(item.tone, size = 8.dp, modifier = Modifier.semantics { contentDescription = item.toneLabel })
        }
        if (fullDetail && !item.detail.isNullOrEmpty()) {
            Text(
                item.detail,
                Modifier.padding(top = JvSpace.sm).fillMaxWidth().clip(JvShapes.sm).background(c.bgSunken)
                    .padding(horizontal = 10.dp, vertical = JvSpace.sm),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (rowActions != null) {
            Row(
                Modifier.fillMaxWidth().padding(top = JvSpace.sm),
                horizontalArrangement = Arrangement.spacedBy(JvSpace.sm, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) { rowActions(item) }
        }
    }
}

enum class UnavailableKind { PLANNED, CORE_UPDATE, ERROR }

/** Honest empty state for what Core does not offer (yet); never sample data. */
@Composable
fun Unavailable(title: String, icon: JvIcon, modifier: Modifier = Modifier, kind: UnavailableKind = UnavailableKind.PLANNED, detail: String? = null) {
    val c = JvTheme.colors
    Row(
        modifier.fillMaxWidth().background(c.bgSunken, JvShapes.md).padding(JvSpace.lg),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(40.dp).clip(JvShapes.md).background(c.bgSurface), contentAlignment = Alignment.Center) {
            JvIconView(icon, tint = c.textSecondary, size = 20.dp)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                when (kind) {
                    UnavailableKind.CORE_UPDATE -> "Requires newer Core"
                    UnavailableKind.ERROR -> "Could not load"
                    UnavailableKind.PLANNED -> "Not yet available"
                },
                Modifier.padding(top = 3.dp),
                color = c.textSecondary,
                style = MaterialTheme.typography.labelSmall,
            )
            detail?.let { Text(it, Modifier.padding(top = JvSpace.sm), color = c.textSecondary, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/** Jarvis name with its ring, as in the hub's top-left corner. */
@Composable
fun Wordmark(modifier: Modifier = Modifier) {
    Row(modifier.semantics(mergeDescendants = true) { contentDescription = "Jarvis" }, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(24.dp).border(3.dp, JvTheme.colors.accentSolid, CircleShape))
        Spacer(Modifier.width(10.dp))
        Column {
            Text("Jarvis", style = MaterialTheme.typography.titleLarge)
            Text("Your second mind", color = JvTheme.colors.textSecondary, style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** Centered page title with a one-line explanation under it. */
@Composable
fun PageTitle(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            title,
            Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Text(
            subtitle, Modifier.padding(top = JvSpace.xxs), color = JvTheme.colors.textSecondary,
            style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
        )
    }
}
