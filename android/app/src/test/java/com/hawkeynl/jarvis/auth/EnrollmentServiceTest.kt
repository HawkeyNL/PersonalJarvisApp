package com.hawkeynl.jarvis.auth

import com.hawkeynl.jarvis.network.AndroidUpdateMetadata
import com.hawkeynl.jarvis.network.ApiResult
import com.hawkeynl.jarvis.network.AccountStatus
import com.hawkeynl.jarvis.network.FirstDeviceResponse
import com.hawkeynl.jarvis.network.ChallengeRequest
import com.hawkeynl.jarvis.network.ChallengeResponse
import com.hawkeynl.jarvis.security.Hex
import com.hawkeynl.jarvis.security.LoginMessage
import com.hawkeynl.jarvis.network.ChatRequest
import com.hawkeynl.jarvis.network.ChatResponse
import com.hawkeynl.jarvis.network.ConversationDetailResponse
import com.hawkeynl.jarvis.network.ConversationListResponse
import com.hawkeynl.jarvis.network.EndpointValidation
import com.hawkeynl.jarvis.network.HealthResponse
import com.hawkeynl.jarvis.network.HomeNodeEndpoint
import com.hawkeynl.jarvis.network.JarvisApi
import com.hawkeynl.jarvis.network.LoginRequest
import com.hawkeynl.jarvis.network.LoginResponse
import com.hawkeynl.jarvis.network.PairingCreateRequest
import com.hawkeynl.jarvis.network.PairingStatusResponse
import com.hawkeynl.jarvis.network.PairingTicket
import com.hawkeynl.jarvis.security.DeviceIdentity
import com.hawkeynl.jarvis.storage.SessionRepository
import com.hawkeynl.jarvis.testing.InMemorySecureValueStore
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnrollmentServiceTest {
    private val endpoint = (HomeNodeEndpoint.parse("https://jarvis.test") as EndpointValidation.Valid).endpoint

    @Test
    fun `refused challenge preserves binding and does not attempt login`() = runTest {
        val api = FakeApi().apply { rejectChallenge = true }
        val sessions = SessionRepository(InMemorySecureValueStore())
        sessions.saveDeviceId(DEVICE_ID)
        val service = EnrollmentService(api, FakeIdentity(), sessions)
        val result = service.startOrResume(endpoint, "fixture", 100, "fixture password")
        assertTrue(result is EnrollmentOutcome.Rejected)
        assertEquals(DEVICE_ID, sessions.session().deviceId)
        assertEquals(null, api.loginRequest)
        assertEquals(null, sessions.session().token)
    }

    @Test
    fun `denied and expired tickets never grant a session and can be retried`() = runTest {
        for (decision in listOf("denied", "expired")) {
            val api = FakeApi().apply { pairingDecision = decision }
            val sessions = SessionRepository(InMemorySecureValueStore())
            val service = EnrollmentService(api, FakeIdentity(), sessions)
            service.startOrResume(endpoint, "fixture", 100)
            val result = service.poll(endpoint)
            assertEquals(if (decision == "denied") EnrollmentOutcome.Denied else EnrollmentOutcome.Expired, result)
            assertEquals(null, sessions.pairingTicket())
            assertEquals(null, sessions.session().token)
            assertEquals(null, api.loginRequest)
        }
    }

    @Test
    fun `password does not replace pairing and is requested again after approval`() = runTest {
        val api = FakeApi().apply { account = AccountStatus(1, true, false) }
        val sessions = SessionRepository(InMemorySecureValueStore())
        val service = EnrollmentService(api, FakeIdentity(), sessions)
        assertEquals(EnrollmentOutcome.PasswordRequired, service.startOrResume(endpoint, "fixture", 100))
        assertEquals(null, api.created)
        val password = "fixture account password"
        assertTrue(service.startOrResume(endpoint, "fixture", 100, password) is EnrollmentOutcome.Pending)
        assertEquals(null, sessions.session().token)
        assertTrue(!api.created.toString().contains(password))
        api.approved = true
        assertEquals(EnrollmentOutcome.PasswordRequired, service.poll(endpoint))
        assertEquals(DEVICE_ID, sessions.session().deviceId)
        assertEquals(null, api.loginRequest)
        assertTrue(service.startOrResume(endpoint, "fixture", 100, password) is EnrollmentOutcome.Authenticated)
        assertEquals(password, api.loginRequest?.password)
        assertTrue(!api.loginRequest.toString().contains(password))
    }

    @Test
    fun `first account reports activation rather than creating pairing`() = runTest {
        val api = FakeApi().apply { account = AccountStatus(1, false, true) }
        val service = EnrollmentService(api, FakeIdentity(), SessionRepository(InMemorySecureValueStore()))
        assertEquals(EnrollmentOutcome.ActivationRequired, service.startOrResume(endpoint, "fixture", 100))
        assertEquals(null, api.created)
    }

    @Test
    fun `persists pending request then signs challenge after approval`() = runTest {
        val api = FakeApi()
        val sessions = SessionRepository(InMemorySecureValueStore())
        val identity = FakeIdentity()
        val service = EnrollmentService(api, identity, sessions)

        val pending = service.startOrResume(endpoint, "Android", 100)
        assertTrue(pending is EnrollmentOutcome.Pending)
        assertEquals("android", api.created?.platform)
        assertEquals("ticket", sessions.pairingTicket()?.request_id)

        api.approved = true
        val authenticated = service.poll(endpoint)
        assertTrue(authenticated is EnrollmentOutcome.Authenticated)
        assertEquals(DEVICE_ID, sessions.session().deviceId)
        assertEquals("token", sessions.session().token)
        assertEquals("cd".repeat(64), api.loginRequest?.signature)
        assertEquals(Hex.encode(LoginMessage.build(CHALLENGE_ID, DEVICE_ID, "02".repeat(32))), identity.signedHex)
    }
}

private const val DEVICE_ID = "ffeeddcc-bbaa-9988-7766-554433221100"
private const val CHALLENGE_ID = "00112233-4455-6677-8899-aabbccddeeff"

private class FakeIdentity : DeviceIdentity {
    var signedHex: String? = null
    override fun publicKeyHex() = "ab".repeat(32)
    override fun signHex(messageHex: String): String {
        signedHex = messageHex
        return "cd".repeat(64)
    }
    override fun reset() = Unit
}

private class FakeApi : JarvisApi {
    var account = AccountStatus(1, false, false)
    override suspend fun accountStatus(endpoint: HomeNodeEndpoint) = ApiResult.Success(account)
    override suspend fun activateFirstDevice(endpoint: HomeNodeEndpoint, request: PairingCreateRequest, code: String): ApiResult<FirstDeviceResponse> = error("unused")
    var created: PairingCreateRequest? = null
    var approved = false
    var pairingDecision: String? = null
    var rejectChallenge = false
    var loginRequest: LoginRequest? = null

    override suspend fun ready(endpoint: HomeNodeEndpoint) = ApiResult.Success(HealthResponse("ready"))
    override suspend fun createPairing(endpoint: HomeNodeEndpoint, request: PairingCreateRequest): ApiResult<PairingTicket> {
        created = request
        return ApiResult.Success(PairingTicket("ticket", "01".repeat(32), 500))
    }
    override suspend fun pairingStatus(endpoint: HomeNodeEndpoint, ticket: PairingTicket) =
        ApiResult.Success(PairingStatusResponse(pairingDecision ?: if (approved) "approved" else "pending", if (approved) DEVICE_ID else null))
    override suspend fun challenge(endpoint: HomeNodeEndpoint, request: ChallengeRequest): ApiResult<ChallengeResponse> =
        if (rejectChallenge) ApiResult.Unauthorized else ApiResult.Success(ChallengeResponse(CHALLENGE_ID, "02".repeat(32)))
    override suspend fun login(endpoint: HomeNodeEndpoint, request: LoginRequest): ApiResult<LoginResponse> {
        loginRequest = request
        return ApiResult.Success(LoginResponse("token", 900))
    }
    override suspend fun logout(endpoint: HomeNodeEndpoint, token: String) = ApiResult.Success(Unit)
    override suspend fun deleteDevice(endpoint: HomeNodeEndpoint, token: String, deviceId: String) = ApiResult.Success(Unit)
    override suspend fun conversations(endpoint: HomeNodeEndpoint, token: String): ApiResult<ConversationListResponse> = error("unused")
    override suspend fun conversation(endpoint: HomeNodeEndpoint, token: String, id: String): ApiResult<ConversationDetailResponse> = error("unused")
    override suspend fun chat(endpoint: HomeNodeEndpoint, token: String, request: ChatRequest): ApiResult<ChatResponse> = error("unused")
    override suspend fun androidUpdate(
        endpoint: HomeNodeEndpoint,
        token: String,
        currentVersionCode: Int,
        clientProtocol: Int,
    ): ApiResult<AndroidUpdateMetadata?> = error("unused")
    override suspend fun downloadAndroidUpdate(
        endpoint: HomeNodeEndpoint,
        token: String,
        destination: File,
        expectedSize: Long,
    ): ApiResult<Unit> = error("unused")
}
