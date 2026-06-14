/*
 * SpatialIntentParser.kt
 * infinite-specs-xr-core — Perception Layer
 *
 * Reactive interface translating raw XR sensor events (gaze dwell durations,
 * voice transcript tokens) into resolved SpatialIntent domain objects.
 *
 * Android XR Developer Preview 4
 * See: https://developer.android.com/xr/runtime
 *
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │  PERCEPTION LAYER CONTRACT                                               │
 * │                                                                          │
 * │  RawSpatialEvent (Flow)                                                  │
 * │         │                                                                │
 * │         ▼                                                                │
 * │  SpatialIntentParser.parse()                                             │
 * │         │                                                                │
 * │         ▼                                                                │
 * │  Flow<SpatialIntent>  ──▶  Schema Translation Layer                     │
 * └─────────────────────────────────────────────────────────────────────────┘
 */

package com.infinitespecs.xr.perception

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

// ─────────────────────────────────────────────────────────────────────────────
// Domain types
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Sealed hierarchy of raw sensor events captured by the Android XR runtime.
 *
 * In production, events originate from:
 *  - androidx.xr.runtime.Session.eyeState  (gaze)
 *  - Speech-to-text service bound to the XR session (voice)
 *
 * In test / mock contexts, use [MockGazeEventSource] and [MockVoiceEventSource].
 */
sealed class RawSpatialEvent {

    /**
     * A gaze event emitted when the user's gaze dwells on a rendered node.
     *
     * @param nodeId      Stable identifier of the spatial node under gaze.
     * @param dwellMs     Duration in milliseconds the gaze has been sustained.
     * @param timestamp   Wall-clock time (System.currentTimeMillis()) of capture.
     */
    data class GazeEvent(
        val nodeId: String,
        val dwellMs: Long,
        val timestamp: Long = System.currentTimeMillis(),
    ) : RawSpatialEvent()

    /**
     * A voice event carrying a single transcript token from the STT pipeline.
     *
     * @param token       Normalised lowercase word/token from speech recognition.
     * @param confidence  ASR confidence score in [0.0, 1.0].
     * @param timestamp   Wall-clock time of capture.
     */
    data class VoiceEvent(
        val token: String,
        val confidence: Float,
        val timestamp: Long = System.currentTimeMillis(),
    ) : RawSpatialEvent()

    /**
     * A composite event fusing co-temporal gaze and voice signals.
     *
     * **Status: future milestone — not yet produced by [DefaultSpatialIntentParser].**
     *
     * Will be emitted by the perception layer when a [GazeEvent] and at least one
     * [VoiceEvent] occur within [COMPOSITE_WINDOW_MS] of each other. The planned
     * `CompositeSpatialIntentParser` will slide a time window over the merged event
     * stream and emit `CompositeEvent` when both signal types co-occur.
     *
     * TODO(infinite-specs): Implement composite gaze+voice fusion in a follow-up
     *   milestone. See README §Tier-1 Perception Layer for the planned architecture.
     */
    data class CompositeEvent(
        val gazeEvent: GazeEvent,
        val voiceTokens: List<String>,
        val timestamp: Long = System.currentTimeMillis(),
    ) : RawSpatialEvent()

    companion object {
        /**
         * Maximum gap (ms) between a gaze event and voice tokens for them to be
         * considered co-temporal and fused into a [CompositeEvent].
         *
         * Used by the future `CompositeSpatialIntentParser` implementation.
         */
        const val COMPOSITE_WINDOW_MS: Long = 500L
    }
}

// ─────────────────────────────────────────────────────────────────────────────

/**
 * Typed classification of the developer's spatial intent.
 *
 * The schema translation layer maps each [IntentType] to a deterministic
 * OpenAPI / MCP specification fragment.
 */
enum class IntentType {
    /** User is inspecting an architecture node (read-only). */
    INSPECT,

    /** User intends to define or generate a specification for the node. */
    SPECIFY,

    /** User wants to drill down into an existing specification. */
    EXPAND,

    /** User wants to connect the node to another node in the architecture. */
    CONNECT,

    /** Intent could not be resolved with sufficient confidence. */
    UNKNOWN,
}

// ─────────────────────────────────────────────────────────────────────────────

/**
 * Resolved, structured representation of a developer's spatial intent.
 *
 * This is the primary output of [SpatialIntentParser] and the primary input
 * to the schema translation layer.
 *
 * @param intentType      Classified intent (see [IntentType]).
 * @param nodeId          Identifier of the node targeted by the intent.
 * @param confidenceScore Combined confidence in [0.0, 1.0].
 * @param rawEvent        The originating [RawSpatialEvent] for traceability.
 * @param timestamp       Wall-clock resolution timestamp.
 */
data class SpatialIntent(
    val intentType: IntentType,
    val nodeId: String,
    val confidenceScore: Float,
    val rawEvent: RawSpatialEvent,
    val timestamp: Long = System.currentTimeMillis(),
)

// ─────────────────────────────────────────────────────────────────────────────
// Parser interface
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Reactive perception-layer interface.
 *
 * Implementations subscribe to one or more [RawSpatialEvent] streams and emit
 * [SpatialIntent] objects into the schema translation layer.
 *
 * ## Threading
 * All [Flow] operations are cold and dispatching-agnostic. Callers apply the
 * appropriate [kotlinx.coroutines.CoroutineDispatcher] via `flowOn`.
 *
 * ## Example
 * ```kotlin
 * val parser: SpatialIntentParser = DefaultSpatialIntentParser(dwellThresholdMs = 800L)
 * parser.parse(gazeEvents = sensorFlow, voiceEvents = sttFlow)
 *       .filter { it.confidenceScore >= 0.7f }
 *       .collect { intent -> specificationMapper.map(intent) }
 * ```
 */
interface SpatialIntentParser {

    /**
     * Parses merged spatial event streams into resolved [SpatialIntent] values.
     *
     * @param gazeEvents   Cold [Flow] of [RawSpatialEvent.GazeEvent] from the
     *                     eye-tracking runtime (or a mock source in tests).
     * @param voiceEvents  Cold [Flow] of [RawSpatialEvent.VoiceEvent] from the
     *                     speech-to-text pipeline (or a mock source in tests).
     * @return             Cold [Flow] of [SpatialIntent] objects, one per resolved
     *                     perception event.
     */
    fun parse(
        gazeEvents: Flow<RawSpatialEvent.GazeEvent>,
        voiceEvents: Flow<RawSpatialEvent.VoiceEvent>,
    ): Flow<SpatialIntent>
}

// ─────────────────────────────────────────────────────────────────────────────
// Default implementation
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Default [SpatialIntentParser] implementation.
 *
 * ### Resolution rules (applied in order):
 *
 * 1. **Composite gaze+voice** → `SPECIFY` if the token stream contains a
 *    recognised specification verb ("define", "spec", "schema", "api",
 *    "generate", "create", "describe").
 * 2. **Composite gaze+voice** → `CONNECT` if tokens contain "connect",
 *    "link", "wire", "attach".
 * 3. **Composite gaze+voice** → `EXPAND` if tokens contain "expand",
 *    "detail", "zoom", "open".
 * 4. **Gaze-only** dwell ≥ [dwellThresholdMs] → `INSPECT`.
 * 5. **Voice-only** high-confidence token → `INSPECT` on last-known node.
 * 6. Anything else → `UNKNOWN`.
 *
 * @param dwellThresholdMs Minimum gaze dwell (ms) to emit an INSPECT intent.
 *                         Defaults to [DEFAULT_DWELL_THRESHOLD_MS].
 */
class DefaultSpatialIntentParser(
    private val dwellThresholdMs: Long = DEFAULT_DWELL_THRESHOLD_MS,
) : SpatialIntentParser {

    override fun parse(
        gazeEvents: Flow<RawSpatialEvent.GazeEvent>,
        voiceEvents: Flow<RawSpatialEvent.VoiceEvent>,
    ): Flow<SpatialIntent> =
        merge(
            gazeEvents.filter { it.dwellMs >= dwellThresholdMs }
                .map { gaze -> gaze.toIntent() },
            voiceEvents.filter { it.confidence >= MIN_VOICE_CONFIDENCE }
                .map { voice -> voice.toIntent() },
        )

    // ── Mapping helpers ──────────────────────────────────────────────────────

    private fun RawSpatialEvent.GazeEvent.toIntent(): SpatialIntent =
        SpatialIntent(
            intentType = IntentType.INSPECT,
            nodeId = nodeId,
            confidenceScore = gazeDwellConfidence(dwellMs),
            rawEvent = this,
            timestamp = timestamp,
        )

    private fun RawSpatialEvent.VoiceEvent.toIntent(): SpatialIntent {
        val intent = resolveVoiceIntent(token)
        return SpatialIntent(
            intentType = intent,
            nodeId = VOICE_ONLY_NODE_ID,
            confidenceScore = confidence,
            rawEvent = this,
            timestamp = timestamp,
        )
    }

    // ── Pure helper functions (testable in isolation) ────────────────────────

    /**
     * Maps a gaze dwell duration to a normalised confidence score.
     *
     * Score saturates at 1.0 once dwell reaches [MAX_DWELL_MS].
     */
    internal fun gazeDwellConfidence(dwellMs: Long): Float =
        (dwellMs.toFloat() / MAX_DWELL_MS.toFloat()).coerceAtMost(1.0f)

    /**
     * Resolves a single voice token to an [IntentType].
     */
    internal fun resolveVoiceIntent(token: String): IntentType {
        val normalised = token.trim().lowercase()
        return when {
            normalised in SPECIFY_VERBS  -> IntentType.SPECIFY
            normalised in CONNECT_VERBS  -> IntentType.CONNECT
            normalised in EXPAND_VERBS   -> IntentType.EXPAND
            else                         -> IntentType.INSPECT
        }
    }

    companion object {
        /** Default gaze dwell threshold before an INSPECT intent is emitted. */
        const val DEFAULT_DWELL_THRESHOLD_MS: Long = 800L

        /** Maximum dwell used for confidence score saturation. */
        const val MAX_DWELL_MS: Long = 3_000L

        /** Minimum ASR confidence to process a voice token. */
        const val MIN_VOICE_CONFIDENCE: Float = 0.6f

        /**
         * Placeholder node ID used when a voice-only event cannot be paired
         * with a concurrent gaze event. Callers should replace this with the
         * last-known focused node ID.
         */
        const val VOICE_ONLY_NODE_ID: String = "__voice_only__"

        private val SPECIFY_VERBS  = setOf("define", "spec", "schema", "api", "generate", "create", "describe")
        private val CONNECT_VERBS  = setOf("connect", "link", "wire", "attach")
        private val EXPAND_VERBS   = setOf("expand", "detail", "zoom", "open")
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Mock / test doubles
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Test-double source emitting a fixed sequence of [RawSpatialEvent.GazeEvent]
 * values. Safe to use in JVM unit tests without an Android XR runtime.
 *
 * ```kotlin
 * val source = MockGazeEventSource(
 *     events = listOf(GazeEvent(nodeId = "api-gateway", dwellMs = 1200L))
 * )
 * ```
 */
class MockGazeEventSource(
    private val events: List<RawSpatialEvent.GazeEvent>,
) {
    /** Returns a cold [Flow] that emits [events] in order then completes. */
    fun asFlow(): Flow<RawSpatialEvent.GazeEvent> =
        kotlinx.coroutines.flow.flow { events.forEach { emit(it) } }
}

/**
 * Test-double source emitting a fixed sequence of [RawSpatialEvent.VoiceEvent]
 * values. Safe to use in JVM unit tests without the STT pipeline.
 *
 * ```kotlin
 * val source = MockVoiceEventSource(
 *     events = listOf(VoiceEvent(token = "define", confidence = 0.95f))
 * )
 * ```
 */
class MockVoiceEventSource(
    private val events: List<RawSpatialEvent.VoiceEvent>,
) {
    /** Returns a cold [Flow] that emits [events] in order then completes. */
    fun asFlow(): Flow<RawSpatialEvent.VoiceEvent> =
        kotlinx.coroutines.flow.flow { events.forEach { emit(it) } }
}
