# 🌀 Infinite Specs XR: Strategic Roadmap

This document outlines the transition from the current "Strange Loop" prototype to a fully functional spatial engineering platform.

---

## Phase 1: Real-World Perception (The Sensors)
**Goal**: Replace mock telemetry with active hardware sensor data.
- [ ] **Voice Integration**: Implement on-device Speech-to-Text (STT) using Android `SpeechRecognizer` or Gemini Nano.
- [x] **Spatial Gaze Mapping**: Access the `androidx.xr.scenecore.Session` to retrieve real-time gaze rays.
- [ ] **Physical Anchoring**: Use ARCore Plane Detection to pin architecture nodes to real-world surfaces (`AnchorEntity`).
- [ ] **Object Recognition**: (Long term) Use the camera feed to identify physical hardware (servers, rigs) as gaze targets.

## Phase 2: The MCP Daemon (The Connectivity)
**Goal**: Establish a bidirectional network bridge between the XR headset and the developer's workstation.
- [x] **Ktor SSE Server**: Implement a real Model Context Protocol (MCP) server inside the app using Ktor.
- [ ] **Network Discovery**: Implement mDNS/NSD so the IDE can "find" the headset on the local network.
- [x] **Bidirectional Sync**: Enable external agents (Claude Code, Cursor) to stream build logs and errors back to the XR HUD.
- [ ] **Schema Export**: Automatically drop `.mcp.json` specs into the connected workspace.

## Phase 3: Intelligent Ingestion (The "Brain")
**Goal**: Use LLMs to translate fuzzy human intent into strict system schemas.
- [ ] **LLM Integration**: Connect the `SpatialIntentParser` to a remote or local LLM (Gemini).
- [ ] **Intent Distillation**: Map natural language + gaze vectors to the `ArchitecturalIntent` schema.
- [ ] **Review & Refine UI**: Add a "Proposal" state in the HUD where users can tweak the AI's parsed plan before committing to code.

## Phase 4: Loop Engineering Visuals (The "Strange Loop")
**Goal**: Create a fully immersive spatial representation of the codebase.
- [ ] **3D Topology**: Replace 2D cards with 3D `GltfModelEntity` nodes spawned at gaze points.
- [ ] **Ghost Coding**: Render real-time code generation streams as volumetric "code clouds" next to the physical hardware they affect.
- [ ] **Spatial Debugging**: Map telemetry metrics (latency, CPU, errors) to visual shaders on 3D nodes (e.g., a node pulses red during a build failure).
- [ ] **Loop Closure**: Finalize the self-referential loop where the observer monitors, the agent compiles, and the system reflects state back to the observer.
