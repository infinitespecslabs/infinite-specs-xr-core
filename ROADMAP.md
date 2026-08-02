# 🌀 Infinite Specs XR: Strategic Roadmap

This document outlines the plan to ship a native Android XR clone of **Even Realities Terminal Mode** — a spatial HUD that lets a developer monitor and steer an AI coding agent (Claude Code) running on their workstation, without being tied to a desk. Unlike the original, this version targets the full range of Android XR device classes: wired display glasses, audio-only glasses, and standalone headsets.

The original product depends on the proprietary `@evenrealities/even-terminal` workstation daemon and Even Realities' G2 hardware/mobile app. We are reverse-engineering the workstation-side protocol and shipping our own daemon + client, so the experience runs on any Android XR device without that dependency.

---

## Phase 0: Reverse-Engineer the Real Daemon ([#9](https://github.com/infinitespecslabs/infinite-specs-xr-core/issues/9)) ✅
**Goal**: Fully understand the actual `even-terminal` server before building a replacement.
- [x] Install `@evenrealities/even-terminal@latest` locally and read the unpacked source (CLI + server).
- [x] Document session lifecycle: how it spawns/manages the Claude Code (and other provider) child processes and captures their output.
- [x] Document auth/pairing token issuance and any endpoints not already captured in `docs/SYSTEM_DESIGN.md` (e.g. "start new session," provider switching, session replay semantics).
- [x] Diff findings against what `McpSpecificationBridge.kt` already assumes; list protocol gaps.

Findings: see `docs/SYSTEM_DESIGN.md` §2, §5, §6.

---

## Phase 1: Our Own Workstation Daemon ([#10](https://github.com/infinitespecslabs/infinite-specs-xr-core/issues/10)) ✅
**Goal**: Replace the dependency on the real npm package with a daemon we ship ourselves.
- [x] Implement the REST+SSE contract the Android client already speaks: SSE events (`status`, `text_delta`, `tool_start`, `tool_end`, `permission_request`, `user_question`, `result`) and REST actions (`permission-response`, `question-response`, `interrupt`, `prompt`, `sessions`). Implemented full protocol parity from `docs/SYSTEM_DESIGN.md` §2, not just this subset.
- [x] Spawn Claude Code as a child process and translate its output into that event schema — via `@anthropic-ai/claude-agent-sdk` directly (same SDK even-terminal itself depends on), not by parsing CLI stdout.
- [x] Real bearer-token pairing flow (QR or manual entry, per `docs/SYSTEM_DESIGN.md` §5.1).
- [x] Retire `macbook-agent/server.js` — deleted; replaced by `daemon/`.

Implementation: `daemon/` (TypeScript). Verified end-to-end against real Claude Code sessions on this machine — prompt/resume/queueing, full SSE event sequence, `AskUserQuestion` round-trip, interrupt, and the production build all confirmed working. See `daemon/README.md` for usage.

---

## Phase 2: Security Hardening ([#11](https://github.com/infinitespecslabs/infinite-specs-xr-core/issues/11))
**Goal**: Move off the development-only network posture used while bootstrapping.
- [ ] Replace the global `android:usesCleartextTraffic="true"` manifest override with a `network_security_config.xml` scoped to private IP ranges (`10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`).
- [ ] Enforce real end-to-end bearer-token auth (no placeholder/empty tokens).
- [ ] Bind daemon sockets to loopback when using ADB port-forwarding tunnels.

---

## Phase 3: Adaptive Presentation Layer ([#12](https://github.com/infinitespecslabs/infinite-specs-xr-core/issues/12))
**Goal**: Support all three Android XR device classes by abstracting the UI behind a device-capability layer, mirroring the existing `SpeechTranscriptionEngine` pattern.
- [x] **Display/headset presenter**: spatial HUD using `Subspace`/`SpatialPanel`/`Orbiter`, gaze-highlight + pinch-approve — already built for the standalone headset case.
- [ ] **Wired glasses presenter**: flattened non-spatial 2D Compose UI (no `SceneCore` dependency) for tethered display-only hardware.
- [ ] **Audio-only glasses presenter**: no visual UI — spatialized audio/TTS status cues, voice-only permission and question responses.
- [ ] Runtime device-capability detection to select the correct presenter at launch.

---

## Phase 4: Voice Interaction Parity ([#13](https://github.com/infinitespecslabs/infinite-specs-xr-core/issues/13))
**Goal**: Make voice the primary input path where there's no display to tap or gaze at.
- [ ] Implement `LocalAndroidSpeechEngine` (interface already defined in `docs/SYSTEM_DESIGN.md` §3.1) using on-device `SpeechRecognizer`/Gemini Nano.
- [ ] Hold-to-talk / tap-equivalent gesture parity across device classes (hand pinch, physical button on wired glasses, etc.).
- [ ] (Stretch) `RemoteWorkstationSpeechEngine` streaming raw audio to the workstation daemon for higher-accuracy transcription.

---

## Phase 5: Multi-Session UX ([#14](https://github.com/infinitespecslabs/infinite-specs-xr-core/issues/14))
**Goal**: Let a developer monitor multiple concurrent agent sessions, per the original product's core value proposition.
- [ ] Session list/switcher UI, adapted per device class (visual list vs. audio menu) — backend groundwork (`sessionsFlow`) already exists in `McpSpecificationBridge.kt`.
- [ ] Persistent pairing: store host + token per paired workstation, auto-reconnect on launch.

---

## Phase 6: Packaging & Distribution ([#15](https://github.com/infinitespecslabs/infinite-specs-xr-core/issues/15))
**Goal**: Ship something end users install without ever touching `even-terminal` or knowing it existed.
- [ ] Bundled installer or first-run pairing flow that installs our daemon on the developer's machine.
- [ ] Cross-platform daemon support, starting with macOS (matching current `macbook-agent` scope), then Linux/Windows.
- [ ] Finalize a product name distinct from "Even Terminal" / "Terminal Mode" before any public release.

---

## Phase 7: Release Hardening ([#16](https://github.com/infinitespecslabs/infinite-specs-xr-core/issues/16))
**Goal**: Production-readiness pass before shipping to real users.
- [ ] Token rotation and revocation.
- [ ] Crash reporting / telemetry.
- [ ] End-user setup documentation.
