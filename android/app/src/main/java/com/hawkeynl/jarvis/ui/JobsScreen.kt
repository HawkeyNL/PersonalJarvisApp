package com.hawkeynl.jarvis.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.hawkeynl.jarvis.network.ApiResult
import com.hawkeynl.jarvis.network.Fact
import com.hawkeynl.jarvis.network.JOBS_NOT_CONFIGURED
import com.hawkeynl.jarvis.network.JOBS_TIME_ZONE
import com.hawkeynl.jarvis.network.Job
import com.hawkeynl.jarvis.network.JobDraft
import com.hawkeynl.jarvis.network.JobFailure
import com.hawkeynl.jarvis.network.JobKind
import com.hawkeynl.jarvis.network.JobRun
import com.hawkeynl.jarvis.network.Priority
import com.hawkeynl.jarvis.network.Roadmap
import com.hawkeynl.jarvis.network.RunDetail
import com.hawkeynl.jarvis.network.RunStatus
import com.hawkeynl.jarvis.network.WEEKDAYS
import com.hawkeynl.jarvis.network.cadenceText
import com.hawkeynl.jarvis.network.jobErrorText
import com.hawkeynl.jarvis.network.jobFailureOf
import com.hawkeynl.jarvis.network.jobFailureText
import com.hawkeynl.jarvis.network.jobPatch
import com.hawkeynl.jarvis.network.newDraft
import com.hawkeynl.jarvis.network.newJob
import com.hawkeynl.jarvis.network.runNowFailureText
import com.hawkeynl.jarvis.network.summarySourceLabel
import com.hawkeynl.jarvis.network.typedTime
import com.hawkeynl.jarvis.network.toDraft
import com.hawkeynl.jarvis.network.triggerLabel
import com.hawkeynl.jarvis.network.writeFailureText
import com.hawkeynl.jarvis.network.zonedText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Jobs (desktop Jobs.vue, Job.vue, JobRun.vue): the owner's scheduled jobs, one job with its
 *  schedule editor, Run now and newest runs, and one run's report. A job and a run open inside
 *  this screen; Back returns to where they were opened from. Content comes from Core only. */
@Composable
fun JobsScreen(actions: JarvisViewModel, onNavigate: (Screen) -> Unit) {
    var jobId by rememberSaveable { mutableStateOf<String?>(null) }
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
private data class JobsOverview(val configured: Boolean, val rows: List<JobRow>)

@Composable
private fun JobListPage(actions: JarvisViewModel, back: () -> Unit, open: (String) -> Unit) {
    var overview by remember { mutableStateOf<JobsOverview?>(null) }
    var failure by remember { mutableStateOf<JobFailure?>(null) }
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
        // ponytail: one runs read per job (at most one job per kind, one kind today).
        val rows = list.value.jobs.map { job ->
            val runs = actions.jobs { e, t -> jobRuns(e, t, job.id) } ?: return
            JobRow(job, (runs as? ApiResult.Success)?.value?.firstOrNull(), runs is ApiResult.Success)
        }
        overview = JobsOverview(list.value.configured, rows)
        failure = null
    }
    LaunchedEffect(Unit) { latest.launch { load() } }

    fun create(kind: JobKind, draft: JobDraft) {
        val body = newJob(kind, draft) ?: return
        if (busy) return
        busy = true
        error = ""
        latest.launch {
            try {
                val result = actions.jobs { e, t -> createJob(e, t, body) } ?: return@launch
                if (result is ApiResult.Success) {
                    creating = null
                    result.value?.let { open(it.id) } ?: load()
                } else {
                    error = writeFailureText(result, update = false)
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
                if (!shown.configured) Warning(JOBS_NOT_CONFIGURED)
                // Outside the set-up card: after a 409 the reload shows the job, and the card goes.
                if (error.isNotEmpty()) ErrorText(error)
                shown.rows.forEach { row -> JobCard(row) { open(row.job.id) } }
                JobKind.entries.filter { kind -> shown.rows.none { it.job.kind == kind } }.forEach { kind ->
                    ListSection(kind.label) {
                        Column(Modifier.padding(JvSpace.lg), verticalArrangement = Arrangement.spacedBy(JvSpace.md)) {
                            MutedText(kind.detail)
                            if (creating == kind) {
                                JobForm(actions, kind, newDraft(), busy, "Set up", onCancel = { creating = null; error = "" }) { create(kind, it) }
                            } else {
                                ActionButton("Set up", { creating = kind; error = "" })
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A job on the list: its name opens it; schedule, next run and newest run below. */
@Composable
private fun JobCard(row: JobRow, open: () -> Unit) {
    val job = row.job
    ListSection(null) {
        NavRow(JvIcon.CLOCK, job.kind.label, if (job.enabled) "Enabled" else "Paused", open, if (job.enabled) Tone.OK else Tone.IDLE)
        ListDivider()
        FactRow("Schedule", cadenceText(job))
        ListDivider()
        FactRow("Next run", if (!job.enabled) "Paused" else job.nextRunAt?.let(::zonedText) ?: "Not scheduled")
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
    val latest = rememberLatest()

    suspend fun load() {
        // Core has no single-job read; the list holds at most one job per kind.
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

    fun save(draft: JobDraft) {
        val current = job ?: return
        if (busy) return
        val patch = jobPatch(current, draft)
        if (patch == null) { notice = "Nothing changed."; return }
        busy = true
        notice = ""
        latest.launch {
            try {
                val result = actions.jobs { e, t -> updateJob(e, t, current.id, current.revision, patch) } ?: return@launch
                (result as? ApiResult.Success)?.value?.let { job = it }
                load()
                notice = if (result is ApiResult.Success) "Saved." else writeFailureText(result, update = true)
            } finally {
                busy = false
            }
        }
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

    val shown = job
    PushedPage(
        shown?.kind?.label ?: "Job", shown?.kind?.detail, back,
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
                ActionButton("Run now", ::runNow, enabled = !busy && configured)
                ListSection("Schedule") {
                    Column(Modifier.padding(JvSpace.lg), verticalArrangement = Arrangement.spacedBy(JvSpace.md)) {
                        StatusBadge(if (shown.enabled) "Enabled" else "Paused", tone = if (shown.enabled) Tone.OK else Tone.IDLE)
                        MutedText(cadenceText(shown) + "." + if (shown.enabled && shown.nextRunAt != null) " Next run ${zonedText(shown.nextRunAt)}." else "")
                        // Reset per revision: a change elsewhere resets the editor to Core's state.
                        key(shown.revision) { JobForm(actions, shown.kind, toDraft(shown), busy, "Save", onCancel = null, onSave = ::save) }
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
                            val detail = run.errorCode?.let(::jobErrorText) ?: if (run.priorities.isNotEmpty()) "${run.priorities.size} priorities" else ""
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
            }
        }
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
            else -> RunReport(shown) { openJob(shown.run.jobId) }
        }
    }
}

/** The run's status, error, summary and ranked priorities. Core's summary and reasons are
 *  display text and shown as plain text only. */
@Composable
private fun RunReport(detail: RunDetail, openJob: () -> Unit) {
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
    run.errorCode?.let { ErrorText(jobErrorText(it), live = false) }
    if (detail.errors.isNotEmpty()) {
        ListSection("Repositories that could not be read") {
            detail.errors.forEachIndexed { i, e ->
                if (i > 0) ListDivider()
                Text("${e.repo}: ${jobErrorText(e.errorCode)}", Modifier.padding(JvSpace.lg), style = MaterialTheme.typography.bodyMedium)
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
    if (run.priorities.isNotEmpty()) {
        val facts = remember(detail) { detail.facts.associateBy { it.ref } }
        ListSection("Priorities") {
            run.priorities.forEachIndexed { i, p ->
                if (i > 0) ListDivider()
                PriorityRow(p, facts[p.ref])
            }
        }
    } else if (run.status == RunStatus.OK || run.status == RunStatus.PARTIAL) {
        ListSection(null) { EmptyState("No open alerts in the repositories Core could read.", compact = true) }
    }
    if (detail.truncated) MutedText("Some entries were left out: a full page from GitHub, an alert Core could not read, or the size limit.")
    GhostButton("Open job", openJob)
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

/** The schedule editor (desktop JobForm.vue): daily or weekly, the weekday, a time in
 *  Europe/Amsterdam, enabled, and for a dependency review the roadmap its runs propose
 *  upgrades to. */
@Composable
private fun JobForm(
    actions: JarvisViewModel, kind: JobKind, initial: JobDraft, busy: Boolean, submit: String, onCancel: (() -> Unit)?, onSave: (JobDraft) -> Unit,
) {
    var draft by remember { mutableStateOf(initial) }
    var timeError by remember { mutableStateOf("") }
    var dayMenu by remember { mutableStateOf(false) }
    // null while loading or when the roadmaps could not be read.
    var choices by remember { mutableStateOf<List<Roadmap>?>(null) }
    if (kind == JobKind.DEPENDENCY_REVIEW) {
        LaunchedEffect(Unit) {
            choices = (actions.jobs { e, t -> roadmaps(e, t) } as? ApiResult.Success)?.value?.filter { it.archivedAt == null }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(JvSpace.md)) {
        SectionLabel("Repeats")
        Segmented(listOf("weekly" to "Weekly", "daily" to "Daily"), if (draft.daily) "daily" else "weekly", { draft = draft.copy(daily = it == "daily") })
        if (!draft.daily) {
            Box {
                GhostButton("Day: ${WEEKDAYS[draft.weekday - 1]}", { dayMenu = true })
                DropdownMenu(expanded = dayMenu, onDismissRequest = { dayMenu = false }) {
                    WEEKDAYS.forEachIndexed { i, day ->
                        val chosen = draft.weekday == i + 1
                        DropdownMenuItem(
                            text = { Text(day) },
                            onClick = { draft = draft.copy(weekday = i + 1); dayMenu = false },
                            modifier = Modifier.semantics { selected = chosen },
                            trailingIcon = { if (chosen) JvIconView(JvIcon.CHECK, size = 18.dp) },
                        )
                    }
                }
            }
        }
        OutlinedTextField(
            value = draft.time,
            onValueChange = { draft = draft.copy(time = it); timeError = "" },
            modifier = Modifier.fillMaxWidth().semantics { if (timeError.isNotEmpty()) this.error(timeError) },
            label = { Text("Time") },
            placeholder = { Text("HH:MM") },
            supportingText = { Text(timeError.ifEmpty { "$JOBS_TIME_ZONE time" }) },
            isError = timeError.isNotEmpty(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            shape = JvShapes.md,
        )
        if (kind == JobKind.DEPENDENCY_REVIEW) {
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
                "Propose upgrades to a roadmap (optional)", options, chosen, { draft = draft.copy(roadmapId = it) },
                enabled = list != null || chosen != null,
            )
            MutedText("Each run then proposes one item per fixable alert. You accept or reject the proposal on the roadmap.")
        }
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .toggleable(value = draft.enabled, role = Role.Switch) { draft = draft.copy(enabled = it) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (draft.enabled) "Enabled: runs on this schedule" else "Paused: runs only when you run it",
                Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
            )
            Switch(checked = draft.enabled, onCheckedChange = null)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(JvSpace.sm), verticalAlignment = Alignment.CenterVertically) {
            onCancel?.let { GhostButton("Cancel", it, enabled = !busy) }
            ActionButton(submit, {
                val time = typedTime(draft.time)
                if (time == null) {
                    timeError = "Pick a time of day."
                } else {
                    timeError = ""
                    draft = draft.copy(time = time)
                    onSave(draft)
                }
            }, Modifier.weight(1f), enabled = !busy)
        }
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
