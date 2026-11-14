package com.hawkeynl.jarvis.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hawkeynl.jarvis.chat.Source
import com.hawkeynl.jarvis.network.ApiResult
import com.hawkeynl.jarvis.network.Cadence
import com.hawkeynl.jarvis.network.Fact
import com.hawkeynl.jarvis.network.JOBS_FULL_TEXT
import com.hawkeynl.jarvis.network.JOBS_NOT_CONFIGURED
import com.hawkeynl.jarvis.network.JOBS_TIME_ZONE
import com.hawkeynl.jarvis.network.Job
import com.hawkeynl.jarvis.network.JobDraft
import com.hawkeynl.jarvis.network.JobFailure
import com.hawkeynl.jarvis.network.JobKind
import com.hawkeynl.jarvis.network.JobList
import com.hawkeynl.jarvis.network.JobRun
import com.hawkeynl.jarvis.network.MAX_OWNER_JOBS
import com.hawkeynl.jarvis.network.NOT_CONFIRMED
import com.hawkeynl.jarvis.network.OWNER_KINDS
import com.hawkeynl.jarvis.network.PAUSED_AFTER_FAILURES_NOTE
import com.hawkeynl.jarvis.network.Priority
import com.hawkeynl.jarvis.network.Roadmap
import com.hawkeynl.jarvis.network.RunDetail
import com.hawkeynl.jarvis.network.RunStatus
import com.hawkeynl.jarvis.network.WEEKDAYS
import com.hawkeynl.jarvis.network.cadencesOf
import com.hawkeynl.jarvis.network.deleteFailureText
import com.hawkeynl.jarvis.network.deleteNote
import com.hawkeynl.jarvis.network.draftError
import com.hawkeynl.jarvis.network.everyUnit
import com.hawkeynl.jarvis.network.hasRecurrence
import com.hawkeynl.jarvis.network.jobCountText
import com.hawkeynl.jarvis.network.jobDeleted
import com.hawkeynl.jarvis.network.jobErrorText
import com.hawkeynl.jarvis.network.jobFailureOf
import com.hawkeynl.jarvis.network.jobFailureText
import com.hawkeynl.jarvis.network.jobPatch
import com.hawkeynl.jarvis.network.jobTitle
import com.hawkeynl.jarvis.network.newDraft
import com.hawkeynl.jarvis.network.newJob
import com.hawkeynl.jarvis.network.nextRunText
import com.hawkeynl.jarvis.network.ordinal
import com.hawkeynl.jarvis.network.originText
import com.hawkeynl.jarvis.network.ownerJobCount
import com.hawkeynl.jarvis.network.runNowFailureText
import com.hawkeynl.jarvis.network.runsText
import com.hawkeynl.jarvis.network.scheduleText
import com.hawkeynl.jarvis.network.stateText
import com.hawkeynl.jarvis.network.summarySourceLabel
import com.hawkeynl.jarvis.network.toDraft
import com.hawkeynl.jarvis.network.triggerLabel
import com.hawkeynl.jarvis.network.typedTime
import com.hawkeynl.jarvis.network.unsupportedText
import com.hawkeynl.jarvis.network.writeFailureText
import com.hawkeynl.jarvis.network.writeNotice
import com.hawkeynl.jarvis.network.zonedText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Jobs (desktop Jobs.vue, Job.vue, JobRun.vue): the owner's scheduled jobs, one job with its
 *  editor, Run now, pause, delete and newest runs, and one run's report. A job and a run open
 *  inside this screen; Back returns to where they were opened from. Content comes from Core
 *  only; a job's conversation opens in chat. */
@Composable
fun JobsScreen(actions: JarvisViewModel, onNavigate: (Screen) -> Unit) {
    // A chat card's "View job" opens on that job.
    var jobId by rememberSaveable { mutableStateOf(actions.takeJobToOpen()) }
    var runId by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(enabled = jobId != null || runId != null) { if (runId != null) runId = null else jobId = null }
    val job = jobId
    val run = runId
    when {
        run != null -> key(run) { RunPage(run, actions, { runId = null }) { jobId = it; runId = null } }
        job != null -> key(job) { JobPage(job, actions, { jobId = null }) { runId = it } }
        else -> JobListPage(actions, { onNavigate(backOf(Screen.JOBS)) }) { jobId = it }
    }
}

/** One load or write at a time per page: a newer one cancels the one before, so the latest
 *  answer wins (desktop's seq counter). Leaving the page cancels it too. */
internal class Latest(private val scope: CoroutineScope) {
    private var current: kotlinx.coroutines.Job? = null
    fun launch(block: suspend () -> Unit) {
        current?.cancel()
        current = scope.launch { block() }
    }
}

@Composable
internal fun rememberLatest(): Latest {
    val scope = rememberCoroutineScope()
    return remember(scope) { Latest(scope) }
}

private data class JobRow(val job: Job, val last: JobRun?, val lastLoaded: Boolean)
private data class JobsOverview(val list: JobList, val rows: List<JobRow>)

@Composable
private fun JobListPage(actions: JarvisViewModel, back: () -> Unit, open: (String) -> Unit) {
    var overview by remember { mutableStateOf<JobsOverview?>(null) }
    var failure by remember { mutableStateOf<JobFailure?>(null) }
    // The kind whose create form is open: one of OWNER_KINDS (New job) or a dependency review.
    var creating by remember { mutableStateOf<JobKind?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val latest = rememberLatest()

    // A null answer means locked or signed out: this page is about to go.
    suspend fun load() {
        val list = actions.jobs { e, t -> jobs(e, t) } ?: return
        if (list !is ApiResult.Success) {
            if (overview == null) failure = jobFailureOf(list, list = true)
            return
        }
        // ponytail: one runs read per job for its newest run, in parallel; Core keeps at most 14 jobs.
        val runs = coroutineScope { list.value.jobs.map { job -> async { actions.jobs { e, t -> jobRuns(e, t, job.id) } } }.awaitAll() }
        if (runs.any { it == null }) return
        val rows = list.value.jobs.zip(runs) { job, r -> JobRow(job, (r as? ApiResult.Success)?.value?.firstOrNull(), r is ApiResult.Success) }
        overview = JobsOverview(list.value, rows)
        failure = null
    }
    LaunchedEffect(Unit) { latest.launch { load() } }

    fun create(draft: JobDraft) {
        val body = newJob(draft) ?: return
        if (busy) return
        busy = true
        error = ""
        latest.launch {
            try {
                val result = actions.jobs { e, t -> createJob(e, t, body) } ?: return@launch
                val created = (result as? ApiResult.Success)?.value
                if (created != null) {
                    creating = null
                    open(created.id)
                } else {
                    // A 2xx whose job does not read may have landed: the reload shows it.
                    error = if (result is ApiResult.Success) NOT_CONFIRMED else writeFailureText(result, update = false, draft.kind)
                    load()
                }
            } finally {
                busy = false
            }
        }
    }

    PushedPage(
        screenTitle(Screen.JOBS), "Work Core does by itself on a schedule you set.", back,
        trailing = { GhostButton("Refresh", { latest.launch { load() } }, enabled = !busy) },
    ) {
        val shown = overview
        val failed = failure
        when {
            shown == null && failed != null ->
                Unavailable("Jobs", JvIcon.CLOCK, kind = UnavailableKind.ERROR, detail = jobFailureText(failed), onRetry = { latest.launch { load() } })
            shown == null -> Skeleton("Loading jobs…")
            else -> {
                if (!shown.list.configured) Warning(JOBS_NOT_CONFIGURED)
                // Outside the create form: after a 409 the reload shows why, and the form stays.
                if (error.isNotEmpty()) ErrorText(error)
                val full = ownerJobCount(shown.list.jobs) >= MAX_OWNER_JOBS
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MutedText(jobCountText(shown.list.jobs, shown.list.unsupported) + if (full) ". $JOBS_FULL_TEXT" else "", Modifier.weight(1f))
                    if (creating !in OWNER_KINDS) GhostButton("New job", { creating = JobKind.PROMPT; error = "" }, enabled = !full)
                }
                if (creating in OWNER_KINDS) {
                    ListSection("New job") {
                        Column(Modifier.padding(JvSpace.lg)) {
                            JobForm(actions, newDraft(JobKind.PROMPT), creating = true, busy, "Create", onCancel = { creating = null; error = "" }, onSave = ::create)
                        }
                    }
                }
                shown.rows.forEach { row -> JobCard(row) { open(row.job.id) } }
                if (shown.list.unsupported > 0) Warning(unsupportedText(shown.list.unsupported))
                val review = JobKind.DEPENDENCY_REVIEW
                if (shown.rows.none { it.job.kind == review }) {
                    ListSection(review.label) {
                        Column(Modifier.padding(JvSpace.lg), verticalArrangement = Arrangement.spacedBy(JvSpace.md)) {
                            MutedText(review.detail)
                            if (creating == review) {
                                JobForm(actions, newDraft(review), creating = true, busy, "Set up", onCancel = { creating = null; error = "" }, onSave = ::create)
                            } else {
                                ActionButton("Set up", { creating = review; error = "" })
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun stateTone(job: Job): Tone = when {
    job.enabled -> Tone.OK
    job.pausedAfterFailures -> Tone.WARN
    else -> Tone.IDLE
}

/** A job on the list: its name opens it; who set it up, schedule, next run and newest run below. */
@Composable
private fun JobCard(row: JobRow, open: () -> Unit) {
    val job = row.job
    ListSection(null) {
        val detail = listOfNotNull(job.kind.label.takeIf { job.params.title != null }, stateText(job)).joinToString(" · ")
        NavRow(JvIcon.CLOCK, jobTitle(job), detail, open, stateTone(job))
        originText(job)?.let {
            ListDivider()
            StatusBadge(it, Modifier.padding(horizontal = JvSpace.lg, vertical = JvSpace.md))
        }
        ListDivider()
        FactRow("Schedule", scheduleText(job))
        runsText(job)?.let {
            ListDivider()
            FactRow("Runs", it)
        }
        ListDivider()
        FactRow("Next run", nextRunText(job))
        ListDivider()
        val last = row.last
        when {
            !row.lastLoaded -> FactRow("Last run", "Could not be loaded")
            last == null -> FactRow("Last run", "Not run yet")
            else -> FactRow("Last run", "${zonedText(last.startedAt)} · ${last.status.label}", runTone(last.status))
        }
    }
}

@Composable
private fun JobPage(id: String, actions: JarvisViewModel, back: () -> Unit, openRun: (String) -> Unit) {
    var job by remember { mutableStateOf<Job?>(null) }
    var configured by remember { mutableStateOf(true) }
    var failure by remember { mutableStateOf<JobFailure?>(null) }
    var runs by remember { mutableStateOf<List<JobRun>?>(null) }
    var notice by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val latest = rememberLatest()

    suspend fun load() {
        // Core has no single-job read; the list is small.
        val (list, runList) = coroutineScope {
            val list = async { actions.jobs { e, t -> jobs(e, t) } }
            val runList = async { actions.jobs { e, t -> jobRuns(e, t, id) } }
            list.await() to runList.await()
        }
        if (list == null || runList == null) return
        if (list is ApiResult.Success) {
            configured = list.value.configured
            val found = list.value.jobs.firstOrNull { it.id == id }
            job = found
            failure = if (found == null) JobFailure.NOT_FOUND else null
        } else if (list is ApiResult.InvalidResponse) {
            // An answer that fails the guards is not shown next to an older one (desktop).
            job = null
            failure = JobFailure.FAILED
        } else if (job == null) {
            failure = jobFailureOf(list, list = true)
        }
        runs = (runList as? ApiResult.Success)?.value
    }
    LaunchedEffect(Unit) { latest.launch { load() } }

    fun write(current: Job, patch: JsonObject, done: String) {
        busy = true
        notice = ""
        latest.launch {
            try {
                val result = actions.jobs { e, t -> updateJob(e, t, current.id, current.revision, patch) } ?: return@launch
                (result as? ApiResult.Success)?.value?.let { job = it }
                load()
                notice = writeNotice(result, update = true, current.kind, done)
            } finally {
                busy = false
            }
        }
    }

    fun save(draft: JobDraft) {
        val current = job ?: return
        if (busy) return
        val patch = jobPatch(current, draft)
        if (patch == null) { notice = "Nothing changed."; return }
        write(current, patch, "Saved.")
    }

    fun setEnabled(enabled: Boolean) {
        val current = job ?: return
        if (busy) return
        write(current, buildJsonObject { put("enabled", enabled) }, if (enabled) "Enabled." else "Paused.")
    }

    fun runNow() {
        val current = job ?: return
        if (busy) return
        busy = true
        notice = ""
        latest.launch {
            try {
                val result = actions.jobs { e, t -> runJob(e, t, current.id) } ?: return@launch
                notice = if (result is ApiResult.Success) "Started. Refresh to see the run when it ends." else runNowFailureText(result)
            } finally {
                busy = false
            }
            load()
        }
    }

    fun delete() {
        val current = job ?: return
        if (busy) return
        busy = true
        notice = ""
        latest.launch {
            try {
                val result = actions.jobs { e, t -> deleteJob(e, t, current.id, current.revision) } ?: return@launch
                deleting = false
                if (jobDeleted(result)) {
                    back()
                } else {
                    notice = deleteFailureText(result)
                    load()
                }
            } finally {
                busy = false
            }
        }
    }

    val shown = job
    PushedPage(
        shown?.let(::jobTitle) ?: "Job", shown?.let { if (it.params.title != null) it.kind.label else it.kind.detail }, back,
        trailing = { GhostButton("Refresh", { latest.launch { load() } }, enabled = !busy) },
    ) {
        // Announced once when it appears or changes, like desktop focusing it.
        if (notice.isNotEmpty()) Text(notice, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium)
        if (!configured) Warning(JOBS_NOT_CONFIGURED)
        val failed = failure
        when {
            failed != null ->
                Unavailable("This job", JvIcon.ALERT, kind = UnavailableKind.ERROR, detail = jobFailureText(failed), onRetry = { latest.launch { load() } })
            shown == null -> Skeleton("Loading the job…")
            else -> {
                ListSection(null) {
                    Column(Modifier.padding(JvSpace.lg), verticalArrangement = Arrangement.spacedBy(JvSpace.sm)) {
                        StatusBadge(stateText(shown), tone = stateTone(shown))
                        originText(shown)?.let { StatusBadge(it) }
                        val next = if (shown.enabled && shown.nextRunAt != null) "Next run ${zonedText(shown.nextRunAt)}" else null
                        MutedText(listOfNotNull("${scheduleText(shown)} ($JOBS_TIME_ZONE)", runsText(shown), next).joinToString(". ") + ".")
                        if (shown.pausedAfterFailures) ErrorText(PAUSED_AFTER_FAILURES_NOTE, live = false)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(JvSpace.sm), verticalAlignment = Alignment.CenterVertically) {
                    ActionButton("Run now", ::runNow, Modifier.weight(1f), enabled = !busy && configured)
                    GhostButton(if (shown.enabled) "Pause" else "Enable", { setEnabled(!shown.enabled) }, enabled = !busy)
                }
                shown.conversationId?.let { conversation ->
                    GhostButton("Open in chat", { actions.openConversation(conversation) }, Modifier.fillMaxWidth())
                }
                ListSection("Edit") {
                    Column(Modifier.padding(JvSpace.lg)) {
                        // Reset per revision: a change elsewhere resets the editor to Core's state.
                        key(shown.revision) { JobForm(actions, toDraft(shown), creating = false, busy, "Save", onCancel = null, onSave = ::save) }
                    }
                }
                val list = runs
                if (list == null) {
                    Unavailable(
                        "Runs", JvIcon.CLOCK, kind = UnavailableKind.ERROR, detail = "Could not load the runs.",
                        onRetry = if (busy) null else ({ latest.launch { load() } }),
                    )
                } else {
                    ActivityList(
                        "Runs",
                        list.map { run ->
                            val detail = run.errorCode?.let { jobErrorText(it, shown.kind) } ?: if (run.priorities.isNotEmpty()) "${run.priorities.size} priorities" else ""
                            ActivityItem(
                                run.id, JvIcon.CLOCK, zonedText(run.startedAt), listOf(triggerLabel(run.byOwner), detail).filter { it.isNotEmpty() }.joinToString(" · "),
                                tone = runTone(run.status), toneLabel = run.status.label,
                            )
                        },
                        "No runs yet.",
                        onRowClick = { openRun(it.id) },
                        rowClickLabel = "Open run report",
                    )
                    if (list.isNotEmpty()) MutedText("The 20 newest runs. Core keeps runs for 180 days.", Modifier.padding(horizontal = JvSpace.xs))
                }
                GhostButton("Delete job", { deleting = true }, Modifier.fillMaxWidth(), enabled = !busy, danger = true)
            }
        }
    }

    if (deleting) {
        AlertDialog(
            onDismissRequest = { if (!busy) deleting = false },
            title = { Text("Delete this job?") },
            text = { Text(job?.let(::deleteNote).orEmpty()) },
            dismissButton = { TextButton(onClick = { deleting = false }, enabled = !busy) { Text("Cancel") } },
            confirmButton = { TextButton(onClick = ::delete, enabled = !busy) { Text("Delete", color = JvTheme.colors.dangerText) } },
        )
    }
}

@Composable
private fun RunPage(id: String, actions: JarvisViewModel, back: () -> Unit, openJob: (String) -> Unit) {
    var run by remember { mutableStateOf<RunDetail?>(null) }
    var failure by remember { mutableStateOf<JobFailure?>(null) }
    val latest = rememberLatest()

    suspend fun load() {
        val result = actions.jobs { e, t -> jobRun(e, t, id) } ?: return
        if (result is ApiResult.Success) {
            run = result.value
            failure = null
        } else if (run == null) {
            failure = jobFailureOf(result)
        }
    }
    LaunchedEffect(Unit) { latest.launch { load() } }

    PushedPage("Run report", null, back, trailing = { GhostButton("Refresh", { latest.launch { load() } }) }) {
        val shown = run
        val failed = failure
        when {
            failed != null ->
                Unavailable("This run", JvIcon.ALERT, kind = UnavailableKind.ERROR, detail = jobFailureText(failed), onRetry = { latest.launch { load() } })
            shown == null -> Skeleton("Loading the run…")
            else -> RunReport(shown, { openJob(shown.run.jobId) }, actions::openConversation)
        }
    }
}

/** The run's status, error, summary, sources and ranked priorities. Core's summary and
 *  reasons are display text and shown as plain text only. */
@Composable
private fun RunReport(detail: RunDetail, openJob: () -> Unit, openConversation: (String) -> Unit) {
    val run = detail.run
    ListSection(null) {
        FactRow("Status", run.status.label, runTone(run.status))
        ListDivider()
        FactRow("How", triggerLabel(run.byOwner))
        ListDivider()
        FactRow("Started", zonedText(run.startedAt))
        ListDivider()
        FactRow("Ended", run.finishedAt?.let(::zonedText) ?: if (run.status == RunStatus.RUNNING) "Still running" else "—")
    }
    if (run.status == RunStatus.MISSED) Text("Core was down at this slot for more than a day, so it did not run.", style = MaterialTheme.typography.bodyMedium)
    run.errorCode?.let { ErrorText(jobErrorText(it, run.kind), live = false) }
    if (detail.errors.isNotEmpty()) {
        ListSection("Repositories that could not be read") {
            detail.errors.forEachIndexed { i, e ->
                if (i > 0) ListDivider()
                Text("${e.repo}: ${jobErrorText(e.errorCode, run.kind)}", Modifier.padding(JvSpace.lg), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    run.summary?.let { summary ->
        ListSection("Summary") {
            Column(Modifier.padding(JvSpace.lg), verticalArrangement = Arrangement.spacedBy(JvSpace.sm)) {
                run.summarySource?.let { StatusBadge(summarySourceLabel(it)) }
                Text(summary, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    if (detail.sources.isNotEmpty()) Sources(detail.sources)
    if (run.priorities.isNotEmpty()) {
        val facts = remember(detail) { detail.facts.associateBy { it.ref } }
        ListSection("Priorities") {
            run.priorities.forEachIndexed { i, p ->
                if (i > 0) ListDivider()
                PriorityRow(p, facts[p.ref])
            }
        }
    } else if (run.kind == JobKind.DEPENDENCY_REVIEW && (run.status == RunStatus.OK || run.status == RunStatus.PARTIAL)) {
        ListSection(null) { EmptyState("No open alerts in the repositories Core could read.", compact = true) }
    }
    if (detail.truncated) MutedText("Some entries were left out: a full page from GitHub, an alert Core could not read, or the size limit.")
    Row(horizontalArrangement = Arrangement.spacedBy(JvSpace.sm)) {
        GhostButton("Open job", openJob)
        detail.conversationId?.let { conversation -> GhostButton("Open in chat", { openConversation(conversation) }) }
    }
}

/** A research run's sources: validated https links (Jobs.kt), opened in the browser on tap.
 *  Without a browser openUri throws and the tap does nothing. */
@Composable
private fun Sources(urls: List<String>) {
    val uris = LocalUriHandler.current
    ListSection("Sources") {
        urls.forEachIndexed { i, url ->
            if (i > 0) ListDivider()
            val source = Source(url)
            Text(
                source.host + source.shortPath,
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .clickable(role = Role.Button, onClickLabel = "Open source") { runCatching { uris.openUri(url) } }
                    .padding(JvSpace.lg),
                color = JvTheme.colors.accentText, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PriorityRow(priority: Priority, fact: Fact?) {
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.padding(JvSpace.lg),
        horizontalArrangement = Arrangement.spacedBy(JvSpace.md),
    ) {
        Text("${priority.rank}", Modifier.width(24.dp), color = JvTheme.colors.textSecondary, style = MaterialTheme.typography.bodyMedium.tabular())
        Column(verticalArrangement = Arrangement.spacedBy(JvSpace.xxs)) {
            Text(fact?.title ?: priority.ref, style = MaterialTheme.typography.titleSmall)
            fact?.detail?.takeIf { it.isNotEmpty() }?.let { MutedText(it) }
            Text(priority.reason, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** The job editor (desktop JobForm.vue): on create the kind (one of [kinds], when there is a
 *  choice), then the kind's fields, the
 *  recurrence (once, daily, weekly or monthly; every N, start date, stop after N runs; a
 *  dependency review daily or weekly only), a time in Europe/Amsterdam and, on create,
 *  enabled. Checked against Core's bounds before it goes out. */
@Composable
internal fun JobForm(
    actions: JarvisViewModel, initial: JobDraft, creating: Boolean, busy: Boolean, submit: String, onCancel: (() -> Unit)?, onSave: (JobDraft) -> Unit,
    kinds: List<JobKind> = OWNER_KINDS,
) {
    var draft by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf("") }
    fun edit(next: JobDraft) { draft = next; error = "" }
    // null while loading or when the roadmaps could not be read.
    var choices by remember { mutableStateOf<List<Roadmap>?>(null) }
    var choicesFailed by remember { mutableStateOf(false) }
    if (initial.kind == JobKind.DEPENDENCY_REVIEW) {
        LaunchedEffect(Unit) {
            val result = actions.jobs { e, t -> roadmaps(e, t) } ?: return@LaunchedEffect
            choices = (result as? ApiResult.Success)?.value?.filter { it.archivedAt == null }
            choicesFailed = result !is ApiResult.Success
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(JvSpace.md)) {
        if (creating && draft.kind in OWNER_KINDS && kinds.size > 1) {
            Picker("Kind", kinds.map { it to it.label }, draft.kind, { edit(draft.copy(kind = it)) })
            MutedText(draft.kind.detail)
        }
        when (draft.kind) {
            JobKind.PROMPT -> {
                Field("Title", draft.title, { edit(draft.copy(title = it)) }, "Up to 60 characters. It can show in a push.")
                // One line for Core: a typed line break reads as a space.
                Field("What to research", draft.instruction, { edit(draft.copy(instruction = it.replace('\n', ' '))) }, "Up to 500 characters.", multiline = true)
                MutedText("The title and instruction leave your Home Node in a web search: leave out personal or financial details and symbols you hold.")
                SwitchRow("Send me a push when a run ends", draft.notify) { edit(draft.copy(notify = it)) }
            }
            JobKind.REMINDER -> {
                Field("Title", draft.title, { edit(draft.copy(title = it)) }, "Up to 60 characters. Sent as the push.")
                Field("Text (optional)", draft.text, { edit(draft.copy(text = it.replace('\n', ' '))) }, "Up to 500 characters, posted in the job's conversation.", multiline = true)
            }
            JobKind.DISK_CHECK ->
                Field("Threshold", draft.threshold, { edit(draft.copy(threshold = it)) }, "50 to 99%. A note when a disk is at or above it.", number = true, suffix = "%")
            JobKind.DEPENDENCY_REVIEW -> {
                val list = choices
                val chosen = draft.roadmapId
                val options = buildList<Pair<String?, String>> {
                    add(null to "No roadmap")
                    if (chosen != null && list?.none { it.id == chosen } != false) {
                        add(chosen to if (list == null) "The roadmap set now" else "A roadmap that is gone or archived")
                    }
                    list?.forEach { add(it.id to it.title) }
                }
                Picker(
                    "Propose upgrades to a roadmap (optional)", options, chosen, { edit(draft.copy(roadmapId = it)) },
                    enabled = list != null || chosen != null,
                )
                if (choicesFailed) ErrorText("Roadmaps could not be loaded.", live = false)
                MutedText("Each run then proposes one item per fixable alert. You accept or reject the proposal on the roadmap.")
            }
        }
        SectionLabel("Repeats")
        Segmented(
            cadencesOf(draft.kind).map { it.wire to it.label }, draft.cadence.wire,
            { wire -> edit(draft.copy(cadence = Cadence.entries.first { it.wire == wire })) },
        )
        val once = draft.cadence == Cadence.ONCE
        if (hasRecurrence(draft.kind) && !once) {
            Field("Every", draft.every, { edit(draft.copy(every = it)) }, null, number = true, suffix = everyUnit(draft.cadence))
        }
        if (draft.cadence == Cadence.WEEKLY) Picker("Day", WEEKDAYS.mapIndexed { i, day -> i + 1 to day }, draft.weekday, { edit(draft.copy(weekday = it)) })
        if (draft.cadence == Cadence.MONTHLY) {
            Picker("Day of the month", (1..31).map { it to ordinal(it) }, draft.monthDay, { edit(draft.copy(monthDay = it)) })
            if (draft.monthDay > 28) MutedText("In a shorter month it runs on the last day.")
        }
        if (hasRecurrence(draft.kind)) {
            Field(
                if (once) "Day" else "Start date (optional)", draft.startDate, { edit(draft.copy(startDate = it)) },
                if (once) "YYYY-MM-DD, at most two years ahead." else "YYYY-MM-DD. It runs from this day on; empty starts now.",
                placeholder = "YYYY-MM-DD",
            )
            if (!once) {
                Field("Stop after (optional)", draft.maxRuns, { edit(draft.copy(maxRuns = it)) }, "1 to 100 runs; empty keeps it going.", number = true, suffix = "runs")
            }
        }
        Field("Time", draft.time, { edit(draft.copy(time = it)) }, "$JOBS_TIME_ZONE time", placeholder = "HH:MM", number = true)
        if (creating) {
            SwitchRow(if (draft.enabled) "Enabled: runs on this schedule" else "Paused: runs only when you run it", draft.enabled) { edit(draft.copy(enabled = it)) }
        }
        if (error.isNotEmpty()) ErrorText(error)
        Row(horizontalArrangement = Arrangement.spacedBy(JvSpace.sm), verticalAlignment = Alignment.CenterVertically) {
            onCancel?.let { GhostButton("Cancel", it, enabled = !busy) }
            ActionButton(submit, {
                val next = typedTime(draft.time)?.let { draft.copy(time = it) } ?: draft
                draft = next
                error = draftError(next).orEmpty()
                if (error.isEmpty()) onSave(next)
            }, Modifier.weight(1f), enabled = !busy)
        }
    }
}

@Composable
private fun Field(
    label: String, value: String, onChange: (String) -> Unit, supporting: String?, placeholder: String? = null,
    number: Boolean = false, suffix: String? = null, multiline: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        placeholder = placeholder?.let { text -> @Composable { Text(text) } },
        suffix = suffix?.let { text -> @Composable { Text(text) } },
        supportingText = supporting?.let { text -> @Composable { Text(text) } },
        keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Number else KeyboardType.Text),
        singleLine = !multiline,
        maxLines = if (multiline) 5 else 1,
        shape = JvShapes.md,
    )
}

@Composable
private fun SwitchRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
internal fun Warning(text: String) {
    Text(text, color = JvTheme.colors.negativeText, style = MaterialTheme.typography.bodyMedium)
}

/** Desktop RUN_STATUS_TONE; the label always carries the meaning too. */
private fun runTone(status: RunStatus): Tone = when (status) {
    RunStatus.OK -> Tone.OK
    RunStatus.PARTIAL, RunStatus.INTERRUPTED -> Tone.WARN
    RunStatus.FAILED -> Tone.ERROR
    RunStatus.RUNNING, RunStatus.MISSED -> Tone.IDLE
}
