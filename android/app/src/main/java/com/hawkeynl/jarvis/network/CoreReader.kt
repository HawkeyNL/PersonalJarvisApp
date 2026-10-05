package com.hawkeynl.jarvis.network

import com.hawkeynl.jarvis.storage.SessionRepository
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Read-only owner endpoints for the hub and node pages, with the same session
 * token as the chat. The only write is denying a pending agent action, which
 * cancels it and needs no signature; approving needs the agent-approval-v1
 * signature, which this app does not implement.
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

    suspend fun denyPending(endpoint: HomeNodeEndpoint, pendingId: String): ApiResult<Unit> {
        if (!PENDING_ID.matches(pendingId)) return ApiResult.InvalidResponse("Invalid pending action id.")
        val token = sessions.session().token ?: return ApiResult.Unauthorized
        return api.postAuthorized(endpoint, token, "/v1/agent/pending/$pendingId/deny", JsonObject(emptyMap()))
    }

    private companion object {
        val PENDING_ID = Regex("[A-Za-z0-9_-]{1,128}")
    }
}
