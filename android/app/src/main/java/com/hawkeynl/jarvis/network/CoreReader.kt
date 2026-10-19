package com.hawkeynl.jarvis.network

import com.hawkeynl.jarvis.storage.SessionRepository
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * Read-only owner endpoints for the hub and node pages, with the same session
 * token as the chat. Agent decisions go through `AgentApprovalService`.
 */
class CoreReader(
    private val api: JarvisApi,
    private val sessions: SessionRepository,
) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }

    suspend fun <T> read(endpoint: HomeNodeEndpoint, path: String, serializer: KSerializer<T>): Availability<T> {
        val token = sessions.session().token ?: return Availability.Failed(LoadFailure.SIGNIN)
        val result = api.getAuthorized(endpoint, token, path)
        if (result !is ApiResult.Success) return result.failure()
        return try {
            Availability.Ok(json.decodeFromJsonElement(serializer, result.value))
        } catch (_: IllegalArgumentException) {
            // SerializationException included: a shape this app cannot read.
            Availability.Failed(LoadFailure.FAILED)
        }
    }
}
