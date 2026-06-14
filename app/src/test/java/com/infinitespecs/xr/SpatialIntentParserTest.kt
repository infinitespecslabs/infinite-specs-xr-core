/*
 * SpatialIntentParserTest.kt
 * infinite-specs-xr-core — Unit Tests
 *
 * Verifies that mock spatial events (gaze dwell, voice tokens) deterministically
 * map to the correct SpatialIntent type and that the McpSpecificationBridge
 * wraps them in well-formed McpEnvelope payloads.
 *
 * Android XR Developer Preview 4 — JVM unit test suite (no device required).
 *
 * Run with:
 *   ./gradlew :app:test
 */

package com.infinitespecs.xr

import app.cash.turbine.test
import com.infinitespecs.xr.bridge.InMemoryMcpBridge
import com.infinitespecs.xr.bridge.McpEnvelope
import com.infinitespecs.xr.bridge.McpSpecificationBridge
import com.infinitespecs.xr.bridge.SpecPayload
import com.infinitespecs.xr.perception.DefaultSpatialIntentParser
import com.infinitespecs.xr.perception.IntentType
import com.infinitespecs.xr.perception.MockGazeEventSource
import com.infinitespecs.xr.perception.MockVoiceEventSource
import com.infinitespecs.xr.perception.RawSpatialEvent
import com.infinitespecs.xr.perception.SpatialIntent
import com.infinitespecs.xr.perception.SpatialIntentParser
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// ─────────────────────────────────────────────────────────────────────────────
// SpatialIntentParser — unit tests
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Tests for [DefaultSpatialIntentParser].
 *
 * All tests are pure JVM; no Android XR runtime or device is required.
 */
class SpatialIntentParserTest {

    private lateinit var parser: DefaultSpatialIntentParser

    @Before
    fun setUp() {
        parser = DefaultSpatialIntentParser(
            dwellThresholdMs = DefaultSpatialIntentParser.DEFAULT_DWELL_THRESHOLD_MS,
        )
    }

    // ── Gaze intent resolution ────────────────────────────────────────────────

    /**
     * A gaze event with dwell ≥ threshold should resolve to INSPECT.
     */
    @Test
    fun `gaze event above dwell threshold emits INSPECT intent`() = runTest {
        val gazeEvents = MockGazeEventSource(
            listOf(
                RawSpatialEvent.GazeEvent(
                    nodeId = "api-gateway",
                    dwellMs = 1_000L,
                ),
            ),
        ).asFlow()

        parser.parse(gazeEvents = gazeEvents, voiceEvents = emptyFlow())
            .test {
                val intent = awaitItem()
                assertEquals(IntentType.INSPECT, intent.intentType)
                assertEquals("api-gateway", intent.nodeId)
                assertTrue(intent.confidenceScore > 0f)
                awaitComplete()
            }
    }

    /**
     * A gaze event with dwell < threshold should NOT produce any emission.
     */
    @Test
    fun `gaze event below dwell threshold emits nothing`() = runTest {
        val gazeEvents = MockGazeEventSource(
            listOf(
                RawSpatialEvent.GazeEvent(
                    nodeId = "api-gateway",
                    dwellMs = 100L,  // well below 800 ms threshold
                ),
            ),
        ).asFlow()

        parser.parse(gazeEvents = gazeEvents, voiceEvents = emptyFlow())
            .test {
                awaitComplete()  // no items expected
            }
    }

    /**
     * Gaze dwell exactly at the threshold should emit an INSPECT intent.
     */
    @Test
    fun `gaze event at exact dwell threshold boundary emits INSPECT intent`() = runTest {
        val gazeEvents = MockGazeEventSource(
            listOf(
                RawSpatialEvent.GazeEvent(
                    nodeId = "data-store",
                    dwellMs = DefaultSpatialIntentParser.DEFAULT_DWELL_THRESHOLD_MS,
                ),
            ),
        ).asFlow()

        parser.parse(gazeEvents = gazeEvents, voiceEvents = emptyFlow())
            .test {
                val intent = awaitItem()
                assertEquals(IntentType.INSPECT, intent.intentType)
                awaitComplete()
            }
    }

    // ── Confidence score computation ──────────────────────────────────────────

    /**
     * Confidence score should saturate at 1.0 for dwell ≥ MAX_DWELL_MS.
     */
    @Test
    fun `gaze dwell at max duration yields confidence score of 1_0`() {
        val score = parser.gazeDwellConfidence(DefaultSpatialIntentParser.MAX_DWELL_MS)
        assertEquals(1.0f, score, 0.001f)
    }

    /**
     * Confidence score should not exceed 1.0 for dwell > MAX_DWELL_MS.
     */
    @Test
    fun `gaze dwell beyond max duration clamps confidence to 1_0`() {
        val score = parser.gazeDwellConfidence(DefaultSpatialIntentParser.MAX_DWELL_MS * 2)
        assertEquals(1.0f, score, 0.001f)
    }

    /**
     * Half of MAX_DWELL_MS should produce ~0.5 confidence.
     */
    @Test
    fun `gaze dwell at half max duration yields 0_5 confidence`() {
        val score = parser.gazeDwellConfidence(DefaultSpatialIntentParser.MAX_DWELL_MS / 2)
        assertEquals(0.5f, score, 0.01f)
    }

    // ── Voice intent resolution ───────────────────────────────────────────────

    /**
     * Each specification verb should map deterministically to SPECIFY.
     */
    @Test
    fun `voice tokens for specification verbs map to SPECIFY intent`() {
        val specVerbs = listOf("define", "spec", "schema", "api", "generate", "create", "describe")
        for (verb in specVerbs) {
            assertEquals(
                "Expected SPECIFY for verb '$verb'",
                IntentType.SPECIFY,
                parser.resolveVoiceIntent(verb),
            )
        }
    }

    /**
     * Connection verbs should map deterministically to CONNECT.
     */
    @Test
    fun `voice tokens for connection verbs map to CONNECT intent`() {
        val connectVerbs = listOf("connect", "link", "wire", "attach")
        for (verb in connectVerbs) {
            assertEquals(
                "Expected CONNECT for verb '$verb'",
                IntentType.CONNECT,
                parser.resolveVoiceIntent(verb),
            )
        }
    }

    /**
     * Expansion verbs should map deterministically to EXPAND.
     */
    @Test
    fun `voice tokens for expansion verbs map to EXPAND intent`() {
        val expandVerbs = listOf("expand", "detail", "zoom", "open")
        for (verb in expandVerbs) {
            assertEquals(
                "Expected EXPAND for verb '$verb'",
                IntentType.EXPAND,
                parser.resolveVoiceIntent(verb),
            )
        }
    }

    /**
     * Unknown tokens should fall back to INSPECT (not UNKNOWN — voice-only
     * events are treated as general inspection signals by default).
     */
    @Test
    fun `unknown voice token falls back to INSPECT intent`() {
        assertEquals(IntentType.INSPECT, parser.resolveVoiceIntent("hello"))
        assertEquals(IntentType.INSPECT, parser.resolveVoiceIntent("xr"))
        assertEquals(IntentType.INSPECT, parser.resolveVoiceIntent(""))
    }

    /**
     * Voice intent resolution should be case-insensitive.
     */
    @Test
    fun `voice intent resolution is case insensitive`() {
        assertEquals(IntentType.SPECIFY, parser.resolveVoiceIntent("DEFINE"))
        assertEquals(IntentType.SPECIFY, parser.resolveVoiceIntent("Define"))
        assertEquals(IntentType.CONNECT, parser.resolveVoiceIntent("CONNECT"))
    }

    // ── Voice event flow ──────────────────────────────────────────────────────

    /**
     * A high-confidence voice event with a SPECIFY verb should emit a
     * SPECIFY intent from the parse flow.
     */
    @Test
    fun `high confidence SPECIFY voice event emits SPECIFY SpatialIntent`() = runTest {
        val voiceEvents = MockVoiceEventSource(
            listOf(
                RawSpatialEvent.VoiceEvent(
                    token = "define",
                    confidence = 0.95f,
                ),
            ),
        ).asFlow()

        parser.parse(gazeEvents = emptyFlow(), voiceEvents = voiceEvents)
            .test {
                val intent = awaitItem()
                assertEquals(IntentType.SPECIFY, intent.intentType)
                assertEquals(0.95f, intent.confidenceScore, 0.001f)
                awaitComplete()
            }
    }

    /**
     * A voice event below the minimum confidence threshold should be filtered
     * out and produce no emissions.
     */
    @Test
    fun `low confidence voice event is filtered out and emits nothing`() = runTest {
        val voiceEvents = MockVoiceEventSource(
            listOf(
                RawSpatialEvent.VoiceEvent(
                    token = "define",
                    confidence = 0.3f,  // below MIN_VOICE_CONFIDENCE = 0.6
                ),
            ),
        ).asFlow()

        parser.parse(gazeEvents = emptyFlow(), voiceEvents = voiceEvents)
            .test {
                awaitComplete()
            }
    }

    // ── Determinism guarantee ────────────────────────────────────────────────

    /**
     * Core determinism invariant: the same spatial event always produces an
     * identical SpatialIntent (same intentType, same nodeId).
     *
     * This is the foundational contract for reliable spec generation.
     */
    @Test
    fun `same spatial event always maps to same SpatialIntent type and nodeId`() = runTest {
        val gazeEvent = RawSpatialEvent.GazeEvent(nodeId = "auth-service", dwellMs = 1_500L)
        val results = mutableListOf<SpatialIntent>()

        // Repeat the same event multiple times and collect results.
        repeat(5) {
            MockGazeEventSource(listOf(gazeEvent)).asFlow().let { flow ->
                parser.parse(gazeEvents = flow, voiceEvents = emptyFlow())
                    .test {
                        results.add(awaitItem())
                        awaitComplete()
                    }
            }
        }

        // All five runs must produce identical intentType and nodeId.
        assertTrue("Expected 5 results", results.size == 5)
        results.forEach { intent ->
            assertEquals(IntentType.INSPECT, intent.intentType)
            assertEquals("auth-service", intent.nodeId)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// McpSpecificationBridge — unit tests
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Tests for [InMemoryMcpBridge] verifying that [SpecPayload] objects are
 * wrapped in well-formed [McpEnvelope] structures with correct routing metadata.
 */
class McpSpecificationBridgeTest {

    private lateinit var bridge: InMemoryMcpBridge

    @Before
    fun setUp() {
        bridge = InMemoryMcpBridge()
    }

    // ── Session lifecycle ─────────────────────────────────────────────────────

    @Test
    fun `openSession returns an open BridgeSession`() {
        val session = bridge.openSession()
        assertTrue(session.isOpen)
        session.close()
    }

    @Test
    fun `closing session marks it as closed`() {
        val session = bridge.openSession()
        session.close()
        assertFalse(session.isOpen)
    }

    // ── OpenApiSpec payload ───────────────────────────────────────────────────

    /**
     * Publishing an [SpecPayload.OpenApiSpec] should produce an [McpEnvelope]
     * with method "spec/push", specType "openapi", and the correct nodeId.
     */
    @Test
    fun `publishing OpenApiSpec produces correct McpEnvelope`() = runTest {
        val session = bridge.openSession()

        bridge.stream().test {
            bridge.publish(
                SpecPayload.OpenApiSpec(
                    nodeId = "api-gateway",
                    openApiJson = """{"openapi":"3.1.0","info":{"title":"API Gateway"}}""",
                    version = 1,
                ),
            )

            val envelope = awaitItem()
            assertEquals("2.0", envelope.jsonrpc)
            assertEquals("spec/push", envelope.method)
            assertEquals("api-gateway", envelope.params.nodeId)
            assertEquals("openapi", envelope.params.specType)
            assertEquals(1, envelope.params.version)
            assertTrue(envelope.params.payloadJson.contains("API Gateway"))

            session.close()
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── McpToolSpec payload ───────────────────────────────────────────────────

    /**
     * Publishing an [SpecPayload.McpToolSpec] should produce an [McpEnvelope]
     * with method "spec/push", specType "mcp-tool", and the tool name embedded
     * in the payloadJson.
     */
    @Test
    fun `publishing McpToolSpec produces correct McpEnvelope`() = runTest {
        val session = bridge.openSession()

        bridge.stream().test {
            bridge.publish(
                SpecPayload.McpToolSpec(
                    nodeId = "auth-service",
                    toolName = "auth_service_login",
                    description = "Authenticates a user and returns a JWT.",
                    inputSchemaJson = """{"type":"object","properties":{"username":{"type":"string"}}}""",
                    version = 2,
                ),
            )

            val envelope = awaitItem()
            assertEquals("spec/push", envelope.method)
            assertEquals("auth-service", envelope.params.nodeId)
            assertEquals("mcp-tool", envelope.params.specType)
            assertEquals(2, envelope.params.version)
            assertTrue(envelope.params.payloadJson.contains("auth_service_login"))

            session.close()
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── Sequence ID monotonicity ──────────────────────────────────────────────

    /**
     * Each published payload should carry a strictly increasing sequence ID.
     */
    @Test
    fun `envelope sequence IDs are strictly increasing`() = runTest {
        val session = bridge.openSession()
        val collectedIds = mutableListOf<Long>()

        bridge.stream().test {
            repeat(3) { i ->
                bridge.publish(
                    SpecPayload.OpenApiSpec(
                        nodeId = "node-$i",
                        openApiJson = "{}",
                        version = i + 1,
                    ),
                )
                collectedIds.add(awaitItem().id)
            }

            session.close()
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(listOf(1L, 2L, 3L), collectedIds)
    }

    // ── No session → no publish ───────────────────────────────────────────────

    /**
     * Publishing without an open session should produce no envelopes.
     */
    @Test
    fun `publish with no open session emits nothing`() = runTest {
        // No session opened.
        bridge.stream().test {
            bridge.publish(SpecPayload.OpenApiSpec(nodeId = "x", openApiJson = "{}", version = 1))
            // No items expected since there is no active session.
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * Publishing after the session is closed should produce no envelopes.
     */
    @Test
    fun `publish after session close emits nothing`() = runTest {
        val session = bridge.openSession()
        session.close()

        bridge.stream().test {
            bridge.publish(SpecPayload.OpenApiSpec(nodeId = "x", openApiJson = "{}", version = 1))
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── End-to-end: spatial event → spec payload ──────────────────────────────

    /**
     * Integration smoke test: a gaze event resolved by the parser should
     * translate to an INSPECT SpatialIntent that can be mapped to an
     * OpenApiSpec and published through the bridge as a valid McpEnvelope.
     *
     * Verifies the full pipeline without the Android XR runtime:
     *   GazeEvent → SpatialIntent(INSPECT) → SpecPayload.OpenApiSpec → McpEnvelope
     */
    @Test
    fun `gaze event flows through parser and bridge to a valid McpEnvelope`() = runTest {
        val parser = DefaultSpatialIntentParser()
        val session = bridge.openSession()

        val gazeEvents = MockGazeEventSource(
            listOf(
                RawSpatialEvent.GazeEvent(nodeId = "event-bus", dwellMs = 1_200L),
            ),
        ).asFlow()

        bridge.stream().test {
            // 1. Parse gaze event to SpatialIntent.
            parser.parse(gazeEvents = gazeEvents, voiceEvents = emptyFlow())
                .collect { intent ->
                    // 2. (Simulate schema translation) Create a minimal OpenApiSpec.
                    val spec = SpecPayload.OpenApiSpec(
                        nodeId = intent.nodeId,
                        openApiJson = buildMinimalOpenApiFragment(intent),
                        version = 1,
                    )
                    // 3. Publish to bridge.
                    bridge.publish(spec)
                }

            val envelope = awaitItem()
            assertEquals("event-bus", envelope.params.nodeId)
            assertEquals("openapi", envelope.params.specType)
            assertTrue(envelope.params.payloadJson.contains("event-bus"))
            assertTrue(envelope.params.payloadJson.contains("INSPECT"))

            session.close()
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Builds a minimal OpenAPI 3.1 JSON fragment from a [SpatialIntent].
     * Mirrors the behaviour of the Schema Translation Layer's SpecificationMapper.
     */
    private fun buildMinimalOpenApiFragment(intent: SpatialIntent): String =
        """
        {
          "openapi": "3.1.0",
          "info": {
            "title": "${intent.nodeId}",
            "x-infinite-specs-intent": "${intent.intentType.name}",
            "x-infinite-specs-confidence": ${intent.confidenceScore}
          },
          "paths": {}
        }
        """.trimIndent()
}
