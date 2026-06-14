# infinite-specs-xr-core

> **Infinite Specs** — A Kotlin-first Android XR research sandbox translating ambient spatial data
> into structured software specifications via the Model Context Protocol (MCP).
>
> Targets **Android XR Developer Preview 4** (Jetpack XR SDK) on display and audio glasses.

---

## Table of Contents

- [Overview](#overview)
- [Three-Tier Architecture](#three-tier-architecture)
  - [Tier 1 — Perception Layer](#tier-1--perception-layer)
  - [Tier 2 — Schema Translation Layer](#tier-2--schema-translation-layer)
  - [Tier 3 — Connection Layer](#tier-3--connection-layer)
- [Module Structure](#module-structure)
- [Key Components](#key-components)
- [Getting Started](#getting-started)
- [Running Tests](#running-tests)
- [Design Principles](#design-principles)
- [References](#references)

---

## Overview

Infinite Specs bridges the physical world (ambient gaze, voice, spatial context) and the
software-development world (OpenAPI / MCP schemas consumed by IDEs and AI coding agents).

A developer wearing XR glasses can focus on an architecture node rendered in their field of view,
speak a design intent, and receive a deterministic technical specification streamed directly to
their IDE via Server-Sent Events — no keyboard required.

```
┌─────────────────────────────────────────────────────────────────┐
│                          Android XR Device                       │
│                                                                  │
│  ┌──────────────┐    ┌──────────────────┐    ┌───────────────┐  │
│  │  PERCEPTION  │───▶│ SCHEMA TRANSLATION│───▶│  CONNECTION   │  │
│  │    LAYER     │    │      LAYER        │    │    LAYER      │  │
│  └──────────────┘    └──────────────────┘    └───────┬───────┘  │
│                                                       │          │
└───────────────────────────────────────────────────────┼──────────┘
                                                        │ SSE / MCP
                                                        ▼
                                              ┌─────────────────┐
                                              │  IDE / AI Agent │
                                              │  (MCP Client)   │
                                              └─────────────────┘
```

---

## Three-Tier Architecture

### Tier 1 — Perception Layer

**Responsibility:** Capture and normalise raw XR sensor events into typed domain objects.

| Signal Source              | Jetpack XR API                             | Mock / Stub                         |
|----------------------------|--------------------------------------------|-------------------------------------|
| Gaze (eye tracking)        | `androidx.xr.runtime.Session.eyeState`     | `MockGazeEventSource`               |
| Voice transcript tokens    | `androidx.xr.scenecore.Session` + STT      | `MockVoiceTranscriptSource`         |
| Projected panel interaction| `androidx.xr.compose.SubspaceComposable`   | `FakeSpatialPanelInteractionSource` |
| Spatial anchor / pose      | `androidx.xr.arcore.ArCoreForXr`           | Pose mock via `PoseEventBuilder`    |

The entry-point contract is **`SpatialIntentParser`** — a `Flow`-based reactive interface that
accepts a stream of `RawSpatialEvent` objects and emits `SpatialIntent` domain objects suitable
for the Schema Translation Layer.

```
RawSpatialEvent (gaze dwell ms + voice tokens)
        │
        ▼
  SpatialIntentParser
        │
        ▼
  SpatialIntent (confidence score, resolved intent type, associated node ID)
```

**Key classes:**
- `SpatialIntentParser` — interface (`perception/SpatialIntentParser.kt`)
- `DefaultSpatialIntentParser` — default implementation with configurable dwell threshold
- `RawSpatialEvent` — sealed class: `GazeEvent`, `VoiceEvent`, `CompositeEvent`
- `SpatialIntent` — data class with `intentType`, `nodeId`, `confidenceScore`, `timestamp`

---

### Tier 2 — Schema Translation Layer

**Responsibility:** Convert `SpatialIntent` objects into structured, machine-readable
technical specifications (OpenAPI 3.1 fragments or MCP `ToolDefinition` JSON).

```
SpatialIntent
      │
      ▼
 SpecificationMapper
      │
      ├──▶  OpenApiFragment  (JSON / YAML)
      └──▶  McpToolDefinition (JSON-RPC 2.0 envelope)
```

Mapping is deterministic: the same `SpatialIntent` always produces the same `SpecPayload`.
This property is enforced by unit tests in `SpatialIntentParserTest`.

**Key classes:**
- `SpecificationMapper` — pure function mapper (`schema/SpecificationMapper.kt`)
- `SpecPayload` — sealed class: `OpenApiSpec`, `McpToolSpec`
- `NodeSchemaRegistry` — in-memory registry mapping `nodeId` → canonical schema fragment

---

### Tier 3 — Connection Layer

**Responsibility:** Stream `SpecPayload` objects to external MCP-aware consumers (IDEs, AI
coding agents) over Server-Sent Events (SSE) or a WebSocket transport.

```
SpecPayload
     │
     ▼
McpSpecificationBridge  ──SSE──▶  IDE Extension (VS Code / JetBrains MCP Client)
     │
     └──JSON-RPC 2.0──▶  Remote AI Agent
```

The bridge is non-blocking and back-pressure–aware, built on Kotlin `Flow` / coroutines.
It exposes a lifecycle-safe `BridgeSession` handle so Android activity / service owners can
cancel the stream without leaking resources.

**Key classes:**
- `McpSpecificationBridge` — interface (`bridge/McpSpecificationBridge.kt`)
- `SseMcpBridgeImpl` — Ktor-based SSE server implementation
- `BridgeSession` — cancellable session handle
- `McpEnvelope` — JSON-RPC 2.0 wrapper for `SpecPayload`

---

## Module Structure

```
infinite-specs-xr-core/
├── app/                            # Android XR host application
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   └── java/com/infinitespecs/xr/
│       │       ├── MainActivity.kt
│       │       ├── perception/
│       │       │   └── SpatialIntentParser.kt
│       │       ├── ui/
│       │       │   └── SpatialPanelComposable.kt
│       │       └── bridge/
│       │           └── McpSpecificationBridge.kt
│       └── test/
│           └── java/com/infinitespecs/xr/
│               └── SpatialIntentParserTest.kt
├── gradle/
│   └── libs.versions.toml
├── build.gradle.kts
└── settings.gradle.kts
```

---

## Key Components

### `SpatialIntentParser.kt`

Reactive, `Flow`-based interface. Accepts a stream of `RawSpatialEvent` values (gaze dwell
durations, voice transcript tokens) and emits resolved `SpatialIntent` objects.

### `SpatialPanelComposable.kt`

`androidx.xr.compose` composable rendering a glanceable, floating heads-up display panel.
Shows architecture node cards in the developer's field of view. Uses `SubspaceComposable`
and `SpatialPanel` APIs from Jetpack XR Developer Preview 4.

### `McpSpecificationBridge.kt`

Outbound streaming bridge. Implements Server-Sent Events transport for pushing structured
`SpecPayload` objects to external MCP clients. Exposes a `Flow<McpEnvelope>` so callers can
apply standard coroutine operators (filter, debounce, conflate).

---

## Getting Started

**Prerequisites:**
- Android Studio Meerkat (2024.3) or later with Android XR plugin
- Android XR emulator image (Developer Preview 4) or physical XR device
- JDK 17+

```bash
# Clone and open in Android Studio
git clone https://github.com/alexdennis/infinite-specs-xr-core.git
cd infinite-specs-xr-core
./gradlew assembleDebug
```

> **Note:** The Jetpack XR SDK APIs (`androidx.xr.*`) require the Android XR system image.
> All perception inputs have mock implementations so the schema translation and connection
> layers can be exercised on a standard Android emulator or JVM unit test environment.

---

## Running Tests

```bash
# JVM unit tests (no device required)
./gradlew :app:test

# Connected device tests
./gradlew :app:connectedAndroidTest
```

---

## Design Principles

1. **Deterministic mappings** — identical spatial events always produce identical spec payloads.
2. **Back-pressure–aware streams** — all inter-layer contracts use Kotlin `Flow`; no unchecked
   callbacks or fire-and-forget coroutines.
3. **Mock-first perception** — every sensor source ships a `Fake*` / `Mock*` counterpart so
   the pipeline is testable without XR hardware.
4. **Zero runtime secrets** — the bridge layer uses local loopback SSE by default; no API
   keys, credentials, or device IDs are transmitted.
5. **Single-source-of-truth schemas** — `NodeSchemaRegistry` is the canonical schema store;
   both the UI layer and the bridge layer read from it.

---

## References

- [Android XR Developer Preview 4 — Release Notes](https://developer.android.com/xr/release-notes)
- [Jetpack XR SDK Overview](https://developer.android.com/xr)
- [androidx.xr.compose Subspace APIs](https://developer.android.com/xr/compose)
- [Model Context Protocol Specification](https://modelcontextprotocol.io/specification)
- [OpenAPI 3.1 Specification](https://spec.openapis.org/oas/v3.1.0)
- [Kotlin Coroutines & Flow](https://kotlinlang.org/docs/flow.html)
- [Ktor Server-Sent Events](https://ktor.io/docs/server-server-sent-events.html)

---

*Infinite Specs is a research prototype. The Jetpack XR SDK APIs referenced here are in
Developer Preview and subject to change. See the Android XR release notes for the latest
stable API surface.*
