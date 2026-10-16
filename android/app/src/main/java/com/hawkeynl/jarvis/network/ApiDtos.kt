package com.hawkeynl.jarvis.network

import kotlinx.serialization.Serializable

@Serializable
data class HealthResponse(val status: String)

@Serializable
data class ErrorResponse(
    val error: String? = null,
    val hint: String? = null,
    val conversation_id: String? = null,
    /** Machine-readable Core error code; compared, never displayed. */
    val code: String? = null,
)

/** `POST /v1/auth/pairing/requests`. Core rejects unknown fields, so this stays
 *  separate from [BootstrapRequest]; optional pairing details are only added
 *  once the app can tell that Core accepts them. */
@Serializable
data class PairingCreateRequest(
    val name: String,
    val platform: String,
    val public_key: String,
    val password: String? = null,
) { override fun toString() = "PairingCreateRequest([redacted])" }

/** `POST /v1/auth/bootstrap` (first owner device); Core rejects unknown fields. */
@Serializable
data class BootstrapRequest(
    val name: String,
    val platform: String,
    val public_key: String,
    val password: String? = null,
) { override fun toString() = "BootstrapRequest([redacted])" }

/** `GET /v1/auth/pairing/requests`. Fields after `expires_at` are absent from
 *  Cores before the pairing-details release; the candidate details are
 *  unauthenticated hints. Display [com.hawkeynl.jarvis.security.PairingApproval.verifiedFingerprint],
 *  never the server's fingerprint fields. */
@Serializable
data class PairingRequestList(val requests: List<PairingRequestItem>)

@Serializable
data class PairingRequestItem(
    val id: String,
    val device_name: String,
    val platform: String,
    val fingerprint: String,
    val nonce: String,
    val candidate_public_key: String,
    val created_at: Long,
    val expires_at: Long,
    val fingerprint_full: String? = null,
    val ip: String? = null,
    val user_agent: String? = null,
    val app_version: String? = null,
    val model: String? = null,
)

@Serializable
data class AuthMe(val user_id: String, val device_id: String)

/** `GET /v1/auth/unlock/pending`: other devices of this account waiting to be unlocked. */
@Serializable
data class UnlockRequestItem(val id: String, val device_name: String = "", val platform: String = "", val nonce: String, val created_at: Long)

@Serializable
data class UnlockRequestList(val requests: List<UnlockRequestItem> = emptyList())

@Serializable
data class AccountStatus(val protocol: Int, val password_required: Boolean, val bootstrap_required: Boolean)

@Serializable
data class FirstDeviceResponse(val device_id: String)

@Serializable
data class PairingTicket(
    val request_id: String,
    val nonce: String,
    val expires_at: Long,
)

@Serializable
data class PairingStatusResponse(
    val status: String,
    val device_id: String? = null,
)

@Serializable
data class ChallengeRequest(val device_id: String)

@Serializable
data class ChallengeResponse(
    val challenge_id: String,
    val nonce: String,
)

@Serializable
data class LoginRequest(
    val device_id: String,
    val challenge_id: String,
    val signature: String,
    val password: String? = null,
) { override fun toString() = "LoginRequest([redacted])" }

@Serializable
data class LoginResponse(
    val token: String,
    val expires_at: Long,
)

@Serializable
data class ConversationSummary(
    val id: String,
    val title: String,
    val updated_at: String,
)

@Serializable
data class ConversationListResponse(val conversations: List<ConversationSummary>)

@Serializable
data class ConversationMessage(
    val role: String,
    val content: String,
    val model: String? = null,
    val at: String,
    val id: String? = null,
)

@Serializable
data class ConversationDetailResponse(
    val id: String,
    val title: String,
    val messages: List<ConversationMessage>,
    val assistant_running: Boolean = false,
)

@Serializable
data class ChatTurn(
    val role: String,
    val content: String,
)

@Serializable
data class ChatRequest(
    val messages: List<ChatTurn>,
    val conversation_id: String? = null,
)

@Serializable
data class ChatResponse(
    val reply: String,
    val model: String? = null,
    val backend: String? = null,
    val routing_reason: String? = null,
    val stop_reason: String? = null,
    val conversation_id: String,
    val conversation_title: String,
    val new_topic: Boolean,
    val routing_mode: String? = null,
)

@Serializable
data class AndroidUpdateArtifact(
    val size: Long,
    val sha256: String,
    val signing_certificate_sha256: String,
)

@Serializable
data class AndroidUpdateMetadata(
    val schema_version: Int,
    val platform: String,
    val package_name: String,
    val version_code: Int,
    val version_name: String,
    val minimum_client_protocol: Int,
    val released_at: String,
    val notes: String,
    val artifact: AndroidUpdateArtifact,
    val download_url: String,
)
