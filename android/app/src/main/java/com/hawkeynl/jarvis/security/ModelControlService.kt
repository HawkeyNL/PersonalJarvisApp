package com.hawkeynl.jarvis.security

import com.hawkeynl.jarvis.network.*
import com.hawkeynl.jarvis.storage.*
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.jsonPrimitive
import java.security.SecureRandom
import java.util.UUID
import android.os.SystemClock
import java.security.MessageDigest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Native-only controller. The authentication callback is wired by the Activity
 * to the real OS prompt; it is never a network/JavaScript approval flag. */
class ModelControlService(
    private val api: JarvisApi, private val sessions: SessionRepository,
    private val settings: EndpointSettingsRepository, private val identity: DeviceIdentity,
    private val authenticate: suspend (String) -> Boolean,
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
        val context = Hex.encode(MessageDigest.getInstance("SHA-256").digest("${endpoint.baseUrl}|$device|$token".toByteArray()))
        val snapshot = (api.modelPolicy(endpoint, token) as? ApiResult.Success)?.value ?: error("Model policy unreachable")
        val now = System.currentTimeMillis() / 1000
        check(snapshot.mutation == "device-signed-model-toggle-v1" && snapshot.policy_sha256 == hash && snapshot.device_id == device
            && snapshot.server_time in (now - 30)..(now + 30) && snapshot.models.any { it == entry }) { "Policy or device changed; refresh the list" }
        val approval = ModelApproval(UUID.randomUUID(), ByteArray(32).also { SecureRandom().nextBytes(it) }, UUID.fromString(snapshot.user_id),
            UUID.fromString(device), now, now + 120, entry.provider, entry.model, !entry.enabled, hash)
        val message = approval.message()
        val fresh = !lease.valid(context, ticket, SystemClock.elapsedRealtime(), System.currentTimeMillis())
        if (fresh && !authenticate("Allow Jarvis model changes for five minutes")) {
            lease.invalidate()
            error("Cancelled; OS authentication required")
        }
        currentCoroutineContext().ensureActive()
        check(settings.endpoint.first() == endpoint && sessions.session() == session && System.currentTimeMillis() / 1000 < approval.expires) { "Approval expired or session changed" }
        check(unlocked() && lease.accepts(ticket)) { "App locked or authorization revoked" }
        if (fresh) check(lease.remember(context, ticket, SystemClock.elapsedRealtime(), System.currentTimeMillis()))
        check(lease.valid(context, ticket, SystemClock.elapsedRealtime(), System.currentTimeMillis())) { "Modelautorisatie verlopen" }
        val signed = approval.signed(identity.signHex(Hex.encode(message)))
        check(unlocked() && lease.accepts(ticket)) { "Authorization revoked" }
        val response = (api.modelToggle(endpoint, token, signed) as? ApiResult.Success)?.value ?: error("Change not confirmed; refresh the status")
        check(response["status"]?.jsonPrimitive?.content == "active") { "Activation not confirmed" }
    }
}
