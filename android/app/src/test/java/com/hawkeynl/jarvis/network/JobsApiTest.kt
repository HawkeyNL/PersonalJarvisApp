package com.hawkeynl.jarvis.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JobsApiTest {
    private val endpoint = (HomeNodeEndpoint.parse("https://jarvis.test") as EndpointValidation.Valid).endpoint
    private val id = "0b6f3c2e-8d1a-4f5e-9c3b-2a1d0e9f8c71"

    private fun api(status: HttpStatusCode = HttpStatusCode.OK, body: String = """{"status":"ok","jobs":[]}"""): Pair<KtorJarvisApi, MockEngine> {
        val engine = MockEngine { respond(body, status, headersOf(HttpHeaders.ContentType, "application/json")) }
        return KtorJarvisApi(HttpClient(engine) { expectSuccess = false }) to engine
    }

    @Test fun `the list is a bearer GET read through the guards`() = runTest {
        val (jobs, engine) = api()
        assertEquals(ApiResult.Success(JobList(true, emptyList())), jobs.jobs(endpoint, "token"))
        val request = engine.requestHistory.single()
        assertEquals(HttpMethod.Get, request.method)
        assertEquals("/v1/jobs", request.url.encodedPath)
        assertEquals("Bearer token", request.headers[HttpHeaders.Authorization])
    }

    @Test fun `401, errors, oversized and invalid answers fail closed`() = runTest {
        assertEquals(ApiResult.Unauthorized, api(HttpStatusCode.Unauthorized).first.jobs(endpoint, "t"))
        assertEquals(ApiResult.HttpError(404, null), api(HttpStatusCode.NotFound).first.jobs(endpoint, "t"))
        val big = """{"status":"ok","jobs":[]}""" + " ".repeat(1024 * 1024)
        assertTrue(api(body = big).first.jobs(endpoint, "t") is ApiResult.InvalidResponse)
        assertTrue(api(body = """{"status":"ok"}""").first.jobs(endpoint, "t") is ApiResult.InvalidResponse)
        assertTrue(api(body = "not json").first.jobRuns(endpoint, "t", id) is ApiResult.InvalidResponse)
    }

    @Test fun `ids other than a UUID never reach the network`() = runTest {
        for (bad in listOf("", "../auth/login", "$id/../x", "$id?x=1", "%2e%2e")) {
            val (jobs, engine) = api()
            assertTrue(bad, jobs.updateJob(endpoint, "t", bad, 1, JsonObject(emptyMap())) is ApiResult.InvalidResponse)
            assertTrue(bad, jobs.runJob(endpoint, "t", bad) is ApiResult.InvalidResponse)
            assertTrue(bad, jobs.deleteJob(endpoint, "t", bad, 1) is ApiResult.InvalidResponse)
            assertTrue(bad, jobs.jobRuns(endpoint, "t", bad) is ApiResult.InvalidResponse)
            assertTrue(bad, jobs.jobRun(endpoint, "t", bad) is ApiResult.InvalidResponse)
            assertTrue(bad, engine.requestHistory.isEmpty())
        }
    }

    @Test fun `a change names its revision and the runs read asks for 20`() = runTest {
        val (jobs, engine) = api(body = "{}")
        // An answer that is not a job still counts as saved; the page reloads.
        assertEquals(ApiResult.Success(null), jobs.updateJob(endpoint, "t", id, 3, JsonObject(emptyMap())))
        val patch = engine.requestHistory.single()
        assertEquals(HttpMethod.Patch, patch.method)
        assertEquals("/v1/jobs/$id", patch.url.encodedPath)
        assertEquals("3", patch.url.parameters["expected_revision"])

        val (runs, runsEngine) = api(body = """{"runs":[]}""")
        assertEquals(ApiResult.Success(emptyList<JobRun>()), runs.jobRuns(endpoint, "t", id))
        val read = runsEngine.requestHistory.single()
        assertEquals("/v1/jobs/$id/runs", read.url.encodedPath)
        assertEquals("20", read.url.parameters["limit"])
    }

    @Test fun `a delete names its revision and a 409 stays a conflict`() = runTest {
        val (jobs, engine) = api(body = """{"deleted":true}""")
        assertEquals(ApiResult.Success(Unit), jobs.deleteJob(endpoint, "t", id, 4))
        val request = engine.requestHistory.single()
        assertEquals(HttpMethod.Delete, request.method)
        assertEquals("/v1/jobs/$id", request.url.encodedPath)
        assertEquals("4", request.url.parameters["expected_revision"])
        assertEquals("Bearer t", request.headers[HttpHeaders.Authorization])
        assertEquals(ApiResult.HttpError(409, null), api(HttpStatusCode.Conflict).first.deleteJob(endpoint, "t", id, 4))
        assertTrue(api(body = "{}" + " ".repeat(1024 * 1024)).first.deleteJob(endpoint, "t", id, 4) is ApiResult.InvalidResponse)
    }

    @Test fun `an oversized write answer fails closed instead of counting as saved`() = runTest {
        val big = "{}" + " ".repeat(1024 * 1024)
        assertTrue(api(body = big).first.updateJob(endpoint, "t", id, 3, JsonObject(emptyMap())) is ApiResult.InvalidResponse)
        assertTrue(api(HttpStatusCode.Accepted, big).first.runJob(endpoint, "t", id) is ApiResult.InvalidResponse)
    }

    @Test fun `run now succeeds on an empty 202 and keeps 429 for its wording`() = runTest {
        val (jobs, engine) = api(HttpStatusCode.Accepted, "")
        assertEquals(ApiResult.Success(Unit), jobs.runJob(endpoint, "t", id))
        assertEquals("/v1/jobs/$id/run", engine.requestHistory.single().url.encodedPath)
        assertEquals(ApiResult.HttpError(429, null), api(HttpStatusCode.TooManyRequests).first.runJob(endpoint, "t", id))
    }
}
