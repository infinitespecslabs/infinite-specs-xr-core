package com.infinitespecs.xr.bridge

import com.infinitespecs.xr.perception.SpatialIntentParser.ArchitecturalIntent
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Acts as an internal Model Context Protocol (MCP) daemon interface.
 * Hosts a Ktor SSE server to stream structured JSON schema constraints to
 * localized autonomous agent loops (IDE plugins, CLI agents).
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

    private val server = embeddedServer(CIO, port = 8080) {
        install(SSE)
        install(ContentNegotiation) {
            json()
        }
        routing {
            sse("/mcp/sse") {
                // When a client connects, start collecting from our outbound flow
                _outboundSpecificationStream.collectLatest { payload ->
                    send(ServerSentEvent(data = payload, event = "specification"))
                }
            }

            post("/mcp/logs") {
                val log = call.receiveText()
                _inboundLogStream.emit(log)
                call.respondText("Log received")
            }
        }
    }

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
