package com.hawkeynl.jarvis.chat

import kotlinx.serialization.Serializable

// Native wire projection of PersonalJarvis/crates/client-core. No credentials,
// Android framework, sockets or LLM dependency belongs in this state module.
@Serializable data class RealtimeRun(val run_id: String, val request_id: String, val conversation_id: String)
@Serializable data class RealtimeMessage(val id: String, val conversation_id: String, val role: String, val content: String, val model: String? = null, val created_at: String)
@Serializable data class RealtimePayload(
    val device_id: String? = null, val run_id: String? = null, val request_id: String? = null,
    val conversation_id: String? = null, val id: String? = null, val title: String? = null,
    val updated_at: String? = null, val run: RealtimeRun? = null, val text: String? = null,
    val message: RealtimeMessage? = null, val reason: String? = null,
)
@Serializable data class RealtimeEvent(val protocol: Int, val epoch: String, val sequence: Long, val event_id: String, val type: String, val payload: RealtimePayload)
