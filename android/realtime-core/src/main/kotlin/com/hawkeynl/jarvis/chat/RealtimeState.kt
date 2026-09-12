package com.hawkeynl.jarvis.chat

import java.util.UUID

/** A socket-scoped, constant-memory cursor. False means stale/duplicate;
 * malformed identity/protocol requires disconnect and REST reconciliation. */
class RealtimeCursor {
    private var epoch: String? = null
    private var sequence = 0L
    fun accept(event: RealtimeEvent): Boolean {
        require(event.protocol == 1 && event.sequence > 0) { "Unsupported event protocol" }
        require(isUuid(event.epoch) && isUuid(event.event_id)) { "Invalid event identity" }
        if (epoch != event.epoch) {
            require(event.type == "connection.ready") { "Missing connection identity" }
            epoch = event.epoch
            sequence = 0
        }
        if (event.sequence <= sequence) return false
        sequence = event.sequence
        return true
    }
    private fun isUuid(value: String): Boolean = value.length == 36 &&
        runCatching { UUID.fromString(value).toString().equals(value, ignoreCase = true) }.getOrDefault(false)
}

/** Used by the actual view model for REST/provisional/event reconciliation.
 * Does not navigate or require Android framework state. */
fun <T> mergeCanonical(rows: List<T>, canonical: T, optimistic: String?, id: (T) -> String?): List<T> {
    val key = requireNotNull(id(canonical))
    require(key.isNotEmpty())
    var inserted = false
    val result = rows.mapNotNull {
        if (id(it) != key && (optimistic == null || id(it) != optimistic)) it
        else if (inserted) null else { inserted = true; canonical }
    }
    return if (inserted) result else result + canonical
}
