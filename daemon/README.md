# @infinitespecs/xr-daemon

Workstation daemon for `infinite-specs-xr-core`. Exposes a Claude Code session over REST + SSE for the Android XR client, replacing the proprietary `@evenrealities/even-terminal` daemon. Protocol is documented in full at [`../docs/SYSTEM_DESIGN.md`](../docs/SYSTEM_DESIGN.md) §2 — that document is the source of truth; this README only covers running the daemon.

**Prerequisite**: the [Claude Code CLI](https://code.claude.com) must be installed and authenticated (`claude auth status`) on this machine — the underlying `@anthropic-ai/claude-agent-sdk` spawns it as a subprocess.

## Running

```bash
npm install
npm run dev            # tsx watch, for development
# or
npm run build && npm start   # compiled production build
```

## CLI flags

| Flag | Alias | Default | Description |
| :--- | :--- | :--- | :--- |
| `--port` | `-p` | `3456` | Server port |
| `--token` | `-t` | `BRIDGE_TOKEN` env, else random | Auth token (also accepted as `Authorization: Bearer <token>` or `?token=`) |
| `--name` | `-n` | — | Display name shown in the pairing banner/QR |
| `--cwd` | `-d` | `process.cwd()` | Project directory for new sessions |
| `--verbose` | | `false` | Log raw SDK messages |
| `--log-file` | | — | Tee logs to a file |

On startup the daemon prints a QR code encoding the connection URL (`http://<lan-ip>:<port>?token=<token>&defaultProvider=claude`) — scan it, or enter host/token manually in the Android XR app's pairing screen.

## Not yet implemented

Alternate LAN discovery (`--tailscale`/`--interface`), public tunnel helpers (`--expose`), and shell completion are intentionally out of scope for now — see `ROADMAP.md` Phase 1 in the repo root for rationale.
