#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

VERSION="v0.0.16"
PREFIX_DIR="${PREFIX:-/data/data/com.termux/files/usr}"
BIN_DIR="$PREFIX_DIR/bin"
WORK="$HOME/.cache/ludomada-personal-mcp"
CFG_DIR="$HOME/.config/ludomada"
PID_FILE="$CFG_DIR/personal-mcp.pid"
LOG_FILE="$CFG_DIR/personal-mcp.log"
SERVER_REL="tools/personal-mcp/termux_server.py"
PROJECT="${LUDOMADA_PROJECT_DIR:-$HOME/LudoMada}"
SERVER="$PROJECT/$SERVER_REL"

mkdir -p "$WORK" "$CFG_DIR" "$BIN_DIR"

arch="$(uname -m)"
case "$arch" in
  aarch64|arm64)
    ASSET="tunnel-client-$VERSION-linux-arm64.zip"
    SHA256="963d0384aaa7c798778479c45673f9051a4039dd891aef8f8978d2a1a628b74f"
    ;;
  x86_64|amd64)
    ASSET="tunnel-client-$VERSION-linux-amd64.zip"
    SHA256="d60cdba019bce451bcc3a15478cc5b9cb11270b049f5b56ea39a80b517f8b117"
    ;;
  *)
    echo "Unsupported architecture: $arch" >&2
    exit 2
    ;;
esac

if [ ! -x "$BIN_DIR/tunnel-client" ] || ! "$BIN_DIR/tunnel-client" --version >/dev/null 2>&1; then
  URL="https://github.com/openai/tunnel-client/releases/download/$VERSION/$ASSET"
  ZIP="$WORK/$ASSET"
  echo "Downloading official OpenAI tunnel-client $VERSION ($arch)..."
  curl -fL --retry 3 --connect-timeout 15 -o "$ZIP" "$URL"

  python3 - "$ZIP" "$SHA256" <<'PY'
import hashlib, sys
path, expected = sys.argv[1], sys.argv[2]
h = hashlib.sha256()
with open(path, "rb") as f:
    for chunk in iter(lambda: f.read(1024 * 1024), b""):
        h.update(chunk)
actual = h.hexdigest()
if actual != expected:
    raise SystemExit(f"SHA256 mismatch: {actual} != {expected}")
print("SHA256=PASS")
PY

  python3 - "$ZIP" "$BIN_DIR" <<'PY'
from pathlib import Path
import sys, zipfile
zip_path, out_dir = sys.argv[1], Path(sys.argv[2])
with zipfile.ZipFile(zip_path) as z:
    names = z.namelist()
    candidates = [n for n in names if n.endswith("/tunnel-client") or n == "tunnel-client"]
    if not candidates:
        raise SystemExit("tunnel-client binary not found in release archive")
    (out_dir / "tunnel-client").write_bytes(z.read(candidates[0]))
PY
  chmod 700 "$BIN_DIR/tunnel-client"
fi

command -v python3 >/dev/null 2>&1 || { echo "python3 is required"; exit 3; }
[ -f "$SERVER" ] || { echo "Missing $SERVER"; echo "Run this from the LudoMada project checkout."; exit 4; }

if [ -z "${CONTROL_PLANE_API_KEY:-}" ]; then
  echo "CONTROL_PLANE_API_KEY is not set." >&2
  echo "Keep it in the shell environment or a user-owned secret manager; do not commit it." >&2
  exit 5
fi

if [ -z "${CONTROL_PLANE_TUNNEL_ID:-}" ]; then
  echo "CONTROL_PLANE_TUNNEL_ID is not set." >&2
  echo "Use the tunnel ID already assigned to this personal MCP." >&2
  exit 6
fi

echo "Stopping previous personal MCP instance (if any)..."
if [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
  kill "$(cat "$PID_FILE")" 2>/dev/null || true
  sleep 1
fi

cat > "$CFG_DIR/start-personal-mcp.sh" <<'SH'
#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
PREFIX_DIR="${PREFIX:-/data/data/com.termux/files/usr}"
PROJECT="${LUDOMADA_PROJECT_DIR:-$HOME/LudoMada}"
SERVER="$PROJECT/tools/personal-mcp/termux_server.py"
CFG_DIR="$HOME/.config/ludomada"
PID_FILE="$CFG_DIR/personal-mcp.pid"
LOG_FILE="$CFG_DIR/personal-mcp.log"

[ -x "$PREFIX_DIR/bin/tunnel-client" ] || { echo "tunnel-client missing; rerun bootstrap."; exit 2; }
[ -f "$SERVER" ] || { echo "MCP server missing: $SERVER"; exit 3; }
[ -n "${CONTROL_PLANE_API_KEY:-}" ] || { echo "CONTROL_PLANE_API_KEY is not set."; exit 4; }
[ -n "${CONTROL_PLANE_TUNNEL_ID:-}" ] || { echo "CONTROL_PLANE_TUNNEL_ID is not set."; exit 5; }

export LUDOMADA_PROJECT_DIR="$PROJECT"

if [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
  echo "PERSONAL_MCP_ALREADY_RUNNING pid=$(cat "$PID_FILE")"
  exit 0
fi

nohup "$PREFIX_DIR/bin/tunnel-client" run \
  --control-plane.tunnel-id "$CONTROL_PLANE_TUNNEL_ID" \
  --control-plane.api-key "env:CONTROL_PLANE_API_KEY" \
  --mcp.command "python3 $SERVER" \
  --health.listen-addr "127.0.0.1:8790" \
  >>"$LOG_FILE" 2>&1 < /dev/null &
echo $! > "$PID_FILE"
echo "PERSONAL_MCP_STARTED pid=$!"
echo "log=$LOG_FILE"
SH
chmod 700 "$CFG_DIR/start-personal-mcp.sh"

cat > "$CFG_DIR/stop-personal-mcp.sh" <<'SH'
#!/data/data/com.termux/files/usr/bin/bash
set -u
PID_FILE="$HOME/.config/ludomada/personal-mcp.pid"
if [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
  kill "$(cat "$PID_FILE")" 2>/dev/null || true
  echo "PERSONAL_MCP_STOP_REQUESTED"
else
  echo "PERSONAL_MCP_NOT_RUNNING"
fi
SH
chmod 700 "$CFG_DIR/stop-personal-mcp.sh"

"$CFG_DIR/start-personal-mcp.sh"

sleep 2
if curl -fsS --connect-timeout 2 --max-time 5 http://127.0.0.1:8790/healthz >/dev/null 2>&1; then
  echo "PERSONAL_MCP_HEALTHZ=PASS"
else
  echo "PERSONAL_MCP_HEALTHZ=NOT_READY"
  echo "Inspect: $LOG_FILE"
fi

echo "DONE: personal MCP uses official tunnel-client, not webtunnel-client."
