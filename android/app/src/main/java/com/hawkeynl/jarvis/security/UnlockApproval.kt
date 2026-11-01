package com.hawkeynl.jarvis.security

import com.hawkeynl.jarvis.network.ApiResult
import com.hawkeynl.jarvis.network.HomeNodeEndpoint
import com.hawkeynl.jarvis.network.JarvisApi
import com.hawkeynl.jarvis.network.UnlockRequestItem
import com.hawkeynl.jarvis.storage.SessionRepository
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The owner picked the wrong number; the unlock request has been denied. */
class WrongUnlockNumber : IllegalStateException("Wrong number. The unlock request was denied.")

/** Choice sets by request id, kept until decided or expired. Fresh decoys on every look
 *  would leak the code (it is the one number in every set), so one instance lives in
 *  `AppContainer` and outlives an Activity: a rotation must not re-roll a set either. */
class UnlockChoiceSets(private val now: () -> Long = { System.currentTimeMillis() / 1000 }) {
    private val sets = LinkedHashMap<String, Pair<Long, List<Int>>>()

    /** The set for this request, built once; refused when [MAX] live sets exist, because
     *  evicting a live one would hand out new decoys for it. */
    @Synchronized fun choices(id: String, createdAt: Long, code: Int): List<Int> {
        sets.values.removeAll { now() - it.first > UNLOCK_MAX_AGE_SECONDS }
        val key = id.lowercase()
        sets[key]?.second?.takeIf { code in it }?.let { return it }
        check(key in sets || sets.size < MAX) { "Too many unlock requests; deny some first." }
        return UnlockCode.choices(code).also { sets[key] = createdAt to it }
    }

    @Synchronized fun forget(id: String) {
        sets.remove(id.lowercase())
    }

    companion object {
        const val MAX = 16
    }
}

/** Core expires unlock requests two minutes after `created_at`; the sheet counts down to it. */
const val UNLOCK_EXPIRES_AFTER_SECONDS = 120L

/** Core expires unlock requests after two minutes; anything older is refused here too. */
private const val UNLOCK_MAX_AGE_SECONDS = 130L

/** Approve or deny another device's unlock request. Every decision re-reads the request
 *  by id and refuses a stale one. Approve needs the owner's pick of the number the other
 *  device shows: a wrong pick denies without any prompt or signature. Otherwise each
 *  decision needs a strong-biometric prompt (approve signs with the device key; deny
 *  uses [authenticate], the same kind of prompt without a key). */
class UnlockApprovalService(
    private val api: JarvisApi,
    private val sessions: SessionRepository,
    private val endpoint: suspend () -> HomeNodeEndpoint?,
    private val identity: DeviceIdentity,
    private val approvals: ApprovalLock,
    private val authenticate: suspend (String) -> Boolean,
    private val unlocked: () -> Boolean,
    private val choiceSets: UnlockChoiceSets,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    /** Requests waiting for this device, holding the call up to [waitSeconds] until one
     *  appears. Callers poll only while the app is in the foreground; a locked app reads
     *  nothing. Staleness is checked when deciding, not here. */
    suspend fun pending(waitSeconds: Int): List<UnlockRequestItem> {
        if (!unlocked()) return emptyList()
        val origin = endpoint() ?: return emptyList()
        val token = sessions.session().token ?: return emptyList()
        return when (val result = api.unlockPending(origin, token, waitSeconds)) {
            is ApiResult.Success -> result.value.requests
            else -> error(refusal(result))
        }
    }

    /** Three numbers for the owner to pick from, built here from the request Core lists
     *  for this id; one of them is the code the other device shows. Asking again for the
     *  same request returns the same three. */
    suspend fun choices(id: String): List<Int> {
        val (_, _, item) = current(id)
        return choiceSets.choices(item.id, item.created_at, UnlockCode.code(item.id, item.nonce))
    }

    suspend fun approve(id: String, choice: Int) = approvals.exclusive {
        val (origin, token, item) = current(id)
        if (choice != UnlockCode.code(item.id, item.nonce)) {
            decide(origin, token, "/v1/auth/unlock/${item.id}/deny", JsonObject(emptyMap()), "denied")
            choiceSets.forget(item.id)
            throw WrongUnlockNumber()
        }
        val session = sessions.session()
        val device = session.deviceId ?: error("Device not paired")
        val message = UnlockApprovalMessage(item.id, device, item.nonce).also { it.message() }
        val signature = identity.sign(message, "Unlock \"${PairingApproval.displayName(item.device_name, 40)}\" (${PairingApproval.displayName(item.platform, 16)})")
        currentCoroutineContext().ensureActive()
        check(unlocked() && endpoint() == origin && sessions.session() == session && fresh(item)) { "Request expired or session changed" }
        decide(origin, token, "/v1/auth/unlock/${item.id}/approve", JsonObject(mapOf("signature" to JsonPrimitive(signature))), "approved")
        choiceSets.forget(item.id)
    }

    suspend fun deny(id: String) = approvals.exclusive {
        val (origin, token, item) = current(id)
        val session = sessions.session()
        if (!authenticate("Deny unlock of \"${PairingApproval.displayName(item.device_name, 40)}\"")) {
            error("Cancelled; fingerprint or face unlock is required.")
        }
        currentCoroutineContext().ensureActive()
        check(unlocked() && endpoint() == origin && sessions.session() == session) { "Session changed; try again" }
        decide(origin, token, "/v1/auth/unlock/${item.id}/deny", JsonObject(emptyMap()), "denied")
        choiceSets.forget(item.id)
    }

    /** The request as Core lists it right now, never a copy the UI held on to. */
    private suspend fun current(id: String): Triple<HomeNodeEndpoint, String, UnlockRequestItem> {
        check(unlocked()) { "App locked" }
        require(PairingApproval.canonicalUuid.matches(id)) { "Invalid unlock request" }
        val origin = endpoint() ?: error("Home Node missing")
        val token = sessions.session().token ?: error("Sign in again")
        val item = when (val result = api.unlockPending(origin, token, 0)) {
            is ApiResult.Success -> result.value.requests.singleOrNull { it.id.equals(id, ignoreCase = true) }
            else -> error(refusal(result))
        } ?: error("This unlock request is no longer pending.")
        check(fresh(item)) { "This unlock request is too old; ask again on the other device." }
        return Triple(origin, token, item)
    }

    private fun fresh(item: UnlockRequestItem): Boolean = now() - item.created_at in -30L..UNLOCK_MAX_AGE_SECONDS

    private suspend fun decide(origin: HomeNodeEndpoint, token: String, path: String, body: JsonObject, expected: String) {
        when (val result = api.postAuthorizedJson(origin, token, path, body)) {
            is ApiResult.Success -> check(
                runCatching { result.value.jsonObject["status"]?.jsonPrimitive?.content }.getOrNull() == expected,
            ) { "Core did not confirm the decision." }
            else -> error(refusal(result))
        }
    }

    // Core answers 401 for an unknown, handled or expired request too: report it, do not sign out here.
    private fun refusal(result: ApiResult<*>): String = when (result) {
        is ApiResult.Success -> "Core did not confirm the decision."
        ApiResult.Unauthorized -> "Core refused this decision. The request may have expired or been handled already."
        is ApiResult.HttpError -> result.message ?: "Core refused this decision (HTTP ${result.status})."
        is ApiResult.Unreachable -> "Home Node unreachable; refresh to see whether the decision applied."
        is ApiResult.InvalidResponse -> result.message
    }
}
