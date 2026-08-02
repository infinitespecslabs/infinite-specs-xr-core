# infinite-specs-xr-core

A native Android XR client for monitoring and steering AI coding agents (Claude Code) from smart glasses — a from-scratch clone of [Even Realities Terminal Mode](https://www.evenrealities.com/terminal), built to run across the full range of Android XR device classes (headsets, wired display glasses, and audio-only glasses) instead of being locked to Even Realities' G2 hardware.

The original product depends on the proprietary `@evenrealities/even-terminal` workstation daemon and Even Realities' mobile app/hardware. This project reverse-engineers that protocol and ships its own workstation daemon and Android XR client, so the experience isn't tied to their ecosystem.

**Status**: early alpha (`0.1.0-alpha`), mid-pivot. See [`ROADMAP.md`](./ROADMAP.md) for the phased plan and [`docs/SYSTEM_DESIGN.md`](./docs/SYSTEM_DESIGN.md) for the reverse-engineered protocol and architecture.

---

## Architecture

The Android XR app acts as an MCP-style client: it connects over Wi-Fi (REST + SSE) to a workstation daemon that spawns and manages the coding agent, then renders agent status, tool activity, and permission/question prompts as a spatial HUD — letting a developer approve actions or dictate guidance without being at their desk.

```
Workstation                              Android XR Device
┌────────────────────┐                   ┌───────────────────────┐
│  AI Agent           │  <== stdout ==>  │  Workstation daemon    │
│  (Claude Code)       │                   │  (REST + SSE server)  │
└──────────────────────┘                   └───────────┬────────────┘
                                                          │ Wi-Fi (HTTP + SSE)
                                             ┌───────────▼────────────┐
                                             │  McpSpecificationBridge │
                                             │  (Ktor client)          │
                                             └───────────┬────────────┘
                                                          │
                                             ┌───────────▼────────────┐
                                             │  Spatial HUD (Compose)  │
                                             └──────────────────────────┘
```

The workstation daemon itself is not yet built — the app currently expects the real `even-terminal` npm package to be running. Replacing it with our own implementation is the focus of `ROADMAP.md` Phase 1.

---

## Module Structure

```
infinite-specs-xr-core/
├── app/                                       # Android XR client application
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   └── java/com/infinitespecs/xr/
│       │       ├── MainActivity.kt
│       │       ├── bridge/
│       │       │   └── McpSpecificationBridge.kt   # Ktor SSE/REST client to the workstation daemon
│       │       ├── perception/
│       │       │   ├── SpatialIntentParser.kt      # Gemini-based gaze/voice → intent parsing
│       │       │   └── SpeechTranscriptionEngine.kt # On-device STT (local + stubbed remote)
│       │       └── ui/
│       │           └── SpatialPanelComposable.kt   # Spatial HUD (Subspace/SpatialPanel/Orbiter)
│       └── test/
│           └── java/com/infinitespecs/xr/
│               └── StrangeLoopTest.kt
├── docs/
│   └── SYSTEM_DESIGN.md                       # Reverse-engineered protocol + architecture
├── macbook-agent/                             # Legacy daemon simulator (being replaced, see ROADMAP.md Phase 1)
├── gradle/
│   └── libs.versions.toml
├── ROADMAP.md
├── build.gradle.kts
└── settings.gradle.kts
```

---

## Key Components

### [`McpSpecificationBridge.kt`](app/src/main/java/com/infinitespecs/xr/bridge/McpSpecificationBridge.kt)
Ktor HTTP client that connects to the workstation daemon's SSE event stream (agent status, tool activity, permission/question prompts, results) and submits developer responses (permission decisions, question answers, prompts, interrupts) back over REST.

### [`SpatialPanelComposable.kt`](app/src/main/java/com/infinitespecs/xr/ui/SpatialPanelComposable.kt)
Renders the spatial HUD showing live agent status and interactive prompts inside the developer's field of view.

### [`SpeechTranscriptionEngine.kt`](app/src/main/java/com/infinitespecs/xr/perception/SpeechTranscriptionEngine.kt)
Abstracts speech-to-text so voice input can run on-device (`LocalAndroidSpeechEngine`) or, in future, be streamed to the workstation for transcription — the primary input path for audio-only glasses.

### [`SpatialIntentParser.kt`](app/src/main/java/com/infinitespecs/xr/perception/SpatialIntentParser.kt)
Gemini-based parsing of gaze/voice telemetry into structured intent, carried over from the project's original "Strange Loop" architecture.

---

## Getting Started

**Prerequisites:**
- Android Studio Meerkat (2024.3) or later
- JDK 17+

Build application:
```bash
./gradlew assembleDebug
```

## Running Tests

```bash
# JVM unit verification of closed-loop logic
./gradlew :app:test
```
