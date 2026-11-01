package com.hawkeynl.jarvis.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.hawkeynl.jarvis.storage.ThemeMode
import kotlinx.coroutines.launch

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
            ThemeChooser(state.themeMode, actions::setThemeMode)

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
                    Text("Speak replies on the active device", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = state.voiceEnabled, onCheckedChange = actions::setVoiceEnabled)
                }
                Column {
                    Text("Speech rate: ${state.voiceRate}× (next fragments)", style = MaterialTheme.typography.bodyMedium)
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
                            Text("No offline voices installed", Modifier.padding(16.dp), color = JvTheme.colors.textSecondary, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            UpdatePanel(state, actions, onInstallUpdate)

            Panel(JvIcon.SHIELD, "This device", Tone.OK, "Signed in with a device-bound key") {
                MutedText("Model access moved to System Health → Models.")
                ActionButton("Sign out", actions::logout)
                PasswordChange(actions)
                PasswordReset(state, actions)
                GhostButton("Erase this device and pair again", actions::resetDevice, Modifier.fillMaxWidth(), danger = true)
            }
        }
    }
}

/** Light, dark or follow the system; saved on this device only. */
@Composable
private fun ThemeChooser(mode: ThemeMode, onChange: (ThemeMode) -> Unit) {
    val c = JvTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(JvSpace.sm)) {
        SectionLabel("Appearance")
        Row(
            Modifier.fillMaxWidth().clip(JvShapes.md).background(c.bgSunken).padding(3.dp).selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(JvSpace.xxs),
        ) {
            for ((option, label) in listOf(ThemeMode.LIGHT to "Light", ThemeMode.DARK to "Dark", ThemeMode.SYSTEM to "System")) {
                val on = option == mode
                Box(
                    Modifier
                        .weight(1f)
                        .heightIn(min = 44.dp)
                        .then(if (on) Modifier.shadowBorder(JvShapes.sm) else Modifier)
                        .clip(JvShapes.sm)
                        .background(if (on) c.bgSurface else Color.Transparent)
                        .selectable(selected = on, role = Role.RadioButton, onClick = { onChange(option) }),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, color = if (on) c.textPrimary else c.textSecondary, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

/** Change the account password with the current one; the change is signed with the device key. */
@Composable
private fun PasswordChange(actions: JarvisViewModel) {
    var open by remember { mutableStateOf(false) }
    var current by remember { mutableStateOf("") }
    var next by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    if (!open) {
        GhostButton("Change account password", { open = true }, Modifier.fillMaxWidth())
        return
    }
    @Composable
    fun field(label: String, value: String, onChange: (String) -> Unit) = OutlinedTextField(
        value = value, onValueChange = onChange, modifier = Modifier.fillMaxWidth(),
        label = { Text(label) }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
    )
    field("Current password", current) { current = it }
    field("New password (at least 15 characters)", next) { next = it }
    field("Repeat new password", repeat) { repeat = it }
    MutedText("Every device is signed out after the change; sign in again with the new password.")
    error?.let { ErrorText(it) }
    ActionButton(
        if (busy) "Verifying…" else "Change password",
        {
            if (next != repeat) {
                error = "The new passwords do not match."
            } else {
                busy = true
                error = null
                scope.launch {
                    error = actions.changePassword(current, next)
                    busy = false
                    if (error == null) { current = ""; next = ""; repeat = ""; open = false }
                }
            }
        },
        enabled = !busy && current.isNotEmpty() && next.isNotEmpty(),
    )
    GhostButton("Cancel", { open = false; current = ""; next = ""; repeat = ""; error = null }, Modifier.fillMaxWidth(), enabled = !busy)
}

/** Forgotten-password reset: signed here, then confirmed as root on the Home Node. */
@Composable
private fun PasswordReset(state: JarvisUiState, actions: JarvisViewModel) {
    var open by remember { mutableStateOf(false) }
    var next by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    state.passwordResetRequest?.let { id ->
        MutedText("Run this on the Home Node within 15 minutes:")
        SelectionContainer {
            Text("sudo jarvis account recovery confirm ${id.lowercase()}", fontFamily = PlexMono)
        }
        MutedText("Check that the request id matches. After confirming, every device is signed out; sign in with the new password.")
        GhostButton("Done", { actions.dismissPasswordReset(); open = false }, Modifier.fillMaxWidth())
        return
    }
    if (!open) {
        GhostButton("Forgot password?", { open = true }, Modifier.fillMaxWidth())
        return
    }
    @Composable
    fun field(label: String, value: String, onChange: (String) -> Unit) = OutlinedTextField(
        value = value, onValueChange = onChange, modifier = Modifier.fillMaxWidth(),
        label = { Text(label) }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
    )
    field("New password (at least 15 characters)", next) { next = it }
    field("Repeat new password", repeat) { repeat = it }
    MutedText("Confirmed with your fingerprint or face, then by the owner as root on the Home Node. Nothing changes until then.")
    error?.let { ErrorText(it) }
    ActionButton(
        if (busy) "Verifying…" else "Reset password",
        {
            if (next != repeat) {
                error = "The new passwords do not match."
            } else {
                busy = true
                error = null
                scope.launch {
                    error = actions.resetPassword(next)
                    busy = false
                    if (error == null) { next = ""; repeat = "" }
                }
            }
        },
        enabled = !busy && next.isNotEmpty(),
    )
    GhostButton("Cancel", { open = false; next = ""; repeat = ""; error = null }, Modifier.fillMaxWidth(), enabled = !busy)
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
