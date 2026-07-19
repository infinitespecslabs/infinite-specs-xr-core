#!/usr/bin/env python3
"""
setup_github_tracker.py
Populates the GitHub issue board with the Even Terminal Android XR port roadmap.
Requires the 'gh' command line tool and active login authentication.
Run: gh auth login
Then: python3 scripts/setup_github_tracker.py
"""

import json
import subprocess
import sys
import shutil

# Ensure gh CLI is installed
if not shutil.which("gh"):
    print("Error: The GitHub CLI ('gh') is not installed or not in your PATH.")
    print("Please install it from https://cli.github.com/ and authenticate via: gh auth login")
    sys.exit(1)

# Check if authenticated
auth_check = subprocess.run(["gh", "auth", "status"], capture_output=True, text=True)
if auth_check.returncode != 0:
    print("Warning: GitHub CLI is not authenticated or token is invalid.")
    print("Run the following command on your workstation before running this script:")
    print("  gh auth login")
    sys.exit(1)

issues = [
    {
        "title": "feat: [EvenTerminal XR] Connect Ktor Client to Workstation SSE Server",
        "body": """### Goal
Implement the core network networking bridge in the Android app to connect to the local workstation's `even-terminal` Express server.

### Requirements
- Add Ktor HTTP Client and SSE dependencies to `build.gradle.kts`.
- Build a network connection interface that parses configuration (IP Address, Authorization Bearer Token).
- Establish an active SSE (Server-Sent Events) subscription to `GET /api/events?sessionId=...`.
- Parse incoming events such as `status`, `text_delta`, `tool_start`, `tool_end`, `permission_request`, `user_question`, and `result`.
- Stream status events into the Compose UI flow.

### Verification
- Run local tests using Ktor mock engine or a local Express server.
""",
        "label": "EvenTerminal,Phase-1"
    },
    {
        "title": "feat: [EvenTerminal XR] Amber Waveguide HUD UI and Session List",
        "body": """### Goal
Develop a polished spatial user interface inside the Android XR app displaying active sessions and terminal logs.

### Requirements
- Redesign `SpatialPanelComposable.kt` with a semi-transparent dark amber aesthetic (simulating monocle waveguides).
- Create a Pairing/Connection screen allowing manual entry of host IP and Token (or scanning support).
- Create a Session Selector showing active resumable coding sessions retrieved via `GET /api/sessions`.
- Implement a scrollable terminal viewport that dynamically displays log lines and highlights new incoming deltas with micro-animations.
- Show running execution metrics in the footer (cost, duration, token usage).

### Verification
- Run layouts in Android Studio preview modes and manual testing in the emulator.
""",
        "label": "EvenTerminal,Phase-2"
    },
    {
        "title": "feat: [EvenTerminal XR] Volumetric Diff Viewer and Interactive Tool Approvals",
        "body": """### Goal
Create volumetric confirmation cards for agent tool execution requests, including a visual code diff viewer.

### Requirements
- Listen for `permission_request` events on the SSE stream.
- When an edit/write tool is called, parse the file path and modification parameters.
- Spawn a secondary floating panel displaying a code diff viewer (showing green additions and red deletions).
- Create glowing visual buttons for 'Approve', 'Deny', and 'Always Allow'.
- Bind gaze targeting and pinch hand-gestures to submit authorization payload back to `POST /api/permission-response`.

### Verification
- Verify that clicking 'Approve' triggers a successful network POST and resumes agent execution.
""",
        "label": "EvenTerminal,Phase-3"
    },
    {
        "title": "feat: [EvenTerminal XR] Integrated Speech-to-Text for Dictation and Control",
        "body": """### Goal
Enable hands-free agent control using on-device Speech-to-Text dictation.

### Requirements
- Integrate Android's `SpeechRecognizer` platform API to capture user voice intent.
- Implement a gaze-activated dictation button: gaze at mic, pinch to speak.
- Process the spoken response locally and format it as the answer payload for `AskUserQuestion` queries.
- Send voice answers back to the workstation via `POST /api/question-response` or post new prompts via `POST /api/prompt`.
- Implement basic voice macros: "Approve" (allows pending tool), "Stop" (interrupts execution).

### Verification
- Speak mock prompts and verify speech parses accurately into text values in logs.
""",
        "label": "EvenTerminal,Phase-4"
    },
    {
        "title": "feat: [EvenTerminal XR] Workspace Anchoring and Window Positioning",
        "body": """### Goal
Utilize Jetpack Compose for XR and SceneCore to allow spatial placement of terminal windows.

### Requirements
- Implement SceneCore `AnchorEntity` in `MainActivity.kt`.
- Enable drag and drop behavior using Jetpack Compose XR `transformingMovable` modifiers.
- Allow pinning the terminal HUD to physical surfaces (like desktop, wall, or next to computer monitor) using plane detection.
- Add support for head-locked mode (HUD follows view margins with low latency) and space-locked mode (stays anchored in the room).
- Support spawning and maintaining multiple concurrent terminal monitoring panels.

### Verification
- Deploy to device/emulator and verify panels anchor and maintain coordinates when lookaway events occur.
""",
        "label": "EvenTerminal,Phase-5"
    },
    {
        "title": "test: [EvenTerminal XR] Mock daemon server & integration testing",
        "body": """### Goal
Build a testing suite to validate all headset UI and connection states without calling production agent pipelines.

### Requirements
- Create `scripts/mock_terminal_daemon.js` using Express.
- Setup simulated endpoints for `/api/sessions`, `/api/prompt`, `/api/events`, and `/api/permission-response`.
- Script a loop that outputs thinking pulses, streams long textual changes, prompts for file-write permission, and simulates a successful compilation log.
- Validate headset reconnection logic, network error states, and token auth failures.

### Verification
- Verify entire developer loop completes successfully in the Android XR emulator connecting to the mock daemon.
""",
        "label": "EvenTerminal,Phase-6"
    }
]

print("🚀 Starting creation of GitHub issues for Even Terminal Android XR Port...")

for issue in issues:
    print(f"Creating issue: {issue['title']}...")
    
    # Run gh issue create
    cmd = [
        "gh", "issue", "create",
        "--title", issue["title"],
        "--body", issue["body"],
        "--label", issue["label"]
    ]
    
    result = subprocess.run(cmd, capture_output=True, text=True)
    
    if result.returncode == 0:
        # Extract the issue URL from stdout
        issue_url = result.stdout.strip()
        print(f"  Successfully created: {issue_url}")
    else:
        print(f"  Failed to create issue.")
        print(f"  Error: {result.stderr.strip()}")

print("\n🎉 Issue backlog populating complete! Check your repository's issues page on GitHub.")
