# LudoMada Personal MCP Commander

Production-oriented developer control plane for Termux/Android.

## Architecture

`ChatGPT / OpenAI Secure MCP Tunnel → tunnel-client → Node MCP Commander → Android / Termux / Browser / GitHub / Build`

The canonical MCP implementation lives in this directory. Runtime dependencies are installed locally with npm and are intentionally ignored by Git.

## Capabilities

The MCP server exposes:

- workspace file read/write/edit/move/create with path confinement
- atomic writes and rollback snapshots
- audited shell execution with command hashes
- background developer jobs with timeouts, persistence, logs and cancellation
- production health and security diagnostics
- Android ADB devices, logs, screenshots, APK install and app launch
- GitHub CLI PR/review/merge/actions access
- Chromium DevTools Protocol navigation, JavaScript evaluation and screenshots
- LudoMada inventory and project discovery
- MCP HTTP transport for the Web Panel

## Safety boundaries

Workspace paths are confined to configured workspace roots after symlink resolution. Runtime keys and Commander configuration are checked for private file modes.

The Android runtime is non-root on the current device. Full Linux/Android root operations are therefore not claimed or simulated.

Commands are executed with the local Termux account. Audit logs store command hashes rather than raw shell commands. Job history stores command hashes rather than raw job commands.

## Lifecycle

The normal entry point is:

`bash scripts/ludomada-connect.sh`

Useful commands:

`bash scripts/ludomada-status.sh`
`bash scripts/ludomada-stop.sh`
`bash scripts/bootstrap-personal-mcp-commander.sh`

The watchdog performs local health checks and restarts the control plane after repeated failures. Atomic lock directories prevent concurrent connect/watchdog starts.

## Production verification

From the Commander directory:

`node smoke.mjs`

The smoke test validates MCP initialization, required production tools, and the end-to-end health check.

A clean production preflight can be started through the MCP `developer_job_start` tool with profile `production_preflight`.

## Remote access

The OpenAI Secure MCP Tunnel is the production remote MCP transport. The Web Panel's local HTTP URL remains suitable for local/device use.

The optional public Web Panel tunnel uses an ephemeral browser-tunnel provider and is not treated as a durable production ingress. A named, authenticated domain tunnel should be configured before exposing the Web Panel as a public service.

## Version

Control plane: 1.0.0
