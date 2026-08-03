package com.infinitespecs.xr.bridge

import app.cash.turbine.test
import app.cash.turbine.turbineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers [McpSpecificationBridge.parseAndProcessSsePayload] (the SSE
 * event-type discriminator) and [McpSpecificationBridge.mapAgentState] —
 * both made `internal` specifically so this suite can drive them directly,
 * without a real or mocked network round trip. This is the protocol contract
 * documented in docs/SYSTEM_DESIGN.md §2.1; regressions here silently break
 * the HUD, as issues #17/#18 demonstrated for adjacent bridge logic.
 */
class McpSpecificationBridgeSseTest {

    @Test
    fun `mapAgentState maps every known server state, and falls back to PROCESSING`() {
        val bridge = McpSpecificationBridge()
        val expected = mapOf(
            "idle" to "IDLE",
            "busy" to "THINKING",
            "think_start" to "THINKING",
            "think_end" to "THINKING",
            "text_start" to "THINKING",
            "text_end" to "THINKING",
            "awaiting" to "AWAITING_INPUT",
            "some_future_state" to "PROCESSING",
        )
        expected.forEach { (serverState, displayState) ->
            assertEquals("mapAgentState(\"$serverState\")", displayState, bridge.mapAgentState(serverState))
        }
    }

    @Test
    fun `status event emits the mapped display state`() = runTest {
        val bridge = McpSpecificationBridge()
        bridge.inboundStateStream.test {
            bridge.parseAndProcessSsePayload("""{"type":"status","state":"busy","sessionId":"s1"}""")
            assertEquals("THINKING", awaitItem().state)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `text_delta event is forwarded verbatim to the log stream`() = runTest {
        val bridge = McpSpecificationBridge()
        bridge.inboundLogStream.test {
            bridge.parseAndProcessSsePayload("""{"type":"text_delta","text":"partial output"}""")
            assertEquals("partial output", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `tool_start event logs and switches state to EXECUTING`() = runTest {
        val bridge = McpSpecificationBridge()
        turbineScope {
            val logs = bridge.inboundLogStream.testIn(backgroundScope)
            val states = bridge.inboundStateStream.testIn(backgroundScope)

            bridge.parseAndProcessSsePayload("""{"type":"tool_start","name":"Bash","toolId":"tool-1"}""")

            assertEquals("> Running Tool: Bash", logs.awaitItem())
            val payload = states.awaitItem()
            assertEquals("EXECUTING", payload.state)
            assertEquals("Running Bash", payload.log)
        }
    }

    @Test
    fun `tool_end event logs the completion summary only`() = runTest {
        val bridge = McpSpecificationBridge()
        bridge.inboundLogStream.test {
            bridge.parseAndProcessSsePayload(
                """{"type":"tool_end","name":"Bash","toolId":"tool-1","summary":"Ran ls","detail":{"output":"a\nb"}}""",
            )
            assertEquals("> Completed Tool: Ran ls", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `permission_request event stores the request and surfaces its options as display text`() = runTest {
        val bridge = McpSpecificationBridge()
        bridge.inboundStateStream.test {
            bridge.parseAndProcessSsePayload(
                """
                {"type":"permission_request","toolName":"Bash","description":"Run rm",
                 "detail":"rm -rf /tmp/x","toolUseId":"t1",
                 "options":[{"text":"Yes","key":"allow"},{"text":"No","key":"deny"}]}
                """.trimIndent(),
            )
            val payload = awaitItem()
            assertEquals("AWAITING_INPUT", payload.state)
            assertEquals("Run rm", payload.prompt)
            assertEquals(listOf("Yes", "No"), payload.options)
            assertEquals("rm -rf /tmp/x", payload.detail)
            cancelAndIgnoreRemainingEvents()
        }

        val stored = bridge.lastPermissionRequest
        assertEquals("Bash", stored?.toolName)
        assertEquals("allow", stored?.options?.first { it.text == "Yes" }?.key)
    }

    @Test
    fun `user_question event stores the request and surfaces the first question`() = runTest {
        val bridge = McpSpecificationBridge()
        bridge.inboundStateStream.test {
            bridge.parseAndProcessSsePayload(
                """
                {"type":"user_question","toolUseId":"t1",
                 "questions":[{"question":"Tabs or spaces?","header":"Style",
                               "options":[{"label":"Spaces","description":"","preview":""}]}]}
                """.trimIndent(),
            )
            val payload = awaitItem()
            assertEquals("AWAITING_INPUT", payload.state)
            assertEquals("Tabs or spaces?", payload.prompt)
            assertEquals(listOf("Spaces"), payload.options)
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals("t1", bridge.lastQuestionRequest?.toolUseId)
    }

    @Test
    fun `user_question event with an empty questions array falls back to a generic prompt`() = runTest {
        val bridge = McpSpecificationBridge()
        bridge.inboundStateStream.test {
            bridge.parseAndProcessSsePayload("""{"type":"user_question","toolUseId":"t1","questions":[]}""")
            val payload = awaitItem()
            assertEquals("Awaiting developer response", payload.prompt)
            assertEquals(emptyList<String>(), payload.options)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `successful result event reports SUCCESS with formatted cost and turn count`() = runTest {
        val bridge = McpSpecificationBridge()
        turbineScope {
            val states = bridge.inboundStateStream.testIn(backgroundScope)
            val logs = bridge.inboundLogStream.testIn(backgroundScope)

            bridge.parseAndProcessSsePayload(
                """{"type":"result","success":true,"text":"Done","sessionId":"s1","costUsd":0.05,"turns":3}""",
            )

            val payload = states.awaitItem()
            assertEquals("SUCCESS", payload.state)
            assertEquals("Session complete. Cost: \$0.050 (3 turns)", payload.log)
            assertEquals("Result: SUCCESS - Done", logs.awaitItem())
        }
    }

    @Test
    fun `failed result event reports FAILURE`() = runTest {
        val bridge = McpSpecificationBridge()
        bridge.inboundStateStream.test {
            bridge.parseAndProcessSsePayload(
                """{"type":"result","success":false,"text":"Interrupted by user","sessionId":"s1"}""",
            )
            assertEquals("FAILURE", awaitItem().state)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a payload matching none of the known shapes is silently ignored`() = runTest {
        val bridge = McpSpecificationBridge()
        // Doesn't match status/text_delta/tool_start/tool_end/permission_request/user_question/result.
        bridge.parseAndProcessSsePayload("""{"unrecognized":"shape"}""")

        assertNull(bridge.lastPermissionRequest)
        assertNull(bridge.lastQuestionRequest)
    }

    @Test
    fun `non-JSON payloads are caught and do not propagate`() = runTest {
        val bridge = McpSpecificationBridge()
        // Must not throw — this is fed directly from the network in production.
        bridge.parseAndProcessSsePayload("not json at all")
    }
}
