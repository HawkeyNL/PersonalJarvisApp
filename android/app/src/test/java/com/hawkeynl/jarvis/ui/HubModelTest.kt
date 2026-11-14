package com.hawkeynl.jarvis.ui

import com.hawkeynl.jarvis.network.AgentUsage
import com.hawkeynl.jarvis.network.AgentsResponse
import com.hawkeynl.jarvis.network.Availability
import com.hawkeynl.jarvis.network.CodingSession
import com.hawkeynl.jarvis.network.ConnectionState
import com.hawkeynl.jarvis.network.DiskStatus
import com.hawkeynl.jarvis.network.IbkrError
import com.hawkeynl.jarvis.network.IbkrLink
import com.hawkeynl.jarvis.network.IbkrPortfolio
import com.hawkeynl.jarvis.network.IbkrProblem
import com.hawkeynl.jarvis.network.IbkrStatus
import com.hawkeynl.jarvis.network.IbkrToken
import com.hawkeynl.jarvis.network.LinkedDevice
import com.hawkeynl.jarvis.network.LiveHost
import com.hawkeynl.jarvis.network.LoadFailure
import com.hawkeynl.jarvis.network.Registry
import com.hawkeynl.jarvis.network.ServiceStatus
import com.hawkeynl.jarvis.network.SoftwareItem
import com.hawkeynl.jarvis.network.UnreachableReason
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HubModelTest {
    private val now = Instant.parse("2026-10-04T12:00:00Z").toEpochMilli()

    @Test
    fun `deep links only map the supported routes`() {
        assertEquals(DeepLink(Screen.PAIRING_APPROVALS), parseDeepLink("jarvis://approvals/pairing"))
        assertEquals(DeepLink(Screen.ACTIVITY), parseDeepLink("jarvis://approvals/unlock"))
        assertNull(parseDeepLink("jarvis://approvals/unlock/approve"))
        assertEquals(DeepLink(Screen.HEALTH, "usage"), parseDeepLink("JARVIS://Health/usage"))
        assertEquals(DeepLink(Screen.HEALTH, "usage"), parseDeepLink("jarvis://health/usage?approve=1"))
        assertNull(parseDeepLink("jarvis://approvals/pairing/approve"))
        assertNull(parseDeepLink("jarvis://approvals"))
        assertNull(parseDeepLink("jarvis://health/models"))
        assertNull(parseDeepLink("https://approvals/pairing"))
        assertNull(parseDeepLink("not a uri"))
        assertNull(parseDeepLink(null))
    }

    @Test
    fun `relative time covers minutes hours days and bad input`() {
        assertEquals("just now", relativeTime("2026-10-04T11:59:30Z", now))
        assertEquals("4m ago", relativeTime("2026-10-04T11:56:00Z", now))
        assertEquals("2h ago", relativeTime("2026-10-04T10:00:00+00:00", now))
        assertEquals("3d ago", relativeTime("2026-10-01T11:00:00Z", now))
        assertEquals("just now", relativeTime("2026-10-05T12:00:00Z", now))
        assertEquals("", relativeTime("yesterday", now))
        assertEquals("", relativeTime(null, now))
    }

    @Test
    fun `uptime and week counts`() {
        assertEquals("14d 2h", formatUptime(14 * 86_400L + 2 * 3600 + 59))
        assertEquals("3h 12m", formatUptime(3 * 3600L + 12 * 60))
        assertEquals("5m", formatUptime(300))
        assertEquals("0m", formatUptime(-5))
        assertEquals(2, countSince(listOf("2026-10-04T08:00:00Z", "2026-09-30T08:00:00Z", "2026-09-01T08:00:00Z", "bad"), 7, now))
    }

    @Test
    fun `cards say why data is missing instead of showing numbers`() {
        assertEquals(CardSummary("Loading…", "", Tone.IDLE), agentsCard(Availability.Loading))
        assertEquals(CardSummary("Requires newer Core", "", Tone.IDLE), agentsCard(Availability.Unsupported))
        assertEquals(CardSummary("Unavailable", "Check the connection", Tone.WARN), agentsCard(Availability.Failed(LoadFailure.NETWORK)))
        assertEquals(CardSummary("1 agent", "Configured on Core", Tone.OK), agentsCard(Availability.Ok(AgentsResponse(agent_count = 1))))
        assertEquals(CardSummary("No conversations yet", "Start one above", Tone.IDLE), conversationsCard(emptyList(), now))
        assertEquals(
            CardSummary("1 this week", "Last: 2h ago", Tone.OK),
            conversationsCard(listOf("2026-10-04T10:00:00Z", "2026-08-01T10:00:00Z"), now),
        )
    }

    @Test
    fun `tasks card warns when approvals wait`() {
        val sessions = Availability.Ok(listOf(CodingSession("a", state = "active"), CodingSession("b", state = "completed")))
        assertEquals(CardSummary("1 active", "2 awaiting approval", Tone.WARN), tasksCard(sessions, Availability.Ok(2)))
        assertEquals(CardSummary("1 active", "Approvals unavailable", Tone.OK), tasksCard(sessions, Availability.Unsupported))
        assertEquals(CardSummary("0 active", "0 awaiting approval", Tone.IDLE), tasksCard(Availability.Ok(emptyList()), Availability.Ok(0)))
    }

    @Test
    fun `integrations context and health cards`() {
        val registry = Registry(software = listOf(SoftwareItem("git", present = true), SoftwareItem("node")))
        assertEquals(CardSummary("1 of 2 present", "1 missing", Tone.WARN), integrationsCard(Availability.Ok(registry)))
        assertEquals(CardSummary("2 devices linked", "Modes not yet available", Tone.OK), contextCard(Availability.Ok(2)))
        assertEquals(CardSummary("Checking…", "", Tone.IDLE), healthCard(null, Availability.Loading))
        assertEquals(CardSummary("Core unreachable", "Check the Home Node", Tone.ERROR), healthCard(false, Availability.Loading))
        assertEquals(CardSummary("Core ready", "Live vitals need newer Core", Tone.OK), healthCard(true, Availability.Ok(registry)))
        val live = registry.copy(live_host = LiveHost(cpu_percent = 12.4, uptime_seconds = 90_000))
        assertEquals(CardSummary("Up 1d 1h", "Core ready · CPU 12%", Tone.OK), healthCard(true, Availability.Ok(live)))
    }

    @Test
    fun `connection mapping and orb speed`() {
        assertEquals(true, onlineOf(ConnectionState.Reachable("ready")))
        assertEquals(false, onlineOf(ConnectionState.Unreachable(UnreachableReason.DNS)))
        assertEquals(null, onlineOf(ConnectionState.Checking))
    }

    @Test
    fun `IBKR link state comes from the portfolio read`() {
        val synced = IbkrPortfolio(as_of = "2026-10-04T11:55:00Z")
        assertEquals(Tone.OK to "Connected", ibkrState(Availability.Ok(synced), now))
        assertEquals(Tone.WARN to "Connected, sync is old", ibkrState(Availability.Ok(IbkrPortfolio(as_of = "2026-10-04T11:00:00Z")), now))
        assertEquals(Tone.WARN to "Connected, sync is old", ibkrState(Availability.Ok(IbkrPortfolio(as_of = "not a date")), now))
        assertEquals(Tone.WARN to "Not connected", ibkrState(Availability.Failed(LoadFailure.UNAVAILABLE), now))
        assertEquals(Tone.WARN to "Unavailable", ibkrState(Availability.Failed(LoadFailure.FAILED), now))
        assertEquals(Tone.IDLE to "Requires newer Core", ibkrState(Availability.Unsupported, now))
        assertEquals(Tone.IDLE to "Loading…", ibkrState(Availability.Loading, now))
        assertFalse(IBKR_NOT_SYNCED.contains("gateway", ignoreCase = true))
    }

    @Test
    fun `future time helper`() {
        assertEquals("now", untilTime("2026-10-04T12:00:30Z", now))
        assertEquals("in 4m", untilTime("2026-10-04T12:04:00Z", now))
        assertEquals("in 3h", untilTime("2026-10-04T15:00:00+00:00", now))
        assertEquals("in 2d", untilTime("2026-10-06T12:00:00Z", now))
        assertEquals("now", untilTime("2026-10-04T11:00:00Z", now))
        assertEquals("", untilTime("tomorrow", now))
        assertEquals("", untilTime(null, now))
    }

    private val status = IbkrStatus(
        overall = "ok", syncEnabled = true, syncState = "ok", lastSuccessAt = "2026-10-04T11:58:00Z", syncError = null,
        service = "active", credentials = "present", reachable = true, token = null, ibkr = null, problems = emptyList(),
    )
    private val problem = IbkrProblem("token_expired", "info", "The IBKR session token expired.", emptyList())

    @Test
    fun `IBKR status header follows overall`() {
        assertEquals(Tone.OK to "Connected", ibkrOverall(status))
        assertEquals(Tone.OK to "Connected", ibkrOverall(status.copy(overall = "info")))
        assertEquals(Tone.IDLE to "Needs attention", ibkrOverall(status.copy(overall = "info", problems = listOf(problem))))
        assertEquals(Tone.WARN to "Needs attention", ibkrOverall(status.copy(overall = "warn")))
        assertEquals(Tone.ERROR to "Not working", ibkrOverall(status.copy(overall = "error")))
        assertEquals(Tone.IDLE to "Off", ibkrOverall(status.copy(overall = "off")))
        assertEquals(Tone.IDLE to "Unknown", ibkrOverall(status.copy(overall = "later")))
        assertEquals(Tone.IDLE to "Unknown", ibkrOverall(status.copy(overall = null)))
    }

    @Test
    fun `IBKR broker and sync rows`() {
        assertEquals(Tone.OK to "Running, reachable", ibkrBroker(status))
        assertEquals(Tone.ERROR to "Running, not reachable", ibkrBroker(status.copy(reachable = false)))
        assertEquals(Tone.OK to "Running", ibkrBroker(status.copy(reachable = null)))
        assertEquals(Tone.WARN to "Stopped", ibkrBroker(status.copy(service = "inactive")))
        assertEquals(Tone.IDLE to "Stopped", ibkrBroker(status.copy(service = "inactive", syncEnabled = false)))
        assertEquals(Tone.ERROR to "Failed", ibkrBroker(status.copy(service = "failed")))
        assertEquals(Tone.WARN to "Not installed", ibkrBroker(status.copy(service = "not_found")))
        assertEquals(Tone.IDLE to "Unknown", ibkrBroker(status.copy(service = null)))
        assertEquals(Tone.ERROR to "Credentials missing", ibkrBroker(status.copy(service = "failed", credentials = "missing")))

        assertEquals(Tone.OK to "On · Syncing", ibkrSync(status))
        assertEquals(Tone.IDLE to "On · Starting", ibkrSync(status.copy(syncState = "starting")))
        assertEquals(Tone.WARN to "On · Failing", ibkrSync(status.copy(syncState = "failing")))
        assertEquals(Tone.WARN to "On · Waiting for the broker", ibkrSync(status.copy(syncState = "waiting_for_broker")))
        assertEquals(Tone.IDLE to "Off", ibkrSync(status.copy(syncState = "off", syncEnabled = false)))
        assertEquals(Tone.ERROR to "Refused", ibkrSync(status.copy(syncState = "refused")))
        assertEquals(Tone.IDLE to "On · Unknown", ibkrSync(status.copy(syncState = "later")))
        assertEquals(Tone.IDLE to "Unknown", ibkrSync(status.copy(syncState = null, syncEnabled = null)))
    }

    @Test
    fun `IBKR token, sync time, error, session and rate limit`() {
        val token = IbkrToken("valid", true, "2026-10-04T09:00:00Z", "2026-10-05T09:00:00Z")
        assertEquals("Valid · refreshed 3h ago · expires in 21h · usable", ibkrToken(token, now))
        assertEquals("Refreshing soon", ibkrToken(IbkrToken("refresh_due", null, null, null), now))
        assertEquals("Expired · expired 1h ago · not usable", ibkrToken(IbkrToken("expired", false, null, "2026-10-04T11:00:00Z"), now))
        assertEquals("Rejected", ibkrToken(IbkrToken("rejected", null, null, null), now))
        assertEquals("Not created yet", ibkrToken(IbkrToken("none", null, null, null), now))
        assertEquals("Unknown", ibkrToken(IbkrToken("later", null, null, null), now))
        assertEquals("Unknown", ibkrToken(null, now))

        assertEquals("2m ago", ibkrLastSync(status, now))
        assertEquals("Never", ibkrLastSync(status.copy(lastSuccessAt = null), now))

        assertNull(ibkrLastError(status))
        val sync = IbkrError("broker_timeout", "2026-10-04T10:00:00Z")
        val broker = IbkrError("rate_limited", "2026-10-04T11:00:00Z")
        assertEquals(broker, ibkrLastError(status.copy(syncError = sync, ibkr = IbkrLink(broker, null, "open"))))
        assertEquals(sync, ibkrLastError(status.copy(syncError = sync)))
        // A parseable time beats none; a tie keeps the sync's error.
        assertEquals(broker, ibkrLastError(status.copy(syncError = IbkrError("broker_busy", null), ibkr = IbkrLink(broker, null, "open"))))
        assertEquals(sync, ibkrLastError(status.copy(syncError = sync, ibkr = IbkrLink(sync.copy(code = "busy"), null, "open"))))
        assertEquals("IBKR refused the login", ibkrErrorLabel("ibkr_auth"))
        assertEquals("Unreadable broker answer", ibkrErrorLabel("broker_protocol"))
        assertEquals("IBKR data refused", ibkrErrorLabel("data_refused"))
        assertEquals("IBKR request failed", ibkrErrorLabel("upstream"))
        assertEquals("Broker timed out", ibkrErrorLabel("broker_timeout"))
        assertEquals("Rejected by IBKR", ibkrErrorLabel("auth_rejected"))
        assertEquals("Unknown", ibkrErrorLabel("unknown"))
        assertEquals("Unknown", ibkrErrorLabel(null))
        assertNull(ibkrErrorLabel("future_code"))

        assertEquals("Open", ibkrBrokerage("open"))
        assertEquals("Closed", ibkrBrokerage("closed"))
        assertEquals("In use elsewhere", ibkrBrokerage("competing"))
        assertEquals("Unknown", ibkrBrokerage(null))

        val utc = ZoneId.of("UTC")
        assertNull(ibkrRateLimit(status, now, utc, Locale.UK))
        assertNull(ibkrRateLimit(status.copy(ibkr = IbkrLink(null, null, "open")), now, utc, Locale.UK))
        assertEquals("12:05 · in 5m", ibkrRateLimit(status.copy(ibkr = IbkrLink(null, "2026-10-04T12:05:00Z", "open")), now, utc, Locale.UK))
        assertEquals("14:05 · in 5m", ibkrRateLimit(status.copy(ibkr = IbkrLink(null, "2026-10-04T12:05:00Z", "open")), now, ZoneId.of("Europe/Amsterdam"), Locale.UK))
        assertEquals("11:59 · now", ibkrRateLimit(status.copy(ibkr = IbkrLink(null, "2026-10-04T11:59:00Z", "open")), now, utc, Locale.UK))
        assertNull(ibkrRateLimit(status.copy(ibkr = IbkrLink(null, "soon", "open")), now, utc, Locale.UK))

        assertEquals(Tone.ERROR to "Error", problemSeverity("error"))
        assertEquals(Tone.WARN to "Warning", problemSeverity("warn"))
        assertEquals(Tone.IDLE to "Info", problemSeverity("info"))
        assertEquals(Tone.IDLE to "Unknown", problemSeverity("later"))
    }

    @Test
    fun `node page state mapping`() {
        assertEquals(Tone.WARN to "Paused", sessionState("suspended"))
        assertEquals(Tone.IDLE to "weird", sessionState("weird"))
        val (open, finished) = splitSessions(listOf(CodingSession("a", state = "completed"), CodingSession("b", state = "suspended")))
        assertEquals(listOf("b"), open.map { it.id })
        assertEquals(listOf("a"), finished.map { it.id })
        assertEquals(Tone.IDLE to "Not measured yet", agentStatus(null, AGENT_USAGE_NOT_INSTRUMENTED))
        assertEquals(Tone.OK to "1 request this month", agentStatus(AgentUsage(requests = 1), null))
        assertEquals(Tone.ERROR to "1 failed", servicesSummary(listOf(ServiceStatus(state = "active"), ServiceStatus(state = "failed"))))
        assertEquals(Tone.WARN to "Activating", serviceState("activating"))
        assertEquals(Tone.IDLE to "Unknown", diskState(DiskStatus(state = "ok")))
        val disk = DiskStatus(state = "ok", total_bytes = 100L * 1024 * 1024 * 1024, free_bytes = 15L * 1024 * 1024 * 1024, used_percent = 85.0)
        assertEquals(Tone.WARN to "85% used · 15.0 GiB free of 100.0 GiB", diskState(disk))
    }

    @Test
    fun `resource meters show CPU, memory and every disk with used and free space`() {
        val g = 1024L * 1024 * 1024
        val live = LiveHost(sampled_at = 1, cpu_percent = 12.34, memory_total_bytes = 32 * g, memory_used_bytes = 28 * g, uptime_seconds = 60)
        val disks = listOf(
            DiskStatus(label = "system", state = "ok", total_bytes = 100 * g, free_bytes = 5 * g, used_percent = 95.0),
            DiskStatus(label = "data", state = "unknown"),
        )
        assertEquals(
            listOf(
                Meter("cpu", "CPU", 12, Tone.OK, "12.3% in use"),
                Meter("memory", "Memory", 88, Tone.WARN, "28.0 GiB used · 4.0 GiB free of 32.0 GiB"),
                Meter("disk-system-0", "Disk · system", 95, Tone.ERROR, "95.0 GiB used · 5.0 GiB free of 100.0 GiB"),
                Meter("disk-data-1", "Disk · data", null, Tone.IDLE, "Unknown"),
            ),
            resourceMeters(live, disks),
        )
        // The first CPU sample and an older Core without live vitals have no reading.
        assertEquals(
            listOf(null to "First sample…", null to "Unknown"),
            resourceMeters(live.copy(cpu_percent = null, memory_total_bytes = 0), emptyList()).map { it.percent to it.detail },
        )
        assertEquals(emptyList<Meter>(), resourceMeters(null, emptyList()))
    }

    @Test
    fun `title filter respects query and period`() {
        val rows = listOf("Budget plan" to "2026-10-04T10:00:00Z", "Old budget" to "2026-08-01T10:00:00Z", "Trip" to "2026-10-03T10:00:00Z")
        val week = filterByTitle(rows, " BUDGET ", 7, now, { it.first }, { it.second })
        assertEquals(listOf("Budget plan"), week.map { it.first })
        assertEquals(3, filterByTitle(rows, "", null, now, { it.first }, { it.second }).size)
    }

    @Test
    fun `health opens on the view a deep link names, else on status`() {
        assertEquals("usage", healthView(parseDeepLink("jarvis://health/usage")!!.healthNode))
        assertEquals("usage", healthView("usage"))
        assertEquals("models", healthView("models"))
        for (node in listOf("core", "node", "services", "events", "", "bogus")) assertEquals(node, "status", healthView(node))
    }

    @Test
    fun `revoke is never offered for this phone or before its id is known`() {
        val other = LinkedDevice("AB12", status = "active")
        val mine = LinkedDevice("cd34", status = "active")
        assertTrue(revocable(other, "CD34"))
        assertFalse(revocable(mine, "CD34"))
        assertTrue(isOwnDevice(mine, "CD34"))
        assertFalse(isOwnDevice(other, "CD34"))
        // Own id unknown: nothing is offered and nothing is marked as this device.
        assertFalse(revocable(other, null))
        assertFalse(isOwnDevice(mine, null))
        // Only active devices.
        assertFalse(revocable(LinkedDevice("ef56", status = "revoked"), "CD34"))
        assertFalse(revocable(LinkedDevice("ef56"), "CD34"))
    }
}
