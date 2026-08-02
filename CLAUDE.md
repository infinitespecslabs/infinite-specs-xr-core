# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Android XR architectural research sandbox exploring "Strange Loops": gaze/voice telemetry from smart glasses is parsed into intent (Gemini), streamed via an MCP-style SSE bridge, and drives an external coding agent that writes code and streams results back to the HUD. Single-module Gradle project, package `com.infinitespecs.xr`, version `0.1.0-alpha`.

**Current objective** (see `ROADMAP.md`): ship a native Android XR clone of Even Realities Terminal Mode — reverse-engineer the proprietary `@evenrealities/even-terminal` workstation daemon protocol and replace it with our own, then support it across all three Android XR device classes (display/headset, wired glasses, audio-only glasses), not just Even Realities' G2 hardware.

**Architecture**: the headset is an MCP *client* connecting out to a workstation daemon (`even-terminal`, port 3456) that hosts the coding agent — this is the current direction per `docs/SYSTEM_DESIGN.md`. The old README description (on-device Ktor MCP server, port 8080) is superseded. Verify against `McpSpecificationBridge.kt` if unsure.

## Tech stack — do not downgrade

Bleeding-edge/preview versions are intentional: Kotlin 2.2.10, AGP 9.2.1, Java 17, Jetpack XR SDKs all alpha (`androidx.xr.compose`, `androidx.xr.scenecore`, `androidx.xr.runtime`), Ktor 2.3.12. Targets Android XR Developer Preview 4.

`gradle.properties` sets `android.builtInKotlin=false` deliberately, to avoid AGP 9.x's built-in Kotlin support conflicting with the manually-applied Kotlin Gradle Plugin. Don't "fix" this.

## Build, test, lint

- Gradle requires JDK 17+. If the system `java` is older (check `java -version`), prefix Gradle commands with `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"` (Android Studio's bundled JBR) rather than assuming the build is broken.
- Build: `./gradlew assembleDebug` — verify this before proposing deep logic changes.
- Unit tests: `./gradlew :app:test` (JVM-only; single test: `./gradlew :app:test --tests "com.infinitespecs.xr.StrangeLoopTest"`). `StrangeLoopTest.kt` is the primary end-to-end integration test for the closed-loop system.
- `testOptions.unitTests.isReturnDefaultValues = true` is set because production code touches unstubbed `androidx.xr.*` classes (e.g. `Ray`, `Vector3`) that would otherwise throw in plain JUnit.
- **Lint/format: `./gradlew spotlessApply` is mandatory before every commit** — never commit without it.
- ktlint is configured (root `build.gradle.kts`) with several standard rules disabled: `no-wildcard-imports`, `filename`, `function-naming`, `backing-property-naming`, `value-parameter-comment`. Trailing commas are mandatory in multi-line parameter/argument lists.
- Don't add unused imports (Spotless strips them, but avoid introducing them).

## XR/Compose conventions

- Wrap all spatial content in `Subspace`.
- Use `SpatialPanel` with `SubspaceModifier.transformingMovable()` for main HUD panels.
- Use `Orbiter` / `OrbiterAnchorPoint` for secondary controls anchored to panels.
- HUD text must use `FontFamily.Monospace` and the `HudColors` palette object (`SpatialPanelComposable.kt`).
- App launches in `XR_ACTIVITY_START_MODE_FULL_SPACE_MANAGED` — keep spatial UI compatible with this mode.

## Workflow

- Check `ROADMAP.md` before starting a new feature.
- Commit messages: Conventional Commits (`feat:`, `fix:`, `chore:`, `docs:`, `build:`, scoped variants like `fix(perception):`), descriptive subjects.
- `local.properties` (gitignored) needs `sdk.dir` and `GOOGLE_AI_API_KEY` (Gemini, injected via `BuildConfig.GOOGLE_AI_API_KEY`) — without it, `SpatialIntentParser` fails at runtime (unit tests mock this out).
- `android:usesCleartextTraffic="true"` is intentional (local SSE/MCP bridge over LAN), not an oversight — hardening tracked in `ROADMAP.md` Phase 2.
- `simulated-agent-worktree/` is gitignored leftover scratch output from the now-retired `macbook-agent` Node daemon simulator (generated Kotlin files) — not hand-authored source, not a real git worktree despite the name. Safe to ignore/delete if encountered.
- Avoid reading/grepping the root `.hprof` heap dump or `even-terminal-*.log` files if present — large, gitignored, not source.

## Non-Gradle tooling (separate from the Android build)

- `daemon/` — the workstation daemon (TypeScript/Node), replacing the proprietary `even-terminal` package. `cd daemon && npm install && npm run dev` for local development (`npm run build && npm start` for the compiled build). Requires the `claude` CLI installed and authenticated on the host — `@anthropic-ai/claude-agent-sdk` spawns it as a subprocess. Protocol is documented in `docs/SYSTEM_DESIGN.md` §2; that doc is the source of truth, not the code structure. Pinned to `@anthropic-ai/claude-agent-sdk@0.2.141` exactly (not a caret range) — see `docs/SYSTEM_DESIGN.md` §6 before bumping it.
- `scripts/setup_github_tracker.py` — one-off script that bulk-creates GitHub issues from a hardcoded roadmap list; requires authenticated `gh` CLI. Not part of the normal dev loop.
