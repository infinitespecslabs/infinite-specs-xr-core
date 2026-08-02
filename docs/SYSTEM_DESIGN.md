# Even Terminal Android XR Port: System Design Document

This document defines the architecture, data protocols, spatial layout, and security constraints for porting **Even Realities Terminal Mode** to the **Android XR** platform.

Sections 2 and 5 are reverse-engineered directly from the installed `@evenrealities/even-terminal@0.8.1` package (unminified compiled TypeScript at `dist/`, not obfuscated). See §6 for the full methodology and a gap list against our current Kotlin client. Protocol details are pinned to that version and may drift in future releases.

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
- **Implementation**: Runs `@evenrealities/even-terminal` CLI package. Its Claude provider does **not** spawn/parse the `claude` CLI's stdout — it depends directly on Anthropic's public `@anthropic-ai/claude-agent-sdk` package and calls its `query()` function in-process (see §6.1). This significantly narrows the scope of Phase 1 (our own daemon): we can depend on the same public SDK rather than reverse-engineering agent spawning.
- **Port**: `3456` (default, `-p`/`--port`).
- **Communication Protocol**: Exposes REST endpoints and an SSE event server to stream agent logs, status changes, and prompt requests.
- **CLI surface** (`even-terminal --help`, v0.8.1): `-p/--port`, `-t/--token`, `-n/--name`, `-d/--cwd` (project directory), `--provider claude|codex`, `--log-file`, `--verbose`, `--tailscale`/`--interface` (alternate LAN discovery), `--expose pinggy|bore|ngrok` (public tunnel helpers).

### 1.2 Android XR Client Application
- **Role**: Renders the spatial terminal console, intercepts developer gaze/gestures, captures voice prompts, and coordinates connection/security.
- **Key Modules**:
  - **McpSpecificationBridge**: Refactored to act as a client connecting to the workstation over Wi-Fi (HTTP & SSE) instead of running a host server on the headset.
  - **Spatial HUD (Compose UI)**: Renders a waveguide-style semi-transparent amber display.
  - **Interaction Engine**: Maps gaze, hand tracking (pinches), and speech transcripts to HTTP responses.

---

## 2. API & SSE Protocol Reference

All requests must contain the `Authorization: Bearer <TOKEN>` header, **or** a `?token=<TOKEN>` query parameter — the server checks both (`dist/index.js`). Auth is a single global token per daemon instance (see §5.1), not per-client.

Nearly every endpoint accepts an optional `?provider=claude|codex` (default resolved from `DEFAULT_PROVIDER` env, else `claude`). `cursor`/`opencode` providers exist in the source but are hidden/disabled in v0.8.1.

### 2.1 Event Stream (SSE)
**Endpoint**: `GET /api/events?sessionId=<session-id>&needReplay=true`

The server streams Server-Sent Events. Each event has a JSON string payload. On connect it sends a `:ok` comment immediately, then heartbeats every 15s as `:heartbeat`. With `needReplay=true` it replays up to the last 500 buffered messages for that session before live events resume (`dist/routes/events.js`).

| Event Type | Structure | Description |
| :--- | :--- | :--- |
| `status` | `{"state": "...", "sessionId": "..."}` | Agent status. `state` is one of: `busy`, `idle`, `think_start`/`think_end` (thinking content block open/close), `text_start`/`text_end` (text content block open/close). **Note**: there is no `awaiting` status event on the SSE stream — that state is derived client/server-side from whether a `permission_request`/`user_question` is currently unanswered (see `GET /api/status`, §2.2). |
| `text_delta` | `{"text": "markdown chunk"}` | Streams real-time thoughts or console output. |
| `tool_start` | `{"name": "Bash", "toolId": "..."}` | Visualizes tool launch. |
| `tool_end` | `{"name": "Bash", "toolId": "...", "summary": "...", "detail": {"input": "...", "output": "..."}}` | Renders tool execution output. |
| `permission_request` | `{"toolName": "...", "description": "...", "detail": "...", "toolUseId": "...", "options": [{"text": "...", "key": "allow\|allowAlways\|deny"}], "suggestions": [...] \| null}` | Prompts the developer to approve tool execution. The `options` array always contains "Yes" (`allow`) and "No" (`deny`); a conditional third option's **label text is dynamically generated** from `suggestions` (e.g. `"Yes, and always allow access to \`/path\`"`, `"Yes, and always allow Bash \`git status\` for this project"`) — not a generic string. |
| `permission_result` | `{"toolName": "...", "summary": "...", "decision": "allowed\|denied\|always"}` | Echo of the resolved permission decision, sent right after the response is processed. |
| `user_question` | `{"questions": [{"question": "...", "header": "...", "options": [{"label", "description", "preview"}]}], "toolUseId": "..."}` | Requests clarifying developer input (from the agent's `AskUserQuestion` tool). |
| `question_answer` | `{"answers": {...}}` | Echo of the resolved answer(s), sent right after the response is processed. |
| `user_prompt` | `{"text": "..."}` | Echoes a submitted prompt once the session ID is known — lets a late-connecting client see what was just sent. |
| `running_stats` | `{"durationMs": ..., "inputTokens": ..., "outputTokens": ...}` | Emitted every 10s while the agent is busy — running token/duration counters. |
| `task_progress` | `{"completed": N, "total": N, "current": "..."}` | Derived from the agent's `TodoWrite` tool calls. |
| `notification` | `{"title": "...", "message": "..."}` | Ad-hoc notices, e.g. API retry ("Retrying (2/5), HTTP 529..."). |
| `result` | `{"success": true, "text": "...", "sessionId": "...", "costUsd": 0.12, "provider": "claude", "turns": 5, "durationMs": ..., "inputTokens": ..., "outputTokens": ...}` | Session turn summary. `idle` status follows separately once the query process fully ends. |
| `error` | `{"message": "..."}` | Non-abort query errors. |

**Client gap**: `McpSpecificationBridge.kt` currently only models `status`, `text_delta`, `tool_start`, `tool_end`, `permission_request`, `user_question`, and `result` — it does not yet parse `permission_result`, `question_answer`, `user_prompt`, `running_stats`, `task_progress`, `notification`, or `error`, and its `status` handling doesn't distinguish `think_start`/`think_end`/`text_start`/`text_end`.

### 2.2 Control Actions (REST)

#### List resumable sessions
**Endpoint**: `GET /api/sessions?provider=claude&cwd=<dir>&limit=10`

#### Submit / start a prompt
**Endpoint**: `POST /api/prompt`
```json
{ "text": "...", "sessionId": "session_id_or_omit_to_create", "provider": "claude", "cwd": "/optional/target/dir" }
```
Returns `202 { "ok": true, "sessionId": "...", "provider": "claude" }`. Omitting `sessionId` creates a new session; if the session is currently busy, the prompt is queued and dispatched when it goes idle rather than rejected.

#### Submit Tool Permission Response
**Endpoint**: `POST /api/permission-response`
```json
{ "sessionId": "...", "provider": "claude", "decision": "allow" }
```
`decision` is one of `allow`, `deny`, `allowAlways` (default `deny` if omitted).

#### Submit Question Answer
**Endpoint**: `POST /api/question-response`
```json
{ "sessionId": "...", "provider": "claude", "answer": "Yes, please build a Kotlin consumer." }
```
For multi-question prompts, the server first tries `JSON.parse(answer)` as a `{question: answer}` map; if that fails, it falls back to applying the same plain-text answer to every pending question.

#### Interrupt Agent Run
**Endpoint**: `POST /api/interrupt`
```json
{ "sessionId": "...", "provider": "claude" }
```

#### Poll status (non-SSE)
**Endpoint**: `GET /api/status?sessionId=...&provider=claude` → `{ "state": "busy|idle|awaiting", "sessionId": "...", "provider": "claude" }`. This is the one place `awaiting` actually appears — computed server-side from whether permissions/questions are pending.

#### Poll messages (SSE fallback / reconnect gap-fill)
**Endpoint**: `GET /api/messages?sessionId=...&after=<lastMessageId>&provider=claude` → `{ "messages": [...], "state": "...", "sessionId": "...", "provider": "..." }`. Useful for recovering missed events after a dropped connection without a full replay.

#### Session history
**Endpoint**: `GET /api/sessions/:id/history?limit=10&provider=claude`

#### Account / model / version info
**Endpoint**: `GET /api/info?provider=claude` → `{ "account": { "email", "organization", "subscriptionType" }, "model": "Opus 4.6", "version": "...", "provider": "claude" }`. Shells out to `claude --version` and `claude auth status` on the workstation.

#### Update check
**Endpoint**: `GET /api/update-check` → current vs. latest npm version of `even-terminal` itself.

**Client gap**: none of `/api/status`, `/api/messages`, `/api/sessions/:id/history`, `/api/info`, or `/api/update-check` are currently called from `McpSpecificationBridge.kt`.

---

## 3. Speech Transcription Subsystem

To evaluate performance across different transcription models (on-device latency vs. host server computing power), the voice system uses an abstracted design.

### 3.1 Interface Definition
```kotlin
interface SpeechTranscriptionEngine {
    fun startListening(onResult: (String) -> Unit, onError: (String) -> Unit)
    fun stopListening()
}
```

### 3.2 Implementations
1. **LocalAndroidSpeechEngine** (implemented, `perception/SpeechTranscriptionEngine.kt`):
   - Uses the Android platform `android.speech.SpeechRecognizer` interface.
   - Performs translation locally on the device; zero network overhead, low latency.
2. **RemoteWorkstationSpeechEngine** (stubbed, same file):
   - Will capture raw audio streams using `AudioRecord`.
   - Will send audio bytes over WebSockets to the workstation server. Not yet implemented — currently returns an error immediately.

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

### 5.1 Connection pairing
1. The developer runs `even-terminal` on their workstation. It generates a single bearer token (`randomBytes(16).toString("hex")`, or a fixed value via `BRIDGE_TOKEN` env / `-t`/`--token` flag) and prints it in full in the startup banner (`dist/startup/common.js`).
2. The banner also prints a QR code encoding the full connection URL with the token embedded: `http://<lan-ip>:<port>?token=<token>&defaultProvider=claude[&name=<display-name>]`. There is no separate pairing handshake — the QR payload *is* the credential.
3. The Android XR app can either scan this QR code or accept manual IP + token entry, saving to `SharedPreferences` for session management. It defaults to the loopback IP (`10.0.2.2:3456`) in emulator modes.
4. The token is a single global secret for the whole daemon instance — not per-client/per-device. There is no token rotation or revocation in v0.8.1 (tracked as a gap for our own daemon in `ROADMAP.md` Phase 2/7).

### 5.2 Local Network Restrictions
- The Ktor client strictly operates within the local subnet range.
- A `network_security_config.xml` profile is set up to allow cleartext traffic (`HTTP` instead of `HTTPS`) exclusively for private IP addresses (`10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`) to enable developer convenience without exposing global network risks.

---

## 6. Reverse-Engineering Notes (Phase 0)

Source: `@evenrealities/even-terminal@0.8.1`, installed locally via `npm install -g`, inspected directly at `<npm global root>/node_modules/@evenrealities/even-terminal/dist/` — the published package ships **unminified compiled TypeScript**, not obfuscated, so this reflects the actual server behavior rather than black-box inference. Corresponds to [issue #9](https://github.com/infinitespecslabs/infinite-specs-xr-core/issues/9).

### 6.1 Claude provider implementation (`dist/claude/session.js`, `dist/claude/provider.js`)
- Depends directly on the public `@anthropic-ai/claude-agent-sdk` npm package — calls its `query()` function in-process. It does not spawn the `claude` CLI and scrape stdout.
- Session identity **is** the SDK's own `session_id`; resume/history is entirely delegated to the SDK's own persistence (`~/.claude/projects/<project-dir>/<sessionId>.jsonl`). even-terminal keeps no session database of its own — an in-memory `Map<sessionId, ClaudeSession>` per running daemon process is the only server-side state.
- Fixed query options: `model: "claude-opus-4-6"` (not user-configurable in this version), `permissionMode: "acceptEdits"`, `maxTurns: 50`, `includePartialMessages: true`, `settingSources: ["user", "project"]`, `allowedTools` limited to a specific list (`Read`, `Edit`, `Glob`, `Grep`, `Agent`, `WebSearch`, `WebFetch`, `TaskOutput`, `ExitPlanMode`, `ListMcpResources`, `ReadMcpResource`).
- **Permission prompts are narrower than assumed**: because `permissionMode` is `acceptEdits`, file edits are pre-approved by the SDK itself and never reach `permission_request`. A `canUseTool` callback explicitly intercepts only: `AskUserQuestion` (→ `user_question` flow), a fixed set (`KillShell`, `Config`, `Mcp`, `RemoteTrigger`), and `Bash` — and even `Bash` auto-approves a safe read-only allowlist (`ls`, `cat`, `head`, `tail`, `wc`, `pwd`, `echo`, `printf`, `date`, `whoami`, `which`, `where`, `type`, `file`, `stat`, `du`, `df`, `env`, `printenv`, `uname`, `hostname`, `id`, and `git status|log|diff|branch|show|remote|rev-parse`) via regex match on the command string. Everything else is auto-approved with no prompt at all.
- `TodoWrite` calls are intercepted (not for permission, just to derive `task_progress` events) and always auto-approved.
- "Always allow" decisions are tracked in an in-memory `Set<toolName>` per session — not persisted across daemon restarts.
- Status derivation: `awaiting` is a *computed* property (`pendingPermissions.length > 0 || pendingQuestions.length > 0`), not a value ever pushed as an SSE `status` event — only surfaced via polling `GET /api/status`. The SSE stream's `status` events are only `busy`/`idle`/`think_start`/`think_end`/`text_start`/`text_end`.

### 6.2 Gap list against `McpSpecificationBridge.kt`
See §2.1/§2.2 inline "Client gap" notes above for the full breakdown. Summary:
- **Missing SSE event handling**: `permission_result`, `question_answer`, `user_prompt`, `running_stats`, `notification`, `task_progress`, `error`; `status` events aren't split into the finer `think_start`/`think_end`/`text_start`/`text_end` states.
- **Missing REST calls**: `/api/status`, `/api/messages` (reconnect gap-fill), `/api/sessions/:id/history`, `/api/info`, `/api/update-check`.
- **Missing feature**: multi-provider (`claude`/`codex`) selection — client hardcodes `provider=claude`.
- **Design implication for Phase 1**: our own daemon can depend on `@anthropic-ai/claude-agent-sdk` directly (same as even-terminal does) rather than reimplementing agent process management from scratch — the main build effort is the HTTP/SSE routing layer and event-schema translation, which this document now specifies precisely enough to reproduce.
