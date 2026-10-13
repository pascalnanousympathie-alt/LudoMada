#!/data/data/com.termux/files/usr/bin/bash
set -u
HOME_DIR="${HOME:-/data/data/com.termux/files/home}"
PREFIX_DIR="${PREFIX:-/data/data/com.termux/files/usr}"
PROJECT="${LUDOMADA_PROJECT_DIR:-$HOME_DIR/LudoMada}"
KEY_FILE="$HOME_DIR/.config/openai/tunnel-client/runtime.key"
PROFILE="$HOME_DIR/.config/tunnel-client/ludomada-termux.yaml"
BIN="$HOME_DIR/bin/ludo-agent"
LOG="$HOME_DIR/.config/ludomada/personal-mcp-runtime-debug.log"
PORT="${PERSONAL_MCP_HEALTH_PORT:-8790}"
TID="$(sed -n 's/^[[:space:]]*tunnel_id:[[:space:]]*"\([^"]*\)".*/\1/p' "$PROFILE" | head -n1)"
rm -f "$HOME_DIR/.config/ludomada/personal-mcp-runtime.pid"
: > "$LOG"
[ -x "$BIN" ] || { echo "RUNTIME_MISSING" | tee -a "$LOG"; exit 2; }
[ -f "$KEY_FILE" ] || { echo "KEY_FILE_MISSING" | tee -a "$LOG"; exit 3; }
[ -n "$TID" ] || { echo "TUNNEL_ID_MISSING" | tee -a "$LOG"; exit 4; }

echo "RUNTIME=$("$BIN" --version)"
echo "PROJECT=$PROJECT"
echo "PYTHON=$PREFIX_DIR/bin/python3"
echo "TUNNEL_ID_PRESENT=yes"
echo "STARTING..."
"$BIN" run   --control-plane.tunnel-id "$TID"   --control-plane.api-key "file:$KEY_FILE"   --mcp.command "$PREFIX_DIR/bin/python3 $PROJECT/tools/personal-mcp/termux_server.py"   --health.listen-addr "127.0.0.1:$PORT"   --mcp.startup-wait-timeout 30s   --log.file "$LOG"   --log.format json   --pid.file "$HOME_DIR/.config/ludomada/personal-mcp-runtime.pid"
rc=$?
echo "EXIT_CODE=$rc"
exit $rc
