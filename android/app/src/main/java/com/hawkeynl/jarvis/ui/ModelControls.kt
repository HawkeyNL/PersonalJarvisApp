package com.hawkeynl.jarvis.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hawkeynl.jarvis.security.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

@Composable
fun ModelControls(controller: ModelControlService) {
    var open by remember { mutableStateOf(false) }
    var policy by remember { mutableStateOf<ModelPolicySnapshot?>(null) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    var search by remember { mutableStateOf("") }
    var page by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<ModelEntry?>(null) }
    val scope = rememberCoroutineScope()
    suspend fun refresh() { policy = controller.policy() }
    ActionButton("Manage model access", { open = true; scope.launch {
        busy = true
        try { refresh() } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { notice = "Model policy unreachable"; policy = null }
        finally { busy = false }
    } })
    if (open) AlertDialog(onDismissRequest = { if (!busy) open = false }, title = { Text("Models", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(JvSpace.md),
            ) {
                Text(
                    "Every change needs your fingerprint or face and applies to all devices. Budget limits stay active.",
                    color = JvTheme.colors.textSecondary, style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(value = search, onValueChange = { search = it; page = 0 }, modifier = Modifier.fillMaxWidth(), label = { Text("Search") })
                if (notice.isNotEmpty()) MutedText(notice)
                val entries = policy?.models?.filter { "${it.provider}/${it.model}".contains(search, ignoreCase = true) }.orEmpty()
                Column {
                    entries.drop(page * 25).take(25).forEach { entry ->
                        ListDivider()
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = JvSpace.sm),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(JvSpace.sm),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(entry.model, color = JvTheme.colors.textPrimary, style = MaterialTheme.typography.titleSmall)
                                MutedText("${entry.provider} · ${if (entry.enabled) "on" else "off"}")
                            }
                            GhostButton(
                                if (entry.enabled) "Turn off" else "Turn on", { selected = entry },
                                enabled = !busy && policy?.mutation == "device-signed-model-toggle-v1",
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(JvSpace.sm)) {
                    GhostButton("Previous", { page-- }, Modifier.weight(1f), enabled = !busy && page > 0)
                    GhostButton("Next", { page++ }, Modifier.weight(1f), enabled = !busy && (page + 1) * 25 < entries.size)
                }
            }
        }, confirmButton = { TextButton(enabled = !busy, onClick = { open = false }) { Text("Close") } })
    selected?.let { entry ->
        AlertDialog(onDismissRequest = { selected = null }, title = { Text("Confirm model change?", style = MaterialTheme.typography.titleLarge) },
            text = {
                Text(
                    "${entry.provider}/${entry.model} ${if (entry.enabled) "off" else "on"}? Running requests are not cancelled.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            dismissButton = { TextButton(onClick = { selected = null }) { Text("Cancel") } },
            confirmButton = { TextButton(onClick = {
                selected = null
                val hash = policy?.policy_sha256 ?: return@TextButton
                scope.launch {
                    busy = true
                    try { controller.toggle(entry, hash); notice = "Change verified and active" }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { notice = error.message ?: "Change not confirmed" }
                    finally {
                        try { refresh() } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { policy = null; notice += "; refreshing the status failed" }
                        busy = false
                    }
                }
            }) { Text("Confirm change") } })
    }
}
