#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

HOME_DIR="${HOME:-/data/data/com.termux/files/home}"
PREFIX_DIR="${PREFIX:-/data/data/com.termux/files/usr}"
PROJECT="${LUDOMADA_PROJECT_DIR:-$HOME_DIR/LudoMada}"
STATE="$HOME_DIR/.config/ludomada"
BIN="$HOME_DIR/bin/ludo-agent"
RUNTIME_VERSION="v0.0.16"
RUNTIME_ARCHIVE="tunnel-client-runtime-v0.0.16-linux-arm64.zip"
RUNTIME_SHA256="ec3bc4815e1b9f7a0773b24de45f64841d9aebe170a58b62742e3f1cef4ac088"
KEY_FILE="$HOME_DIR/.config/openai/tunnel-client/runtime.key"
TUNNEL_PROFILE="$HOME_DIR/.config/tunnel-client/ludomada-termux.yaml"
TUNNEL_PID="$STATE/personal-mcp-runtime.pid"
TUNNEL_LOG="$STATE/personal-mcp-runtime.log"
HEALTH_PORT="${PERSONAL_MCP_HEALTH_PORT:-8790}"
DASHBOARD_DIR="$HOME_DIR/ludomada-mcp-commander"

mkdir -p "$STATE" "$HOME_DIR/bin"

say() { printf '[LudoMada] %s\n' "$*"; }
die() { printf '[LudoMada] ERROR: %s\n' "$*" >&2; exit 1; }

command -v curl >/dev/null 2>&1 || die "curl not installed"
command -v unzip >/dev/null 2>&1 || die "unzip not installed"
command -v python3 >/dev/null 2>&1 || die "python3 not installed"
[ -d "$PROJECT" ] || die "LudoMada project not found: $PROJECT"
[ -f "$PROJECT/tools/personal-mcp/termux_server.py" ] || die "Personal MCP server missing"
[ -f "$KEY_FILE" ] || die "local runtime key missing: $KEY_FILE"
[ -f "$TUNNEL_PROFILE" ] || die "tunnel profile missing: $TUNNEL_PROFILE"

TUNNEL_ID="$(sed -n 's/^[[:space:]]*tunnel_id:[[:space:]]*"\([^"]*\)".*/\1/p' "$TUNNEL_PROFILE" | head -n1)"
[ -n "$TUNNEL_ID" ] || die "no tunnel_id in $TUNNEL_PROFILE"

install_runtime() {
  local archive="$HOME_DIR/.cache/ludomada-personal-mcp/$RUNTIME_ARCHIVE"
  local extract="$HOME_DIR/.cache/ludomada-personal-mcp/runtime-install"
  mkdir -p "$HOME_DIR/.cache/ludomada-personal-mcp" "$extract"
  if [ ! -x "$BIN" ]; then
    say "Downloading official tunnel runtime $RUNTIME_VERSION..."
    curl -fL --retry 3 --connect-timeout 15 -o "$archive" \
      "https://github.com/openai/tunnel-client/releases/download/$RUNTIME_VERSION/$RUNTIME_ARCHIVE"
    printf '%s  %s\n' "$RUNTIME_SHA256" "$archive" | sha256sum -c -
    rm -rf "$extract"
    mkdir -p "$extract"
    unzip -oq "$archive" -d "$extract"
    local src="$extract/tunnel-client-runtime"
    [ -x "$src" ] || { chmod 700 "$src"; }
    [ -f "$src" ] || die "runtime binary missing after extraction"
    install -m 700 "$src" "$BIN"
  fi
  "$BIN" --version | grep -q 'flavor=runtime' || die "runtime binary is not the run-only flavor"
}

start_if_down() {
  local url="$1"
  local label="$2"
  shift 2
  if curl -fsS --max-time 2 "$url" >/dev/null 2>&1; then
    say "$label already running"
    return 0
  fi
  "$@" >/dev/null 2>&1 &
  say "$label started pid=$!"
}

install_runtime

if ! curl -fsS --max-time 2 "http://127.0.0.1:8787/mcp" >/dev/null 2>&1; then
  if [ -f "$DASHBOARD_DIR/server.mjs" ]; then
    (cd "$DASHBOARD_DIR" && nohup node server.mjs --http >"$STATE/commander-server.log" 2>&1 </dev/null &)
    say "Commander MCP HTTP server starting"
  fi
fi

if ! curl -fsS --max-time 2 "http://127.0.0.1:8788/" >/dev/null 2>&1; then
  if [ -f "$DASHBOARD_DIR/dashboard.mjs" ]; then
    (cd "$DASHBOARD_DIR" && nohup node dashboard.mjs >"$STATE/commander-dashboard.log" 2>&1 </dev/null &)
    say "Commander Web Panel starting"
  fi
fi

if [ -f "$TUNNEL_PID" ] && kill -0 "$(cat "$TUNNEL_PID")" 2>/dev/null; then
  say "Personal MCP tunnel already running pid=$(cat "$TUNNEL_PID")"
else
  rm -f "$TUNNEL_PID"
  say "Connecting GPT <-> Termux..."
  nohup "$BIN" run \
    --control-plane.tunnel-id "$TUNNEL_ID" \
    --control-plane.api-key "file:$KEY_FILE" \
    --mcp.command "$PREFIX_DIR/bin/python3 $PROJECT/tools/personal-mcp/termux_server.py" \
    --health.listen-addr "127.0.0.1:$HEALTH_PORT" \
    --mcp.startup-wait-timeout 30s \
    --log.file "$TUNNEL_LOG" \
    --log.format json \
    --pid.file "$TUNNEL_PID" \
    >/dev/null 2>&1 &
  sleep 2
fi

if curl -fsS --max-time 5 "http://127.0.0.1:$HEALTH_PORT/healthz" >/dev/null 2>&1; then
  say "READY"
else
  say "Tunnel process started but health is not ready yet; see $TUNNEL_LOG"
fi

printf '\nLudoMada Commander\n'
printf '  MCP health : http://127.0.0.1:%s/healthz\n' "$HEALTH_PORT"
printf '  Web Panel  : http://127.0.0.1:8788/\n'
printf '  Tunnel     : CONNECTED CONFIGURED\n'
printf '  Project    : %s\n' "$PROJECT"
