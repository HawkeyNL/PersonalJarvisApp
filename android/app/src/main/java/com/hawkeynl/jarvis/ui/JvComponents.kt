package com.hawkeynl.jarvis.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
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
fun ErrorText(text: String, modifier: Modifier = Modifier, live: Boolean = true) {
    // Announced when it appears or changes, unless [live] is off for text that follows every edit.
    Text(text, if (live) modifier.semantics { liveRegion = LiveRegionMode.Polite } else modifier, color = JvTheme.colors.dangerText, style = MaterialTheme.typography.bodySmall)
}

/** Round icon button (back, send). */
@Composable
fun CircleIconButton(
    icon: JvIcon,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
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

/** Primary full-width button. Disabled it keeps a readable label ("Verifying…") and an outline. */
@Composable
fun ActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    arrow: Boolean = false,
    style: TextStyle = MaterialTheme.typography.labelLarge,
    maxLines: Int = Int.MAX_VALUE,
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
        Text(text, color = content, style = style, maxLines = maxLines, softWrap = maxLines != 1)
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
            .heightIn(min = 48.dp)
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

data class ActivityItem(
    val id: String,
    val icon: JvIcon,
    val title: String,
    val detail: String? = null,
    val time: String? = null,
    val tone: Tone,
    /** Text of the row's status badge; empty shows no badge. */
    val toneLabel: String,
)

/** An Instrument list (`.list` of `.li` rows): icon tile, title, detail, time and a status
 *  badge. [onRowClick] makes each row one button; [rowActions] adds buttons under a row. */
@Composable
fun ActivityList(
    title: String,
    items: List<ActivityItem>,
    empty: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
    onRowClick: ((ActivityItem) -> Unit)? = null,
    rowClickLabel: String? = null,
    rowActions: (@Composable RowScope.(ActivityItem) -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(JvSpace.sm)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 32.dp).padding(horizontal = JvSpace.xs), verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(title, Modifier.weight(1f))
            action?.invoke()
        }
        Column(Modifier.fillMaxWidth().shadowBorder(JvShapes.lg).clip(JvShapes.lg).background(JvTheme.colors.bgSurface)) {
            if (items.isEmpty()) EmptyState(empty, compact = true)
            items.forEachIndexed { index, item ->
                if (index > 0) ListDivider()
                ActivityRow(item, onRowClick, rowClickLabel, rowActions)
            }
        }
    }
}

@Composable
private fun ActivityRow(
    item: ActivityItem,
    onClick: ((ActivityItem) -> Unit)?,
    clickLabel: String?,
    rowActions: (@Composable RowScope.(ActivityItem) -> Unit)?,
) {
    val c = JvTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .then(if (onClick != null) Modifier.clickable(onClickLabel = clickLabel, role = Role.Button) { onClick(item) } else Modifier)
            .padding(horizontal = JvSpace.lg, vertical = JvSpace.md),
    ) {
        Row(Modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
            IconTile(item.icon)
            Spacer(Modifier.width(JvSpace.md))
            Column(Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                item.detail?.takeIf { it.isNotEmpty() }?.let {
                    Text(it, color = c.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            item.time?.takeIf { it.isNotEmpty() }?.let {
                Text(it, Modifier.padding(start = JvSpace.sm), color = c.textSecondary, style = MaterialTheme.typography.labelSmall.tabular())
            }
            // Capped, so a long label never squeezes the title.
            if (item.toneLabel.isNotEmpty()) StatusBadge(item.toneLabel, Modifier.padding(start = JvSpace.sm).widthIn(max = 120.dp), item.tone)
        }
        if (rowActions != null) {
            // No padding of its own: a row without actions stays as tall as its content.
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(JvSpace.sm, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) { rowActions(item) }
        }
    }
}

/** 36dp rounded icon tile (`.tile`): neutral, tinted by [tone], or in the accent colour. */
@Composable
fun IconTile(icon: JvIcon, tone: Tone? = null, accent: Boolean = false) {
    val c = JvTheme.colors
    val (bg, fg) = when {
        accent -> c.accentSubtle to c.accentText
        tone == null || tone == Tone.IDLE -> c.bgSunken to c.textSecondary
        else -> toneSubtle(tone) to toneTextColor(tone)
    }
    Box(Modifier.size(36.dp).clip(JvShapes.md).background(bg), contentAlignment = Alignment.Center) {
        JvIconView(icon, tint = fg, size = 18.dp)
    }
}

/** Badge background of a status. */
@Composable
@ReadOnlyComposable
fun toneSubtle(tone: Tone): Color = with(JvTheme.colors) {
    when (tone) {
        Tone.OK -> positiveSubtle
        Tone.WARN -> negativeSubtle
        Tone.ERROR -> dangerSubtle
        Tone.IDLE -> bgSunken
    }
}

/** A status badge (`.badge`): dot and label, so colour never carries the meaning alone. */
@Composable
fun StatusBadge(label: String, modifier: Modifier = Modifier, tone: Tone = Tone.IDLE) {
    val fg = if (tone == Tone.IDLE) JvTheme.colors.textPrimary else toneTextColor(tone)
    Row(
        modifier.heightIn(min = 24.dp).clip(JvShapes.pill).background(toneSubtle(tone)).padding(horizontal = JvSpace.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(6.dp).background(fg, CircleShape))
        Text(label, color = fg, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Segmented control (`.seg`) that switches the view of a page; each segment is a tab. */
@Composable
fun Segmented(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = JvTheme.colors
    Row(
        modifier.fillMaxWidth().clip(JvShapes.md).background(c.bgSunken).padding(3.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(JvSpace.xxs),
    ) {
        for ((id, label) in options) {
            val on = id == selected
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .then(if (on) Modifier.shadowBorder(JvShapes.sm) else Modifier)
                    .clip(JvShapes.sm)
                    .background(if (on) c.bgSurface else Color.Transparent)
                    .selectable(selected = on, role = Role.Tab, onClick = { onSelect(id) })
                    .padding(horizontal = JvSpace.xs),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label, color = if (on) c.textPrimary else c.textSecondary, style = MaterialTheme.typography.labelLarge,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** One label and value row of a list (`.li` with the value on the right); a [tone]
 *  shows the value as a status badge. */
@Composable
fun FactRow(label: String, value: String, tone: Tone? = null) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).semantics(mergeDescendants = true) {}.padding(horizontal = JvSpace.lg, vertical = JvSpace.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(JvSpace.lg),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            if (tone != null) {
                StatusBadge(value, tone = tone)
            } else {
                Text(value, color = JvTheme.colors.textSecondary, textAlign = TextAlign.End, style = MaterialTheme.typography.bodyMedium.tabular())
            }
        }
    }
}

/** A [ListSection] of label and value rows. */
@Composable
fun Facts(title: String?, facts: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    ListSection(title, modifier) {
        facts.forEachIndexed { i, (label, value) ->
            if (i > 0) ListDivider()
            FactRow(label, value)
        }
    }
}

/** Empty state (`.empty`): centred title, an optional line, icon tile and action.
 *  [compact] is the one-line version inside a list. */
@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    icon: JvIcon? = null,
    action: Pair<String, () -> Unit>? = null,
    compact: Boolean = false,
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = JvSpace.xxl, vertical = if (compact) JvSpace.xl else JvSpace.xxxl)
            .semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(JvSpace.sm),
    ) {
        icon?.let { IconTile(it, accent = true) }
        Text(
            title, textAlign = TextAlign.Center,
            color = if (compact) JvTheme.colors.textSecondary else JvTheme.colors.textPrimary,
            style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.titleMedium,
        )
        body?.let { Text(it, color = JvTheme.colors.textSecondary, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall) }
        action?.let { (label, onClick) -> GhostButton(label, onClick) }
    }
}

/** Loading placeholder card (`.skel` bars); it pulses unless animations are off.
 *  TalkBack reads [label]. */
@Composable
fun Skeleton(label: String, modifier: Modifier = Modifier) {
    val c = JvTheme.colors
    val pulse = if (rememberReduceMotion()) {
        null
    } else {
        rememberInfiniteTransition("skeleton").animateFloat(
            1f, 0.55f, infiniteRepeatable(tween(700, easing = JvMotion.easeInOut), RepeatMode.Reverse), "skeleton",
        )
    }
    Column(
        modifier
            .fillMaxWidth()
            .shadowBorder(JvShapes.lg)
            .clip(JvShapes.lg)
            .background(c.bgSurface)
            .clearAndSetSemantics { contentDescription = label }
            .padding(JvSpace.lg),
        verticalArrangement = Arrangement.spacedBy(JvSpace.md),
    ) {
        SkeletonBar(0.4f, 14.dp, pulse)
        SkeletonBar(0.7f, 28.dp, pulse)
        ListDivider()
        for (width in listOf(1f, 0.92f, 0.85f)) SkeletonBar(width, 14.dp, pulse)
    }
}

@Composable
private fun SkeletonBar(width: Float, height: Dp, pulse: State<Float>?) {
    Box(
        Modifier.fillMaxWidth(width).height(height).graphicsLayer { alpha = pulse?.value ?: 1f }
            .clip(JvShapes.sm).background(JvTheme.colors.bgSunken),
    )
}

/** What Core does not offer yet: plain rows with a "Later" badge, never sample data. */
@Composable
fun LaterSection(items: List<Triple<JvIcon, String, String>>, note: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(JvSpace.sm)) {
        ActivityList(
            "Later",
            items.map { (icon, title, detail) -> ActivityItem(title, icon, title, detail, tone = Tone.IDLE, toneLabel = "Later") },
            "",
        )
        note?.let { MutedText(it, Modifier.padding(horizontal = JvSpace.xs)) }
    }
}

enum class UnavailableKind { PLANNED, CORE_UPDATE, ERROR }

/** Honest state for what Core does not offer (yet) or could not load, as an Instrument card;
 *  never sample data. An error can offer [onRetry]. Not a live region: several sections
 *  can fail at once, and only errors caused by an action are announced ([ErrorText]). */
@Composable
fun Unavailable(
    title: String,
    icon: JvIcon,
    modifier: Modifier = Modifier,
    kind: UnavailableKind = UnavailableKind.PLANNED,
    detail: String? = null,
    onRetry: (() -> Unit)? = null,
) {
    val c = JvTheme.colors
    val error = kind == UnavailableKind.ERROR
    Column(
        modifier
            .fillMaxWidth()
            .shadowBorder(JvShapes.lg)
            .clip(JvShapes.lg)
            .background(c.bgSurface)
            .padding(JvSpace.lg),
        verticalArrangement = Arrangement.spacedBy(JvSpace.md),
    ) {
        Row(
            Modifier.semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTile(icon, if (error) Tone.ERROR else null)
            Spacer(Modifier.width(JvSpace.md))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    when (kind) {
                        UnavailableKind.CORE_UPDATE -> "Requires newer Core"
                        UnavailableKind.ERROR -> "Could not load"
                        UnavailableKind.PLANNED -> "Not yet available"
                    },
                    color = if (error) c.dangerText else c.textSecondary,
                    style = MaterialTheme.typography.labelSmall,
                )
                detail?.let { Text(it, Modifier.padding(top = JvSpace.xs), color = c.textSecondary, style = MaterialTheme.typography.bodySmall) }
            }
        }
        onRetry?.let { GhostButton("Try again", it) }
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

/** Large left-aligned title of a tab root, with an optional line under it. */
@Composable
fun TabTitle(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = JvSpace.xs), verticalArrangement = Arrangement.spacedBy(JvSpace.xxs)) {
        Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineLarge)
        subtitle?.let { Text(it, color = JvTheme.colors.textSecondary, style = MaterialTheme.typography.bodySmall) }
    }
}

/** An Instrument list: optional section label above one rounded surface of rows. */
@Composable
fun ListSection(title: String?, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(JvSpace.sm)) {
        title?.let { SectionLabel(it, Modifier.padding(horizontal = JvSpace.xs)) }
        Column(Modifier.fillMaxWidth().shadowBorder(JvShapes.lg).clip(JvShapes.lg).background(JvTheme.colors.bgSurface)) { content() }
    }
}

/** Hairline between two rows of a [ListSection]. */
@Composable
fun ListDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(JvTheme.colors.separator))
}

/** One tappable row of a [ListSection]: icon tile, title, detail line, status dot and chevron.
 *  The dot only repeats what [detail] says; TalkBack reads title and detail as one button. */
@Composable
fun NavRow(icon: JvIcon, title: String, detail: String, onClick: () -> Unit, tone: Tone? = null) {
    val c = JvTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = JvSpace.md, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(32.dp).clip(JvShapes.md).background(c.accentSubtle), contentAlignment = Alignment.Center) {
            JvIconView(icon, tint = c.accentText, size = 18.dp)
        }
        Spacer(Modifier.width(JvSpace.md))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (detail.isNotEmpty()) {
                Text(detail, color = c.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        tone?.let { StatusDot(it, modifier = Modifier.padding(horizontal = JvSpace.sm)) }
        JvIconView(JvIcon.ARROW_RIGHT, tint = c.textSecondary, size = 16.dp)
    }
}

/**
 * Layout of every pushed screen: a back button (with an optional action on the right),
 * the sentence-case page title and its line, then [content] as Instrument sections.
 */
@Composable
fun PushedPage(
    title: String,
    subtitle: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = JvSpace.lg, end = JvSpace.lg, bottom = JvSpace.xxl),
        verticalArrangement = Arrangement.spacedBy(JvSpace.xl),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(JvSpace.sm)) {
            BackBar(onBack, trailing)
            TabTitle(title, subtitle)
        }
        content()
    }
}

/** Back button on the left and an optional action on the right. */
@Composable
private fun BackBar(onBack: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(top = JvSpace.sm), verticalAlignment = Alignment.CenterVertically) {
        CircleIconButton(JvIcon.ARROW_LEFT, "Back", onBack)
        Spacer(Modifier.weight(1f))
        trailing?.invoke()
    }
}
