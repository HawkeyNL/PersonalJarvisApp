package com.hawkeynl.jarvis.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hawkeynl.jarvis.security.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private fun Routing.withTier(tier: Tier, route: TierRoute?) = copy(tiers = if (route == null) tiers - tier else tiers + (tier to route))

private fun kind(provider: String) = if (isSubscription(provider)) "Subscription" else if (isMetered(provider)) "Paid API" else "Local"

/** Owner routing editor. The service re-validates, authenticates and signs; this only edits the typed document. */
@Composable
fun ModelRoutingEditor(controller: ModelControlService) {
    var policy by remember { mutableStateOf<ModelPolicySnapshot?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(Routing()) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    var confirming by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf<Tier?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun refresh() {
        val fresh = controller.policy()
        policy = fresh
        draft = reportedRouting(fresh) ?: Routing()
    }
    fun reload() = scope.launch {
        busy = true
        try { refresh(); loadFailed = false; notice = "" }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { loadFailed = true; policy = null }
        finally { busy = false }
    }
    LaunchedEffect(Unit) { reload() }

    val current = policy
    val mode = current?.let(::routingMode) ?: RoutingMode.UNSUPPORTED
    val reported = current?.let(::reportedRouting)
    val reason = routingReason(current?.routing_unavailable_reason)
    val dirty = reported != null && draft != reported
    // An unusable routing file can be repaired by re-signing the shown document.
    val canApply = dirty || current?.routing_unavailable_reason != null
    val issue = current?.let { routingIssue(draft, it.models.map { m -> RouteEntry(m.provider, m.model) }) }
    val editable = mode == RoutingMode.EDITABLE && reported != null && !busy
    val enabled = current?.models?.filter { it.enabled }?.map { "${it.provider}/${it.model}" }?.toSet().orEmpty()

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Eyebrow("Model routing")
        when {
            loadFailed -> Unavailable("Model routing", JvIcon.LAYERS, kind = UnavailableKind.ERROR,
                detail = "Model policy unreachable. Check the connection and session.")
            current == null -> MutedText("Loading routing…")
            mode == RoutingMode.UNSUPPORTED -> Unavailable("Model routing", JvIcon.LAYERS, kind = UnavailableKind.CORE_UPDATE,
                detail = "This Core cannot change routing from the app. Update Core to choose the model order per tier.")
            else -> {
                MutedText("Routing only orders models per tier. A model still has to be allowed, within budget and healthy; a tier on the built-in order keeps Core's own choice. Changes apply to all devices and need OS authentication.")
                reason?.let { ErrorText(it) }
                if (mode == RoutingMode.READONLY) MutedText("Routing changes are unavailable on this Core right now (privileged broker unavailable or routing file unreadable).")
                if (reported == null) ErrorText("This app does not understand the routing document on the Home Node. Update the app to edit it.")

                SwitchRow("Paid APIs ${if (draft.paidApiAllowed) "allowed" else "off"}",
                    "Off: only subscriptions and local models, in every tier and the built-in order.",
                    draft.paidApiAllowed, editable) { draft = draft.copy(paidApiAllowed = it) }
                SwitchRow("Research web search ${if (draft.researchWebSearch) "on" else "off"}",
                    "On: explicit Research requests may use the subscription's provider-hosted web search. Never a paid API.",
                    draft.researchWebSearch, editable) { draft = draft.copy(researchWebSearch = it) }

                Tier.entries.forEach { tier ->
                    TierEditor(
                        tier, draft.tiers[tier], draft.paidApiAllowed, editable, enabled,
                        candidates(current.models, draft.tiers[tier]?.chain.orEmpty()),
                        picking == tier, { picking = if (it) tier else null },
                    ) { draft = draft.withTier(tier, it) }
                }

                if (canApply) issue?.let { ErrorText(it) }
                if (notice.isNotEmpty()) MutedText(notice)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GhostButton("Refresh", { reload() }, enabled = !busy)
                    GhostButton("Discard", { draft = reported ?: Routing() }, enabled = editable && dirty)
                }
                ActionButton("Apply routing", { confirming = true }, enabled = editable && canApply && issue == null)
            }
        }
    }

    if (confirming) {
        val relaxed = relaxation(current?.let(::currentRouting), draft)
        AlertDialog(onDismissRequest = { confirming = false }, title = { Text("Replace model routing?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("This replaces the routing for all devices${if (!draft.paidApiAllowed) " and turns paid APIs off" else ""}" +
                        "${if (draft.researchWebSearch) ". Research may use web search" else ""}. Running requests are not cancelled.")
                    Tier.entries.forEach { tier ->
                        Text("${tier.label}: ${draft.tiers[tier]?.chain?.joinToString(" → ") { it.label } ?: "built-in order"}")
                    }
                    if (relaxed.any()) Text("This can increase cost or use web search, so Android asks you to authenticate again.")
                }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } },
            confirmButton = { TextButton(onClick = {
                confirming = false
                val hash = current?.routing_sha256 ?: return@TextButton
                if (busy || issue != null) return@TextButton
                val next = draft
                scope.launch {
                    busy = true
                    try { controller.setRouting(next, hash); notice = "Routing verified and updated." }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { notice = error.message ?: "Routing not confirmed" }
                    finally {
                        try { refresh() } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { policy = null; loadFailed = true }
                        busy = false
                    }
                }
            }) { Text("Confirm change") } })
    }
}

@Composable
private fun SwitchRow(title: String, detail: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = exo(13.5f, FontWeight.Medium, Jv.Text1))
            MutedText(detail)
        }
        Switch(checked, onChange, enabled = enabled)
    }
}

@Composable
private fun TierEditor(
    tier: Tier, route: TierRoute?, paidApiAllowed: Boolean, editable: Boolean, enabled: Set<String>,
    options: List<ModelEntry>, menuOpen: Boolean, setMenu: (Boolean) -> Unit, onChange: (TierRoute?) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().background(Jv.accent(0.05f), Jv.R12).border(1.dp, Jv.Line16, Jv.R12).padding(14.dp)
            .semantics { contentDescription = "${tier.label} tier" },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tier.label, style = exo(14f, FontWeight.SemiBold, Jv.Text0))
                MutedText(tier.blurb)
            }
            if (route == null) GhostButton("Customize", { onChange(TierRoute(emptyList())) }, enabled = editable)
            else GhostButton("Reset to built-in", { onChange(null) }, enabled = editable)
        }
        if (route == null) { MutedText("Built-in order."); return@Column }
        route.chain.forEachIndexed { i, entry ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${i + 1}", Modifier.width(18.dp), style = exo(12.5f, color = Jv.Accent))
                Column(Modifier.weight(1f)) {
                    Text(entry.model, style = exo(13f, color = Jv.Text1))
                    MutedText("${entry.provider} · ${kind(entry.provider)}${if (entry.label !in enabled) " · blocked" else ""}")
                }
                GhostButton("Up", { onChange(route.copy(chain = moveEntry(route.chain, i, -1))) }, enabled = editable && i > 0)
                GhostButton("Down", { onChange(route.copy(chain = moveEntry(route.chain, i, 1))) }, enabled = editable && i < route.chain.lastIndex)
                GhostButton("Remove", { onChange(route.copy(chain = route.chain.filterIndexed { j, _ -> j != i })) }, enabled = editable, danger = true)
            }
        }
        if (route.chain.isEmpty()) MutedText("Add at least one model, or reset to the built-in order.")
        Box {
            GhostButton("Add a discovered model", { setMenu(true) }, enabled = editable && route.chain.size < MAX_CHAIN && options.isNotEmpty())
            DropdownMenu(expanded = menuOpen, onDismissRequest = { setMenu(false) }) {
                options.forEach { m ->
                    DropdownMenuItem(text = { Text("${m.model} (${m.provider}${if (m.enabled) "" else ", blocked"})") }, onClick = {
                        setMenu(false)
                        onChange(route.copy(chain = route.chain + RouteEntry(m.provider, m.model)))
                    })
                }
            }
        }
        if (meteredAfterSubscription(route.chain) || route.meteredAfterSubscription) {
            SwitchRow("Allow paid APIs after a subscription", "", route.meteredAfterSubscription, editable) {
                onChange(route.copy(meteredAfterSubscription = it))
            }
        }
        if (route.meteredAfterSubscription) {
            Text("When the subscription is unavailable or its plan is used up, this tier may fall back to a paid API and cost money.",
                style = exo(12.5f, color = Jv.Warn))
        }
        if (!paidApiAllowed && route.chain.any { isMetered(it.provider) }) MutedText("Paid APIs are off: paid entries in this tier are skipped.")
    }
}
