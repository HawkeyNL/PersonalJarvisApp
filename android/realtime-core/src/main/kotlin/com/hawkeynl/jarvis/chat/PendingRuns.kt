package com.hawkeynl.jarvis.chat

import kotlinx.serialization.Serializable

@Serializable data class RecoveredRun(val request_id: String, val run_id: String, val conversation_id: String, val state: String)

/** Correlation IDs only. Never stores prompts or silently evicts uncertain work. */
class PendingRuns {
    private val values = linkedMapOf<String, String>()
    fun add(request: String, optimistic: String): Boolean {
        if (values.size >= 32 || values.containsKey(request)) return false
        values[request] = optimistic; return true
    }
    operator fun get(request: String?): String? = values[request]
    fun remove(request: String) { values.remove(request) }
    fun clear() { values.clear() }
    fun requests(): List<String> = values.keys.toList()
    fun reconcile(request: String, result: RecoveredRun) {
        if (result.request_id == request && result.state in setOf("completed", "failed", "interrupted")) values.remove(request)
    }
}
