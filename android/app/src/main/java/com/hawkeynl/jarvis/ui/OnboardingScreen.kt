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
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text("Jarvis", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            Text(
                "Verbind deze telefoon met je Home Node en laat een bestaand vertrouwd apparaat de koppeling goedkeuren.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Home Node", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = state.endpointDraft,
                        onValueChange = actions::editEndpoint,
                        modifier = Modifier.fillMaxWidth().testTag("endpoint"),
                        label = { Text("https://jarvis.local") },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { actions.saveEndpoint() }),
                    )
                    Button(onClick = actions::saveEndpoint, enabled = !state.busy) {
                        Text("Opslaan en controleren")
                    }
                    ConnectionLine(state.connection)
                }
            }
        }
        if (state.endpoint != null) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Apparaat koppelen", style = MaterialTheme.typography.titleMedium)
                        if (state.pairingPending) {
                            Text("Wacht op goedkeuring vanaf een vertrouwd Jarvis-apparaat.")
                            Text(
                                "Verloopt: ${state.pairingExpiresAt ?: "—"}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontFamily = FontFamily.Monospace,
                            )
                            CircularProgressIndicator()
                        } else {
                            if (state.activationRequired) {
                                Text("Gebruik de eenmalige code van je Home Node en kies een wachtwoord van minimaal 15 tekens.")
                                OutlinedTextField(value = activationCode, onValueChange = { activationCode = it.take(256) },
                                    label = { Text("Activatiecode") }, singleLine = true,
                                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                        autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
                                    visualTransformation = VisualTransformation.None)
                            }
                            OutlinedTextField(value = password, onValueChange = { password = it.take(1024) },
                                label = { Text("Accountwachtwoord") }, singleLine = true,
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                    autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
                                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                        Icon(painterResource(if (passwordVisible) R.drawable.ic_visibility_off else R.drawable.ic_visibility),
                                            contentDescription = if (passwordVisible) "Wachtwoord verbergen" else "Wachtwoord tonen")
                                    }
                                })
                            Button(onClick = {
                                val suppliedPassword = password.ifEmpty { null }
                                val suppliedCode = if (state.activationRequired) activationCode else null
                                password = ""
                                passwordVisible = false
                                activationCode = ""
                                actions.beginEnrollment(suppliedPassword, suppliedCode)
                            }, enabled = !state.busy) {
                                Text(if (state.busy) "Bezig…" else "Doorgaan")
                            }
                        }
                    }
                }
            }
        }
        state.error?.let { error ->
            item { Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("error")) }
        }
    }
}
