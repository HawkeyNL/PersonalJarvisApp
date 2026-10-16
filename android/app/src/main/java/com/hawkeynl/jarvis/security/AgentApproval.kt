package com.hawkeynl.jarvis.security

import com.hawkeynl.jarvis.network.ApiResult
import com.hawkeynl.jarvis.network.HomeNodeEndpoint
import com.hawkeynl.jarvis.network.JarvisApi
import com.hawkeynl.jarvis.network.PendingAction
import com.hawkeynl.jarvis.network.PendingResponse
import com.hawkeynl.jarvis.storage.SessionRepository
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Exact v1 client-core `agent_approval_message`: domain, pending id, 32-byte nonce,
 *  32-byte SHA-256 of the action JSON, approver device id. */
data class AgentApprovalMessage(
    val pendingId: String,
    val nonceHex: String,
    val actionSha256Hex: String,
    val deviceId: String,
) : DeviceMessage {
    override fun message(): ByteArray {
        require(PairingApproval.canonicalUuid.matches(pendingId) && PairingApproval.canonicalUuid.matches(deviceId)) { "Invalid UUID" }
        require(HEX64.matches(nonceHex) && HEX64.matches(actionSha256Hex)) { "Invalid agent approval payload" }
        val domain = "jarvis/agent-approval/v1\u0000".toByteArray(Charsets.UTF_8)
        val buffer = ByteBuffer.allocate(domain.size + 16 + 32 + 32 + 16).put(domain)
        buffer.putUuid(pendingId).put(Hex.decode(nonceHex)).put(Hex.decode(actionSha256Hex))
        return buffer.putUuid(deviceId).array()
    }

    private fun ByteBuffer.putUuid(value: String): ByteBuffer =
        UUID.fromString(value).let { putLong(it.mostSignificantBits).putLong(it.leastSignificantBits) }

    private companion object {
        val HEX64 = Regex("[0-9a-fA-F]{64}")
    }
}

/** An agent action this app can show in full. Only this kind and exactly these fields are
 *  accepted (client-core `MOBILE_APPROVABLE_AGENT_ACTIONS`); Core refuses others too. */
sealed interface ReviewedAgentAction {
    val kind: String

    data class WriteFile(val path: String, val content: String) : ReviewedAgentAction {
        override val kind = "write_file"
    }

    /** The compact serde_json bytes Core stores for this action and hashes. */
    fun canonicalJson(): String = when (this) {
        is WriteFile -> """{"type":${quote(kind)},"path":${quote(path)},"content":${quote(content)}}"""
    }

    private fun quote(value: String): String = buildString {
        append('"')
        for (c in value) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                in '\u0000'..'\u001f' -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }
        append('"')
    }
}

/** What this app shows for one pending agent action, and whether it may approve it. */
sealed interface AgentReview {
    /** [sha256] is computed here from [action]: the hash the approval will sign. */
    data class Approvable(val action: ReviewedAgentAction, val sha256: String) : AgentReview

    /** Shown with [raw] (escaped) when present; only deny is offered. */
    data class Refused(val reason: String, val raw: String?) : AgentReview

    companion object {
        /** client-core `MAX_REVIEWABLE_ACTION_JSON_LEN`. */
        const val MAX_ACTION_JSON_BYTES = 16 * 1024
        private val FIELDS = mapOf("write_file" to listOf("type", "path", "content"))

        /** Approvable only if the listed JSON is one known kind with exactly its string
         *  fields, re-encodes to the same bytes, and those bytes hash to Core's `action_sha256`. */
        fun of(item: PendingAction): AgentReview {
            val raw = item.action_json
                ?: return Refused("This phone cannot review this action. Approve it in the macOS app, or deny it.", null)
            if (item.approval_message != "agent-approval-v1") return Refused("This Home Node uses an approval format this app does not know.", raw)
            if (raw.toByteArray(Charsets.UTF_8).size > MAX_ACTION_JSON_BYTES) return Refused("Too large to review on this phone.", raw)
            // A lone surrogate would show here but hash as '?'.
            if (String(raw.toByteArray(Charsets.UTF_8), Charsets.UTF_8) != raw) return Refused("The action contains invalid text. Do not approve it.", raw)
            val action = parse(raw) ?: return Refused("Phones approve only file writes they can show in full. Approve it in the macOS app, or deny it.", raw)
            if (action.kind != item.action || action.canonicalJson() != raw) return Refused("Core listed this action inconsistently. Do not approve it.", raw)
            val sha256 = Hex.encode(MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8)))
            if (!sha256.equals(item.action_sha256, ignoreCase = true)) return Refused("The action does not match its hash. Do not approve it.", raw)
            return Approvable(action, sha256)
        }

        private fun parse(raw: String): ReviewedAgentAction? {
            val fields = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
            val values = fields.mapValues { (_, value) -> (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null }
            if (values.keys.toList() != FIELDS[values["type"]]) return null
            return when (values["type"]) {
                "write_file" -> ReviewedAgentAction.WriteFile(values.getValue("path"), values.getValue("content"))
                else -> null
            }
        }

        /** Server text shown in full, with every invisible, control, bidi or private-use
         *  character (and stacked combining marks past two) as a visible `\u{XXXX}`.
         *  Newlines stay only where [keepNewlines]. */
        fun visible(text: String, keepNewlines: Boolean = true): String = buildString {
            var marks = 0
            text.codePoints().forEach { c ->
                val type = Character.getType(c)
                marks = if (type == Character.NON_SPACING_MARK.toInt() || type == Character.ENCLOSING_MARK.toInt()) marks + 1 else 0
                val plain = (c == '\n'.code && keepNewlines) || c == '\t'.code || c == ' '.code ||
                    (type !in INVISIBLE_TYPES && !PairingApproval.isHidden(c) && marks <= 2)
                if (plain) appendCodePoint(c) else append("\\u{%04X}".format(c))
            }
        }

        private val INVISIBLE_TYPES = setOf(
            Character.CONTROL, Character.FORMAT, Character.PRIVATE_USE, Character.SURROGATE, Character.UNASSIGNED,
            Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR, Character.SPACE_SEPARATOR,
        ).map(Byte::toInt).toSet()
    }
}

/** Approve or deny pending agent actions. Every decision re-reads the list and takes the
 *  one entry with this id. Approve signs only when that entry still reviews as approvable
 *  with the hash the owner was shown; the strong-biometric prompt unlocks the device key.
 *  Deny needs [authenticate], the same kind of prompt without a key. [unlocked] is checked
 *  before and again after each prompt. */
class AgentApprovalService(
    private val api: JarvisApi,
    private val sessions: SessionRepository,
    private val endpoint: suspend () -> HomeNodeEndpoint?,
    private val identity: DeviceIdentity,
    private val approvals: ApprovalLock,
    private val authenticate: suspend (String) -> Boolean,
    private val unlocked: () -> Boolean,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun approve(id: String, shownSha256: String) = approvals.exclusive {
        val (origin, token, item) = current(id)
        val review = AgentReview.of(item) as? AgentReview.Approvable ?: error("This action cannot be approved on this phone.")
        check(review.sha256.equals(shownSha256, ignoreCase = true)) { "The action changed since it was shown. Review it again." }
        val session = sessions.session()
        val device = session.deviceId ?: error("Device not paired")
        // Built once before prompting, so a malformed entry never prompts.
        val message = AgentApprovalMessage(item.pending_id, item.nonce, review.sha256, device).also { it.message() }
        val signature = identity.sign(message, prompt(review.action))
        currentCoroutineContext().ensureActive()
        check(unlocked() && endpoint() == origin && sessions.session() == session) { "Session changed or Jarvis locked; nothing was approved." }
        when (val result = api.postAuthorizedJson(origin, token, "/v1/agent/pending/${item.pending_id}/approve", JsonObject(mapOf("signature" to JsonPrimitive(signature))))) {
            is ApiResult.Success -> check(
                runCatching { result.value.jsonObject["action"]?.jsonPrimitive?.content }.getOrNull() == item.action,
            ) { "Core did not confirm the approval." }
            // Core claims the action before it runs: a failed run is not pending any more.
            is ApiResult.HttpError -> error(if (result.status == 400 && result.message == RUN_FAILED) "Approved, but the action failed on the Home Node." else refusal(result))
            // The signed approval may have reached Core before the answer failed.
            is ApiResult.Unreachable, is ApiResult.InvalidResponse -> error("The outcome is unknown. Refresh to see whether it ran.")
            ApiResult.Unauthorized -> error(refusal(result))
        }
    }

    suspend fun deny(id: String) = approvals.exclusive {
        val (origin, token, item) = current(id)
        val session = sessions.session()
        if (!authenticate("Deny agent action ${PairingApproval.displayName(item.action, 32)}")) error("Cancelled; fingerprint or face unlock is required.")
        currentCoroutineContext().ensureActive()
        check(unlocked() && endpoint() == origin && sessions.session() == session) { "Session changed or Jarvis locked; try again." }
        when (val result = api.postAuthorizedJson(origin, token, "/v1/agent/pending/${item.pending_id}/deny", JsonObject(emptyMap()))) {
            is ApiResult.Success -> check(
                runCatching { result.value.jsonObject["status"]?.jsonPrimitive?.content }.getOrNull() == "denied",
            ) { "Core did not confirm the denial." }
            else -> error(refusal(result))
        }
    }

    /** The entry as Core lists it right now, never a copy the UI held on to. */
    private suspend fun current(id: String): Triple<HomeNodeEndpoint, String, PendingAction> {
        check(unlocked()) { "App locked" }
        require(PairingApproval.canonicalUuid.matches(id)) { "Invalid agent action" }
        val origin = endpoint() ?: error("Home Node missing")
        val token = sessions.session().token ?: error("Sign in again")
        val list = when (val result = api.getAuthorized(origin, token, "/v1/agent/pending")) {
            is ApiResult.Success -> runCatching { json.decodeFromJsonElement(PendingResponse.serializer(), result.value) }.getOrNull()
                ?: error("Core returned an invalid list.")
            else -> error(refusal(result))
        }
        val item = list.pending.singleOrNull { it.pending_id.equals(id, ignoreCase = true) }
            ?: error("This agent action is no longer pending.")
        return Triple(origin, token, item)
    }

    private fun prompt(action: ReviewedAgentAction): String = when (action) {
        is ReviewedAgentAction.WriteFile ->
            "Write ${PairingApproval.displayName(action.path, 60)} (${action.content.toByteArray(Charsets.UTF_8).size} bytes)"
    }

    private companion object {
        const val RUN_FAILED = "agent action failed"
    }

    // Core answers 401 for a refused approval too: report it, do not sign out here.
    private fun refusal(result: ApiResult<*>): String = when (result) {
        is ApiResult.Success -> "Core did not confirm the decision."
        ApiResult.Unauthorized -> "Core refused this decision. The action may have expired or been handled already."
        is ApiResult.HttpError -> result.message ?: "Core refused this decision (HTTP ${result.status})."
        is ApiResult.Unreachable -> "Home Node unreachable; refresh to see whether the decision applied."
        is ApiResult.InvalidResponse -> result.message
    }
}
