package com.hawkeynl.jarvis.security

import com.hawkeynl.jarvis.network.*
import com.hawkeynl.jarvis.storage.*
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.jsonPrimitive
import java.security.SecureRandom
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Native-only controller. Every change is signed with [identity], whose strong-biometric
 * prompt unlocks the device key; there is no approval flag and no time window. */
class ModelControlService(
    private val api: JarvisApi, private val sessions: SessionRepository,
    private val settings: EndpointSettingsRepository, private val identity: DeviceIdentity,
    private val unlocked: () -> Boolean,
) {
    private val mutation = Mutex()
    suspend fun policy(): ModelPolicySnapshot {
        val endpoint = settings.endpoint.first() ?: error("Home Node missing")
        val token = sessions.session().token ?: error("Sessie ontbreekt")
        return (api.modelPolicy(endpoint, token) as? ApiResult.Success)?.value ?: error("Model policy unreachable")
    }

    suspend fun toggle(entry: ModelEntry, hash: String) = mutation.withLock {
        check(unlocked()) { "App locked" }
        val lease = sessions.modelAuthorization
        val ticket = lease.ticket()
        val endpoint = settings.endpoint.first() ?: error("Home Node missing")
        val session = sessions.session()
        val token = session.token ?: error("Sessie ontbreekt")
        val device = session.deviceId ?: error("Device not paired")
        val snapshot = (api.modelPolicy(endpoint, token) as? ApiResult.Success)?.value ?: error("Model policy unreachable")
        val now = System.currentTimeMillis() / 1000
        check(snapshot.mutation == "device-signed-model-toggle-v1" && snapshot.policy_sha256 == hash && snapshot.device_id == device
            && snapshot.server_time in (now - 30)..(now + 30) && snapshot.models.any { it == entry }) { "Policy or device changed; refresh the list" }
        val approval = ModelApproval(UUID.randomUUID(), ByteArray(32).also { SecureRandom().nextBytes(it) }, UUID.fromString(snapshot.user_id),
            UUID.fromString(device), now, now + 120, entry.provider, entry.model, !entry.enabled, hash)
        val message = approval.message()
        val action = if (entry.enabled) "Disable" else "Enable"
        val signature = identity.signHex(Hex.encode(message), "$action ${PairingApproval.hint("${entry.provider}/${entry.model}", 60)}")
        currentCoroutineContext().ensureActive()
        check(settings.endpoint.first() == endpoint && sessions.session() == session && System.currentTimeMillis() / 1000 < approval.expires) { "Approval expired or session changed" }
        check(unlocked() && lease.accepts(ticket)) { "App locked or authorization revoked" }
        val signed = approval.signed(signature)
        val response = (api.modelToggle(endpoint, token, signed) as? ApiResult.Success)?.value ?: error("Change not confirmed; refresh the status")
        check(response["status"]?.jsonPrimitive?.content == "active") { "Activation not confirmed" }
    }

    /** Replace the owner routing document. Validates, classifies cost relaxation (named in
     *  the prompt) and signs Core's canonical payload. */
    suspend fun setRouting(next: Routing, hash: String) = mutation.withLock {
        check(unlocked()) { "App locked" }
        val lease = sessions.modelAuthorization
        val ticket = lease.ticket()
        val endpoint = settings.endpoint.first() ?: error("Home Node missing")
        val session = sessions.session()
        val token = session.token ?: error("Sessie ontbreekt")
        val device = session.deviceId ?: error("Device not paired")
        val snapshot = (api.modelPolicy(endpoint, token) as? ApiResult.Success)?.value ?: error("Model policy unreachable")
        val now = System.currentTimeMillis() / 1000
        check(snapshot.routing_mutation == ROUTING_MUTATION && snapshot.routing_sha256 == hash && snapshot.device_id == device
            && snapshot.server_time in (now - 30)..(now + 30)) { "Model routing, device or clock changed; refresh and try again." }
        routingIssue(next, snapshot.models.map { RouteEntry(it.provider, it.model) })?.let { error(it) }
        // Fail closed: an unknown current routing makes every paid permission count as new.
        val relaxed = relaxation(currentRouting(snapshot), next)
        val approval = ModelRoutingApproval(UUID.randomUUID(), ByteArray(32).also { SecureRandom().nextBytes(it) }, UUID.fromString(snapshot.user_id),
            UUID.fromString(device), now, now + ROUTING_APPROVAL_SECONDS, next, hash)
        val message = approval.message()
        // The broker reads one 16 KiB frame; refuse before prompting.
        check(approval.signed("00".repeat(64)).let { """{"request":$it}""" }.toByteArray(Charsets.UTF_8).size + 1 < BROKER_FRAME_BYTES) { "Routing is too large to send" }
        val signature = identity.signHex(Hex.encode(message), relaxed.prompt())
        currentCoroutineContext().ensureActive()
        check(settings.endpoint.first() == endpoint && sessions.session() == session && System.currentTimeMillis() / 1000 < approval.expires) { "Approval expired or session changed" }
        check(unlocked() && lease.accepts(ticket)) { "App locked or authorization revoked" }
        val signed = approval.signed(signature)
        // Same fixed privileged route as model toggles.
        val response = when (val result = api.modelToggle(endpoint, token, signed)) {
            is ApiResult.Success -> result.value
            is ApiResult.HttpError -> error(routingRefused(result.status))
            else -> error("Outcome unknown; refresh the model status")
        }
        check(response["status"]?.jsonPrimitive?.content == "active") { "Activation not confirmed" }
    }
}
