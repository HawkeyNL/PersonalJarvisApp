package com.hawkeynl.jarvis.network

import java.io.File

interface JarvisApi {
    suspend fun modelPolicy(endpoint: HomeNodeEndpoint, token: String): ApiResult<com.hawkeynl.jarvis.security.ModelPolicySnapshot> =
        ApiResult.InvalidResponse("Model control unavailable")
    suspend fun modelToggle(endpoint: HomeNodeEndpoint, token: String, body: kotlinx.serialization.json.JsonObject): ApiResult<kotlinx.serialization.json.JsonObject> =
        ApiResult.InvalidResponse("Model control unavailable")
    /** Authenticated GET of an owner route under /v1/; the caller decodes the JSON. */
    suspend fun getAuthorized(endpoint: HomeNodeEndpoint, token: String, path: String): ApiResult<kotlinx.serialization.json.JsonElement> =
        ApiResult.InvalidResponse("Not supported")
    /** Authenticated POST of a JSON body to an owner route under /v1/. */
    suspend fun postAuthorized(endpoint: HomeNodeEndpoint, token: String, path: String, body: kotlinx.serialization.json.JsonObject): ApiResult<Unit> =
        ApiResult.InvalidResponse("Not supported")
    /** Authenticated POST of a JSON body to an owner route under /v1/, returning Core's JSON answer. */
    suspend fun postAuthorizedJson(endpoint: HomeNodeEndpoint, token: String, path: String, body: kotlinx.serialization.json.JsonObject): ApiResult<kotlinx.serialization.json.JsonElement> =
        ApiResult.InvalidResponse("Not supported")
    /** Unlock requests for this device to decide, held up to [waitSeconds] until one appears. */
    suspend fun unlockPending(endpoint: HomeNodeEndpoint, token: String, waitSeconds: Int): ApiResult<UnlockRequestList> =
        ApiResult.InvalidResponse("Not supported")
    suspend fun accountStatus(endpoint: HomeNodeEndpoint): ApiResult<AccountStatus>
    suspend fun activateFirstDevice(endpoint: HomeNodeEndpoint, request: BootstrapRequest, code: String): ApiResult<FirstDeviceResponse>
    suspend fun ready(endpoint: HomeNodeEndpoint): ApiResult<HealthResponse>
    suspend fun createPairing(
        endpoint: HomeNodeEndpoint,
        request: PairingCreateRequest,
    ): ApiResult<PairingTicket>

    suspend fun pairingStatus(
        endpoint: HomeNodeEndpoint,
        ticket: PairingTicket,
    ): ApiResult<PairingStatusResponse>

    suspend fun challenge(
        endpoint: HomeNodeEndpoint,
        request: ChallengeRequest,
    ): ApiResult<ChallengeResponse>

    suspend fun login(
        endpoint: HomeNodeEndpoint,
        request: LoginRequest,
    ): ApiResult<LoginResponse>

    suspend fun logout(endpoint: HomeNodeEndpoint, token: String): ApiResult<Unit>
    suspend fun conversations(
        endpoint: HomeNodeEndpoint,
        token: String,
    ): ApiResult<ConversationListResponse>

    suspend fun conversation(
        endpoint: HomeNodeEndpoint,
        token: String,
        id: String,
    ): ApiResult<ConversationDetailResponse>

    suspend fun chat(
        endpoint: HomeNodeEndpoint,
        token: String,
        request: ChatRequest,
    ): ApiResult<ChatResponse>

    /** One chat photo as JPEG bytes (<= 2 MiB); HttpError(404) once deleted or expired. */
    suspend fun photo(endpoint: HomeNodeEndpoint, token: String, id: String): ApiResult<ByteArray> =
        ApiResult.InvalidResponse("Photos are not supported")

    /** Upload one prepared JPEG; returns the new photo's id. HttpError carries Core's status. */
    suspend fun uploadPhoto(endpoint: HomeNodeEndpoint, token: String, jpeg: ByteArray): ApiResult<String> =
        ApiResult.InvalidResponse("Photos are not supported")

    /** Keep a photo past its 90 days, or stop keeping it. */
    suspend fun keepPhoto(endpoint: HomeNodeEndpoint, token: String, id: String, saved: Boolean): ApiResult<PhotoMeta> =
        ApiResult.InvalidResponse("Photos are not supported")

    /** Delete a photo for good; HttpError(404) when it is already gone. */
    suspend fun deletePhoto(endpoint: HomeNodeEndpoint, token: String, id: String): ApiResult<Unit> =
        ApiResult.InvalidResponse("Photos are not supported")

    /** Delete a conversation with its messages and photos. */
    suspend fun deleteConversation(endpoint: HomeNodeEndpoint, token: String, id: String): ApiResult<Unit> =
        ApiResult.InvalidResponse("Not supported")

    /** Trading reads (desktop tradingApi.ts) and the manual holdings (desktop portfolio.ts);
     *  answers pass the guards in Trading.kt. */
    suspend fun portfolio(endpoint: HomeNodeEndpoint, token: String): ApiResult<Portfolio> = ApiResult.InvalidResponse("Not supported")
    suspend fun valueHistory(endpoint: HomeNodeEndpoint, token: String, range: String): ApiResult<PortfolioHistory> =
        ApiResult.InvalidResponse("Not supported")
    suspend fun instrumentBars(endpoint: HomeNodeEndpoint, token: String, conid: Long, range: String): ApiResult<InstrumentBars> =
        ApiResult.InvalidResponse("Not supported")
    suspend fun holdings(endpoint: HomeNodeEndpoint, token: String): ApiResult<Holdings> = ApiResult.InvalidResponse("Not supported")
    suspend fun addHolding(endpoint: HomeNodeEndpoint, token: String, symbol: String, quantity: String, avgCost: String): ApiResult<Unit> =
        ApiResult.InvalidResponse("Not supported")
    suspend fun deleteHolding(endpoint: HomeNodeEndpoint, token: String, id: String): ApiResult<Unit> = ApiResult.InvalidResponse("Not supported")

    /** Scheduled jobs (desktop jobsApi.ts); answers pass the guards in Jobs.kt. */
    suspend fun jobs(endpoint: HomeNodeEndpoint, token: String): ApiResult<JobList> = ApiResult.InvalidResponse("Not supported")
    /** The created job, or null when Core's answer cannot be read. */
    suspend fun createJob(endpoint: HomeNodeEndpoint, token: String, body: kotlinx.serialization.json.JsonObject): ApiResult<Job?> =
        ApiResult.InvalidResponse("Not supported")
    /** A change built on [revision]; HttpError(409) when the job moved on. */
    suspend fun updateJob(endpoint: HomeNodeEndpoint, token: String, id: String, revision: Long, patch: kotlinx.serialization.json.JsonObject): ApiResult<Job?> =
        ApiResult.InvalidResponse("Not supported")
    /** Queues a run (202); HttpError(429) within an hour of the last owner run. */
    suspend fun runJob(endpoint: HomeNodeEndpoint, token: String, id: String): ApiResult<Unit> = ApiResult.InvalidResponse("Not supported")
    /** The 20 newest runs of a job. */
    suspend fun jobRuns(endpoint: HomeNodeEndpoint, token: String, id: String): ApiResult<List<JobRun>> = ApiResult.InvalidResponse("Not supported")
    suspend fun jobRun(endpoint: HomeNodeEndpoint, token: String, runId: String): ApiResult<RunDetail> = ApiResult.InvalidResponse("Not supported")

    suspend fun androidUpdate(
        endpoint: HomeNodeEndpoint,
        token: String,
        currentVersionCode: Int,
        clientProtocol: Int,
    ): ApiResult<AndroidUpdateMetadata?>

    suspend fun downloadAndroidUpdate(
        endpoint: HomeNodeEndpoint,
        token: String,
        destination: File,
        expectedSize: Long,
    ): ApiResult<Unit>
}
