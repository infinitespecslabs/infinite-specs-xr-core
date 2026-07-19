package com.infinitespecs.xr.bridge

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException

/**
 * Connects the Android XR app to a workstation host running even-terminal.
 * Subscribes to the server-sent events stream (SSE) and handles tool/agent approval prompts.
 */
class McpSpecificationBridge {

    // ── Ktor Client ──────────────────────────────────────────────────────────
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                coerceInputValues = true
            })
        }
        install(HttpTimeout)
    }

    private val jsonParser = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    private var sseJob: Job? = null

    // ── Settings & State ─────────────────────────────────────────────────────
    var activeHost: String = "10.0.2.2:3456"
    var activeToken: String = ""
    var currentSessionId: String? = null

    private val _connectionState = MutableStateFlow("DISCONNECTED")
    val connectionState: StateFlow<String> = _connectionState

    private val _sessionsFetched = MutableStateFlow(false)
    val sessionsFetched: StateFlow<Boolean> = _sessionsFetched

    // ── Streams mapped to MainActivity & SpatialPanel ────────────────────────
    private val _inboundLogStream = MutableSharedFlow<String>()
    val inboundLogStream: Flow<String> = _inboundLogStream

    private val _inboundStateStream = MutableSharedFlow<AgentStatePayload>()
    val inboundStateStream: Flow<AgentStatePayload> = _inboundStateStream

    private val _sessionsFlow = MutableStateFlow<List<SessionInfo>>(emptyList())
    val sessionsFlow: StateFlow<List<SessionInfo>> = _sessionsFlow

    // Active interactive prompt details
    var lastPermissionRequest: PermissionRequestEvent? = null
    var lastQuestionRequest: UserQuestionEvent? = null

    @Serializable
    data class AgentStatePayload(
        val state: String,
        val prompt: String = "",
        val options: List<String> = emptyList(),
        val log: String = "",
        val detail: String = ""
    )

    fun start() {
        // Automatically fetch active sessions on start if host is configured
        Log.d("McpSpecBridge", "Bridge started. Ready to connect to host: $activeHost")
        refreshSessions()
    }

    fun stop() {
        disconnect()
        client.close()
    }

    /**
     * Connect to the selected session's Server-Sent Events stream.
     */
    fun connectToSession(sessionId: String) {
        disconnect()
        currentSessionId = sessionId
        _connectionState.value = "CONNECTING"
        
        sseJob = coroutineScope.launch {
            try {
                val url = "http://$activeHost/api/events?sessionId=$sessionId&needReplay=true"
                Log.d("McpSpecBridge", "Connecting to SSE stream at: $url")
                
                client.prepareGet(url) {
                    header("Authorization", "Bearer $activeToken")
                    timeout {
                        requestTimeoutMillis = HttpTimeout.INFINITE_TIMEOUT_MS
                        socketTimeoutMillis = HttpTimeout.INFINITE_TIMEOUT_MS
                    }
                }.execute { response ->
                    if (response.status.value == 200) {
                        _connectionState.value = "CONNECTED"
                        _inboundLogStream.emit("Session Connected: $sessionId")
                        
                        val channel = response.bodyAsChannel()
                        readSseChannel(channel)
                    } else {
                        val errText = "Auth error: ${response.status.description}"
                        Log.e("McpSpecBridge", errText)
                        _connectionState.value = "ERROR"
                        _inboundLogStream.emit("Failed to connect: $errText")
                    }
                }
            } catch (e: Exception) {
                Log.e("McpSpecBridge", "SSE Connection Failed", e)
                _connectionState.value = "ERROR"
                _inboundLogStream.emit("Connection failed: ${e.message}")
            }
        }
    }

    private suspend fun readSseChannel(channel: ByteReadChannel) {
        while (!channel.isClosedForRead) {
            val line = channel.readUTF8Line() ?: break
            val trimmedLine = line.trim()
            if (trimmedLine.startsWith("data:")) {
                val dataContent = trimmedLine.substring(5).trim()
                if (dataContent.isNotEmpty() && dataContent != ":heartbeat" && dataContent != ":ok") {
                    parseAndProcessSsePayload(dataContent)
                }
            }
        }
        Log.d("McpSpecBridge", "SSE channel closed")
        _connectionState.value = "DISCONNECTED"
    }

    private suspend fun parseAndProcessSsePayload(jsonStr: String) {
        try {
            // Raw JSON logs for diagnostics
            Log.d("McpSpecBridge", "SSE Event payload: $jsonStr")

            // Determine event type by scanning json keys dynamically
            val baseElement = jsonParser.parseToJsonElement(jsonStr)
            val jsonObject = baseElement.jsonObject

            when {
                // 1. Status event
                jsonObject.containsKey("state") && jsonObject.containsKey("sessionId") && !jsonObject.containsKey("success") -> {
                    val status = jsonParser.decodeFromString<StatusEvent>(jsonStr)
                    val displayState = mapAgentState(status.state)
                    _inboundStateStream.emit(AgentStatePayload(state = displayState))
                }

                // 2. Text delta stream
                jsonObject.containsKey("text") && !jsonObject.containsKey("sessionId") && !jsonObject.containsKey("toolName") -> {
                    val delta = jsonParser.decodeFromString<TextDeltaEvent>(jsonStr)
                    _inboundLogStream.emit(delta.text)
                }

                // 3. Tool execution start
                jsonObject.containsKey("name") && jsonObject.containsKey("toolId") && !jsonObject.containsKey("summary") -> {
                    val toolStart = jsonParser.decodeFromString<ToolStartEvent>(jsonStr)
                    _inboundLogStream.emit("> Running Tool: ${toolStart.name}")
                    _inboundStateStream.emit(AgentStatePayload(state = "EXECUTING", log = "Running ${toolStart.name}"))
                }

                // 4. Tool execution end
                jsonObject.containsKey("name") && jsonObject.containsKey("toolId") && jsonObject.containsKey("summary") -> {
                    val toolEnd = jsonParser.decodeFromString<ToolEndEvent>(jsonStr)
                    _inboundLogStream.emit("> Completed Tool: ${toolEnd.summary}")
                }

                // 5. Tool permission request (diffs, confirm shell run)
                jsonObject.containsKey("toolName") && jsonObject.containsKey("toolUseId") && jsonObject.containsKey("options") -> {
                    val permission = jsonParser.decodeFromString<PermissionRequestEvent>(jsonStr)
                    lastPermissionRequest = permission
                    
                    _inboundStateStream.emit(
                        AgentStatePayload(
                            state = "AWAITING_INPUT",
                            prompt = permission.description,
                            options = permission.options.map { it.text },
                            detail = permission.detail
                        )
                    )
                }

                // 6. User Clarification Question
                jsonObject.containsKey("questions") && jsonObject.containsKey("toolUseId") -> {
                    val question = jsonParser.decodeFromString<UserQuestionEvent>(jsonStr)
                    lastQuestionRequest = question
                    
                    val firstQuestion = question.questions.firstOrNull()
                    val promptText = firstQuestion?.question ?: "Awaiting developer response"
                    val options = firstQuestion?.options?.map { it.label } ?: emptyList()

                    _inboundStateStream.emit(
                        AgentStatePayload(
                            state = "AWAITING_INPUT",
                            prompt = promptText,
                            options = options
                        )
                    )
                }

                // 7. Session result
                jsonObject.containsKey("success") && jsonObject.containsKey("sessionId") -> {
                    val result = jsonParser.decodeFromString<ResultEvent>(jsonStr)
                    val statusText = if (result.success) "SUCCESS" else "FAILURE"
                    _inboundStateStream.emit(
                        AgentStatePayload(
                            state = statusText,
                            log = "Session complete. Cost: \$${String.format("%.3f", result.costUsd)} (${result.turns} turns)"
                        )
                    )
                    _inboundLogStream.emit("Result: $statusText - ${result.text}")
                }
            }
        } catch (e: Exception) {
            Log.e("McpSpecBridge", "Error parsing SSE payload: ${e.message}", e)
        }
    }

    private fun mapAgentState(serverState: String): String {
        return when (serverState) {
            "idle" -> "IDLE"
            "busy" -> "THINKING"
            "think_start" -> "THINKING"
            "think_end" -> "THINKING"
            "text_start" -> "THINKING"
            "text_end" -> "THINKING"
            "awaiting" -> "AWAITING_INPUT"
            else -> "PROCESSING"
        }
    }

    fun disconnect() {
        sseJob?.cancel()
        sseJob = null
        currentSessionId = null
        lastPermissionRequest = null
        lastQuestionRequest = null
        _sessionsFetched.value = false
        _connectionState.value = "DISCONNECTED"
    }

    fun refreshSessions() {
        coroutineScope.launch {
            try {
                val url = "http://$activeHost/api/sessions?provider=claude"
                Log.d("McpSpecBridge", "Fetching sessions: $url")
                val response = client.get(url) {
                    header("Authorization", "Bearer $activeToken")
                }
                
                if (response.status.value == 200) {
                    val responseText = response.bodyAsText()
                    Log.d("McpSpecBridge", "Sessions response: $responseText")
                    val parsed = jsonParser.decodeFromString<List<SessionInfo>>(responseText)
                    _sessionsFlow.value = parsed
                    _sessionsFetched.value = true
                } else {
                    Log.e("McpSpecBridge", "Session fetch failed: HTTP ${response.status.value}")
                }
            } catch (e: Exception) {
                Log.e("McpSpecBridge", "Failed to refresh sessions", e)
            }
        }
    }

    /**
     * Sends user confirmation (decision) for a tool call.
     */
    fun submitPermissionResponse(decision: String) {
        val sessionId = currentSessionId ?: return
        val permissionReq = lastPermissionRequest ?: return
        
        // Maps decision string to matches ('Yes' -> 'allow', 'No' -> 'deny')
        val key = when (decision) {
            "Yes" -> "allow"
            "No" -> "deny"
            "allowAlways" -> "allowAlways"
            else -> decision
        }

        coroutineScope.launch {
            try {
                val url = "http://$activeHost/api/permission-response"
                Log.d("McpSpecBridge", "Submitting permission decision: $key for session: $sessionId")
                val response = client.post(url) {
                    header("Authorization", "Bearer $activeToken")
                    contentType(ContentType.Application.Json)
                    setBody(PermissionResponse(sessionId = sessionId, decision = key))
                }
                Log.d("McpSpecBridge", "Permission submission response: ${response.status}")
                lastPermissionRequest = null
            } catch (e: Exception) {
                Log.e("McpSpecBridge", "Failed to submit permission", e)
            }
        }
    }

    /**
     * Sends answers to AskUserQuestion queries.
     */
    fun submitQuestionResponse(answer: String) {
        val sessionId = currentSessionId ?: return
        coroutineScope.launch {
            try {
                val url = "http://$activeHost/api/question-response"
                Log.d("McpSpecBridge", "Submitting question answer: $answer for session: $sessionId")
                val response = client.post(url) {
                    header("Authorization", "Bearer $activeToken")
                    contentType(ContentType.Application.Json)
                    setBody(QuestionResponse(sessionId = sessionId, answer = answer))
                }
                Log.d("McpSpecBridge", "Question submission response: ${response.status}")
                lastQuestionRequest = null
            } catch (e: Exception) {
                Log.e("McpSpecBridge", "Failed to submit answer", e)
            }
        }
    }

    /**
     * Interrupts execution.
     */
    fun submitInterrupt() {
        val sessionId = currentSessionId ?: return
        coroutineScope.launch {
            try {
                val url = "http://$activeHost/api/interrupt"
                Log.d("McpSpecBridge", "Interrupting session: $sessionId")
                client.post(url) {
                    header("Authorization", "Bearer $activeToken")
                    contentType(ContentType.Application.Json)
                    setBody(InterruptRequest(sessionId = sessionId))
                }
            } catch (e: Exception) {
                Log.e("McpSpecBridge", "Interrupt failed", e)
            }
        }
    }

    /**
     * Submits a fresh text prompt to start or run in a session.
     */
    fun submitPrompt(text: String) {
        val sessionId = currentSessionId ?: return
        coroutineScope.launch {
            try {
                val url = "http://$activeHost/api/prompt"
                Log.d("McpSpecBridge", "Submitting prompt: '$text' to session: $sessionId")
                client.post(url) {
                    header("Authorization", "Bearer $activeToken")
                    contentType(ContentType.Application.Json)
                    setBody(PromptRequest(text = text, sessionId = sessionId))
                }
            } catch (e: Exception) {
                Log.e("McpSpecBridge", "Prompt submission failed", e)
            }
        }
    }

    // Stub for future intent streaming (design loop compatibility)
    suspend fun streamIntentToAutonomousAgentWorktree(intent: Any) {
        Log.d("McpSpecBridge", "Spatial intent streaming ignored in client terminal mode.")
    }

    // ── SSE Models ───────────────────────────────────────────────────────────
    @Serializable
    data class SessionInfo(
        val id: String,
        val title: String = "",
        val timestamp: String = "",
        val cwd: String = "",
        val provider: String = "claude",
        val status: String? = null
    )

    @Serializable
    data class SessionListResponse(
        val sessions: List<SessionInfo> = emptyList(),
        val error: String? = null
    )

    @Serializable
    data class StatusEvent(
        val state: String,
        val sessionId: String
    )

    @Serializable
    data class TextDeltaEvent(
        val text: String
    )

    @Serializable
    data class ToolStartEvent(
        val name: String,
        val toolId: String
    )

    @Serializable
    data class ToolEndEvent(
        val name: String,
        val toolId: String,
        val summary: String = "",
        val detail: ToolEndDetail? = null
    )

    @Serializable
    data class ToolEndDetail(
        val output: String = ""
    )

    @Serializable
    data class PermissionRequestEvent(
        val toolName: String,
        val description: String = "",
        val detail: String = "",
        val toolUseId: String,
        val options: List<PermissionOption> = emptyList()
    )

    @Serializable
    data class PermissionOption(
        val text: String,
        val key: String
    )

    @Serializable
    data class UserQuestionEvent(
        val questions: List<QuestionItem> = emptyList(),
        val toolUseId: String
    )

    @Serializable
    data class QuestionItem(
        val question: String,
        val header: String = "",
        val options: List<QuestionOption> = emptyList()
    )

    @Serializable
    data class QuestionOption(
        val label: String,
        val description: String = "",
        val preview: String = ""
    )

    @Serializable
    data class ResultEvent(
        val success: Boolean,
        val text: String = "",
        val sessionId: String,
        val costUsd: Double = 0.0,
        val turns: Int = 0,
        val durationMs: Long = 0L,
        val inputTokens: Int = 0,
        val outputTokens: Int = 0
    )

    @Serializable
    data class PromptRequest(
        val text: String,
        val sessionId: String? = null,
        val provider: String = "claude"
    )

    @Serializable
    data class PermissionResponse(
        val sessionId: String,
        val provider: String = "claude",
        val decision: String
    )

    @Serializable
    data class QuestionResponse(
        val sessionId: String,
        val provider: String = "claude",
        val answer: String
    )

    @Serializable
    data class InterruptRequest(
        val sessionId: String,
        val provider: String = "claude"
    )

    companion object {
        var connectedHostIp: String = "10.0.2.2"
    }
}
