package com.hawkeynl.jarvis.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.hawkeynl.jarvis.R
import com.hawkeynl.jarvis.network.ConnectionState
import com.hawkeynl.jarvis.security.DeviceKeyInvalidatedException
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
    // Local only: the user opened the Home Node step again from a later step.
    var editingEndpoint by remember { mutableStateOf(false) }
    LaunchedEffect(state.endpoint) { editingEndpoint = false }
    val step = onboardingStep(state, editingEndpoint)
    LazyColumn(
        modifier = Modifier.fillMaxSize().imePadding().testTag("onboarding"),
        contentPadding = PaddingValues(JvSpace.lg),
        verticalArrangement = Arrangement.spacedBy(JvSpace.lg, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            Column(
                Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .shadowBorder(JvShapes.lg)
                    .clip(JvShapes.lg)
                    .background(JvTheme.colors.bgSurface)
                    .padding(JvSpace.xxl),
                verticalArrangement = Arrangement.spacedBy(JvSpace.xxl),
            ) {
                StepProgress(step)
                val endpoint = state.endpoint
                if (step == 0 || endpoint == null) {
                    StepBody(
                        "Connect to your Home Node",
                        "Connect this phone to your Home Node and let a trusted device approve the pairing.",
                    ) {
                        OutlinedTextField(
                            value = state.endpointDraft,
                            onValueChange = actions::editEndpoint,
                            modifier = Modifier.fillMaxWidth().testTag("endpoint"),
                            label = { Text("Home Node address") },
                            placeholder = { Text("https://jarvis.local") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { actions.saveEndpoint() }),
                        )
                        if (endpoint != null) ConnectionLine(state.connection)
                        Row(horizontalArrangement = Arrangement.spacedBy(JvSpace.sm)) {
                            if (endpoint != null) {
                                GhostButton("Back", {
                                    actions.editEndpoint(endpoint.baseUrl)
                                    editingEndpoint = false
                                })
                            }
                            ActionButton("Save and check", actions::saveEndpoint, Modifier.weight(1f), enabled = !state.busy)
                        }
                    }
                } else {
                    when {
                        state.deviceKeyLost -> StepBody("Device key lost", DeviceKeyInvalidatedException().message.orEmpty()) {
                            GhostButton("Erase this device and pair again", actions::resetDevice, Modifier.fillMaxWidth(), danger = true)
                        }
                        state.pairingPending -> StepBody(
                            "Approve on a trusted device",
                            "Waiting for approval from a trusted Jarvis device.",
                        ) {
                            state.ownFingerprint?.let { fingerprint ->
                                MutedText("Check that the approving device shows this key fingerprint:")
                                Text(
                                    fingerprint,
                                    Modifier.fillMaxWidth().clip(JvShapes.md).background(JvTheme.colors.bgSunken).padding(JvSpace.md),
                                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = PlexMono),
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(JvSpace.sm)) {
                                if (!rememberReduceMotion()) {
                                    CircularProgressIndicator(Modifier.size(16.dp), color = JvTheme.colors.textSecondary, strokeWidth = 2.dp)
                                }
                                Text(
                                    "Expires: ${state.pairingExpiresAt?.let(::clockTime) ?: "—"}",
                                    color = JvTheme.colors.textSecondary,
                                    style = MaterialTheme.typography.bodySmall.tabular(),
                                )
                            }
                        }
                        else -> StepBody(
                            if (state.activationRequired) "Activate this phone" else "Sign in",
                            if (state.activationRequired) {
                                "Use the one-time code from your Home Node and choose a password of at least 15 characters."
                            } else {
                                "Enter the password of your Jarvis account."
                            },
                        ) {
                            if (state.activationRequired) {
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
                    HomeNodeRow(endpoint.baseUrl, state.connection) { editingEndpoint = true }
                }
                state.error?.let { error -> ErrorText(error, Modifier.testTag("error")) }
            }
        }
    }
}

/** Steps the sign-in flow really has; signing in opens the app, so there is no "done" step. */
internal val ONBOARDING_STEPS = listOf("Home Node", "Sign in", "Approve")

/** Index into [ONBOARDING_STEPS]; only reads state, never changes the flow. */
internal fun onboardingStep(state: JarvisUiState, editingEndpoint: Boolean): Int = when {
    editingEndpoint || state.endpoint == null -> 0
    state.pairingPending -> 2
    else -> 1
}

/** Bars and labels over the card; TalkBack reads it as "Step 2 of 3: Sign in". */
@Composable
private fun StepProgress(step: Int) {
    val c = JvTheme.colors
    Row(
        Modifier.fillMaxWidth().clearAndSetSemantics {
            contentDescription = "Step ${step + 1} of ${ONBOARDING_STEPS.size}: ${ONBOARDING_STEPS[step]}"
        },
        horizontalArrangement = Arrangement.spacedBy(JvSpace.sm),
    ) {
        ONBOARDING_STEPS.forEachIndexed { i, label ->
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(JvSpace.xs)) {
                Box(Modifier.fillMaxWidth().height(4.dp).clip(JvShapes.pill).background(if (i <= step) c.accentSolid else c.bgSunken))
                Text(
                    label,
                    color = if (i <= step) c.textPrimary else c.textSecondary,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = if (i == step) FontWeight.SemiBold else FontWeight.Normal),
                )
            }
        }
    }
}

/** Title, one explaining line and the step's fields. */
@Composable
private fun StepBody(title: String, body: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(JvSpace.lg)) {
        Column(verticalArrangement = Arrangement.spacedBy(JvSpace.xs)) {
            Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
            Text(body, color = JvTheme.colors.textSecondary, style = MaterialTheme.typography.bodyMedium)
        }
        content()
    }
}

/** The saved Home Node on later steps, with a way back to change it. */
@Composable
private fun HomeNodeRow(address: String, connection: ConnectionState, onChange: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(JvShapes.md).background(JvTheme.colors.bgSunken).padding(start = JvSpace.md, end = JvSpace.xs, top = JvSpace.xs, bottom = JvSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(JvSpace.sm),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(JvSpace.xxs)) {
            Text(address, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            ConnectionLine(connection)
        }
        GhostButton("Change", onChange)
    }
}

private fun clockTime(epochSeconds: Long): String =
    DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(epochSeconds))
