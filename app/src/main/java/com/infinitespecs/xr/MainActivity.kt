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
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.xr.arcore.ArDevice
import androidx.xr.compose.platform.LocalSession
import androidx.xr.compose.spatial.Subspace
import androidx.xr.compose.subspace.SpatialPanel
import androidx.xr.compose.subspace.layout.SubspaceModifier
import androidx.xr.compose.subspace.layout.height
import androidx.xr.compose.subspace.layout.transformingMovable
import androidx.xr.compose.subspace.layout.width
import androidx.xr.runtime.Session
import androidx.xr.runtime.math.Ray
import androidx.xr.runtime.math.Vector3
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
import kotlin.time.Duration.Companion.milliseconds

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
    private val _logs = MutableStateFlow<List<String>>(emptyList())

    private var session: Session? = null

    /**
     * Triggers the mock perception pipeline.
     * Maps physical telemetry to architectural intent and streams it over MCP.
     */
    private fun triggerPerceptionPipeline() {
        val currentSession = session ?: return
        val arDevice = ArDevice.getInstance(currentSession)
        val headPose = arDevice.state.value.devicePose
        val gazeRay = Ray(headPose.translation, headPose.forward)

        lifecycleScope.launch {
            _panelStatus.value = PanelStatus.PROCESSING
            delay(1500.milliseconds) // Simulate some telemetry processing latency

            val intent = parser.parseTokensToSchemaConstraint(
                voiceTranscript = "Declare an asynchronous consumer tracking the stage rig left",
                gazeRay = gazeRay,
            )

            bridge.streamIntentToAutonomousAgentWorktree(intent)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bridge.stop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Start the MCP daemon server
        bridge.start()

        // Wire up the bridge output stream to update the UI State
        bridge.outboundSpecificationStream
            .onEach {
                // Transition UI to STREAMING when a payload is active
                _panelStatus.value = PanelStatus.STREAMING

                // Note: In a real app, we would parse the payload back into an Intent
                // For this prototype, we just refresh the UI with a consistent state
                val intent = parser.parseTokensToSchemaConstraint(
                    voiceTranscript = "Declare an asynchronous consumer tracking the stage rig left",
                    gazeRay = Ray(Vector3.Zero, Vector3(0f, 0f, -1f)),
                )

                _nodes.value = listOf(
                    NodeCardState(
                        nodeId = "event-bridge",
                        label = intent.nodeType,
                        nodeType = intent.nodeType,
                        physicalAnchorId = intent.physicalAnchorId,
                        semanticConstraints = intent.semanticConstraints,
                        loopEngineeringSkillTemplate = intent.loopEngineeringSkillTemplate,
                        isActive = true,
                    ),
                )
            }
            .launchIn(lifecycleScope)

        // Listen for inbound logs from external agents
        bridge.inboundLogStream
            .onEach { log ->
                _logs.value = (_logs.value + log).takeLast(5)
            }
            .launchIn(lifecycleScope)

        setContent {
            session = LocalSession.current
            MaterialTheme {
                Subspace {
                    SpatialPanel(
                        modifier = SubspaceModifier
                            .width(640.dp)
                            .height(480.dp)
                            .transformingMovable(),
                    ) {
                        val nodes by _nodes.collectAsState()
                        val status by _panelStatus.collectAsState()
                        val logs by _logs.collectAsState()
                        InfiniteSpecsHudPanel(
                            nodes = nodes,
                            status = status,
                            logs = logs,
                        ) { triggerPerceptionPipeline() }
                    }
                }
            }
        }
    }
}
