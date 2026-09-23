package com.hawkeynl.jarvis.security

import com.hawkeynl.jarvis.network.*
import com.hawkeynl.jarvis.storage.*
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.jsonPrimitive
import java.security.SecureRandom
import java.util.UUID

/** Native-only controller. The authentication callback is wired by the Activity
 * to the real OS prompt; it is never a network/JavaScript approval flag. */
class ModelControlService(
    private val api: JarvisApi, private val sessions: SessionRepository,
    private val settings: EndpointSettingsRepository, private val identity: DeviceIdentity,
    private val authenticate: suspend (String) -> Boolean,
) {
    suspend fun policy(): ModelPolicySnapshot {
        val endpoint = settings.endpoint.first() ?: error("Home Node ontbreekt")
        val token = sessions.session().token ?: error("Sessie ontbreekt")
        return (api.modelPolicy(endpoint, token) as? ApiResult.Success)?.value ?: error("Modelpolicy niet bereikbaar")
    }

    suspend fun toggle(entry: ModelEntry, hash: String) {
        val endpoint = settings.endpoint.first() ?: error("Home Node ontbreekt")
        val session = sessions.session()
        val token = session.token ?: error("Sessie ontbreekt")
        val device = session.deviceId ?: error("Apparaat niet gekoppeld")
        val snapshot = (api.modelPolicy(endpoint, token) as? ApiResult.Success)?.value ?: error("Modelpolicy niet bereikbaar")
        val now = System.currentTimeMillis() / 1000
        check(snapshot.mutation == "device-signed-model-toggle-v1" && snapshot.policy_sha256 == hash && snapshot.device_id == device
            && snapshot.server_time in (now - 30)..(now + 30) && snapshot.models.any { it == entry }) { "Policy of apparaat gewijzigd; ververs de lijst" }
        val approval = ModelApproval(UUID.randomUUID(), ByteArray(32).also { SecureRandom().nextBytes(it) }, UUID.fromString(snapshot.user_id),
            UUID.fromString(device), now, now + 120, entry.provider, entry.model, !entry.enabled, hash)
        val message = approval.message()
        check(authenticate("${entry.provider}/${entry.model} ${if (entry.enabled) "uitschakelen" else "inschakelen"}")) { "Geannuleerd; OS-authenticatie vereist" }
        currentCoroutineContext().ensureActive()
        check(settings.endpoint.first() == endpoint && sessions.session() == session && System.currentTimeMillis() / 1000 < approval.expires) { "Goedkeuring verlopen of sessie gewijzigd" }
        val signed = approval.signed(identity.signHex(Hex.encode(message)))
        val response = (api.modelToggle(endpoint, token, signed) as? ApiResult.Success)?.value ?: error("Wijziging niet bevestigd; ververs de status")
        check(response["status"]?.jsonPrimitive?.content == "active") { "Activatie niet bevestigd" }
    }
}
