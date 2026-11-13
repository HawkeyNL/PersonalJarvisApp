package com.hawkeynl.jarvis.network

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.timeout
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.preparePatch
import io.ktor.client.request.preparePost
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpStatement
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.content.TextContent
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.jvm.javaio.toInputStream
import java.io.File
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class KtorJarvisApi(
    private val client: HttpClient = defaultHttpClient(),
) : JarvisApi {
    override suspend fun modelPolicy(endpoint: HomeNodeEndpoint, token: String) = boundedModelRequest<com.hawkeynl.jarvis.security.ModelPolicySnapshot> {
        client.get(endpoint.url("/v1/system/models")) { bearerAuth(token) }
    }
    override suspend fun modelToggle(endpoint: HomeNodeEndpoint, token: String, body: kotlinx.serialization.json.JsonObject) = boundedModelRequest<kotlinx.serialization.json.JsonObject> {
        client.post(endpoint.url("/v1/system/config/privileged")) {
            bearerAuth(token); contentType(ContentType.Application.Json); setBody(body)
        }
    }

    private suspend inline fun <reified T> boundedModelRequest(crossinline block: suspend () -> io.ktor.client.statement.HttpResponse): ApiResult<T> {
        return try {
            val response = block()
            if (response.status.value !in 200..299) return ApiResult.HttpError(response.status.value, "Model change not confirmed; refresh the status.")
            val raw = withContext(Dispatchers.IO) {
                response.bodyAsChannel().toInputStream().use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        check(output.size() + read <= 8 * 1024 * 1024)
                        output.write(buffer, 0, read)
                    }
                    output.toByteArray()
                }
            }
            ApiResult.Success(Json { ignoreUnknownKeys = true }.decodeFromString<T>(raw.decodeToString()))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { ApiResult.InvalidResponse("Model control unreachable or invalid response") }
    }
    override suspend fun getAuthorized(endpoint: HomeNodeEndpoint, token: String, path: String): ApiResult<kotlinx.serialization.json.JsonElement> {
        if (!OWNER_PATH.matches(path)) return ApiResult.InvalidResponse("Invalid Core path")
        return request<kotlinx.serialization.json.JsonElement> { client.get(endpoint.url(path)) { bearerAuth(token) } }
    }

    override suspend fun postAuthorized(endpoint: HomeNodeEndpoint, token: String, path: String, body: kotlinx.serialization.json.JsonObject): ApiResult<Unit> {
        if (!OWNER_PATH.matches(path)) return ApiResult.InvalidResponse("Invalid Core path")
        return request<Unit> {
            client.post(endpoint.url(path)) {
                bearerAuth(token); contentType(ContentType.Application.Json); setBody(body)
            }
        }
    }

    override suspend fun postAuthorizedJson(endpoint: HomeNodeEndpoint, token: String, path: String, body: kotlinx.serialization.json.JsonObject): ApiResult<kotlinx.serialization.json.JsonElement> {
        if (!OWNER_PATH.matches(path)) return ApiResult.InvalidResponse("Invalid Core path")
        return request<kotlinx.serialization.json.JsonElement> {
            client.post(endpoint.url(path)) {
                bearerAuth(token); contentType(ContentType.Application.Json); setBody(body)
            }
        }
    }

    // Core holds the call at most 25 s; stay well inside the 30 s request timeout.
    override suspend fun unlockPending(endpoint: HomeNodeEndpoint, token: String, waitSeconds: Int) = request<UnlockRequestList> {
        client.get(endpoint.url("/v1/auth/unlock/pending")) { bearerAuth(token); parameter("wait", waitSeconds.coerceIn(0, 20)) }
    }

    override suspend fun accountStatus(endpoint: HomeNodeEndpoint) = request<AccountStatus> {
        client.get(endpoint.url("/v1/auth/account/status"))
    }
    override suspend fun activateFirstDevice(endpoint: HomeNodeEndpoint, request: BootstrapRequest, code: String): ApiResult<FirstDeviceResponse> {
        if (!code.matches(Regex("[a-fA-F0-9]{64}"))) return ApiResult.InvalidResponse("Ongeldige activatiecode.")
        return request<FirstDeviceResponse> {
            client.post(endpoint.url("/v1/auth/bootstrap")) {
                contentType(ContentType.Application.Json)
                header("X-Jarvis-Bootstrap-Secret", code)
                setBody(request)
            }
        }
    }
    override suspend fun ready(endpoint: HomeNodeEndpoint) = request<HealthResponse> {
        client.get(endpoint.url("/readyz"))
    }

    override suspend fun createPairing(endpoint: HomeNodeEndpoint, request: PairingCreateRequest) =
        request<PairingTicket> {
            client.post(endpoint.url("/v1/auth/pairing/requests")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun pairingStatus(endpoint: HomeNodeEndpoint, ticket: PairingTicket) =
        request<PairingStatusResponse> {
            client.get(endpoint.url("/v1/auth/pairing/requests/${ticket.request_id}/status")) {
                header("X-Jarvis-Pairing-Nonce", ticket.nonce)
            }
        }

    override suspend fun challenge(endpoint: HomeNodeEndpoint, request: ChallengeRequest) =
        request<ChallengeResponse> {
            client.post(endpoint.url("/v1/auth/challenge")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun login(endpoint: HomeNodeEndpoint, request: LoginRequest) =
        request<LoginResponse> {
            client.post(endpoint.url("/v1/auth/login")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun logout(endpoint: HomeNodeEndpoint, token: String) = request<Unit> {
        client.post(endpoint.url("/v1/auth/logout")) { bearerAuth(token) }
    }

    override suspend fun conversations(endpoint: HomeNodeEndpoint, token: String) =
        request<ConversationListResponse> {
            client.get(endpoint.url("/v1/conversations")) { bearerAuth(token) }
        }

    override suspend fun conversation(endpoint: HomeNodeEndpoint, token: String, id: String) =
        request<ConversationDetailResponse> {
            client.get(endpoint.url("/v1/conversations/$id")) { bearerAuth(token) }
        }

    override suspend fun chat(endpoint: HomeNodeEndpoint, token: String, request: ChatRequest) =
        request<ChatResponse> {
            client.post(endpoint.url("/v1/assistant/chat")) {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun androidUpdate(
        endpoint: HomeNodeEndpoint,
        token: String,
        currentVersionCode: Int,
        clientProtocol: Int,
    ): ApiResult<AndroidUpdateMetadata?> {
        return try {
            val response = client.get(
                endpoint.url("/v1/app-updates/android/$currentVersionCode?client_protocol=$clientProtocol"),
            ) { bearerAuth(token) }
            when {
                response.status == HttpStatusCode.NoContent -> ApiResult.Success(null)
                response.status == HttpStatusCode.Unauthorized -> ApiResult.Unauthorized
                response.status.value !in 200..299 -> {
                    val error = runCatching { response.body<ErrorResponse>() }.getOrNull()
                    ApiResult.HttpError(response.status.value, error?.hint ?: error?.error)
                }
                else -> ApiResult.Success(response.body<AndroidUpdateMetadata>())
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: SerializationException) {
            ApiResult.InvalidResponse(error.message ?: "Ongeldige Android-updatemetadata")
        } catch (error: Exception) {
            networkFailure(error)
        }
    }

    override suspend fun downloadAndroidUpdate(
        endpoint: HomeNodeEndpoint,
        token: String,
        destination: File,
        expectedSize: Long,
    ): ApiResult<Unit> {
        return try {
            val response = client.get(endpoint.url("/v1/app-updates/android/download")) {
                bearerAuth(token)
                timeout {
                    requestTimeoutMillis = 5 * 60 * 1_000
                    socketTimeoutMillis = 30_000
                }
            }
            when {
                response.status == HttpStatusCode.Unauthorized -> ApiResult.Unauthorized
                response.status.value !in 200..299 -> {
                    val error = runCatching { response.body<ErrorResponse>() }.getOrNull()
                    ApiResult.HttpError(response.status.value, error?.hint ?: error?.error)
                }
                response.contentType()?.withoutParameters() != ANDROID_APK_CONTENT_TYPE ->
                    ApiResult.InvalidResponse("Home Node did not send an Android APK")
                response.headers[HttpHeaders.ContentLength]
                    ?.toLongOrNull()
                    ?.let { it != expectedSize } == true ->
                    ApiResult.InvalidResponse("Home Node sent an APK of unexpected size")
                else -> {
                    val exactSize = withContext(Dispatchers.IO) {
                        var written = 0L
                        destination.outputStream().use { output ->
                            response.bodyAsChannel().toInputStream().use { input ->
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    written += read
                                    if (written > expectedSize) return@withContext false
                                    output.write(buffer, 0, read)
                                }
                            }
                        }
                        written == expectedSize
                    }
                    if (!exactSize) {
                        destination.delete()
                        ApiResult.InvalidResponse("APK download is not the expected size")
                    } else {
                        ApiResult.Success(Unit)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            destination.delete()
            throw cancelled
        } catch (error: Exception) {
            destination.delete()
            networkFailure(error)
        }
    }

    override suspend fun photo(endpoint: HomeNodeEndpoint, token: String, id: String): ApiResult<ByteArray> {
        val photo = photoId(id) ?: return ApiResult.InvalidResponse("Invalid photo id")
        return try {
            // Streamed (execute), so the size bound holds while reading, not after buffering.
            client.prepareGet(endpoint.url("/v1/photos/$photo")) { bearerAuth(token) }.execute { response -> when {
                response.status == HttpStatusCode.Unauthorized -> ApiResult.Unauthorized
                response.status != HttpStatusCode.OK -> ApiResult.HttpError(response.status.value, null)
                response.contentType()?.withoutParameters() != ContentType.Image.JPEG ->
                    ApiResult.InvalidResponse("Home Node did not send a JPEG")
                (response.contentLength() ?: 0) > MAX_PHOTO_BYTES -> ApiResult.InvalidResponse("Home Node sent an invalid photo")
                else -> {
                    val bytes = withContext(Dispatchers.IO) {
                        response.bodyAsChannel().toInputStream().use { input -> readAtMost(input, MAX_PHOTO_BYTES + 1) }
                    }
                    if (bytes.size > MAX_PHOTO_BYTES || !isJpeg(bytes)) ApiResult.InvalidResponse("Home Node sent an invalid photo")
                    else ApiResult.Success(bytes)
                }
            } }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            networkFailure(error)
        }
    }

    override suspend fun uploadPhoto(endpoint: HomeNodeEndpoint, token: String, jpeg: ByteArray): ApiResult<String> {
        if (jpeg.isEmpty() || jpeg.size > MAX_UPLOAD_BYTES) return ApiResult.InvalidResponse("The photo is too large.")
        return try {
            client.preparePost(endpoint.url("/v1/photos")) {
                bearerAuth(token)
                accept(ContentType.Application.Json)
                setBody(ByteArrayContent(jpeg, ContentType.Application.OctetStream))
                // A photo over a slow mobile link may need longer than the default.
                timeout { requestTimeoutMillis = 120_000 }
            }.execute { response -> when {
                response.status == HttpStatusCode.Unauthorized -> ApiResult.Unauthorized
                // The status alone tells an older Core (404) from a refusal.
                response.status.value !in 200..299 -> ApiResult.HttpError(response.status.value, null)
                else -> {
                    val id = (smallJson(response) as? JsonObject)?.get("id")?.let { it as? JsonPrimitive }
                        ?.takeIf { it.isString }?.let { photoId(it.content) }
                    if (id == null) ApiResult.InvalidResponse("Home Node sent an invalid photo id") else ApiResult.Success(id)
                }
            } }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            networkFailure(error)
        }
    }

    override suspend fun keepPhoto(endpoint: HomeNodeEndpoint, token: String, id: String, saved: Boolean): ApiResult<PhotoMeta> {
        val photo = photoId(id) ?: return ApiResult.InvalidResponse("Invalid photo id")
        return try {
            client.preparePatch(endpoint.url("/v1/photos/$photo")) {
                bearerAuth(token)
                accept(ContentType.Application.Json)
                setBody(TextContent(buildJsonObject { put("saved", saved) }.toString(), ContentType.Application.Json))
            }.execute { response -> when {
                response.status == HttpStatusCode.Unauthorized -> ApiResult.Unauthorized
                response.status.value !in 200..299 -> ApiResult.HttpError(response.status.value, null)
                else -> smallJson(response)
                    ?.let { runCatching { REPLY_JSON.decodeFromJsonElement(PhotoMeta.serializer(), it) }.getOrNull() }
                    ?.takeIf { it.id == photo }
                    ?.let { ApiResult.Success(it) }
                    ?: ApiResult.InvalidResponse("Home Node sent invalid photo details")
            } }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            networkFailure(error)
        }
    }

    override suspend fun deletePhoto(endpoint: HomeNodeEndpoint, token: String, id: String): ApiResult<Unit> {
        val photo = photoId(id) ?: return ApiResult.InvalidResponse("Invalid photo id")
        return request<Unit> { client.delete(endpoint.url("/v1/photos/$photo")) { bearerAuth(token) } }
    }

    override suspend fun deleteConversation(endpoint: HomeNodeEndpoint, token: String, id: String): ApiResult<Unit> {
        if (!CONVERSATION_ID.matches(id)) return ApiResult.InvalidResponse("Invalid conversation id")
        return request<Unit> { client.delete(endpoint.url("/v1/conversations/$id")) { bearerAuth(token) } }
    }

    override suspend fun jobs(endpoint: HomeNodeEndpoint, token: String) =
        jobCall({ requireNotNull(readJobList(it)) }) { client.prepareGet(endpoint.url("/v1/jobs")) { bearerAuth(token) } }

    override suspend fun createJob(endpoint: HomeNodeEndpoint, token: String, body: JsonObject) = jobCall(::readJob) {
        client.preparePost(endpoint.url("/v1/jobs")) { bearerAuth(token); setBody(TextContent(body.toString(), ContentType.Application.Json)) }
    }

    override suspend fun updateJob(endpoint: HomeNodeEndpoint, token: String, id: String, revision: Long, patch: JsonObject): ApiResult<Job?> {
        if (!isJobId(id) || revision < 0) return ApiResult.InvalidResponse("Invalid job id")
        return jobCall(::readJob) {
            client.preparePatch(endpoint.url("/v1/jobs/$id")) {
                bearerAuth(token); parameter("expected_revision", revision)
                setBody(TextContent(patch.toString(), ContentType.Application.Json))
            }
        }
    }

    override suspend fun runJob(endpoint: HomeNodeEndpoint, token: String, id: String): ApiResult<Unit> {
        if (!isJobId(id)) return ApiResult.InvalidResponse("Invalid job id")
        // Only the status matters; the body is read within the bound and dropped.
        return jobCall({ }) { client.preparePost(endpoint.url("/v1/jobs/$id/run")) { bearerAuth(token); setBody(TextContent("{}", ContentType.Application.Json)) } }
    }

    override suspend fun jobRuns(endpoint: HomeNodeEndpoint, token: String, id: String): ApiResult<List<JobRun>> {
        if (!isJobId(id)) return ApiResult.InvalidResponse("Invalid job id")
        return jobCall({ requireNotNull(readRuns(it)) }) {
            client.prepareGet(endpoint.url("/v1/jobs/$id/runs")) { bearerAuth(token); parameter("limit", 20) }
        }
    }

    override suspend fun jobRun(endpoint: HomeNodeEndpoint, token: String, runId: String): ApiResult<RunDetail> {
        if (!isJobId(runId)) return ApiResult.InvalidResponse("Invalid run id")
        return jobCall({ requireNotNull(readRunDetail(it)) }) { client.prepareGet(endpoint.url("/v1/jobs/runs/$runId")) { bearerAuth(token) } }
    }

    override suspend fun roadmaps(endpoint: HomeNodeEndpoint, token: String) =
        jobCall({ requireNotNull(readRoadmapList(it)) }, MAX_ROADMAP_BYTES) { client.prepareGet(endpoint.url("/v1/roadmaps")) { bearerAuth(token) } }

    override suspend fun roadmap(endpoint: HomeNodeEndpoint, token: String, id: String): ApiResult<RoadmapSnapshot> {
        if (!isRoadmapId(id)) return ApiResult.InvalidResponse("Invalid roadmap id")
        return jobCall({ requireNotNull(readSnapshot(it)) }, MAX_ROADMAP_BYTES) { client.prepareGet(endpoint.url("/v1/roadmaps/$id")) { bearerAuth(token) } }
    }

    override suspend fun roadmapProposals(endpoint: HomeNodeEndpoint, token: String, id: String): ApiResult<List<Proposal>> {
        if (!isRoadmapId(id)) return ApiResult.InvalidResponse("Invalid roadmap id")
        return jobCall({ requireNotNull(readProposals(it)) }, MAX_ROADMAP_BYTES) { client.prepareGet(endpoint.url("/v1/roadmaps/$id/proposals")) { bearerAuth(token) } }
    }

    override suspend fun roadmapHistory(endpoint: HomeNodeEndpoint, token: String, id: String): ApiResult<List<HistoryEntry>> {
        if (!isRoadmapId(id)) return ApiResult.InvalidResponse("Invalid roadmap id")
        return jobCall({ requireNotNull(readHistory(it)) }, MAX_ROADMAP_BYTES) {
            client.prepareGet(endpoint.url("/v1/roadmaps/$id/history")) { bearerAuth(token); parameter("limit", 50) }
        }
    }

    override suspend fun createRoadmap(endpoint: HomeNodeEndpoint, token: String, body: JsonObject) = jobCall(::readSnapshot, MAX_ROADMAP_BYTES) {
        client.preparePost(endpoint.url("/v1/roadmaps")) { bearerAuth(token); setBody(TextContent(body.toString(), ContentType.Application.Json)) }
    }

    override suspend fun writeRoadmap(endpoint: HomeNodeEndpoint, token: String, id: String, revision: Long, write: RoadmapWrite): ApiResult<RoadmapSnapshot?> {
        val (method, path, body) = when (write) {
            is RoadmapWrite.Update -> Triple(HttpMethod.Patch, "", write.patch)
            RoadmapWrite.Archive -> Triple(HttpMethod.Delete, "", null)
            is RoadmapWrite.CreateItem -> Triple(HttpMethod.Post, "/items", write.item)
            is RoadmapWrite.UpdateItem -> Triple(HttpMethod.Patch, "/items/${write.itemId}", write.patch)
            is RoadmapWrite.DeleteItem -> Triple(HttpMethod.Delete, "/items/${write.itemId}", null)
        }
        val itemId = (write as? RoadmapWrite.UpdateItem)?.itemId ?: (write as? RoadmapWrite.DeleteItem)?.itemId
        if (!isRoadmapId(id) || (itemId != null && !isRoadmapId(itemId)) || revision < 0) return ApiResult.InvalidResponse("Invalid roadmap id")
        return jobCall(::readSnapshot, MAX_ROADMAP_BYTES) {
            client.prepareRequest(endpoint.url("/v1/roadmaps/$id$path")) {
                this.method = method
                bearerAuth(token); parameter("expected_revision", revision)
                body?.let { setBody(TextContent(it.toString(), ContentType.Application.Json)) }
            }
        }
    }

    override suspend fun decideProposal(endpoint: HomeNodeEndpoint, token: String, id: String, proposalId: String, accept: Boolean): ApiResult<RoadmapSnapshot?> {
        if (!isRoadmapId(id) || !isRoadmapId(proposalId)) return ApiResult.InvalidResponse("Invalid roadmap id")
        val decision = if (accept) "accept" else "reject"
        return jobCall(::readSnapshot, MAX_ROADMAP_BYTES) {
            client.preparePost(endpoint.url("/v1/roadmaps/$id/proposals/$proposalId/$decision")) {
                bearerAuth(token); setBody(TextContent("{}", ContentType.Application.Json))
            }
        }
    }

    /** A Jobs or Roadmaps call: the answer is read within [limit] and passed through [read],
     *  which throws IllegalArgumentException for a shape the guards refuse. An answer over the
     *  limit fails closed, also for a write. */
    private suspend fun <T> jobCall(
        read: (JsonElement) -> T, limit: Int = MAX_JOBS_BYTES, statement: suspend () -> HttpStatement,
    ): ApiResult<T> = try {
        statement().execute { response ->
            when {
                response.status == HttpStatusCode.Unauthorized -> ApiResult.Unauthorized
                response.status.value !in 200..299 -> ApiResult.HttpError(response.status.value, null)
                else -> try {
                    val json = boundedJson(response, limit)
                    ApiResult.Success(withContext(Dispatchers.Default) { read(json) })
                } catch (_: IllegalArgumentException) {
                    ApiResult.InvalidResponse("Home Node sent an invalid answer")
                }
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        networkFailure(error)
    }

    /** A JSON reply read within [limit], off the main thread: JsonNull when empty or not JSON;
     *  IllegalArgumentException when larger, so an oversized answer never reads as nothing. */
    private suspend fun boundedJson(response: io.ktor.client.statement.HttpResponse, limit: Int): JsonElement =
        withContext(Dispatchers.IO) {
            val bytes = response.bodyAsChannel().toInputStream().use { input -> readAtMost(input, limit + 1) }
            require(bytes.size <= limit)
            runCatching { Json.parseToJsonElement(bytes.decodeToString()) }.getOrDefault(JsonNull)
        }

    /** A small JSON reply, read with a size bound; null when too large or not JSON. */
    private suspend fun smallJson(response: io.ktor.client.statement.HttpResponse, limit: Int = MAX_REPLY_BYTES): JsonElement? =
        try { boundedJson(response, limit).takeUnless { it == JsonNull } } catch (_: IllegalArgumentException) { null }

    private suspend inline fun <reified T> request(
        crossinline block: suspend () -> io.ktor.client.statement.HttpResponse,
    ): ApiResult<T> {
        return try {
            val response = block()
            when {
                response.status == HttpStatusCode.Unauthorized -> ApiResult.Unauthorized
                response.status.value !in 200..299 -> {
                    val error = runCatching { response.body<ErrorResponse>() }.getOrNull()
                    ApiResult.HttpError(
                        response.status.value,
                        if (error?.code == PAIRING_PASSWORD_NOT_SET) PAIRING_PASSWORD_NOT_SET_MESSAGE else error?.hint ?: error?.error,
                    )
                }
                T::class == Unit::class -> {
                    @Suppress("UNCHECKED_CAST")
                    ApiResult.Success(Unit as T)
                }
                else -> ApiResult.Success(response.body())
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: HttpRequestTimeoutException) {
            ApiResult.Unreachable(UnreachableReason.TIMEOUT)
        } catch (_: ConnectTimeoutException) {
            ApiResult.Unreachable(UnreachableReason.TIMEOUT)
        } catch (_: SocketTimeoutException) {
            ApiResult.Unreachable(UnreachableReason.TIMEOUT)
        } catch (_: UnresolvedAddressException) {
            ApiResult.Unreachable(UnreachableReason.DNS)
        } catch (_: UnknownHostException) {
            ApiResult.Unreachable(UnreachableReason.DNS)
        } catch (_: SSLException) {
            ApiResult.Unreachable(UnreachableReason.TLS)
        } catch (_: ConnectException) {
            ApiResult.Unreachable(UnreachableReason.REFUSED)
        } catch (error: SerializationException) {
            ApiResult.InvalidResponse(error.message ?: "Invalid response from Home Node")
        } catch (_: Exception) {
            ApiResult.Unreachable(UnreachableReason.NETWORK)
        }
    }

    companion object {
        /** Core stores at most 1 MiB per photo; twice that leaves room, no more. */
        const val MAX_PHOTO_BYTES = 2 * 1024 * 1024
        /** Core's upload body cap; a prepared photo is far smaller. */
        const val MAX_UPLOAD_BYTES = 15 * 1024 * 1024
        /** An upload id or a photo's keep state is tiny. */
        private const val MAX_REPLY_BYTES = 4 * 1024
        /** A run report with its facts is the largest Jobs answer; Core caps its lists. */
        private const val MAX_JOBS_BYTES = 1024 * 1024
        /** A full roadmap (300 items with notes and links) can pass 1 MiB; desktop's
         *  auth_request caps answers at 16 MiB. */
        private const val MAX_ROADMAP_BYTES = 16 * 1024 * 1024
        private val REPLY_JSON = Json { ignoreUnknownKeys = true }
        private val CONVERSATION_ID = Regex("[A-Za-z0-9_-]{1,128}")

        /** Only a canonical UUID reaches a photo path; null for anything else. */
        fun photoId(id: String): String? =
            runCatching { java.util.UUID.fromString(id) }.getOrNull()?.toString()?.takeIf { it == id.lowercase() }

        fun isJpeg(bytes: ByteArray): Boolean =
            bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()

        private fun readAtMost(input: java.io.InputStream, limit: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (out.size() < limit) {
                val read = input.read(buffer, 0, minOf(buffer.size, limit - out.size()))
                if (read < 0) break
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }

        private const val PAIRING_PASSWORD_NOT_SET = "pairing_password_not_set"
        const val PAIRING_PASSWORD_NOT_SET_MESSAGE = "Set an account password on an active device first."

        // Fixed owner routes only: no query, no dot segments, no foreign origin.
        private val OWNER_PATH = Regex("/v1/[A-Za-z0-9_/-]+")
        private val ANDROID_APK_CONTENT_TYPE = ContentType("application", "vnd.android.package-archive")

        private fun defaultHttpClient() = HttpClient(Android) {
            expectSuccess = false
            // Authenticated requests never follow redirects: bearer credentials
            // must remain bound to the enrolled Home Node origin.
            followRedirects = false
            install(HttpTimeout) {
                connectTimeoutMillis = 5_000
                requestTimeoutMillis = 30_000
                socketTimeoutMillis = 30_000
            }
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    explicitNulls = false
                })
            }
            // Deliberately no HTTP logging plugin: bearer and pairing tokens must not reach logs.
        }
    }

    private fun networkFailure(error: Exception): ApiResult<Nothing> = when (error) {
        is HttpRequestTimeoutException, is ConnectTimeoutException, is SocketTimeoutException ->
            ApiResult.Unreachable(UnreachableReason.TIMEOUT)
        is UnresolvedAddressException, is UnknownHostException ->
            ApiResult.Unreachable(UnreachableReason.DNS)
        is SSLException -> ApiResult.Unreachable(UnreachableReason.TLS)
        is ConnectException -> ApiResult.Unreachable(UnreachableReason.REFUSED)
        is SerializationException -> ApiResult.InvalidResponse(
            error.message ?: "Invalid response from Home Node",
        )
        else -> ApiResult.Unreachable(UnreachableReason.NETWORK)
    }
}

private fun HomeNodeEndpoint.url(path: String): String = "$baseUrl$path"
