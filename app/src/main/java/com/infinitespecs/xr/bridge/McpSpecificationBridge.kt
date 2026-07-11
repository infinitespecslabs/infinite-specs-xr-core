package com.infinitespecs.xr.bridge

import com.infinitespecs.xr.perception.SpatialIntentParser.ArchitecturalIntent
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Acts as an internal Model Context Protocol (MCP) daemon interface.
 * Hosts a Ktor server with a manual SSE implementation to stream structured JSON
 * schema constraints to localized autonomous agent loops (IDE plugins, CLI agents).
 */
class McpSpecificationBridge {

    private val _outboundSpecificationStream = MutableSharedFlow<String>()

    /**
     * Observable stream of specifications emitted by the bridge.
     * Used by the local UI to reflect the bridge state.
     */
    val outboundSpecificationStream: Flow<String> = _outboundSpecificationStream

    private val _inboundLogStream = MutableSharedFlow<String>()

    /**
     * Observable stream of logs/messages received from external agents.
     */
    val inboundLogStream: Flow<String> = _inboundLogStream

    private val _inboundStateStream = MutableSharedFlow<AgentStatePayload>()

    /**
     * Observable stream of state payloads received from external agents.
     */
    val inboundStateStream: Flow<AgentStatePayload> = _inboundStateStream

    private val server = embeddedServer(CIO, port = 8080) {
        install(ContentNegotiation) {
            json()
        }
        routing {
            get("/mcp/sse") {
                call.respondBytesWriter(contentType = ContentType.Text.EventStream) {
                    // Send connection header
                    writeStringUtf8("event: connected\ndata: MCP Specification Daemon Active\n\n")
                    flush()

                    // Stream specifications to the connected client
                    _outboundSpecificationStream.collectLatest { payload ->
                        val singleLinePayload = payload.replace("\n", "").replace("\r", "")
                        writeStringUtf8("event: specification\n")
                        writeStringUtf8("data: $singleLinePayload\n\n")
                        flush()
                    }
                }
            }

            post("/mcp/logs") {
                val log = call.receiveText()
                _inboundLogStream.emit(log)
                call.respondText("Log received")
            }

            post("/mcp/agent-state") {
                val payloadText = call.receiveText()
                try {
                    val payload = Json.decodeFromString<AgentStatePayload>(payloadText)
                    _inboundStateStream.emit(payload)
                    call.respondText("State updated")
                } catch (e: Exception) {
                    call.respondText("Error: ${e.message}", status = io.ktor.http.HttpStatusCode.BadRequest)
                }
            }
        }
    }

    @Serializable
    data class AgentStatePayload(
        val state: String,
        val prompt: String = "",
        val options: List<String> = emptyList(),
        val log: String = ""
    )

    /**
     * Starts the MCP daemon server.
     */
    fun start() {
        server.start(wait = false)
    }

    /**
     * Stops the MCP daemon server.
     */
    fun stop() {
        server.stop(1000, 2000)
    }

    /**
     * Serializes spatial intent into an idempotent MCP specification payload.
     * This payload is broadcast to all connected SSE clients (e.g., Cursor, Claude Code).
     */
    suspend fun streamIntentToAutonomousAgentWorktree(intent: ArchitecturalIntent) {
        val rulesJson = Json.encodeToString(intent.semanticConstraints)
        val spatialContextJson = Json.encodeToString(intent.spatialContext)
        val formattedMcpPayload = """
            {
              "mcp_protocol_version": "2026-06-01",
              "intent_node": "${intent.nodeType}",
              "spatial_context_id": "${intent.physicalAnchorId}",
              "loop_constraints": {
                 "rules": $rulesJson,
                 "target_skill": "${intent.loopEngineeringSkillTemplate}"
              },
              "real_world_telemetry": $spatialContextJson
            }
        """.trimIndent()

        _outboundSpecificationStream.emit(formattedMcpPayload)
    }
}
