package com.hawkeynl.jarvis.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
internal fun AppLockScreen(message: String?, onRetry: () -> Unit, onReset: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        JvOrb(150.dp, Mood.IDLE, Modifier.padding(bottom = 40.dp))
        PageTitle("JARVIS IS LOCKED", "YOUR SECOND MIND")
        Text(
            message ?: "Confirm with strong biometrics to open your session.",
            style = exo(13.5f, color = Jv.Text2),
            textAlign = TextAlign.Center,
        )
        ActionButton("Unlock again", onRetry)
        GhostButton("Erase this device and pair again", onReset, Modifier.padding(top = 16.dp), danger = true)
    }
}
