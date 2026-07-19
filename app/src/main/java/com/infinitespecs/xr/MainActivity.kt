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
import com.infinitespecs.xr.perception.SpeechTranscriptionEngine
import com.infinitespecs.xr.perception.LocalAndroidSpeechEngine
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
    private lateinit var speechEngine: SpeechTranscriptionEngine

    // ── UI state ─────────────────────────────────────────────────────────────

    private val _agentState = MutableStateFlow("OFFLINE")
    private val _inputPrompt = MutableStateFlow("")
    private val _inputOptions = MutableStateFlow<List<String>>(emptyList())
    private val _inputDetail = MutableStateFlow("")
    private val _isListening = MutableStateFlow(false)
    private val _promptInput = MutableStateFlow("")
    private val _viewMode = MutableStateFlow("SPACE")
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

    private fun submitAgentInput(selectedOption: String) {
        _logs.value = (_logs.value + "Submitting choice: $selectedOption").takeLast(5)
        if (bridge.lastPermissionRequest != null) {
            bridge.submitPermissionResponse(selectedOption)
        } else if (bridge.lastQuestionRequest != null) {
            bridge.submitQuestionResponse(selectedOption)
        }
    }

    private fun startVoiceDictation() {
        _isListening.value = true
        speechEngine.startListening(
            onResult = { text ->
                _isListening.value = false
                if (text.isNotEmpty()) {
                    _promptInput.value = text
                    _logs.value = (_logs.value + "Dictated: \"$text\"").takeLast(5)
                }
            },
            onError = { err ->
                _isListening.value = false
                _logs.value = (_logs.value + "STT: $err").takeLast(5)
                
                // Inject fallback simulated text on emulator if STT is not supported/configured
                if (err.contains("not available", ignoreCase = true) || err.contains("Client", ignoreCase = true)) {
                    val fallbackText = "Run debug build and test stage rig left"
                    _promptInput.value = fallbackText
                    _logs.value = (_logs.value + "Emulator STT: \"$fallbackText\"").takeLast(5)
                }
            }
        )
    }

    private fun stopVoiceDictation() {
        speechEngine.stopListening()
        _isListening.value = false
    }

    private fun Float.format(digits: Int) = "%.${digits}f".format(this)

    override fun onDestroy() {
        super.onDestroy()
        bridge.stop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        speechEngine = LocalAndroidSpeechEngine(this)

        // Start the MCP daemon server
        bridge.start()
        _agentState.value = "IDLE"



        // Listen for inbound state stream from external agents
        bridge.inboundStateStream
            .onEach { payload ->
                _agentState.value = payload.state
                _inputPrompt.value = payload.prompt
                _inputOptions.value = payload.options
                _inputDetail.value = payload.detail
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
                        val agentState by _agentState.collectAsState()
                        val prompt by _inputPrompt.collectAsState()
                        val options by _inputOptions.collectAsState()
                        val detail by _inputDetail.collectAsState()
                        val isListening by _isListening.collectAsState()
                        val promptInput by _promptInput.collectAsState()
                        val viewMode by _viewMode.collectAsState()
                        val logs by _logs.collectAsState()
                        val connectionState by bridge.connectionState.collectAsState()
                        val sessions by bridge.sessionsFlow.collectAsState()
                        val sessionsFetched by bridge.sessionsFetched.collectAsState()

                        val panelModifier = SubspaceModifier
                            .width(520.dp)
                            .height(380.dp)
                            .let { modifier ->
                                if (viewMode == "SPACE") {
                                    modifier.transformingMovable()
                                } else {
                                    modifier
                                }
                            }

                        SpatialPanel(
                            modifier = panelModifier,
                        ) {
                            InfiniteSpecsTerminalHudPanel(
                                agentState = agentState,
                                prompt = prompt,
                                detail = detail,
                                options = options,
                                logs = logs,
                                connectionState = connectionState,
                                sessions = sessions,
                                sessionsFetched = sessionsFetched,
                                activeSessionId = bridge.currentSessionId,
                                isListening = isListening,
                                viewMode = viewMode,
                                promptInput = promptInput,
                                onPromptInputChange = { text -> _promptInput.value = text },
                                onViewModeToggle = {
                                    _viewMode.value = if (viewMode == "SPACE") "HUD" else "SPACE"
                                },
                                onStartDictation = { startVoiceDictation() },
                                onStopDictation = { stopVoiceDictation() },
                                onConnect = { host, token ->
                                    bridge.activeHost = host
                                    bridge.activeToken = token
                                    bridge.refreshSessions()
                                },
                                onSelectSession = { sessionId ->
                                    bridge.connectToSession(sessionId)
                                },
                                onDisconnect = {
                                    bridge.disconnect()
                                    _agentState.value = "IDLE"
                                },
                                onInterrupt = {
                                    bridge.submitInterrupt()
                                },
                                onSubmitPrompt = { text ->
                                    bridge.submitPrompt(text)
                                    _promptInput.value = ""
                                },
                                onOptionSelected = { option -> submitAgentInput(option) },
                                onTrigger = { triggerPerceptionPipeline() }
                            )
                        }
                }
            }
        }
    }
}
