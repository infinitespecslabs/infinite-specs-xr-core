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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.infinitespecs.xr.bridge.InMemoryMcpBridge
import com.infinitespecs.xr.perception.DefaultSpatialIntentParser
import com.infinitespecs.xr.perception.MockGazeEventSource
import com.infinitespecs.xr.perception.MockVoiceEventSource
import com.infinitespecs.xr.perception.RawSpatialEvent
import com.infinitespecs.xr.ui.InfiniteSpecsHudPanel
import com.infinitespecs.xr.ui.NodeCardState
import com.infinitespecs.xr.ui.PanelStatus
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Root activity for the Infinite Specs XR application.
 *
 * ## XR Integration (Developer Preview 4)
 *
 * In a full XR session the [InfiniteSpecsHudPanel] composable would be hosted
 * inside a `Subspace { SpatialPanel { … } }` block provided by
 * `androidx.xr.compose`. The spatial panel floats in the developer's field of
 * view at ~80 cm depth.
 *
 * For non-XR environments (standard emulator / 2-D phone) the panel is
 * rendered as a standard Compose surface, which is the default behaviour here.
 */
class MainActivity : ComponentActivity() {

    // ── Pipeline components ──────────────────────────────────────────────────

    private val parser = DefaultSpatialIntentParser()
    private val bridge = InMemoryMcpBridge()

    // ── Mock sensor sources (replace with real XR runtime sources on device) ─

    private val mockGazeSource = MockGazeEventSource(
        listOf(
            RawSpatialEvent.GazeEvent(nodeId = "api-gateway",  dwellMs = 1_200L),
            RawSpatialEvent.GazeEvent(nodeId = "auth-service", dwellMs = 900L),
            RawSpatialEvent.GazeEvent(nodeId = "data-store",   dwellMs = 2_000L),
        ),
    )

    private val mockVoiceSource = MockVoiceEventSource(
        listOf(
            RawSpatialEvent.VoiceEvent(token = "define",  confidence = 0.92f),
            RawSpatialEvent.VoiceEvent(token = "connect", confidence = 0.85f),
        ),
    )

    // ── UI state ─────────────────────────────────────────────────────────────

    private val _panelStatus = MutableStateFlow(PanelStatus.IDLE)
    private val _nodes = MutableStateFlow<List<NodeCardState>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // TODO: Open and retain a BridgeSession when wiring the bridge to an active spec stream.

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

    override fun onDestroy() {
        super.onDestroy()
        bridge.openSession().close()
    }
}
