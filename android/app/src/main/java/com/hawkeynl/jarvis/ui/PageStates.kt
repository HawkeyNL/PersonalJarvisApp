package com.hawkeynl.jarvis.ui

import androidx.compose.runtime.Composable
import com.hawkeynl.jarvis.network.Availability

/** Status of an optional Core source. */
fun <T> statusOf(source: Availability<T>, ok: (T) -> Pair<Tone, String>): Pair<Tone, String> = when (source) {
    is Availability.Ok -> ok(source.value)
    Availability.Loading -> Tone.IDLE to "Loading…"
    Availability.Unsupported -> Tone.IDLE to "Requires newer Core"
    is Availability.Failed -> Tone.WARN to "Unavailable"
}

/** A skeleton while [source] loads, "Requires newer Core" or the failure (with [onRetry]);
 *  [content] once it is there. */
@Composable
fun <T> Loaded(
    source: Availability<T>,
    title: String,
    icon: JvIcon,
    loading: String = "Loading…",
    forbidden: String = "Turned off in Core.",
    onRetry: (() -> Unit)? = null,
    content: @Composable (T) -> Unit,
) {
    when (source) {
        Availability.Loading -> Skeleton(loading)
        Availability.Unsupported -> Unavailable(title, icon, kind = UnavailableKind.CORE_UPDATE, detail = "This Core does not offer this yet. Update Core to see it here.")
        is Availability.Failed -> Unavailable(title, icon, kind = UnavailableKind.ERROR, detail = failureText(source.reason, forbidden), onRetry = onRetry)
        is Availability.Ok -> content(source.value)
    }
}
