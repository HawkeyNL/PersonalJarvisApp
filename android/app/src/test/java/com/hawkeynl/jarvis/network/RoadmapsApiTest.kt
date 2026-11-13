package com.hawkeynl.jarvis.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoadmapsApiTest {
    private val endpoint = (HomeNodeEndpoint.parse("https://jarvis.test") as EndpointValidation.Valid).endpoint
    private val id = "0b6f3c2e-8d1a-4f5e-9c3b-2a1d0e9f8c71"
    private val roadmap = """{"id":"$id","title":"Example","description":null,"phases":["Now"],"revision":4,
        "created_at":"2026-11-01T10:00:00Z","updated_at":"2026-11-02T10:00:00Z","archived_at":null}"""
    private val snapshot = """{"roadmap":$roadmap,"items":[]}"""

    private fun api(status: HttpStatusCode = HttpStatusCode.OK, body: String = """{"roadmaps":[]}"""): Pair<KtorJarvisApi, MockEngine> {
        val engine = MockEngine { respond(body, status, headersOf(HttpHeaders.ContentType, "application/json")) }
        return KtorJarvisApi(HttpClient(engine) { expectSuccess = false }) to engine
    }

    @Test fun `the list is a bearer GET read through the guards`() = runTest {
        val (roadmaps, engine) = api()
        assertEquals(ApiResult.Success(emptyList<Roadmap>()), roadmaps.roadmaps(endpoint, "token"))
        val request = engine.requestHistory.single()
        assertEquals(HttpMethod.Get, request.method)
        assertEquals("/v1/roadmaps", request.url.encodedPath)
        assertEquals("Bearer token", request.headers[HttpHeaders.Authorization])
    }

    @Test fun `401, errors, oversized and invalid answers fail closed`() = runTest {
        assertEquals(ApiResult.Unauthorized, api(HttpStatusCode.Unauthorized).first.roadmaps(endpoint, "t"))
        assertEquals(ApiResult.HttpError(404, null), api(HttpStatusCode.NotFound).first.roadmap(endpoint, "t", id))
        val big = """{"roadmaps":[]}""" + " ".repeat(1024 * 1024)
        assertTrue(api(body = big).first.roadmaps(endpoint, "t") is ApiResult.InvalidResponse)
        assertTrue(api(body = """{"roadmap":$roadmap}""").first.roadmap(endpoint, "t", id) is ApiResult.InvalidResponse)
        assertTrue(api(body = "not json").first.roadmapProposals(endpoint, "t", id) is ApiResult.InvalidResponse)
    }

    @Test fun `ids that are not path-safe never reach the network`() = runTest {
        for (bad in listOf("", "../auth/login", "$id/../x", "$id?x=1", "%2e%2e")) {
            val (roadmaps, engine) = api()
            assertTrue(bad, roadmaps.roadmap(endpoint, "t", bad) is ApiResult.InvalidResponse)
            assertTrue(bad, roadmaps.roadmapProposals(endpoint, "t", bad) is ApiResult.InvalidResponse)
            assertTrue(bad, roadmaps.roadmapHistory(endpoint, "t", bad) is ApiResult.InvalidResponse)
            assertTrue(bad, roadmaps.writeRoadmap(endpoint, "t", bad, 1, RoadmapWrite.Archive) is ApiResult.InvalidResponse)
            assertTrue(bad, roadmaps.writeRoadmap(endpoint, "t", id, 1, RoadmapWrite.DeleteItem(bad)) is ApiResult.InvalidResponse)
            assertTrue(bad, roadmaps.writeRoadmap(endpoint, "t", id, 1, RoadmapWrite.UpdateItem(bad, JsonObject(emptyMap()))) is ApiResult.InvalidResponse)
            assertTrue(bad, roadmaps.decideProposal(endpoint, "t", id, bad, accept = true) is ApiResult.InvalidResponse)
            assertTrue(bad, engine.requestHistory.isEmpty())
        }
    }

    @Test fun `every write names its revision and goes to its route`() = runTest {
        val cases = listOf(
            RoadmapWrite.Update(JsonObject(emptyMap())) to (HttpMethod.Patch to "/v1/roadmaps/$id"),
            RoadmapWrite.Archive to (HttpMethod.Delete to "/v1/roadmaps/$id"),
            RoadmapWrite.CreateItem(newItem("T", null)) to (HttpMethod.Post to "/v1/roadmaps/$id/items"),
            RoadmapWrite.UpdateItem("i1", JsonObject(emptyMap())) to (HttpMethod.Patch to "/v1/roadmaps/$id/items/i1"),
            RoadmapWrite.DeleteItem("i1") to (HttpMethod.Delete to "/v1/roadmaps/$id/items/i1"),
        )
        for ((write, route) in cases) {
            val (roadmaps, engine) = api(body = snapshot)
            val result = roadmaps.writeRoadmap(endpoint, "t", id, 7, write)
            assertEquals(write.toString(), 4L, (result as ApiResult.Success).value!!.roadmap.revision)
            val request = engine.requestHistory.single()
            assertEquals(route.first, request.method)
            assertEquals(route.second, request.url.encodedPath)
            assertEquals("7", request.url.parameters["expected_revision"])
        }
        val (create, createEngine) = api(body = snapshot)
        create.writeRoadmap(endpoint, "t", id, 7, RoadmapWrite.CreateItem(newItem("T", "Now")))
        assertEquals("""{"title":"T","phase":"Now"}""", (createEngine.requestHistory.single().body as TextContent).text)
    }

    @Test fun `an unreadable or empty write answer still counts as done and a 409 stays a conflict`() = runTest {
        assertEquals(ApiResult.Success(null), api(HttpStatusCode.NoContent, "").first.writeRoadmap(endpoint, "t", id, 1, RoadmapWrite.Archive))
        assertEquals(ApiResult.HttpError(409, null), api(HttpStatusCode.Conflict).first.writeRoadmap(endpoint, "t", id, 1, RoadmapWrite.Archive))
    }

    @Test fun `roadmaps read up to 16 MiB and an oversized write answer fails closed`() = runTest {
        // A full roadmap can pass 1 MiB.
        val large = snapshot.dropLast(1) + ",\"pad\":\"" + "x".repeat(2 * 1024 * 1024) + "\"}"
        assertTrue(api(body = large).first.roadmap(endpoint, "t", id) is ApiResult.Success)
        val oversized = "{}" + " ".repeat(16 * 1024 * 1024)
        assertTrue(api(body = oversized).first.writeRoadmap(endpoint, "t", id, 1, RoadmapWrite.Archive) is ApiResult.InvalidResponse)
        assertTrue(api(body = oversized).first.decideProposal(endpoint, "t", id, "p1", accept = false) is ApiResult.InvalidResponse)
    }

    @Test fun `proposals are decided without a revision and history asks for 50`() = runTest {
        val (accept, engine) = api(body = snapshot)
        assertTrue(accept.decideProposal(endpoint, "t", id, "p1", accept = true) is ApiResult.Success)
        val request = engine.requestHistory.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("/v1/roadmaps/$id/proposals/p1/accept", request.url.encodedPath)
        assertEquals(null, request.url.parameters["expected_revision"])
        val (reject, rejectEngine) = api(body = "{}")
        assertEquals(ApiResult.Success(null), reject.decideProposal(endpoint, "t", id, "p1", accept = false))
        assertEquals("/v1/roadmaps/$id/proposals/p1/reject", rejectEngine.requestHistory.single().url.encodedPath)

        val (history, historyEngine) = api(body = """{"changes":[]}""")
        assertEquals(ApiResult.Success(emptyList<HistoryEntry>()), history.roadmapHistory(endpoint, "t", id))
        assertEquals("50", historyEngine.requestHistory.single().url.parameters["limit"])
    }
}
