package com.hawkeynl.jarvis.chat

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

// Native wire projection of PersonalJarvis/crates/client-core. No credentials,
// Android framework, sockets or LLM dependency belongs in this state module.
@Serializable data class RealtimeRun(val run_id: String, val request_id: String, val conversation_id: String)
/** `attachments`: chat photo ids; absent on text-only messages. */
@Serializable data class RealtimeMessage(val id: String, val conversation_id: String, val role: String, val content: String, val model: String? = null, val created_at: String, val attachments: List<String> = emptyList())
/** Core's advice to offer a web search; only the owner's click sends it. */
@Serializable data class ResearchOffer(val question: String)
@Serializable data class RealtimePayload(
    val device_id: String? = null, val run_id: String? = null, val request_id: String? = null,
    val conversation_id: String? = null, val id: String? = null, val title: String? = null,
    val updated_at: String? = null, val run: RealtimeRun? = null, val text: String? = null,
    val message: RealtimeMessage? = null, val reason: String? = null,
    val research_offer: ResearchOffer? = null, val web_search: Boolean? = null,
)
@Serializable data class RealtimeEvent(val protocol: Int, val epoch: String, val sequence: Long, val event_id: String, val type: String, val payload: RealtimePayload)

/** Event types this app reads. Keep in sync with the receivers' `when` branches. */
val knownRealtimeTypes = setOf(
    "connection.ready", "conversation.created", "conversation.updated", "conversation.deleted",
    "message.created", "assistant.started", "assistant.delta", "assistant.completed", "assistant.failed",
    "voice.owner_changed", "voice.started", "voice.stopped", "voice.failed",
)

/** A newer Core may add event types. Their payload fields can reuse names with
 * other types, so it is dropped: the envelope still decodes and the cursor still
 * validates and advances, while receivers ignore the unknown type. */
fun decodeRealtimeEvent(json: Json, text: String): RealtimeEvent {
    val frame = json.parseToJsonElement(text).jsonObject
    val type = (frame["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    return json.decodeFromJsonElement(RealtimeEvent.serializer(),
        if (type == null || type in knownRealtimeTypes) frame else JsonObject(frame + ("payload" to JsonObject(emptyMap()))))
}
