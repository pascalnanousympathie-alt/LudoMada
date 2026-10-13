# LudoMada Personal MCP — Termux device parity

This package is the local MCP endpoint intended to replace Desktop Commander for LudoMada development on the Android/Termux device.

## What it exposes

The stdio MCP server in \`tools/personal-mcp/termux_server.py\` exposes:

- shell execution
- UTF-8 and base64 file read/write
- directory listing/stat/move/create
- recursive source search
- persistent process sessions with stdin/stdout, wait and kill
- Android screen capture
- Android app launch
- APK installation/open-installer
- MCP/tunnel-client self-test

The server runs **on the user's Termux device**, so commands operate on the real LudoMada checkout rather than a cloud sandbox.

## Transport

Use the official OpenAI \`tunnel-client\` as the transport. Do not use Termux \`webtunnel-client\`: that package is a different Tor WebTunnel client.

Required environment:

\`CONTROL_PLANE_API_KEY\`
\`CONTROL_PLANE_TUNNEL_ID\`

The runtime key should be referenced as \`env:CONTROL_PLANE_API_KEY\`; do not commit a literal key.

## Bootstrap

From the LudoMada checkout:

\`\`\`bash
bash scripts/bootstrap-personal-mcp-termux.sh
\`\`\`

The bootstrap:

1. detects ARM64/AMD64
2. downloads OpenAI tunnel-client v0.0.16
3. verifies the release SHA-256
4. installs \`tunnel-client\` under Termux's \$PREFIX/bin
5. creates start/stop helpers under \$HOME/.config/ludomada
6. starts the personal MCP runtime
7. checks the local health endpoint

## Long-lived use

Start:

\`\`\`bash
~/.config/ludomada/start-personal-mcp.sh
\`\`\`

Stop:

\`\`\`bash
~/.config/ludomada/stop-personal-mcp.sh
\`\`\`

Logs:

\`\`\`text
~/.config/ludomada/personal-mcp.log
\`\`\`

## Important boundary

This MCP is deliberately device-first. The GitHub MCP remains the source-control plane; this MCP is the execution/device plane. No gameplay rules, wallet/ledger logic, authentication logic, or payment approval state is modified by this integration.
