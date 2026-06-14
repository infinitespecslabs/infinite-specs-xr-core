/*
 * McpSpecificationBridge.kt
 * infinite-specs-xr-core — Connection Layer
 *
 * Reactive outbound streaming bridge that publishes structured specification
 * payloads (OpenAPI 3.1 fragments or MCP ToolDefinition envelopes) to
 * external IDE / AI-agent consumers over Server-Sent Events (SSE).
 *
 * Android XR Developer Preview 4
 * See: https://developer.android.com/xr
 *      https://modelcontextprotocol.io/specification
 *      https://ktor.io/docs/server-server-sent-events.html
 *
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │  CONNECTION LAYER CONTRACT                                               │
 * │                                                                          │
 * │  SpecPayload (from Schema Translation Layer)                             │
 * │         │                                                                │
 * │         ▼                                                                │
 * │  McpSpecificationBridge.stream()                                         │
 * │         │                                                                │
 * │         ▼                                                                │
 * │  Flow<McpEnvelope>  ──SSE──▶  IDE Extension / Remote AI Agent           │
 * └─────────────────────────────────────────────────────────────────────────┘
 */

package com.infinitespecs.xr.bridge

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

// ─────────────────────────────────────────────────────────────────────────────
// Specification payload domain types
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Sealed hierarchy of structured specification payloads produced by the
 * Schema Translation Layer and consumed by [McpSpecificationBridge].
 */
sealed class SpecPayload {

    /**
     * An OpenAPI 3.1 specification fragment describing a single architecture
     * component (e.g., a REST endpoint, a data schema, a service contract).
     *
     * @param nodeId       The architecture node this fragment describes.
     * @param openApiJson  Serialised OpenAPI 3.1 JSON fragment (not a full doc).
     * @param version      Spec revision counter; incremented on each update.
     */
    data class OpenApiSpec(
        val nodeId: String,
        val openApiJson: String,
        val version: Int = 1,
    ) : SpecPayload()

    /**
     * A Model Context Protocol `ToolDefinition` envelope describing a
     * callable tool or resource exposed by the architecture node.
     *
     * @param nodeId          The architecture node this tool definition describes.
     * @param toolName        Unique tool name used in JSON-RPC method routing.
     * @param description     Human-readable tool description for the LLM prompt.
     * @param inputSchemaJson JSON Schema string for the tool's input parameters.
     * @param version         Spec revision counter.
     */
    data class McpToolSpec(
        val nodeId: String,
        val toolName: String,
        val description: String,
        val inputSchemaJson: String,
        val version: Int = 1,
    ) : SpecPayload()
}

// ─────────────────────────────────────────────────────────────────────────────
// SSE / MCP envelope
// ─────────────────────────────────────────────────────────────────────────────

/**
 * JSON-RPC 2.0–compatible envelope wrapping a [SpecPayload] for SSE transport.
 *
 * SSE event format (one envelope per `data:` line):
 * ```
 * event: mcp-spec
 * data: {"jsonrpc":"2.0","id":"<id>","method":"spec/push","params":{...}}
 * ```
 *
 * @param jsonrpc  Protocol version — always `"2.0"`.
 * @param id       Monotonically increasing event sequence identifier.
 * @param method   RPC method name (e.g., `"spec/push"`).
 * @param params   Serialised [SpecPayload] parameters.
 */
@Serializable
data class McpEnvelope(
    val jsonrpc: String = "2.0",
    val id: Long,
    val method: String,
    val params: McpParams,
)

/**
 * Parameters block inside an [McpEnvelope].
 *
 * @param nodeId      Architecture node the spec describes.
 * @param specType    `"openapi"` or `"mcp-tool"`.
 * @param version     Spec revision counter.
 * @param payloadJson Serialised spec content (OpenAPI fragment or MCP tool def).
 */
@Serializable
data class McpParams(
    val nodeId: String,
    val specType: String,
    val version: Int,
    val payloadJson: String,
)

// ─────────────────────────────────────────────────────────────────────────────
// Bridge session handle
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Lifecycle-safe handle representing an active [McpSpecificationBridge] session.
 *
 * Callers retain this handle and invoke [close] from the appropriate Android
 * lifecycle scope (e.g., `onStop`, `viewModelScope` cancellation) to terminate
 * the outbound SSE stream without leaking coroutines or sockets.
 */
interface BridgeSession : AutoCloseable {
    /** True while the session is active and the SSE connection is open. */
    val isOpen: Boolean

    /**
     * Closes the bridge session, cancelling the underlying coroutine job and
     * closing the SSE server connection.
     */
    override fun close()
}

// ─────────────────────────────────────────────────────────────────────────────
// Bridge interface
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Outbound streaming bridge for pushing structured [SpecPayload] objects to
 * external MCP-aware consumers (IDE extensions, AI coding agents) over SSE.
 *
 * ## Transport
 * Default implementation (`SseMcpBridgeImpl`) binds a Ktor CIO HTTP server
 * on localhost and streams JSON-RPC 2.0 envelopes as SSE events. External
 * clients connect via:
 * ```
 * GET http://localhost:<port>/mcp/stream
 * Accept: text/event-stream
 * ```
 *
 * ## Back pressure
 * [stream] returns a cold [Flow]. Callers may apply any standard coroutine
 * operator (debounce, conflate, buffer) before collecting or forwarding to
 * the Ktor SSE emitter.
 *
 * ## Usage
 * ```kotlin
 * val bridge: McpSpecificationBridge = SseMcpBridgeImpl(port = 8765)
 * val session: BridgeSession = bridge.openSession()
 *
 * // Wire up the schema translation layer output:
 * specificationMapper.specFlow
 *     .onEach { payload -> bridge.publish(payload) }
 *     .launchIn(lifecycleScope)
 *
 * // On lifecycle end:
 * session.close()
 * ```
 */
interface McpSpecificationBridge {

    /**
     * Opens a new [BridgeSession] and starts the SSE server.
     *
     * Only one session may be open at a time. Calling [openSession] while a
     * session is already open replaces the previous one (closing it first).
     *
     * @return A [BridgeSession] handle. Callers must invoke [BridgeSession.close]
     *         when the session is no longer needed.
     */
    fun openSession(): BridgeSession

    /**
     * Publishes a [SpecPayload] into the active session's SSE stream.
     *
     * The payload is wrapped in an [McpEnvelope] and emitted as a
     * `data:` line on the open SSE connection. If no session is open
     * the call is a no-op.
     *
     * This function is safe to call from any coroutine dispatcher.
     *
     * @param payload The [SpecPayload] to publish.
     */
    suspend fun publish(payload: SpecPayload)

    /**
     * Returns a cold [Flow] of [McpEnvelope] objects reflecting every
     * [SpecPayload] published since the flow was collected.
     *
     * Useful for testing, logging, or chaining additional operators before
     * the envelopes reach the SSE transport layer.
     */
    fun stream(): Flow<McpEnvelope>
}

// ─────────────────────────────────────────────────────────────────────────────
// In-memory stub implementation (no Ktor dependency at compile time)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * In-memory (no-network) implementation of [McpSpecificationBridge].
 *
 * Serialises [SpecPayload] objects to JSON-RPC 2.0 envelopes and emits them
 * on an internal [MutableSharedFlow] without opening any network sockets.
 *
 * **Use cases:**
 * - Unit / integration tests (no Ktor server required)
 * - CI pipeline runs (no port binding required)
 * - Compose Preview data feeding
 *
 * **Production use:** Replace with `SseMcpBridgeImpl` (Ktor CIO + SSE plugin).
 */
class InMemoryMcpBridge : McpSpecificationBridge {

    private val _envelopes = MutableSharedFlow<McpEnvelope>(replay = 0, extraBufferCapacity = 64)
    private val sequenceId = java.util.concurrent.atomic.AtomicLong(0L)
    private var currentSession: InMemoryBridgeSession? = null

    override fun openSession(): BridgeSession {
        currentSession?.close()
        return InMemoryBridgeSession().also { currentSession = it }
    }

    override suspend fun publish(payload: SpecPayload) {
        if (currentSession?.isOpen != true) return
        val envelope = payload.toEnvelope(id = sequenceId.incrementAndGet())
        _envelopes.emit(envelope)
    }

    override fun stream(): Flow<McpEnvelope> = _envelopes.asSharedFlow()

    // ── Serialisation helpers ────────────────────────────────────────────────

    private fun SpecPayload.toEnvelope(id: Long): McpEnvelope = when (this) {
        is SpecPayload.OpenApiSpec -> McpEnvelope(
            id = id,
            method = MCP_METHOD_SPEC_PUSH,
            params = McpParams(
                nodeId = nodeId,
                specType = SPEC_TYPE_OPENAPI,
                version = version,
                payloadJson = openApiJson,
            ),
        )
        is SpecPayload.McpToolSpec -> McpEnvelope(
            id = id,
            method = MCP_METHOD_SPEC_PUSH,
            params = McpParams(
                nodeId = nodeId,
                specType = SPEC_TYPE_MCP_TOOL,
                version = version,
                payloadJson = Json.encodeToString(
                    McpToolDefinitionJson(
                        name = toolName,
                        description = description,
                        // Parse the JSON Schema string into a structured JsonObject so the
                        // serialised envelope contains a proper JSON object rather than an
                        // escaped string literal. Throws SerializationException on malformed input.
                        inputSchema = Json.parseToJsonElement(inputSchemaJson).jsonObject,
                    )
                ),
            ),
        )
    }

    // ── Inner session handle ─────────────────────────────────────────────────

    private inner class InMemoryBridgeSession : BridgeSession {
        override var isOpen: Boolean = true
            private set

        override fun close() {
            isOpen = false
        }
    }

    companion object {
        private const val MCP_METHOD_SPEC_PUSH = "spec/push"
        private const val SPEC_TYPE_OPENAPI    = "openapi"
        private const val SPEC_TYPE_MCP_TOOL   = "mcp-tool"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Internal serialisation helpers
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Minimal JSON representation of an MCP `ToolDefinition` used when
 * serialising [SpecPayload.McpToolSpec] into an [McpEnvelope].
 *
 * @param name        Unique tool name for JSON-RPC method routing.
 * @param description Human-readable description for the LLM prompt.
 * @param inputSchema Structured [JsonObject] representing the JSON Schema
 *                    for the tool's input parameters. Using [JsonObject]
 *                    (rather than a raw [String]) ensures the schema is
 *                    well-formed JSON and prevents double-encoded strings in
 *                    the serialised MCP envelope.
 */
@Serializable
internal data class McpToolDefinitionJson(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
)

// ─────────────────────────────────────────────────────────────────────────────
// Ktor SSE bridge stub (wiring comment — full impl requires ktor-server-sse)
// ─────────────────────────────────────────────────────────────────────────────

/*
 * SseMcpBridgeImpl — production Ktor SSE implementation sketch
 *
 * ```kotlin
 * class SseMcpBridgeImpl(private val port: Int = 8765) : McpSpecificationBridge {
 *
 *     private val delegate = InMemoryMcpBridge()
 *
 *     private val server: ApplicationEngine = embeddedServer(CIO, port = port) {
 *         install(SSE)
 *         routing {
 *             sse("/mcp/stream") {
 *                 delegate.stream().collect { envelope ->
 *                     send(
 *                         ServerSentEvent(
 *                             data  = Json.encodeToString(envelope),
 *                             event = "mcp-spec",
 *                             id    = envelope.id.toString(),
 *                         )
 *                     )
 *                 }
 *             }
 *         }
 *     }
 *
 *     override fun openSession(): BridgeSession {
 *         server.start(wait = false)
 *         return delegate.openSession()
 *     }
 *
 *     override suspend fun publish(payload: SpecPayload) = delegate.publish(payload)
 *     override fun stream(): Flow<McpEnvelope>           = delegate.stream()
 * }
 * ```
 *
 * SSE client snippet (VS Code extension / Node.js MCP client):
 * ```javascript
 * const source = new EventSource("http://localhost:8765/mcp/stream");
 * source.addEventListener("mcp-spec", (e) => {
 *   const envelope = JSON.parse(e.data);   // McpEnvelope
 *   applySpecToEditor(envelope.params);
 * });
 * ```
 */
