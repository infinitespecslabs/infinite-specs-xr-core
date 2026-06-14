package com.infinitespecs.xr.bridge

import com.infinitespecs.xr.perception.SpatialIntentParser.ArchitecturalIntent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Acts as an internal Model Context Protocol (MCP) daemon interface.
 * Streams structured JSON schema constraints directly to localized autonomous agent loops
 * running inside active workspace IDE environments or remote cloud worktrees.
 */
class McpSpecificationBridge {

    private val _outboundSpecificationStream = MutableSharedFlow<String>()
    val outboundSpecificationStream: Flow<String> = _outboundSpecificationStream

    /**
     * Serializes spatial intent into an idempotent OpenAPI specification file variant.
     * This file gets dropped directly into the local project directory, triggering the 
     * background loop execution.
     */
    suspend fun streamIntentToAutonomousAgentWorktree(intent: ArchitecturalIntent) {
        val rulesJson = Json.encodeToString(intent.semanticConstraints)
        val formattedMcpPayload = """
            {
              "mcp_protocol_version": "2026-06-01",
              "intent_node": "${intent.nodeType}",
              "spatial_context_id": "${intent.physicalAnchorId}",
              "loop_constraints": {
                 "rules": $rulesJson,
                 "target_skill": "${intent.loopEngineeringSkillTemplate}"
              }
            }
        """.trimIndent()

        _outboundSpecificationStream.emit(formattedMcpPayload)
    }
}
