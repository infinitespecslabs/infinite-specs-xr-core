# Even Terminal Android XR Port: System Design Document

This document defines the architecture, data protocols, spatial layout, and security constraints for porting **Even Realities Terminal Mode** to the **Android XR** platform (targeting XReal AURA hardware).

---

## 1. Architectural Components

```
+--------------------------------------------------------------+
|                         WORKSTATION                          |
|                                                              |
|   +-------------------+          +-----------------------+   |
|   |    AI Agent       | <======> |     even-terminal     |   |
|   | (Claude/Cursor)   | (Process |   Express WebServer   |   |
|   +-------------------+  Stdout) +-----------------------+   |
+----------------------------------------------^---------------+
                                               |
                                        Wi-Fi  | (HTTP REST + SSE)
                                               |
+----------------------------------------------v---------------+
|                         ANDROID XR                           |
|                                                              |
|   +-------------------+          +-----------------------+   |
|   |   Spatial HUD     | <======> |   McpSpecBridge       |   |
|   |   Compose UI      |          |   Ktor Network Client |   |
|   +-------------------+          +-----------------------+   |
|             ^                                                |
|             | (Gaze / Gesture / Voice)                       |
|   +---------+---------+                                      |
|   |  SceneCore Engine |                                      |
|   +-------------------+                                      |
+--------------------------------------------------------------+
```

### 1.1 Workstation Host Daemon
- **Role**: Spawns and manages the AI coding agent as a child process.
- **Implementation**: Runs `@evenrealities/even-terminal` CLI package.
- **Port**: `3456` (default).
- **Communication Protocol**: Exposes REST endpoints and an SSE event server to stream agent logs, status changes, and prompt requests.

### 1.2 Android XR Client Application
- **Role**: Renders the spatial terminal console, intercepts developer gaze/gestures, captures voice prompts, and coordinates connection/security.
- **Key Modules**:
  - **McpSpecificationBridge**: Refactored to act as a client connecting to the workstation over Wi-Fi (HTTP & SSE) instead of running a host server on the headset.
  - **Spatial HUD (Compose UI)**: Renders a waveguide-style semi-transparent amber display.
  - **Interaction Engine**: Maps gaze, hand tracking (pinches), and speech transcripts to HTTP responses.

---

## 2. API & SSE Protocol Reference

All requests must contain the `Authorization: Bearer <TOKEN>` header.

### 2.1 Event Stream (SSE)
**Endpoint**: `GET /api/events?sessionId=<session-id>&needReplay=true`

The server streams Server-Sent Events (SSE). Each event has a JSON string payload.

| Event Type | Structure | Description |
| :--- | :--- | :--- |
| `status` | `{"state": "idle/busy/think_start/text_start/awaiting", "sessionId": "..."}` | Updates the agent status on the HUD. |
| `text_delta` | `{"text": "markdown chunk"}` | Streams real-time thoughts or console output. |
| `tool_start` | `{"name": "Bash", "toolId": "..."}` | Visualizes tool launch. |
| `tool_end` | `{"name": "Bash", "toolId": "...", "summary": "...", "detail": {"input": "...", "output": "..."}}` | Renders tool execution output. |
| `permission_request` | `{"toolName": "...", "description": "...", "detail": "...", "toolUseId": "...", "options": [...], "suggestions": [...]}` | Prompts the developer to approve tool execution (e.g. file edit, run command). |
| `user_question` | `{"questions": [{"question": "...", "options": [...]}], "toolUseId": "..."}` | Requests clarifying developer input. |
| `result` | `{"success": true, "text": "...", "turns": 5, "costUsd": 0.12}` | Displays session summary upon completion. |

### 2.2 Control Actions (REST)
All POST endpoints send a JSON body.

#### Submit Tool Permission Response
**Endpoint**: `POST /api/permission-response`
```json
{
  "sessionId": "session_id_here",
  "provider": "claude",
  "decision": "allow" // or "deny", "allowAlways"
}
```

#### Submit Question Answer
**Endpoint**: `POST /api/question-response`
```json
{
  "sessionId": "session_id_here",
  "provider": "claude",
  "answer": "Yes, please build a Kotlin consumer."
}
```

#### Interrupt Agent Run
**Endpoint**: `POST /api/interrupt`
```json
{
  "sessionId": "session_id_here",
  "provider": "claude"
}
```

---

## 3. Speech Transcription Subsystem

To evaluate performance across different transcription models (on-device latency vs. host server computing power), the voice system uses an abstracted design.

### 3.1 Interface Definition
```kotlin
interface SpeechTranscriptionEngine {
    /**
     * Start capturing microphone input and transcribe it.
     * Returns the finalized transcript text as a Flow or callback.
     */
    fun startListening(onResult: (String) -> Unit, onError: (String) -> Unit)
    
    /** Stop capturing immediately. */
    fun stopListening()
}
```

### 3.2 Implementations
1. **LocalAndroidSpeechEngine (Phase 4)**:
   - Uses the Android platform `android.speech.SpeechRecognizer` interface.
   - Performs translation locally on the device (using standard system STT or Gemini Nano).
   - Zero network overhead; low latency.
2. **RemoteWorkstationSpeechEngine (Future Phase)**:
   - Captures raw audio streams using `AudioRecord`.
   - Sends audio bytes over WebSockets to the workstation server (`ws://<workstation-ip>:3456/api/audio-stream`).
   - Workstation transcribes via local Whisper model or remote API and returns the text result.

---

## 4. Spatial User Interface & Interaction Models

### 4.1 Head-Locked vs. Space-Locked Layouts
- **Space-Locked (Workspace Mode)**: The developer can drag the terminal console using a hand ray and anchor it in their physical space (e.g. pinned directly above their monitor or adjacent to their physical keyboard).
- **Head-Locked (Walkaround Mode)**: When walking away from the desk, the panel shifts to a minimal, semi-transparent waveguide HUD pinned to the lower-right margin of the viewport, matching the Even Realities G2 smart glasses style.

### 4.2 Gaze-Highlight and Pinch-Approve Interaction
- **Gaze Targeting**: Hovering over menu items or confirmation buttons highlights the border with solid glowing amber (`HudColors.BorderActive`).
- **Pinch Confirmation**: Performing a hand pinch gesture triggers the selected action.
- **Quick Deny**: Swiping/flicking the hand outwards acts as an immediate `deny` / `interrupt` command.

### 4.3 Speech-to-Text Integration
- To answer agent questions or provide new coding instructions, the developer gazes at the "Dictate" microphone icon and holds a pinch gesture.
- The app invokes the active `SpeechTranscriptionEngine` implementation to resolve user intent, posting the resulting string.

---

## 5. Security & Connection Lifecycle

### 5.1 Connection pairing via manual dashboard config
1. The developer runs `even-terminal` on their workstation. The CLI prints the local URL and authorization token.
2. The developer opens the Android XR app, which defaults to the loopback IP (`10.0.2.2:3456`) in emulator modes.
3. The developer inputs/pastes the Bearer token into the spatial settings form, saving it to `SharedPreferences` for session management.

### 5.2 Local Network Restrictions
- The Ktor client strictly operates within the local subnet range.
- A `network_security_config.xml` profile is set up to allow cleartext traffic (`HTTP` instead of `HTTPS`) exclusively for private IP addresses (`10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`) to enable developer convenience without exposing global network risks.
