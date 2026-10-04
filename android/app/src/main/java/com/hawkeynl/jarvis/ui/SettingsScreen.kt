package com.hawkeynl.jarvis.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.hawkeynl.jarvis.R
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.hawkeynl.jarvis.network.ConnectionState
import com.hawkeynl.jarvis.network.ConversationMessage

@Composable
internal fun SettingsScreen(
    state: JarvisUiState,
    actions: JarvisViewModel,
    onInstallUpdate: () -> Unit,
    modelControls: com.hawkeynl.jarvis.security.ModelControlService?,
) {
    var voiceMenu by remember { mutableStateOf(false) }
    LazyColumn(
        modifier = Modifier.fillMaxSize().imePadding().testTag("settings"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("Instellingen", style = MaterialTheme.typography.headlineSmall) }
        if (modelControls != null) { item { ModelControls(modelControls) } }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Lokale spraak op actief apparaat")
                Switch(checked = state.voiceEnabled, onCheckedChange = actions::setVoiceEnabled)
            }
        }
        item {
            Text("Spreeksnelheid: ${state.voiceRate}× (volgende fragmenten)")
            androidx.compose.material3.Slider(value = state.voiceRate, onValueChange = actions::setVoiceRate,
                valueRange = 0.5f..2f, steps = 5)
        }
        item {
            androidx.compose.foundation.layout.Box {
                OutlinedButton(onClick = { actions.refreshLocalVoices(); voiceMenu = true }) {
                    Text(if (state.selectedVoice.isEmpty()) "Stem: lokale standaard" else
                        state.availableVoices.firstOrNull { it.id == state.selectedVoice }?.label ?: "Opgeslagen lokale stem")
                }
                androidx.compose.material3.DropdownMenu(expanded = voiceMenu, onDismissRequest = { voiceMenu = false }) {
                    androidx.compose.material3.DropdownMenuItem(text = { Text("Lokale standaard") },
                        onClick = { actions.selectLocalVoice(""); voiceMenu = false })
                    for (voice in state.availableVoices) {
                        androidx.compose.material3.DropdownMenuItem(text = { Text(voice.label) },
                            onClick = { actions.selectLocalVoice(voice.id); voiceMenu = false })
                    }
                    if (state.availableVoices.isEmpty()) Text("Geen geïnstalleerde offline stemmen beschikbaar")
                }
            }
        }
        item {
            OutlinedTextField(
                value = state.endpointDraft,
                onValueChange = actions::editEndpoint,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Home Node-adres") },
                singleLine = true,
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = actions::saveEndpoint) { Text("Opslaan") }
                OutlinedButton(onClick = actions::checkConnection) { Text("Test") }
            }
        }
        item { ConnectionLine(state.connection) }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Jarvis Android", style = MaterialTheme.typography.titleMedium)
                    when (val update = state.appUpdate) {
                        AndroidUpdateUiState.Idle -> Text("Update nog niet gecontroleerd")
                        AndroidUpdateUiState.Checking -> Text("Update controleren…")
                        AndroidUpdateUiState.Current -> Text("App is bijgewerkt")
                        is AndroidUpdateUiState.Available -> {
                            Text("Versie ${update.metadata.version_name} is beschikbaar")
                            Button(onClick = actions::downloadUpdate) { Text("Download update") }
                        }
                        AndroidUpdateUiState.Downloading -> Text("APK downloaden en controleren…")
                        is AndroidUpdateUiState.Ready -> {
                            Text("Versie ${update.versionName} is gecontroleerd en klaar voor installatie")
                            Button(onClick = onInstallUpdate) { Text("Open Android-installatie") }
                        }
                        AndroidUpdateUiState.PermissionRequired -> Text(
                            "Geef Jarvis in Android-instellingen toestemming om deze gecontroleerde APK te installeren en probeer opnieuw.",
                            color = MaterialTheme.colorScheme.error,
                        )
                        is AndroidUpdateUiState.Failed -> Text(
                            update.message,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    OutlinedButton(
                        onClick = actions::checkForUpdate,
                        enabled = state.appUpdate !is AndroidUpdateUiState.Checking &&
                        state.appUpdate !is AndroidUpdateUiState.Downloading,
                    ) { Text("Opnieuw controleren") }
                    if (state.appUpdate is AndroidUpdateUiState.PermissionRequired) {
                        Button(onClick = onInstallUpdate) { Text("Installatie opnieuw openen") }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
        item { OutlinedButton(onClick = actions::logout) { Text("Uitloggen") } }
        item { OutlinedButton(onClick = actions::resetDevice) { Text("Apparaat wissen en opnieuw koppelen") } }
    }
}
