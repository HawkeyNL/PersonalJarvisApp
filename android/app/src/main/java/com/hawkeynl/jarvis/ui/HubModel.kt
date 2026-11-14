package com.hawkeynl.jarvis.ui

import com.hawkeynl.jarvis.network.AgentsResponse
import com.hawkeynl.jarvis.network.AgentUsage
import com.hawkeynl.jarvis.network.Availability
import com.hawkeynl.jarvis.network.CodingSession
import com.hawkeynl.jarvis.network.ConnectionState
import com.hawkeynl.jarvis.network.DiskStatus
import com.hawkeynl.jarvis.network.IbkrError
import com.hawkeynl.jarvis.network.IbkrPortfolio
import com.hawkeynl.jarvis.network.IbkrStatus
import com.hawkeynl.jarvis.network.IbkrToken
import com.hawkeynl.jarvis.network.LinkedDevice
import com.hawkeynl.jarvis.network.LiveHost
import com.hawkeynl.jarvis.network.LoadFailure
import com.hawkeynl.jarvis.network.Registry
import com.hawkeynl.jarvis.network.ServiceStatus
import com.hawkeynl.jarvis.network.valueOrNull
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

// Pure presentation helpers for the tab roots and node pages (no Android or Compose
// imports), ported from desktop/src/hubModel.ts and nodeModels.ts.

/** Target of a notification deep link: a screen and, for Health, its node. */
data class DeepLink(val screen: Screen, val healthNode: String = "core")

/** The supported notification links; anything else is ignored. Links only
 *  navigate, never act. Unlock requests show above every tab, so their link
 *  opens Activity. */
fun parseDeepLink(uri: String?): DeepLink? {
    val parsed = runCatching { java.net.URI(uri) }.getOrNull() ?: return null
    if (!parsed.scheme.equals("jarvis", ignoreCase = true)) return null
    return when (parsed.host?.lowercase(Locale.ROOT) to parsed.path) {
        "approvals" to "/pairing" -> DeepLink(Screen.PAIRING_APPROVALS)
        "approvals" to "/unlock" -> DeepLink(Screen.ACTIVITY)
        "health" to "/usage" -> DeepLink(Screen.HEALTH, "usage")
        else -> null
    }
}

/** Status colour of a dot or label; every tone is always paired with text. */
enum class Tone { OK, IDLE, WARN, ERROR }

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
    LoadFailure.UNAVAILABLE -> "Core cannot provide this right now. Try again later."
    LoadFailure.FAILED -> "Core returned an error. Try again later."
}

/** "Good morning" before noon, "Good afternoon" until six, then "Good evening". */
fun greeting(hour: Int): String = when (hour) {
    in 5..11 -> "Good morning"
    in 12..17 -> "Good afternoon"
    else -> "Good evening"
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

/** "in under a minute", "in 4m", "in 3h", "in 2d"; "" for an unparseable time. A past
 *  time reads as "in under a minute". */
fun untilTime(iso: String?, now: Long): String {
    val at = parseInstant(iso) ?: return ""
    val diff = maxOf(0L, at - now)
    return when {
        diff < MINUTE -> "in under a minute"
        diff < HOUR -> "in ${diff / MINUTE}m"
        diff < DAY -> "in ${diff / HOUR}h"
        else -> "in ${diff / DAY}d"
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

/** What a home or settings row shows: two short lines and a status tone. */
data class CardSummary(val line1: String, val line2: String, val tone: Tone)

/** Both lines as one row detail: "1 active · 2 awaiting approval". */
val CardSummary.detail: String get() = listOf(line1, line2).filter { it.isNotEmpty() }.joinToString(" · ")

private fun hint(reason: LoadFailure) = when (reason) {
    LoadFailure.SIGNIN -> "Sign in again"
    LoadFailure.FORBIDDEN -> "Turned off in Core"
    LoadFailure.NETWORK -> "Check the connection"
    LoadFailure.UNAVAILABLE -> "Not available now"
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
    val waiting = pending.valueOrNull
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
    val live = registry.valueOrNull?.live_host
        ?: return CardSummary("Core ready", if (registry is Availability.Ok) "Live vitals need newer Core" else "", Tone.OK)
    val cpu = live.cpu_percent?.let { " · CPU ${Math.round(it)}%" } ?: ""
    return CardSummary("Up ${formatUptime(live.uptime_seconds)}", "Core ready$cpu", Tone.OK)
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

/** Core's last IBKR sync is older than this: still connected, but say so. */
private const val IBKR_STALE_AFTER_MS = 10 * MINUTE

/** Tone and label of the IBKR link, from GET /v1/trading/portfolio: a
 *  portfolio means connected, 503 means not connected or not synced yet. */
fun ibkrState(source: Availability<IbkrPortfolio>, now: Long): Pair<Tone, String> =
    if (source == Availability.Failed(LoadFailure.UNAVAILABLE)) {
        Tone.WARN to "Not connected"
    } else {
        statusOf(source) { p ->
            val at = parseInstant(p.as_of)
            if (at == null || now - at > IBKR_STALE_AFTER_MS) Tone.WARN to "Connected, sync is old" else Tone.OK to "Connected"
        }
    }

const val IBKR_NOT_SYNCED = "Core has no IBKR data yet: the IBKR connection is not set up, the first sync has not finished, " +
    "or Core cannot reach IBKR right now. Check the IBKR broker on your Home Node, then refresh."

// GET /v1/trading/ibkr/status. Unknown values read as "Unknown", never as a failure.

/** The pane's header, from `overall`. */
fun ibkrOverall(status: IbkrStatus): Pair<Tone, String> = when (status.overall) {
    "ok" -> Tone.OK to "Connected"
    "info" -> if (status.problems.isEmpty()) Tone.OK to "Connected" else Tone.IDLE to "Needs attention"
    "warn" -> Tone.WARN to "Needs attention"
    "error" -> Tone.ERROR to "Not working"
    "off" -> Tone.IDLE to "Off"
    else -> Tone.IDLE to "Unknown"
}

/** The broker unit and whether Core reached it; missing credentials come first. */
fun ibkrBroker(status: IbkrStatus): Pair<Tone, String> {
    // A stopped broker only matters while the sync is on.
    val stopped = if (status.syncEnabled == true) Tone.WARN else Tone.IDLE
    return when {
        status.credentials == "missing" -> Tone.ERROR to "Credentials missing"
        status.service == "active" || status.service == "activating" -> when (status.reachable) {
            true -> Tone.OK to "Running, reachable"
            false -> Tone.ERROR to "Running, not reachable"
            null -> Tone.OK to "Running"
        }
        status.service == "inactive" || status.service == "deactivating" -> stopped to "Stopped"
        status.service == "failed" -> Tone.ERROR to "Failed"
        status.service == "not_found" -> stopped to "Not installed"
        else -> Tone.IDLE to "Unknown"
    }
}

/** "On · Syncing", "Off", "Refused", …; an unknown state keeps On or Off. */
fun ibkrSync(status: IbkrStatus): Pair<Tone, String> = when (status.syncState) {
    "off" -> Tone.IDLE to "Off"
    "refused" -> Tone.ERROR to "Refused"
    "ok" -> Tone.OK to "On · Syncing"
    "starting" -> Tone.IDLE to "On · Starting"
    "failing" -> Tone.WARN to "On · Failing"
    "waiting_for_broker" -> Tone.WARN to "On · Waiting for the broker"
    else -> Tone.IDLE to when (status.syncEnabled) {
        true -> "On · Unknown"
        false -> "Off"
        null -> "Unknown"
    }
}

private val TOKEN_STATES = mapOf(
    "valid" to "Valid", "refresh_due" to "Refreshing soon", "expired" to "Expired", "rejected" to "Rejected", "none" to "Not created yet",
)

/** "Valid · refreshed 3h ago · expires in 21h · usable"; parts Core did not send are left out. */
fun ibkrToken(token: IbkrToken?, now: Long): String {
    if (token == null) return "Unknown"
    val refreshed = relativeTime(token.refreshedAt, now).takeIf { it.isNotEmpty() }?.let { "refreshed $it" }
    val expires = parseInstant(token.expiresAt)?.let {
        if (it > now) "expires ${untilTime(token.expiresAt, now)}" else "expired ${relativeTime(token.expiresAt, now)}"
    }
    val usable = token.usable?.let { if (it) "usable" else "not usable" }
    return listOfNotNull(TOKEN_STATES[token.state] ?: "Unknown", refreshed, expires, usable).joinToString(" · ")
}

/** Relative time of the last good sync, or "Never". */
fun ibkrLastSync(status: IbkrStatus, now: Long): String =
    if (status.lastSuccessAt == null) "Never" else relativeTime(status.lastSuccessAt, now).ifEmpty { "Unknown" }

/** The most recent error of the sync and the broker; null when there is none. */
fun ibkrLastError(status: IbkrStatus): IbkrError? =
    listOfNotNull(status.syncError, status.ibkr?.lastError).maxByOrNull { parseInstant(it.at) ?: Long.MIN_VALUE }

private val IBKR_ERRORS = mapOf(
    // The sync's codes.
    "broker_not_running" to "Broker not running",
    "broker_unavailable" to "Broker unavailable",
    "broker_busy" to "Broker busy",
    "broker_timeout" to "Broker timed out",
    "ibkr_auth" to "IBKR sign-in failed",
    "ibkr_rate_limited" to "Rate limited by IBKR",
    "ibkr_failed" to "IBKR request failed",
    "broker_protocol" to "Broker answer not readable",
    "account_unsupported" to "Account not supported",
    "data_refused" to "Data refused",
    "store_failed" to "Could not store the portfolio",
    "socket_path_invalid" to "Broker socket path invalid",
    "broker_account_missing" to "Broker account missing",
    "broker_account_unsafe" to "Broker account unsafe",
    // The broker's codes.
    "auth_rejected" to "Rejected by IBKR",
    "not_authenticated" to "Not signed in to IBKR",
    "rate_limited" to "Rate limited by IBKR",
    "upstream" to "IBKR error",
    "busy" to "Broker busy",
    "invalid_input" to "Invalid request",
    "unknown" to "Unknown",
)

/** Label of an error code; null for a code this app does not know (shown as the code). */
fun ibkrErrorLabel(code: String?): String? = if (code == null) "Unknown" else IBKR_ERRORS[code]

fun ibkrBrokerage(session: String?): String = when (session) {
    "open" -> "Open"
    "closed" -> "Closed"
    "competing" -> "In use elsewhere"
    else -> "Unknown"
}

/** "14:05 · in 3m" in the device's time zone and locale; null when not rate limited. */
fun ibkrRateLimit(status: IbkrStatus, now: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String? {
    val iso = status.ibkr?.rateLimitedUntil ?: return null
    val at = parseInstant(iso) ?: return "Unknown"
    val clock = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).withZone(zone).format(Instant.ofEpochMilli(at))
    return "$clock · ${untilTime(iso, now)}"
}

fun problemSeverity(severity: String?): Pair<Tone, String> = when (severity) {
    "error" -> Tone.ERROR to "Error"
    "warn" -> Tone.WARN to "Warning"
    "info" -> Tone.IDLE to "Info"
    else -> Tone.IDLE to "Unknown"
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

/** The System health view a node id opens on (a deep link names "usage"). */
fun healthView(node: String): String = when (node) {
    "usage", "models" -> node
    else -> "status"
}

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

/** One resource bar; [percent] is null when Core has no reading. */
data class Meter(val id: String, val label: String, val percent: Int?, val tone: Tone, val detail: String)

private fun loadTone(percent: Double): Tone = when {
    percent >= 90 -> Tone.ERROR
    percent >= 80 -> Tone.WARN
    else -> Tone.OK
}

private fun spaceDetail(used: Long, total: Long) = "${gib(used)} used · ${gib(total - used)} free of ${gib(total)}"

/** CPU, memory and disk bars for the Home Node, in that order; each warns at 80% and 90%. */
fun resourceMeters(live: LiveHost?, disks: List<DiskStatus>): List<Meter> = buildList {
    if (live != null) {
        val cpu = live.cpu_percent
        add(
            if (cpu == null) Meter("cpu", "CPU", null, Tone.IDLE, "First sample…")
            else Meter("cpu", "CPU", Math.round(cpu).toInt(), loadTone(cpu), String.format(Locale.ROOT, "%.1f%% in use", cpu)),
        )
        val used = live.memory_used_bytes
        val total = live.memory_total_bytes
        val pct = if (total > 0) used * 100.0 / total else 0.0
        add(
            if (total > 0) Meter("memory", "Memory", Math.round(pct).toInt(), loadTone(pct), spaceDetail(used, total))
            else Meter("memory", "Memory", null, Tone.IDLE, "Unknown"),
        )
    }
    disks.forEachIndexed { i, disk ->
        val id = "disk-${disk.label}-$i"
        val label = "Disk · ${disk.label}"
        val (tone) = diskState(disk)
        val total = disk.total_bytes
        val free = disk.free_bytes
        val used = disk.used_percent
        add(
            if (tone == Tone.IDLE || total == null || free == null || used == null) Meter(id, label, null, Tone.IDLE, "Unknown")
            else Meter(id, label, Math.round(used).toInt(), tone, spaceDetail(total - free, total)),
        )
    }
}

// --- Devices ---------------------------------------------------------------------------------

/** True for this phone's own entry; false while its id is unknown. */
fun isOwnDevice(device: LinkedDevice, own: String?): Boolean = own != null && device.id.equals(own, ignoreCase = true)

/** Revoke is offered only for another active device, and only once this phone's own id is known. */
fun revocable(device: LinkedDevice, own: String?): Boolean = own != null && device.status == "active" && !isOwnDevice(device, own)
