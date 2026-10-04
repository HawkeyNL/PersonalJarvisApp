package com.hawkeynl.jarvis.ui

import com.hawkeynl.jarvis.network.AgentsResponse
import com.hawkeynl.jarvis.network.AgentUsage
import com.hawkeynl.jarvis.network.Availability
import com.hawkeynl.jarvis.network.CodingSession
import com.hawkeynl.jarvis.network.ConnectionState
import com.hawkeynl.jarvis.network.DiskStatus
import com.hawkeynl.jarvis.network.IbkrStatus
import com.hawkeynl.jarvis.network.LoadFailure
import com.hawkeynl.jarvis.network.Registry
import com.hawkeynl.jarvis.network.ServiceStatus
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Locale

// Pure presentation helpers for the hub and node pages (no Android or Compose
// imports), ported from desktop/src/hubModel.ts and nodeModels.ts.

/** Status colour of a dot or label; every tone is always paired with text. */
enum class Tone { OK, IDLE, WARN, ERROR }

/** What the orb shows. */
enum class Mood { IDLE, LISTENING, THINKING }

/** Animation-duration multiplier per mood (export: x1 / x0.7 / x0.4). */
fun moodFactor(mood: Mood): Float = when (mood) {
    Mood.THINKING -> 0.4f
    Mood.LISTENING -> 0.7f
    Mood.IDLE -> 1f
}

/** Reachability of Core from the connection check: null while unknown. */
fun onlineOf(connection: ConnectionState): Boolean? = when (connection) {
    is ConnectionState.Reachable -> true
    is ConnectionState.Unreachable, is ConnectionState.Rejected -> false
    ConnectionState.Checking, ConnectionState.NotConfigured -> null
}

/** One-line explanation of a failed read; [forbidden] names what Core turned off. */
fun failureText(reason: LoadFailure, forbidden: String = "Turned off in Core."): String = when (reason) {
    LoadFailure.SIGNIN -> "Sign in again."
    LoadFailure.FORBIDDEN -> forbidden
    LoadFailure.NETWORK -> "Check the connection to your Home Node."
    LoadFailure.FAILED -> "Core returned an error. Try again later."
}

private const val MINUTE = 60_000L
private const val HOUR = 60 * MINUTE
private const val DAY = 24 * HOUR

/** Epoch millis of an ISO-8601 timestamp, or null when it does not parse. */
fun parseInstant(iso: String?): Long? {
    if (iso.isNullOrBlank()) return null
    return runCatching { Instant.parse(iso).toEpochMilli() }
        .recoverCatching { OffsetDateTime.parse(iso).toInstant().toEpochMilli() }
        .getOrNull()
}

/** "just now", "4m ago", "2h ago", "3d ago"; "" for an unparseable time. */
fun relativeTime(iso: String?, now: Long): String {
    val at = parseInstant(iso) ?: return ""
    val diff = maxOf(0L, now - at)
    return when {
        diff < MINUTE -> "just now"
        diff < HOUR -> "${diff / MINUTE}m ago"
        diff < DAY -> "${diff / HOUR}h ago"
        else -> "${diff / DAY}d ago"
    }
}

/** Number of timestamps within the last [days] days. */
fun countSince(timestamps: List<String>, days: Int, now: Long): Int {
    val since = now - days * DAY
    return timestamps.count { (parseInstant(it) ?: Long.MIN_VALUE) >= since }
}

/** "14d 2h", "3h 12m", "5m" from seconds of uptime. */
fun formatUptime(seconds: Long): String {
    val s = maxOf(0L, seconds)
    val d = s / 86_400
    val h = (s % 86_400) / 3600
    val m = (s % 3600) / 60
    return when {
        d > 0 -> "${d}d ${h}h"
        h > 0 -> "${h}h ${m}m"
        else -> "${m}m"
    }
}

fun gib(bytes: Long): String = String.format(Locale.ROOT, "%.1f GiB", bytes / 1024.0 / 1024.0 / 1024.0)
fun eur(amount: Double): String = String.format(Locale.ROOT, "€%.2f", amount)

private fun plural(n: Int, one: String, many: String = "${one}s") = "$n ${if (n == 1) one else many}"

/** What a hub card shows: two short lines and a status tone. */
data class CardSummary(val line1: String, val line2: String, val tone: Tone)

private fun hint(reason: LoadFailure) = when (reason) {
    LoadFailure.SIGNIN -> "Sign in again"
    LoadFailure.FORBIDDEN -> "Turned off in Core"
    LoadFailure.NETWORK -> "Check the connection"
    LoadFailure.FAILED -> "Core error"
}

/** Shared handling of loading / older Core / failed reads for a card. */
fun <T> summarize(source: Availability<T>, ok: (T) -> CardSummary): CardSummary = when (source) {
    Availability.Loading -> CardSummary("Loading…", "", Tone.IDLE)
    Availability.Unsupported -> CardSummary("Requires newer Core", "", Tone.IDLE)
    is Availability.Failed -> CardSummary("Unavailable", hint(source.reason), Tone.WARN)
    is Availability.Ok -> ok(source.value)
}

fun conversationsCard(updatedAt: List<String>, now: Long): CardSummary {
    if (updatedAt.isEmpty()) return CardSummary("No conversations yet", "Start one above", Tone.IDLE)
    val last = relativeTime(updatedAt.maxByOrNull { parseInstant(it) ?: Long.MIN_VALUE }, now)
    return CardSummary("${countSince(updatedAt, 7, now)} this week", if (last.isEmpty()) "" else "Last: $last", Tone.OK)
}

fun agentsCard(source: Availability<AgentsResponse>): CardSummary = summarize(source) {
    val count = it.agent_count ?: it.agents.size
    CardSummary(plural(count, "agent"), if (count > 0) "Configured on Core" else "None configured", if (count > 0) Tone.OK else Tone.IDLE)
}

fun tasksCard(sessions: Availability<List<CodingSession>>, pending: Availability<Int>): CardSummary = summarize(sessions) { list ->
    val active = list.count { it.state == "active" }
    val waiting = (pending as? Availability.Ok)?.value
    CardSummary(
        "$active active",
        if (waiting == null) "Approvals unavailable" else "$waiting awaiting approval",
        when {
            (waiting ?: 0) > 0 -> Tone.WARN
            active > 0 -> Tone.OK
            else -> Tone.IDLE
        },
    )
}

fun integrationsCard(source: Availability<Registry>): CardSummary = summarize(source) { registry ->
    val present = registry.software.count { it.present }
    val missing = registry.software.size - present
    CardSummary(
        "$present of ${registry.software.size} present",
        if (missing > 0) "$missing missing" else "All present",
        if (missing > 0) Tone.WARN else Tone.OK,
    )
}

fun contextCard(devices: Availability<Int>): CardSummary = summarize(devices) {
    CardSummary(plural(it, "device") + " linked", "Modes not yet available", if (it > 0) Tone.OK else Tone.IDLE)
}

fun healthCard(online: Boolean?, registry: Availability<Registry>): CardSummary {
    if (online == null) return CardSummary("Checking…", "", Tone.IDLE)
    if (!online) return CardSummary("Core unreachable", "Check the Home Node", Tone.ERROR)
    val live = (registry as? Availability.Ok)?.value?.live_host
        ?: return CardSummary("Core ready", if (registry is Availability.Ok) "Live vitals need newer Core" else "", Tone.OK)
    val cpu = live.cpu_percent?.let { " · CPU ${Math.round(it)}%" } ?: ""
    return CardSummary("Up ${formatUptime(live.uptime_seconds)}", "Core ready$cpu", Tone.OK)
}

/** Label, hint and tone of the status pill under the orb. */
data class StatusPill(val label: String, val hint: String, val tone: Tone)

fun statusPill(mood: Mood, online: Boolean?): StatusPill = when {
    online == false -> StatusPill("Offline", "Home Node unreachable", Tone.ERROR)
    online == null -> StatusPill("Connecting", "Reaching your Home Node…", Tone.IDLE)
    mood == Mood.THINKING -> StatusPill("Thinking", "Working on your request…", Tone.OK)
    mood == Mood.LISTENING -> StatusPill("Listening", "Speak now…", Tone.OK)
    else -> StatusPill("Ready", "Type to begin", Tone.OK)
}

// --- Conversations --------------------------------------------------------------

/** Items whose title contains [query] (case-insensitive) and, when [days] is
 *  set, were updated within the last [days] days. */
fun <T> filterByTitle(list: List<T>, query: String, days: Int?, now: Long, title: (T) -> String, updatedAt: (T) -> String): List<T> {
    val q = query.trim().lowercase(Locale.ROOT)
    val since = if (days == null) Long.MIN_VALUE else now - days * DAY
    return list.filter { title(it).lowercase(Locale.ROOT).contains(q) && (parseInstant(updatedAt(it)) ?: 0L) >= since }
}

// --- Tasks ------------------------------------------------------------------------

/** Tone and label of a coding session state; unknown states stay neutral. */
fun sessionState(state: String): Pair<Tone, String> = when (state) {
    "active" -> Tone.OK to "Running"
    "suspended" -> Tone.WARN to "Paused"
    "completed" -> Tone.OK to "Completed"
    "cancelled" -> Tone.IDLE to "Cancelled"
    "archived" -> Tone.IDLE to "Archived"
    else -> Tone.IDLE to state.ifEmpty { "Unknown" }
}

/** Open (running or paused) and finished coding sessions, order kept. */
fun splitSessions(list: List<CodingSession>): Pair<List<CodingSession>, List<CodingSession>> =
    list.partition { it.state == "active" || it.state == "suspended" }

/** Tone of an agent audit outcome ("ok", "denied", "error", …). */
fun outcomeTone(outcome: String): Tone = when (outcome) {
    "ok" -> Tone.OK
    "error" -> Tone.ERROR
    "denied" -> Tone.WARN
    else -> Tone.IDLE
}

// --- Integrations -------------------------------------------------------------------

/** Tone and label of the IBKR gateway link. */
fun ibkrState(status: IbkrStatus): Pair<Tone, String> = when {
    !status.reachable -> Tone.WARN to "Gateway unreachable"
    !status.authenticated -> Tone.WARN to "Not logged in"
    status.connected == false -> Tone.WARN to "Logged in, not connected"
    else -> Tone.OK to "Connected"
}

/** "3 of 9" style count of items with a truthy flag. */
fun <T> countOf(list: List<T>, flag: (T) -> Boolean): String = "${list.count(flag)} of ${list.size}"

// --- Agents -----------------------------------------------------------------------------

const val AGENT_USAGE_NOT_INSTRUMENTED = "agent_usage_not_instrumented"

/** What an agent's status line may honestly say: its usage this month, never
 *  a live Active/Idle state (Core does not report one). */
fun agentStatus(usage: AgentUsage?, unavailableReason: String?): Pair<Tone, String> = when {
    usage == null -> Tone.IDLE to if (unavailableReason == AGENT_USAGE_NOT_INSTRUMENTED) "Not measured yet" else "Usage unavailable"
    usage.requests == 0L -> Tone.IDLE to "Not used this month"
    else -> Tone.OK to "${usage.requests} ${if (usage.requests == 1L) "request" else "requests"} this month"
}

// --- Health ---------------------------------------------------------------------------------

/** Tone and label of a systemd unit state as Core reports it. */
fun serviceState(state: String): Pair<Tone, String> = when (state) {
    "active" -> Tone.OK to "Running"
    "failed" -> Tone.ERROR to "Failed"
    "not_found" -> Tone.IDLE to "Not installed"
    "", "unknown" -> Tone.IDLE to "Unknown"
    else -> Tone.WARN to state.replaceFirstChar { it.uppercase(Locale.ROOT) }
}

/** Summary for the services satellite. */
fun servicesSummary(services: List<ServiceStatus>): Pair<Tone, String> {
    if (services.isEmpty()) return Tone.IDLE to "No services reported"
    val failed = services.count { it.state == "failed" }
    if (failed > 0) return Tone.ERROR to "$failed failed"
    val running = services.count { it.state == "active" }
    return (if (running == services.size) Tone.OK else Tone.WARN) to "$running of ${services.size} running"
}

/** Tone and one-line summary of a disk; full disks warn early. */
fun diskState(disk: DiskStatus): Pair<Tone, String> {
    val used = disk.used_percent
    val free = disk.free_bytes
    val total = disk.total_bytes
    if (disk.state != "ok" || used == null || free == null || total == null) return Tone.IDLE to "Unknown"
    val tone = when {
        used >= 90 -> Tone.ERROR
        used >= 80 -> Tone.WARN
        else -> Tone.OK
    }
    return tone to "${Math.round(used)}% used · ${gib(free)} free of ${gib(total)}"
}
