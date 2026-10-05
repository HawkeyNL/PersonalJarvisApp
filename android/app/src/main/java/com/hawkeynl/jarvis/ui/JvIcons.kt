package com.hawkeynl.jarvis.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Line icons from the design export (24×24, stroke-based), as in desktop NavIcon.vue. */
enum class JvIcon(val path: String) {
    CHAT("M4 5h16v11H11l-4 4v-4H4zM9 10.5h.01M12 10.5h.01M15 10.5h.01"),
    AGENTS("M12 3l3 1.7v3.4L12 9.8 9 8.1V4.7zM7 11.2l3 1.7v3.4L7 18l-3-1.7v-3.4zM17 11.2l3 1.7v3.4L17 18l-3-1.7v-3.4z"),
    MEMORY("M5 6c0-1.7 3.1-3 7-3s7 1.3 7 3-3.1 3-7 3-7-1.3-7-3zM5 6v12c0 1.7 3.1 3 7 3s7-1.3 7-3V6M5 12c0 1.7 3.1 3 7 3s7-1.3 7-3"),
    TASKS("M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0zM8 12.5l3 3 5-6"),
    INTEGRATIONS("M9 3v5M15 3v5M6 8h12v3a6 6 0 0 1-12 0zM12 17v4"),
    CONTEXT("M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"),
    HEALTH("M3 12h4l2-6 4 12 2-6h6"),
    WAVE("M3 12h1.5M8 8v8M12 4v16M16 8v8M19.5 12H21"),
    USER("M12 3a3 3 0 1 1 0 6 3 3 0 0 1 0-6zM5 20c0-4 3-6 7-6s7 2 7 6"),
    CLOCK("M12 3a9 9 0 1 1 0 18 9 9 0 0 1 0-18zM12 7v5l3 2"),
    CHIP("M8 8h8v8H8zM4 10h2M4 14h2M18 10h2M18 14h2M10 4v2M14 4v2M10 18v2M14 18v2"),
    API("M4 12h4M16 12h4M8 8h8v8H8z"),
    SANDBOX("M12 3l8 4.5v9L12 21l-8-4.5v-9zM4 7.5l8 4.5 8-4.5M12 12v9"),
    TERMINAL("M4 5h16v14H4zM7 9l3 3-3 3M12 15h5"),
    HOME("M3 11l9-7 9 7v9H3zM9 20v-6h6v6"),
    DOC("M7 3h7l5 5v13H7zM14 3v5h5M10 13h6M10 17h6"),
    LAYERS("M12 3l9 5-9 5-9-5zM3 13l9 5 9-5"),
    LINES("M4 6h16M4 12h16M4 18h10"),
    DISK("M3 13h18v6H3zM5 13l2-8h10l2 8M17 16h.01"),
    BRANCH("M6 4v10M6 14a3 3 0 1 1 0 6 3 3 0 0 1 0-6zM18 4a3 3 0 1 1 0 6 3 3 0 0 1 0-6zM18 10c0 4-4 5-9 5"),
    SHIELD("M12 3l7 3v5c0 5-3 8-7 10-4-2-7-5-7-10V6z"),
    KEY("M8 15a4 4 0 1 1 0-8 4 4 0 0 1 0 8zM12 11h9M18 11v3M15 11v2"),
    ARROW_RIGHT("M5 12h14M13 6l6 6-6 6"),
    ARROW_LEFT("M19 12H5M11 6l-6 6 6 6"),
    TREND("M4 17l4.5-5.5 3.5 3L19 6M5 9v6M19 12v6"),
    SEARCH("M11 4.5a6.5 6.5 0 1 1 0 13 6.5 6.5 0 0 1 0-13zM16 16l4.5 4.5"),
    CODE("M8 7l-5 5 5 5M16 7l5 5-5 5M13.5 5l-3 14"),
    MONITOR("M3 5h18v11H3zM8 20h8M12 16v4"),
    PHONE("M7 3h10v18H7zM11 18h2"),
    SUN("M12 8a4 4 0 1 1 0 8 4 4 0 0 1 0-8zM12 2v2M12 20v2M2 12h2M20 12h2M5 5l1.5 1.5M17.5 17.5L19 19M19 5l-1.5 1.5M6.5 17.5L5 19"),
    PEOPLE("M12 4a4 4 0 1 1 0 8 4 4 0 0 1 0-8zM4 21c0-4 3.5-6.5 8-6.5s8 2.5 8 6.5"),
    HEART("M12 20s-7-4.5-7-10a4 4 0 0 1 7-2.6A4 4 0 0 1 19 10c0 5.5-7 10-7 10z"),
    BOOK("M4 5a2 2 0 0 1 2-2h13v16H6a2 2 0 0 0-2 2zM4 19V5M8 7h7"),
    REPEAT("M17 2l3 3-3 3M4 11V9a4 4 0 0 1 4-4h12M7 22l-3-3 3-3M20 13v2a4 4 0 0 1-4 4H4"),
    BULB("M9 18h6M10 21h4M12 3a6 6 0 0 0-3.5 10.9c.6.5 1 1.2 1 2.1h5c0-.9.4-1.6 1-2.1A6 6 0 0 0 12 3z"),
    PLAY("M7 4l13 8-13 8z"),
    CALENDAR("M4 6h16v14H4zM4 10h16M9 3v4M15 3v4"),
    ALERT("M12 3a9 9 0 1 1 0 18 9 9 0 0 1 0-18zM12 8v5M12 16h.01"),
    GOAL("M12 3a9 9 0 1 1 0 18 9 9 0 0 1 0-18zM12 7a5 5 0 1 1 0 10 5 5 0 0 1 0-10zM12 11a1 1 0 1 1 0 2 1 1 0 0 1 0-2z"),
    SPARK("M12 3v18M3 12h18M5.6 5.6l12.8 12.8M18.4 5.6L5.6 18.4"),
    BRIEFCASE("M3 8h18v12H3zM8 8V5h8v3M3 13h18"),
    MOON("M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z"),
    LINK("M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1"),
    SEND("M22 2 11 13M22 2 15 22l-4-9-9-4 20-7Z"),
    GEAR("M12 8.8a3.2 3.2 0 1 1 0 6.4 3.2 3.2 0 0 1 0-6.4zM12 2.6v2.2M12 19.2v2.2M21.4 12h-2.2M4.8 12H2.6M18.6 5.4l-1.6 1.6M7 17l-1.6 1.6M18.6 18.6 17 17M7 7 5.4 5.4"),
}

private fun vectorOf(icon: JvIcon): ImageVector =
    ImageVector.Builder(icon.name, 24.dp, 24.dp, 24f, 24f)
        .addPath(
            pathData = addPathNodes(icon.path),
            stroke = SolidColor(Color.White),
            strokeLineWidth = 1.7f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
        .build()

/** A design-export line icon. Decorative unless [contentDescription] is given. */
@Composable
fun JvIconView(
    icon: JvIcon,
    modifier: Modifier = Modifier,
    tint: Color = Jv.Accent,
    size: Dp = 20.dp,
    contentDescription: String? = null,
) {
    val vector = remember(icon) { vectorOf(icon) }
    Icon(vector, contentDescription, modifier.size(size), tint = tint)
}
