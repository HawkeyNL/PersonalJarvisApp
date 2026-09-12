package com.hawkeynl.jarvis.chat

/** Device-local presentation preference. Never sent to Core or a provider. */
object SpeechRate {
    fun normalize(value: Float): Float = if (value.isFinite()) value.coerceIn(0.5f, 2f) else 1f
}
