package com.hawkeynl.jarvis.ui

import com.hawkeynl.jarvis.network.AgentUsage
import com.hawkeynl.jarvis.network.AgentsResponse
import com.hawkeynl.jarvis.network.Availability
import com.hawkeynl.jarvis.network.CodingSession
import com.hawkeynl.jarvis.network.ConnectionState
import com.hawkeynl.jarvis.network.DiskStatus
import com.hawkeynl.jarvis.network.IbkrStatus
import com.hawkeynl.jarvis.network.LiveHost
import com.hawkeynl.jarvis.network.LoadFailure
import com.hawkeynl.jarvis.network.Registry
import com.hawkeynl.jarvis.network.ServiceStatus
import com.hawkeynl.jarvis.network.SoftwareItem
import com.hawkeynl.jarvis.network.UnreachableReason
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class HubModelTest {
    private val now = Instant.parse("2026-10-04T12:00:00Z").toEpochMilli()

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
    fun `status pill and connection mapping`() {
        assertEquals(true, onlineOf(ConnectionState.Reachable("ready")))
        assertEquals(false, onlineOf(ConnectionState.Unreachable(UnreachableReason.DNS)))
        assertEquals(null, onlineOf(ConnectionState.Checking))
        assertEquals("Offline", statusPill(Mood.THINKING, false).label)
        assertEquals("Thinking", statusPill(Mood.THINKING, true).label)
        assertEquals("Ready", statusPill(Mood.IDLE, true).label)
        assertEquals(0.4f, moodFactor(Mood.THINKING))
    }

    @Test
    fun `node page state mapping`() {
        assertEquals(Tone.WARN to "Paused", sessionState("suspended"))
        assertEquals(Tone.IDLE to "weird", sessionState("weird"))
        val (open, finished) = splitSessions(listOf(CodingSession("a", state = "completed"), CodingSession("b", state = "suspended")))
        assertEquals(listOf("b"), open.map { it.id })
        assertEquals(listOf("a"), finished.map { it.id })
        assertEquals(Tone.WARN to "Not logged in", ibkrState(IbkrStatus(reachable = true)))
        assertEquals(Tone.OK to "Connected", ibkrState(IbkrStatus(reachable = true, authenticated = true)))
        assertEquals(Tone.IDLE to "Not measured yet", agentStatus(null, AGENT_USAGE_NOT_INSTRUMENTED))
        assertEquals(Tone.OK to "1 request this month", agentStatus(AgentUsage(requests = 1), null))
        assertEquals(Tone.ERROR to "1 failed", servicesSummary(listOf(ServiceStatus(state = "active"), ServiceStatus(state = "failed"))))
        assertEquals(Tone.WARN to "Activating", serviceState("activating"))
        assertEquals(Tone.IDLE to "Unknown", diskState(DiskStatus(state = "ok")))
        val disk = DiskStatus(state = "ok", total_bytes = 100L * 1024 * 1024 * 1024, free_bytes = 15L * 1024 * 1024 * 1024, used_percent = 85.0)
        assertEquals(Tone.WARN to "85% used · 15.0 GiB free of 100.0 GiB", diskState(disk))
    }

    @Test
    fun `title filter respects query and period`() {
        val rows = listOf("Budget plan" to "2026-10-04T10:00:00Z", "Old budget" to "2026-08-01T10:00:00Z", "Trip" to "2026-10-03T10:00:00Z")
        val week = filterByTitle(rows, " BUDGET ", 7, now, { it.first }, { it.second })
        assertEquals(listOf("Budget plan"), week.map { it.first })
        assertEquals(3, filterByTitle(rows, "", null, now, { it.first }, { it.second }).size)
    }
}
