package com.hawkeynl.jarvis.security

import java.util.UUID

/** Memory-only model authorization; never stores credentials or signed requests. */
class ModelAuthorization {
    private var generation = UUID.randomUUID()
    private data class Grant(val context: String, val monotonic: Long, val wall: Long)
    private var grant: Grant? = null

    @Synchronized fun ticket(): UUID = generation
    @Synchronized fun accepts(ticket: UUID): Boolean = generation == ticket
    @Synchronized fun invalidate() { generation = UUID.randomUUID(); grant = null }
    @Synchronized fun valid(context: String, ticket: UUID, monotonic: Long, wall: Long): Boolean {
        val current = grant ?: return false
        return generation == ticket && current.context == context &&
            monotonic - current.monotonic in 0 until 300_000L && wall - current.wall in 0 until 300_000L
    }
    @Synchronized fun remember(context: String, ticket: UUID, monotonic: Long, wall: Long): Boolean {
        if (generation != ticket) return false
        grant = Grant(context, monotonic, wall)
        return true
    }
}
