package com.hawkeynl.jarvis.chat

data class LocalVoice(val id: String, val label: String)
data class VoiceCandidate(val id: String, val label: String, val networkRequired: Boolean, val installed: Boolean)

object LocalVoiceCatalog {
    private fun safe(value: String) = value.isNotBlank() && value.length <= 128 && value.none { it.isISOControl() }
    fun catalog(candidates: Sequence<VoiceCandidate>): List<LocalVoice> = candidates.take(512)
        .filter { !it.networkRequired && it.installed && safe(it.id) && safe(it.label) }
        .map { LocalVoice(it.id, it.label) }.distinctBy { it.id }.take(128).sortedBy { it.label }.toList()

    /** Explicit choices never silently fall back; empty means local default. */
    fun choose(voices: List<LocalVoice>, selected: String, default: String?): String? =
        if (selected.isNotEmpty()) voices.firstOrNull { it.id == selected }?.id
        else voices.firstOrNull { it.id == default }?.id ?: voices.firstOrNull()?.id
}
