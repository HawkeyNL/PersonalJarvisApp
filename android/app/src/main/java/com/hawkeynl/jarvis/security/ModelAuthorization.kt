package com.hawkeynl.jarvis.security

import java.util.UUID

/** Revokes model changes in flight: lock, logout and session changes move to a new ticket. */
class ModelAuthorization {
    private var generation = UUID.randomUUID()

    @Synchronized fun ticket(): UUID = generation
    @Synchronized fun accepts(ticket: UUID): Boolean = generation == ticket
    @Synchronized fun invalidate() { generation = UUID.randomUUID() }
}
