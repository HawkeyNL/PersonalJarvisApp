package com.hawkeynl.jarvis.chat

import com.hawkeynl.jarvis.network.ChatTurn
import com.hawkeynl.jarvis.network.HomeNodeEndpoint
import com.hawkeynl.jarvis.storage.SessionRepository
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.random.Random

@Serializable private data class Capability(val protocol: Int, val asynchronous_chat: Boolean)
@Serializable private data class Submit(val request_id: String, val conversation_id: String?, val messages: List<ChatTurn>)
private fun HomeNodeEndpoint.url(path: String): String = "$baseUrl$path"
private sealed interface VoiceCommand {
    data class Report(val payload: PlaybackReport) : VoiceCommand
    data class Release(val payload: VoiceRelease) : VoiceCommand
}

// Native bearer transport; the endpoint comes only from validated runtime
// configuration. The shared authoritative contract is jarvis-client-core.
class RealtimeService(private val sessions: SessionRepository) {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient(OkHttp) {
        followRedirects = false
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) { requestTimeoutMillis = 20_000; connectTimeoutMillis = 15_000 }
        install(WebSockets) { maxFrameSize = 256L * 1024 }
    }
    private var worker: Job? = null
    private var reporting: Job? = null
    @Volatile private var reports: Channel<VoiceCommand>? = null
    fun stop() {
        worker?.cancel(); worker = null
        reporting?.cancel(); reporting = null
        reports?.close(); reports = null
    }
    fun reportPlayback(report: PlaybackReport) {
        // OS callbacks never wait for network, accumulate unbounded work, or
        // contain speech text. Reporting failure cannot fail canonical chat.
        reports?.trySend(VoiceCommand.Report(report))
    }
    fun releaseVoice(runId: String) { reports?.trySend(VoiceCommand.Release(VoiceRelease(runId))) }
    suspend fun recover(endpoint: HomeNodeEndpoint, requests: List<String>): List<Pair<String, RecoveredRun>> {
        val token = sessions.session().token ?: return emptyList()
        // One immutable origin/session binding for this bounded batch. Never POST.
        return kotlinx.coroutines.coroutineScope {
            requests.take(32).map { request ->
                async {
                    try {
                        val id = java.util.UUID.fromString(request).toString()
                        val result = client.get(endpoint.url("/v1/assistant/requests/$id")) {
                            bearerAuth(token); timeout { requestTimeoutMillis = 5_000 }
                        }.body<RecoveredRun>()
                        request to result
                    } catch (error: CancellationException) { throw error } catch (_: Exception) { null }
                }
            }.mapNotNull { it.await() }
        }
    }
    suspend fun available(endpoint: HomeNodeEndpoint): Boolean = try {
        val token = sessions.session().token
        if (token == null) false else {
            val result = client.get(endpoint.url("/v1/events/capability")) { bearerAuth(token) }.body<Capability>()
            result.protocol == 1 && result.asynchronous_chat
        }
    } catch (error: CancellationException) { throw error } catch (_: Exception) { false }

    suspend fun submit(endpoint: HomeNodeEndpoint, requestId: String, conversationId: String?, history: List<ChatTurn>, text: String): RealtimeRun {
        val token = sessions.session().token ?: error("Session unavailable")
        return client.post(endpoint.url("/v1/assistant/runs")) {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody(Submit(requestId, conversationId, (history + ChatTurn("user", text)).takeLast(20)))
        }.body()
    }

    fun start(scope: CoroutineScope, endpoint: HomeNodeEndpoint, disconnected: () -> Unit = {}, receive: suspend (RealtimeEvent) -> Unit) {
        stop()
        val queue = Channel<VoiceCommand>(16)
        val reportingToken = sessions.session().token
        reports = queue
        reporting = scope.launch {
            // Bind this transport's credential snapshot to this origin. A
            // future origin/session change stops the task, never retargets it.
            val token = reportingToken ?: return@launch
            for (command in queue) {
                try {
                    val path = when (command) {
                        is VoiceCommand.Report -> "/v1/voice/playback"
                        is VoiceCommand.Release -> "/v1/voice/release"
                    }
                    client.post(endpoint.url(path)) {
                        bearerAuth(token); timeout { requestTimeoutMillis = 5_000 }
                        contentType(ContentType.Application.Json)
                        when (command) {
                            is VoiceCommand.Report -> setBody(command.payload)
                            is VoiceCommand.Release -> setBody(command.payload)
                        }
                    }
                } catch (error: CancellationException) { throw error } catch (_: Exception) {
                    // Best effort, no retry storm and no response-body logging.
                }
            }
        }
        worker = scope.launch {
            var retry = 0
            while (true) {
                val token = sessions.session().token ?: return@launch
                // HomeNodeEndpoint already enforces HTTPS in production.
                val url = endpoint.url("/v1/events").replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")
                val started = System.nanoTime()
                try {
                    client.webSocket(urlString = url, request = { bearerAuth(token) }) {
                        val cursor = RealtimeCursor()
                        for (frame in incoming) {
                            if (frame !is Frame.Text || frame.data.size > 256 * 1024) error("Invalid event frame")
                            val event = json.decodeFromString<RealtimeEvent>(frame.readText())
                            if (!cursor.accept(event)) continue
                            receive(event)
                        }
                    }
                } catch (error: CancellationException) { throw error } catch (_: Exception) {
                    // No response bodies, headers, bearer or message content in logs.
                }
                disconnected()
                if ((System.nanoTime() - started) > 30_000_000_000L) retry = 0
                retry = (retry + 1).coerceAtMost(6)
                delay((500L shl retry).coerceAtMost(30_000) + Random.nextLong(750))
            }
        }
    }
}
