package com.infinitespecs.xr.bridge

import app.cash.turbine.test
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers every REST call [McpSpecificationBridge] makes — permission/question
 * responses, prompt/interrupt submission, session listing, and the SSE
 * connection lifecycle — via Ktor's MockEngine (see [TestBridge]) instead of
 * a real network. `submitPermissionResponse`'s key-resolution path is the
 * exact bug class fixed in issue #18; regression coverage lives here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class McpSpecificationBridgeApiTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun samplePermissionRequest(
        options: List<McpSpecificationBridge.PermissionOption> = listOf(
            McpSpecificationBridge.PermissionOption("Yes", "allow"),
            McpSpecificationBridge.PermissionOption("Yes, and always allow Bash `npm install *`", "allowAlways"),
            McpSpecificationBridge.PermissionOption("No", "deny"),
        ),
    ) = McpSpecificationBridge.PermissionRequestEvent(
        toolName = "Bash",
        description = "Install a package",
        toolUseId = "tool-1",
        options = options,
    )

    // ── submitPermissionResponse ────────────────────────────────────────────

    @Test
    fun `submitPermissionResponse resolves the key from the matching option text`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val testBridge = testBridge(dispatcher, jsonResponse("""{"ok":true}"""))
        testBridge.bridge.currentSessionId = "s1"
        testBridge.bridge.lastPermissionRequest = samplePermissionRequest()

        testBridge.bridge.submitPermissionResponse("Yes, and always allow Bash `npm install *`")?.join()

        val sent = json.decodeFromString<McpSpecificationBridge.PermissionResponse>(testBridge.requestBodies.single())
        assertEquals("allowAlways", sent.decision)
        assertEquals("s1", sent.sessionId)
        assertEquals("http://test.local:3456/api/permission-response", testBridge.requests.single().url.toString())
        assertEquals("Bearer test-token", testBridge.requests.single().headers["Authorization"])
    }

    @Test
    fun `submitPermissionResponse falls back to deny for text matching no known option`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val testBridge = testBridge(dispatcher, jsonResponse("""{"ok":true}"""))
        testBridge.bridge.currentSessionId = "s1"
        testBridge.bridge.lastPermissionRequest = samplePermissionRequest()

        // Stale/unexpected text — e.g. a race where the option list changed underneath the click.
        testBridge.bridge.submitPermissionResponse("some stale button text")?.join()

        val sent = json.decodeFromString<McpSpecificationBridge.PermissionResponse>(testBridge.requestBodies.single())
        assertEquals("deny", sent.decision)
    }

    @Test
    fun `submitPermissionResponse clears lastPermissionRequest once the request completes`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val testBridge = testBridge(dispatcher, jsonResponse("""{"ok":true}"""))
        testBridge.bridge.currentSessionId = "s1"
        testBridge.bridge.lastPermissionRequest = samplePermissionRequest()

        testBridge.bridge.submitPermissionResponse("Yes")?.join()

        assertNull(testBridge.bridge.lastPermissionRequest)
    }

    @Test
    fun `submitPermissionResponse is a no-op with no active permission request`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val testBridge = noRequestsExpectedBridge(dispatcher)
        testBridge.bridge.currentSessionId = "s1" // session active, but no lastPermissionRequest

        testBridge.bridge.submitPermissionResponse("Yes")?.join()

        assertTrue(testBridge.requests.isEmpty())
    }

    @Test
    fun `submitPermissionResponse is a no-op with no active session`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val testBridge = noRequestsExpectedBridge(dispatcher)
        testBridge.bridge.lastPermissionRequest = samplePermissionRequest() // request pending, but no session

        testBridge.bridge.submitPermissionResponse("Yes")?.join()

        assertTrue(testBridge.requests.isEmpty())
    }

    // ── submitQuestionResponse ───────────────────────────────────────────────

    @Test
    fun `submitQuestionResponse sends the raw answer text and clears lastQuestionRequest`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val testBridge = testBridge(dispatcher, jsonResponse("""{"ok":true}"""))
        testBridge.bridge.currentSessionId = "s1"
        testBridge.bridge.lastQuestionRequest = McpSpecificationBridge.UserQuestionEvent(toolUseId = "t1")

        testBridge.bridge.submitQuestionResponse("Spaces")?.join()

        val sent = json.decodeFromString<McpSpecificationBridge.QuestionResponse>(testBridge.requestBodies.single())
        assertEquals("Spaces", sent.answer)
        assertEquals("s1", sent.sessionId)
        assertNull(testBridge.bridge.lastQuestionRequest)
    }

    @Test
    fun `submitQuestionResponse is a no-op with no active session`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val testBridge = noRequestsExpectedBridge(dispatcher)

        testBridge.bridge.submitQuestionResponse("Spaces")?.join()

        assertTrue(testBridge.requests.isEmpty())
    }

    // ── submitInterrupt / submitPrompt ──────────────────────────────────────

    @Test
    fun `submitInterrupt posts the active session id`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val testBridge = testBridge(dispatcher, jsonResponse("""{"ok":true}"""))
        testBridge.bridge.currentSessionId = "s1"

        testBridge.bridge.submitInterrupt()?.join()

        val sent = json.decodeFromString<McpSpecificationBridge.InterruptRequest>(testBridge.requestBodies.single())
        assertEquals("s1", sent.sessionId)
        assertEquals("http://test.local:3456/api/interrupt", testBridge.requests.single().url.toString())
    }

    @Test
    fun `submitInterrupt is a no-op with no active session`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val testBridge = noRequestsExpectedBridge(dispatcher)

        testBridge.bridge.submitInterrupt()?.join()

        assertTrue(testBridge.requests.isEmpty())
    }

    @Test
    fun `submitPrompt posts the text and active session id`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val testBridge = testBridge(dispatcher, jsonResponse("""{"ok":true}"""))
        testBridge.bridge.currentSessionId = "s1"

        testBridge.bridge.submitPrompt("what's next?")?.join()

        val sent = json.decodeFromString<McpSpecificationBridge.PromptRequest>(testBridge.requestBodies.single())
        assertEquals("what's next?", sent.text)
        assertEquals("s1", sent.sessionId)
    }

    @Test
    fun `submitPrompt is a no-op with no active session`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val testBridge = noRequestsExpectedBridge(dispatcher)

        testBridge.bridge.submitPrompt("hello")?.join()

        assertTrue(testBridge.requests.isEmpty())
    }

    // ── refreshSessions ──────────────────────────────────────────────────────

    @Test
    fun `refreshSessions accepts an object-wrapped sessions response`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val testBridge = testBridge(dispatcher, jsonResponse("""{"sessions":[{"id":"s1","title":"My session"}]}"""))

        testBridge.bridge.refreshSessions()?.join()

        assertEquals(listOf("s1"), testBridge.bridge.sessionsFlow.value.map { it.id })
        assertTrue(testBridge.bridge.sessionsFetched.value)
    }

    @Test
    fun `refreshSessions accepts a plain array sessions response`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val testBridge = testBridge(dispatcher, jsonResponse("""[{"id":"s1","title":"My session"}]"""))

        testBridge.bridge.refreshSessions()?.join()

        assertEquals(listOf("s1"), testBridge.bridge.sessionsFlow.value.map { it.id })
        assertTrue(testBridge.bridge.sessionsFetched.value)
    }

    @Test
    fun `refreshSessions leaves sessionsFetched false on a non-200 response`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val testBridge = testBridge(dispatcher, jsonResponse("""{"error":"unauthorized"}""", HttpStatusCode.Unauthorized))

        testBridge.bridge.refreshSessions()?.join()

        assertFalse(testBridge.bridge.sessionsFetched.value)
        assertTrue(testBridge.bridge.sessionsFlow.value.isEmpty())
    }

    @Test
    fun `refreshSessions does not crash on a malformed response body`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val testBridge = testBridge(dispatcher, jsonResponse("not json"))

        testBridge.bridge.refreshSessions()?.join()

        assertFalse(testBridge.bridge.sessionsFetched.value)
    }

    // ── connectToSession / disconnect ────────────────────────────────────────

    @Test
    fun `connectToSession processes the streamed events and reaches CONNECTED`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val sseBody = "data: " + """{"type":"status","state":"busy","sessionId":"s1"}""" + "\n\n"
        val testBridge = testBridge(dispatcher, sseResponse(sseBody))

        testBridge.bridge.inboundStateStream.test {
            testBridge.bridge.connectToSession("s1").join()
            assertEquals("THINKING", awaitItem().state)
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals("http://test.local:3456/api/events?sessionId=s1&needReplay=true", testBridge.requests.single().url.toString())
        assertEquals("s1", testBridge.bridge.currentSessionId)
    }

    @Test
    fun `connectToSession reports ERROR on a non-200 response`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val testBridge = testBridge(dispatcher, sseResponse("", HttpStatusCode.Unauthorized))

        testBridge.bridge.connectToSession("s1").join()

        assertEquals("ERROR", testBridge.bridge.connectionState.value)
    }

    @Test
    fun `disconnect resets session state without requiring an active connection`() {
        val bridge = McpSpecificationBridge()
        bridge.currentSessionId = "s1"
        bridge.lastPermissionRequest = samplePermissionRequest()
        bridge.lastQuestionRequest = McpSpecificationBridge.UserQuestionEvent(toolUseId = "t1")

        bridge.disconnect()

        assertNull(bridge.currentSessionId)
        assertNull(bridge.lastPermissionRequest)
        assertNull(bridge.lastQuestionRequest)
        assertFalse(bridge.sessionsFetched.value)
        assertEquals("DISCONNECTED", bridge.connectionState.value)
    }
}
