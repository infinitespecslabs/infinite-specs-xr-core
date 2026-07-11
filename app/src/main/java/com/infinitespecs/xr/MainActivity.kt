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

import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.xr.arcore.ArDevice
import androidx.xr.arcore.hitTest
import androidx.xr.compose.platform.LocalSession
import androidx.xr.compose.spatial.Subspace
import androidx.xr.compose.subspace.SpatialPanel
import androidx.xr.compose.subspace.layout.SubspaceModifier
import androidx.xr.compose.subspace.layout.height
import androidx.xr.compose.subspace.layout.transformingMovable
import androidx.xr.compose.subspace.layout.width
import androidx.xr.runtime.DeviceTrackingMode
import androidx.xr.runtime.PlaneTrackingMode
import androidx.xr.runtime.Session
import androidx.xr.runtime.math.Ray
import androidx.xr.runtime.math.Vector3
import com.infinitespecs.xr.bridge.McpSpecificationBridge
import com.infinitespecs.xr.bridge.McpSpecificationBridge.AgentStatePayload
import com.infinitespecs.xr.perception.SpatialIntentParser
import com.infinitespecs.xr.ui.InfiniteSpecsTerminalHudPanel
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

    private val _agentState = MutableStateFlow("OFFLINE")
    private val _inputPrompt = MutableStateFlow("")
    private val _inputOptions = MutableStateFlow<List<String>>(emptyList())
    private val _logs = MutableStateFlow<List<String>>(emptyList())

    private var session: Session? = null

    private val planePermission = "android.permission.SCENE_UNDERSTANDING"
    private val planeCoarsePermission = "android.permission.SCENE_UNDERSTANDING_COARSE"
    private val cameraPermission = "android.permission.CAMERA"
    private val eyePermission = "android.permission.EYE_TRACKING_FINE"
    private val handPermission = "android.permission.HAND_TRACKING"
    private val audioPermission = "android.permission.RECORD_AUDIO"

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val planeGranted = permissions[planePermission] ?: permissions[planeCoarsePermission] ?: false
            val cameraGranted = permissions[cameraPermission] ?: false
            val eyeGranted = permissions[eyePermission] ?: false
            val handGranted = permissions[handPermission] ?: false
            val audioGranted = permissions[audioPermission] ?: false

            if (planeGranted) {
                _logs.value = (_logs.value + "Permission granted: Plane tracking active").takeLast(5)
            }
            if (cameraGranted) {
                _logs.value = (_logs.value + "Permission granted: Camera tracking active").takeLast(5)
            }
            if (eyeGranted) {
                _logs.value = (_logs.value + "Permission granted: Eye tracking active").takeLast(5)
            }
            if (handGranted) {
                _logs.value = (_logs.value + "Permission granted: Hand tracking active").takeLast(5)
            }
            if (audioGranted) {
                _logs.value = (_logs.value + "Permission granted: Audio recording active").takeLast(5)
            }

            configureSession(enablePlanes = planeGranted)
        }

    private fun configureSession(enablePlanes: Boolean = true) {
        val s = session ?: return

        val newConfig = s.config.copy(
            deviceTracking = DeviceTrackingMode.SPATIAL_LAST_KNOWN,
            planeTracking = if (enablePlanes) {
                PlaneTrackingMode.HORIZONTAL_AND_VERTICAL
            } else {
                PlaneTrackingMode.DISABLED
            },
        )

        lifecycleScope.launch {
            try {
                s.configure(newConfig)
            } catch (e: SecurityException) {
                Log.e("MainActivity", "Security Exception during session configuration", e)
                _logs.value = (_logs.value + "Security Error: Missing permissions").takeLast(5)
                // Fallback to basic tracking if planes fail
                if (enablePlanes) configureSession(enablePlanes = false)
            } catch (e: Exception) {
                Log.e("MainActivity", "Error configuring session", e)
                _logs.value = (_logs.value + "Error configuring session: ${e.message}").takeLast(5)
            }
        }
    }

    /**
     * Triggers the mock perception pipeline.
     * Maps physical telemetry to architectural intent and streams it over MCP.
     */
    private fun triggerPerceptionPipeline() {
        Log.d("MainActivity", "triggerPerceptionPipeline called")
        val currentSession = session ?: run {
            Log.e("MainActivity", "Session is null")
            return
        }

        // Ensure tracking is active before proceeding
        val arDevice = try {
            ArDevice.getInstance(currentSession)
        } catch (_: IllegalStateException) {
            Log.w("MainActivity", "Tracking not active")
            _logs.value = (_logs.value + "Error: Tracking not active").takeLast(5)
            return
        }

        val headPose = arDevice.state.value.devicePose
        val gazeRay = Ray(headPose.translation, headPose.forward)

        // Perform a spatial hit-test against the environment if plane tracking is active
        val planeTrackingActive = currentSession.config.planeTracking != PlaneTrackingMode.DISABLED
        val primaryHit = if (planeTrackingActive) {
            try {
                hitTest(currentSession, gazeRay).firstOrNull()
            } catch (e: Exception) {
                Log.w("MainActivity", "Hit-test failed", e)
                null
            }
        } else {
            Log.d("MainActivity", "Plane tracking is disabled; skipping hitTest")
            null
        }

        val physicalAnchorId = primaryHit?.let { "anchor_${it.trackable.hashCode()}" } ?: "floating_context"

        if (primaryHit != null) {
            _logs.value = (_logs.value + "Hit detected: ${primaryHit.distance.format(2)}m").takeLast(5)
        } else if (!planeTrackingActive) {
            _logs.value = (_logs.value + "Planes disabled: using floating context").takeLast(5)
        }

        lifecycleScope.launch {
            _agentState.value = "THINKING"
            _inputPrompt.value = ""
            _inputOptions.value = emptyList()
            delay(1500.milliseconds) // Simulate some telemetry processing latency

            val intent = parser.parseTokensToSchemaConstraint(
                voiceTranscript = "Declare an asynchronous consumer tracking the stage rig left",
                gazeRay = gazeRay,
            ).copy(physicalAnchorId = physicalAnchorId)

            bridge.streamIntentToAutonomousAgentWorktree(intent)
        }
    }

    /**
     * Submits user choice back to the Macbook agent loop.
     */
    private fun submitAgentInput(selectedOption: String) {
        _logs.value = (_logs.value + "Submitting choice: $selectedOption").takeLast(5)
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val url = java.net.URL("http://10.0.2.2:3000/api/agent-input")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                
                val jsonPayload = "{\"selectedOption\":\"$selectedOption\"}"
                conn.outputStream.use { os ->
                    os.write(jsonPayload.toByteArray())
                }
                
                val responseCode = conn.responseCode
                if (responseCode == 200) {
                    _logs.value = (_logs.value + "Workstation acknowledged choice").takeLast(5)
                } else {
                    _logs.value = (_logs.value + "Workstation error: $responseCode").takeLast(5)
                }
                conn.disconnect()
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to submit agent input", e)
                _logs.value = (_logs.value + "Input delivery failed: ${e.message}").takeLast(5)
            }
        }
    }

    private fun Float.format(digits: Int) = "%.${digits}f".format(this)

    override fun onDestroy() {
        super.onDestroy()
        bridge.stop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Start the MCP daemon server
        bridge.start()
        _agentState.value = "IDLE"

        // Wire up the bridge output stream to update the UI State
        bridge.outboundSpecificationStream
            .onEach {
                _agentState.value = "THINKING"
                _inputPrompt.value = ""
                _inputOptions.value = emptyList()
            }
            .launchIn(lifecycleScope)

        // Listen for inbound state stream from external agents
        bridge.inboundStateStream
            .onEach { payload ->
                _agentState.value = payload.state
                _inputPrompt.value = payload.prompt
                _inputOptions.value = payload.options
                if (payload.log.isNotEmpty()) {
                    _logs.value = (_logs.value + payload.log).takeLast(5)
                }
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
            LaunchedEffect(session) {
                if (session != null) {
                    val permissionsToRequest = arrayOf(
                        planePermission,
                        planeCoarsePermission,
                        cameraPermission,
                        eyePermission,
                        handPermission,
                        audioPermission,
                    )

                    val allGranted = permissionsToRequest.all {
                        ContextCompat.checkSelfPermission(this@MainActivity, it) == PackageManager.PERMISSION_GRANTED
                    }

                    if (!allGranted) {
                        requestPermissionLauncher.launch(permissionsToRequest)
                    } else {
                        configureSession()
                    }
                }
            }

            MaterialTheme {
                Subspace {
                    SpatialPanel(
                        modifier = SubspaceModifier
                            .width(520.dp)
                            .height(380.dp)
                            .transformingMovable(),
                    ) {
                        val agentState by _agentState.collectAsState()
                        val prompt by _inputPrompt.collectAsState()
                        val options by _inputOptions.collectAsState()
                        val logs by _logs.collectAsState()
                        InfiniteSpecsTerminalHudPanel(
                            agentState = agentState,
                            prompt = prompt,
                            options = options,
                            logs = logs,
                            onOptionSelected = { option -> submitAgentInput(option) },
                            onTrigger = { triggerPerceptionPipeline() }
                        )
                    }
                }
            }
        }
    }
}
