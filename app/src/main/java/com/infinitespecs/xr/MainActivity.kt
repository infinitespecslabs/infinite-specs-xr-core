/*
 * MainActivity.kt
 * infinite-specs-xr-core — App entry point
 *
 * Android XR host activity. Initialises the Jetpack Compose content and
 * connects the perception pipeline to the spatial HUD panel.
 *
 * Android XR Developer Preview 4
 * See: https://developer.android.com/xr
 */

package com.infinitespecs.xr

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.infinitespecs.xr.bridge.McpSpecificationBridge
import com.infinitespecs.xr.perception.SpatialIntentParser
import com.infinitespecs.xr.ui.InfiniteSpecsHudPanel
import com.infinitespecs.xr.ui.NodeCardState
import com.infinitespecs.xr.ui.PanelStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Root activity for the Infinite Specs XR application.
 *
 * Simulates a closed-loop system where physical observer telemetry (voice & gaze)
 * resolves to an architectural intent, gets published over MCP, and streams back
 * to update the display panel states.
 */
class MainActivity : ComponentActivity() {

    // ── Pipeline components ──────────────────────────────────────────────────

    private val parser = SpatialIntentParser()
    private val bridge = McpSpecificationBridge()

    // ── UI state ─────────────────────────────────────────────────────────────

    private val _panelStatus = MutableStateFlow(PanelStatus.IDLE)
    private val _nodes = MutableStateFlow<List<NodeCardState>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Wire up the bridge output stream to update the UI State
        bridge.outboundSpecificationStream
            .onEach { payloadJson ->
                // Transition UI to STREAMING when a payload is active
                _panelStatus.value = PanelStatus.STREAMING
                
                // Map the parsed intent details to a NodeCardState displayed in the HUD
                val intent = parser.parseTokensToSchemaConstraint(
                    voiceTranscript = "Declare an asynchronous consumer tracking the stage rig left",
                    gazeVector = floatArrayOf(0.12f, 0.85f, -0.44f)
                )
                
                _nodes.value = listOf(
                    NodeCardState(
                        nodeId = "event-bridge",
                        label = intent.nodeType,
                        nodeType = intent.nodeType,
                        physicalAnchorId = intent.physicalAnchorId,
                        semanticConstraints = intent.semanticConstraints,
                        loopEngineeringSkillTemplate = intent.loopEngineeringSkillTemplate,
                        isActive = true
                    )
                )
            }
            .launchIn(lifecycleScope)

        // Simulate a physical telemetry trigger
        lifecycleScope.launch {
            _panelStatus.value = PanelStatus.PROCESSING
            delay(1500L) // Simulate some telemetry processing latency
            
            val intent = parser.parseTokensToSchemaConstraint(
                voiceTranscript = "Declare an asynchronous consumer tracking the stage rig left",
                gazeVector = floatArrayOf(0.12f, 0.85f, -0.44f)
            )
            
            bridge.streamIntentToAutonomousAgentWorktree(intent)
        }

        setContent {
            MaterialTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    val nodes by _nodes.collectAsState()
                    val status by _panelStatus.collectAsState()
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        InfiniteSpecsHudPanel(nodes = nodes, status = status)
                    }
                }
            }
        }
    }
}
