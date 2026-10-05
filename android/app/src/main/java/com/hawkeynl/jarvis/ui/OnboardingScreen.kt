package com.hawkeynl.jarvis.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.hawkeynl.jarvis.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun OnboardingScreen(state: JarvisUiState, actions: JarvisViewModel) {
    // Deliberately not rememberSaveable: never serialize passwords into activity state.
    var password by remember { mutableStateOf("") }
    var activationCode by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    LaunchedEffect(state.activationRequired) { passwordVisible = false }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                passwordVisible = false
                password = ""
                activationCode = ""
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().imePadding().testTag("onboarding"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Wordmark()
                JvOrb(150.dp, if (state.busy || state.pairingPending) Mood.THINKING else Mood.IDLE, Modifier.padding(vertical = 40.dp))
                Text(
                    "Connect this phone to your Home Node and let a trusted device approve the pairing.",
                    style = exo(13.5f, color = Jv.Text2),
                )
            }
        }
        item {
            Panel(JvIcon.HOME, "Home Node", connectionTone(state.connection), connectionText(state.connection)) {
                OutlinedTextField(
                    value = state.endpointDraft,
                    onValueChange = actions::editEndpoint,
                    modifier = Modifier.fillMaxWidth().testTag("endpoint"),
                    label = { Text("https://jarvis.local") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { actions.saveEndpoint() }),
                )
                ActionButton("Save and check", actions::saveEndpoint, enabled = !state.busy)
            }
        }
        if (state.endpoint != null) {
            item {
                Panel(
                    JvIcon.PHONE, "Pair this device",
                    if (state.pairingPending) Tone.WARN else Tone.IDLE,
                    if (state.pairingPending) "Waiting for approval" else "Not paired",
                ) {
                    if (state.pairingPending) {
                        Text("Waiting for approval from a trusted Jarvis device.", style = exo(13.5f, color = Jv.Text2))
                        Text("Expires: ${state.pairingExpiresAt?.let(::clockTime) ?: "—"}", style = exo(12f, color = Jv.Text5))
                        CircularProgressIndicator()
                    } else {
                        if (state.activationRequired) {
                            Text(
                                "Use the one-time code from your Home Node and choose a password of at least 15 characters.",
                                style = exo(13.5f, color = Jv.Text2),
                            )
                            OutlinedTextField(
                                value = activationCode, onValueChange = { activationCode = it.take(256) },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Activation code") }, singleLine = true,
                                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
                                visualTransformation = VisualTransformation.None,
                            )
                        }
                        OutlinedTextField(
                            value = password, onValueChange = { password = it.take(1024) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Account password") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
                            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                    Icon(
                                        painterResource(if (passwordVisible) R.drawable.ic_visibility_off else R.drawable.ic_visibility),
                                        contentDescription = if (passwordVisible) "Hide password" else "Show password",
                                    )
                                }
                            },
                        )
                        ActionButton(
                            if (state.busy) "Working…" else "Continue",
                            {
                                val suppliedPassword = password.ifEmpty { null }
                                val suppliedCode = if (state.activationRequired) activationCode else null
                                password = ""
                                passwordVisible = false
                                activationCode = ""
                                actions.beginEnrollment(suppliedPassword, suppliedCode)
                            },
                            enabled = !state.busy,
                        )
                    }
                }
            }
        }
        state.error?.let { error ->
            item { ErrorText(error, Modifier.testTag("error")) }
        }
    }
}

private fun clockTime(epochSeconds: Long): String =
    DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(epochSeconds))
