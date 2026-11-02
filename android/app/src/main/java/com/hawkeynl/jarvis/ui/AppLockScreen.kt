package com.hawkeynl.jarvis.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
internal fun AppLockScreen(message: String?, onRetry: () -> Unit, onReset: () -> Unit) {
    val c = JvTheme.colors
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(JvSpace.xxl),
        verticalArrangement = Arrangement.spacedBy(JvSpace.lg, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(48.dp).clip(JvShapes.lg).background(c.accentSubtle), contentAlignment = Alignment.Center) {
            JvIconView(JvIcon.KEY, tint = c.accentText, size = 24.dp)
        }
        Text("Jarvis is locked", Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Text(
            message ?: "Confirm with strong biometrics to open your session.",
            Modifier.widthIn(max = 400.dp),
            color = c.textSecondary,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Column(Modifier.widthIn(max = 400.dp).fillMaxWidth().padding(top = JvSpace.sm), verticalArrangement = Arrangement.spacedBy(JvSpace.md)) {
            ActionButton("Unlock again", onRetry)
            GhostButton("Erase this device and pair again", onReset, Modifier.fillMaxWidth(), danger = true)
        }
    }
}
