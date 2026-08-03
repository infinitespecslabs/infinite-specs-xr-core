---
name: file-bug
description: File a GitHub issue for a bug just found, pulling in adb logcat evidence when the bug involves the Android app's live behavior. Use when the user asks to track/file/report a bug, or says something like "create an issue for this" after a bug is identified during testing or live debugging.
---

File a well-evidenced GitHub issue for a bug that's just been identified in conversation (during live testing, code review, or investigation). Don't invoke this for feature requests or roadmap work — that's what `ROADMAP.md` and its phase issues are for.

## 1. Confirm what the bug actually is

Before writing anything, make sure you can state precisely: what was expected, what actually happened, and why (if the root cause is already known from the conversation). If the root cause isn't yet clear, trace it in the actual source — cite real `file:line`, don't guess. Never file an issue speculating about a cause you haven't verified by reading the code.

## 2. Gather adb logcat evidence, if the bug involves the Android app's live behavior

Skip this section entirely for bugs that don't involve the running Android app (e.g. a pure daemon/unit-test bug) — logcat evidence is only relevant when the bug was observed (or is reproducible) through the app's actual runtime behavior.

1. Confirm a device/emulator is connected: `adb devices -l`. If more than one is attached, ask which to use (or infer from context if only one was active in the conversation).
2. Get the app's current pid: `adb -s <device> shell pidof com.infinitespecs.xr`. The pid changes on every reinstall/relaunch — always fetch it fresh, don't reuse one from earlier in the conversation.
3. Pull recent logs scoped to that pid, filtered to the app's own tag: `adb -s <device> logcat -d -t 500 --pid=<pid> | grep McpSpecBridge` (widen `-t` or drop the grep if the relevant lines might carry a different tag, e.g. a raw stack trace or a different subsystem).
4. Extract the smallest excerpt that actually demonstrates the bug — the request that triggered it and the response/state that shows it went wrong. If you're verifying a *fix* rather than filing a fresh bug, capture both the before (reproducing the bug) and after (confirming the fix) excerpts for the issue comment, not the initial issue body.

Quote raw log lines verbatim in the issue — don't paraphrase them into prose. The exact JSON/log text is what makes the report verifiable by someone else.

## 3. Draft the issue

Match the structure already established in this repo's bug issues (see issues #17, #18 for reference examples):

- **Title**: `bug: <short, specific description>` (imperative/descriptive, not a question)
- **Summary**: one or two sentences — what's broken, and how it was confirmed (e.g. "Confirmed via `adb logcat` against a real emulator + daemon, not mocked" — say so explicitly when true, since it's stronger evidence than a hypothesized bug)
- **Repro**: numbered steps, plus the actual logcat/log excerpt from step 2 in a fenced code block
- **Root cause**: the real explanation, with `file:line` citations and short code quotes for each hop in the call chain if the bug crosses files
- **Proposed fix**: only include this if you're actually confident in it — a concrete code sketch is more useful than a vague direction
- **Files involved**: bullet list of the files implicated

## 4. Labels

Check what's actually available before assuming: `gh label list --repo <owner>/<repo>`. For this repo, use `bug` plus `EvenTerminal` (the project-wide label). Don't invent labels that don't exist.

## 5. Create it and report back

```
gh issue create --repo <owner>/<repo> --title "..." --label "bug,EvenTerminal" --body "$(cat <<'EOF'
...
EOF
)"
```

Report the issue URL back to the user. Don't close it yourself — that happens later, once the fix is verified (see the pattern in issues #17/#18: comment with the fix + verification evidence, then close).
