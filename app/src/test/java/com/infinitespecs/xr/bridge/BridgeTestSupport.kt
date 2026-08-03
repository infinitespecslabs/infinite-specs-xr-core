package com.infinitespecs.xr.bridge

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/**
 * Shared MockEngine-backed test harness for [McpSpecificationBridge], used by
 * every test file in this package. Records every request the bridge sends
 * (for assertions on method/URL/headers/body) and preconfigures a host/token
 * so individual tests only need to describe how the fake server responds.
 *
 * [dispatcher] should normally be `UnconfinedTestDispatcher(testScheduler)`
 * from the enclosing `runTest` block, so the bridge's fire-and-forget
 * `coroutineScope.launch { ... }` calls run eagerly on the test's own virtual
 * scheduler instead of racing a real background thread.
 */
class TestBridge(
    dispatcher: CoroutineDispatcher,
    respond: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
) {
    val requests = mutableListOf<HttpRequestData>()

    /** Decoded text bodies, in request order — parallel to [requests]. */
    val requestBodies = mutableListOf<String>()

    val bridge = McpSpecificationBridge(
        engine = MockEngine { request ->
            requests.add(request)
            val content = request.body
            requestBodies.add(if (content is OutgoingContent.NoContent) "" else content.toByteArray().decodeToString())
            respond(request)
        },
        dispatcher = dispatcher,
    ).apply {
        activeHost = "test.local:3456"
        activeToken = "test-token"
    }
}

/** Convenience overload for tests that don't need scheduler-synchronized dispatch. */
@OptIn(ExperimentalCoroutinesApi::class)
fun testBridge(
    dispatcher: CoroutineDispatcher = UnconfinedTestDispatcher(),
    respond: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
) = TestBridge(dispatcher, respond)

@OptIn(ExperimentalCoroutinesApi::class)
fun noRequestsExpectedBridge(dispatcher: CoroutineDispatcher = UnconfinedTestDispatcher()) =
    testBridge(dispatcher) { request ->
        error("Expected no HTTP request, but got ${request.method.value} ${request.url}")
    }

fun jsonResponse(body: String, status: HttpStatusCode = HttpStatusCode.OK): MockRequestHandleScope.(HttpRequestData) -> HttpResponseData =
    {
        respond(
            content = ByteReadChannel(body),
            status = status,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
    }

fun sseResponse(body: String, status: HttpStatusCode = HttpStatusCode.OK): MockRequestHandleScope.(HttpRequestData) -> HttpResponseData =
    {
        respond(
            content = ByteReadChannel(body),
            status = status,
            headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
        )
    }
