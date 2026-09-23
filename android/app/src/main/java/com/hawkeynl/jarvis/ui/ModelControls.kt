package com.hawkeynl.jarvis.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
    Button(onClick = { open = true; scope.launch {
        busy = true
        try { refresh() } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { notice = "Modelpolicy niet bereikbaar"; policy = null }
        finally { busy = false }
    } }) { Text("Modeltoegang beheren") }
    if (open) AlertDialog(onDismissRequest = { if (!busy) open = false }, title = { Text("Modellen") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                Text("Elke wijziging vereist OS-authenticatie en geldt voor alle apparaten. Budgetgrenzen blijven actief.")
                OutlinedTextField(value = search, onValueChange = { search = it; page = 0 }, label = { Text("Zoeken") })
                Text(notice)
                val entries = policy?.models?.filter { "${it.provider}/${it.model}".contains(search, ignoreCase = true) }.orEmpty()
                entries.drop(page * 25).take(25).forEach { entry ->
                    HorizontalDivider()
                    Text(entry.model, style = MaterialTheme.typography.titleSmall)
                    Text("${entry.provider} · ${if (entry.enabled) "aan" else "uit"}")
                    Button(enabled = !busy && policy?.mutation == "device-signed-model-toggle-v1", onClick = { selected = entry }) {
                        Text(if (entry.enabled) "Uitschakelen" else "Inschakelen")
                    }
                }
                Row {
                    TextButton(enabled = !busy && page > 0, onClick = { page-- }) { Text("Vorige") }
                    TextButton(enabled = !busy && (page + 1) * 25 < entries.size, onClick = { page++ }) { Text("Volgende") }
                }
            }
        }, confirmButton = { TextButton(enabled = !busy, onClick = { open = false }) { Text("Sluiten") } })
    selected?.let { entry ->
        AlertDialog(onDismissRequest = { selected = null }, title = { Text("Modelwijziging bevestigen?") },
            text = { Text("${entry.provider}/${entry.model} ${if (entry.enabled) "uitschakelen" else "inschakelen"}? Lopende requests worden niet geannuleerd.") },
            dismissButton = { TextButton(onClick = { selected = null }) { Text("Annuleren") } },
            confirmButton = { TextButton(onClick = {
                selected = null
                val hash = policy?.policy_sha256 ?: return@TextButton
                scope.launch {
                    busy = true
                    try { controller.toggle(entry, hash); notice = "Wijziging geverifieerd en actief" }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { notice = error.message ?: "Wijziging niet bevestigd" }
                    finally {
                        try { refresh() } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { policy = null; notice += "; status verversen mislukt" }
                        busy = false
                    }
                }
            }) { Text("Bevestigen met OS-authenticatie") } })
    }
}
