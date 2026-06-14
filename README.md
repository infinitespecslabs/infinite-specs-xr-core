# infinite-specs-xr-core

Core architectural research sandbox exploring the intersection of **Spatial Intelligence**, **Model Context Protocol (MCP) Daemons**, and **Loop Engineering** on Android XR eyewear interfaces.

## 🌀 Theoretical Architecture: Tangled Hierarchies & Strange Loops
Inspired by Douglas Hofstadter's concept of a *Strange Loop*, this platform breaks down traditional hierarchical barriers between system engineering layers. It bridges three distinct tiers into a self-referential perception-action feedback loop:

1. **The Human Observer (Macro Layer):** Uses lightweight ambient eyewear to monitor real-world assets (e.g., concert lighting topologies or structural framing) away from a workstation.
2. **The Perception Ingestion Engine (Micro Layer):** Translates real-time visual scene parameters (via Camera Access) and natural voice intent (via Audio Polling) into strict, invariant technical schemas.
3. **The Autonomous Worktree (Execution Loop):** Streams these schemas over local Server-Sent Events (SSE) via the Model Context Protocol. Local background sub-agents ingest the schema as a "Skill/State constraint", autonomously compile the necessary codebase updates, verify build success, and flash real-time visual compilation logs back onto the smart glasses.

The observer is no longer outside the machine; they are an integrated state node *inside* the automated compilation loop.

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
│               └── StrangeLoopTest.kt
├── gradle/
│   └── libs.versions.toml
├── build.gradle.kts
└── settings.gradle.kts
```

---

## Key Components

### [SpatialIntentParser.kt](file:///Users/alex/Documents/GitHub/infinite-specs-xr-core/app/src/main/java/com/infinitespecs/xr/perception/SpatialIntentParser.kt)
Translates user voice transcripts and gaze vectors into immutable `ArchitecturalIntent` schema constraints targeted for localized agent workspaces.

### [McpSpecificationBridge.kt](file:///Users/alex/Documents/GitHub/infinite-specs-xr-core/app/src/main/java/com/infinitespecs/xr/bridge/McpSpecificationBridge.kt)
Serves as the internal Model Context Protocol (MCP) server daemon interface that streams serialized JSON schema constraints directly to background agent loops.

### [SpatialPanelComposable.kt](file:///Users/alex/Documents/GitHub/infinite-specs-xr-core/app/src/main/java/com/infinitespecs/xr/ui/SpatialPanelComposable.kt)
Renders real-time compilation feedback and visual loop telemetry parameters inside the developer's smart glasses field of view.

---

## Getting Started

**Prerequisites:**
- Android Studio Meerkat (2024.3) or later
- JDK 17+

Build application:
```bash
./gradlew assembleDebug
```

---

## Running Tests

```bash
# JVM unit verification of closed-loop logic
./gradlew :app:test
```
