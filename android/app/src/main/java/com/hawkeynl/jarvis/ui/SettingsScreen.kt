package com.hawkeynl.jarvis.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
internal fun SettingsScreen(
    state: JarvisUiState,
    actions: JarvisViewModel,
    onInstallUpdate: () -> Unit,
) {
    var voiceMenu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().imePadding().testTag("settings")) {
        ScreenHeader("Settings", { actions.navigate(Screen.HUB) })
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Panel(JvIcon.HOME, "Home Node", connectionTone(state.connection), connectionText(state.connection)) {
                OutlinedTextField(
                    value = state.endpointDraft,
                    onValueChange = actions::editEndpoint,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Home Node address") },
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton("Save", actions::saveEndpoint, Modifier.weight(1f))
                    ActionButton("Test", actions::checkConnection, Modifier.weight(1f))
                }
            }

            Panel(JvIcon.WAVE, "Voice", if (state.voiceEnabled) Tone.OK else Tone.IDLE, if (state.voiceEnabled) "Local speech on" else "Local speech off") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Speak replies on the active device", Modifier.weight(1f), style = exo(13f, color = Jv.Text2))
                    Switch(checked = state.voiceEnabled, onCheckedChange = actions::setVoiceEnabled)
                }
                Column {
                    Text("Speech rate: ${state.voiceRate}× (next fragments)", style = exo(13f, color = Jv.Text2))
                    Slider(value = state.voiceRate, onValueChange = actions::setVoiceRate, valueRange = 0.5f..2f, steps = 5)
                }
                Box {
                    ActionButton(
                        if (state.selectedVoice.isEmpty()) "Voice: local default"
                        else state.availableVoices.firstOrNull { it.id == state.selectedVoice }?.label ?: "Saved local voice",
                        { actions.refreshLocalVoices(); voiceMenu = true },
                    )
                    DropdownMenu(expanded = voiceMenu, onDismissRequest = { voiceMenu = false }) {
                        DropdownMenuItem(text = { Text("Local default") }, onClick = { actions.selectLocalVoice(""); voiceMenu = false })
                        for (voice in state.availableVoices) {
                            DropdownMenuItem(text = { Text(voice.label) }, onClick = { actions.selectLocalVoice(voice.id); voiceMenu = false })
                        }
                        if (state.availableVoices.isEmpty()) {
                            Text("No offline voices installed", Modifier.padding(16.dp), style = exo(13f, color = Jv.Text4))
                        }
                    }
                }
            }

            UpdatePanel(state, actions, onInstallUpdate)

            Panel(JvIcon.SHIELD, "This device", Tone.OK, "Signed in with a device-bound key") {
                MutedText("Model access moved to System Health → Models.")
                ActionButton("Sign out", actions::logout)
                GhostButton("Erase this device and pair again", actions::resetDevice, Modifier.fillMaxWidth(), danger = true)
            }
        }
    }
}

@Composable
private fun UpdatePanel(state: JarvisUiState, actions: JarvisViewModel, onInstallUpdate: () -> Unit) {
    val update = state.appUpdate
    val (tone, status) = when (update) {
        AndroidUpdateUiState.Idle -> Tone.IDLE to "Not checked yet"
        AndroidUpdateUiState.Checking -> Tone.IDLE to "Checking for updates…"
        AndroidUpdateUiState.Current -> Tone.OK to "Up to date"
        is AndroidUpdateUiState.Available -> Tone.WARN to "Version ${update.metadata.version_name} is available"
        AndroidUpdateUiState.Downloading -> Tone.IDLE to "Downloading and verifying the APK…"
        is AndroidUpdateUiState.Ready -> Tone.OK to "Version ${update.versionName} verified, ready to install"
        AndroidUpdateUiState.PermissionRequired -> Tone.WARN to "Install permission needed"
        is AndroidUpdateUiState.Failed -> Tone.ERROR to "Update failed"
    }
    Panel(JvIcon.PHONE, "Jarvis Android", tone, status) {
        when (update) {
            is AndroidUpdateUiState.Available -> ActionButton("Download update", actions::downloadUpdate)
            is AndroidUpdateUiState.Ready -> ActionButton("Open Android installer", onInstallUpdate)
            AndroidUpdateUiState.PermissionRequired -> {
                ErrorText("Allow Jarvis to install this verified APK in Android settings, then try again.")
                ActionButton("Open installer again", onInstallUpdate)
            }
            is AndroidUpdateUiState.Failed -> ErrorText(update.message)
            else -> Unit
        }
        GhostButton(
            "Check again", actions::checkForUpdate, Modifier.fillMaxWidth(),
            enabled = update !is AndroidUpdateUiState.Checking && update !is AndroidUpdateUiState.Downloading,
        )
    }
}
