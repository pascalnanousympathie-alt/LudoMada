#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

HOME_DIR="${HOME:-/data/data/com.termux/files/home}"
PREFIX_DIR="${PREFIX:-/data/data/com.termux/files/usr}"
PROJECT="${LUDOMADA_PROJECT_DIR:-$HOME_DIR/LudoMada}"
STATE="$HOME_DIR/.config/ludomada"
KEY_FILE="$HOME_DIR/.config/openai/tunnel-client/runtime.key"
TUNNEL_PROFILE="$HOME_DIR/.config/tunnel-client/ludomada-termux.yaml"
RUNTIME="$HOME_DIR/bin/ludo-agent"
PROOT_TUNNEL="$HOME_DIR/ludomada-mcp-commander/tunnel/tunnel-client"
COMMANDER_EXTERNAL="$HOME_DIR/ludomada-mcp-commander"
COMMANDER_REPO="$PROJECT/tools/personal-mcp-commander"
if [ -f "$COMMANDER_REPO/server.mjs" ]; then
  if [ ! -d "$COMMANDER_REPO/node_modules/@modelcontextprotocol/server" ]; then
    npm --prefix "$COMMANDER_REPO" install --omit=dev >/dev/null 2>npm --prefix "$COMMANDER_REPO" install --omit=dev >/dev/null 2>&1 || die "failed to install production MCP dependencies"1 || { printf "[LudoMada] ERROR: failed to install production MCP dependencies\n" >npm --prefix "$COMMANDER_REPO" install --omit=dev >/dev/null 2>&1 || die "failed to install production MCP dependencies"2; exit 1; }
  fi
  COMMANDER_ROOT="$COMMANDER_REPO"
else
  COMMANDER_ROOT="$COMMANDER_EXTERNAL"
fi
SERVER_JS="$COMMANDER_ROOT/server.mjs"
DASHBOARD_JS="$COMMANDER_ROOT/dashboard.mjs"
MCP_COMMAND="env COMMANDER_HOME=$COMMANDER_ROOT $PREFIX_DIR/bin/node $SERVER_JS"
HEALTH_PORT="${PERSONAL_MCP_HEALTH_PORT:-8790}"
PIDFILE="$STATE/personal-mcp-runtime.pid"
WATCHDOG_PIDFILE="$STATE/watchdog.pid"
LOG="$STATE/personal-mcp.log"
PROOT_LOG="$STATE/personal-mcp-proot.log"

say(){ printf '[LudoMada] %s\n' "$*"; }
die(){ printf '[LudoMada] ERROR: %s\n' "$*" >&2; exit 1; }

[ -d "$PROJECT" ] || die "LudoMada project missing"
[ -f "$PROJECT/tools/personal-mcp/termux_server.py" ] || die "Personal MCP server missing"
[ -f "$KEY_FILE" ] || die "local runtime key missing"
[ -f "$TUNNEL_PROFILE" ] || die "tunnel profile missing"
[ -x "$PREFIX_DIR/bin/python3" ] || die "Termux python3 missing"
[ -x "$PREFIX_DIR/bin/node" ] || die "Termux node missing"
[ -f "$SERVER_JS" ] || die "Canonical Node MCP server missing"

TUNNEL_ID="$(sed -n 's/^[[:space:]]*tunnel_id:[[:space:]]*"\([^"]*\)".*/\1/p' "$TUNNEL_PROFILE" | head -n1)"
[ -n "$TUNNEL_ID" ] || die "tunnel id missing"

mkdir -p "$STATE"
rm -f "$STATE/stop.flag"
CONNECT_LOCK="$STATE/connect.lock"
if ! mkdir "$CONNECT_LOCK" 2>/dev/null; then
  LOCK_PID="$(cat "$CONNECT_LOCK/pid" 2>/dev/null || true)"
  if [ -n "$LOCK_PID" ] && kill -0 "$LOCK_PID" 2>/dev/null; then
    say "connect already running pid=$LOCK_PID"
    exit 0
  fi
  rm -rf "$CONNECT_LOCK"
  mkdir "$CONNECT_LOCK"
fi
printf '%s\n' "$$" > "$CONNECT_LOCK/pid"
trap 'rm -rf "$CONNECT_LOCK"' EXIT INT TERM

# Remove legacy/duplicate MCP tunnels for this tunnel ID. Keep only the canonical Node server.
CANONICAL_PID="$(ps -eo pid=,args= 2>/dev/null | awk -v id="$TUNNEL_ID" -v port="$HEALTH_PORT" -v script="$SERVER_JS" '$0 ~ /tunnel-client run/ && index($0,id) && index($0,script) && index($0, "127.0.0.1:" port) {print $1; exit}')"
if [ -n "$CANONICAL_PID" ] && kill -0 "$CANONICAL_PID" 2>/dev/null && curl -fsS --max-time 3 "http://127.0.0.1:$HEALTH_PORT/healthz" >/dev/null 2>&1; then
  echo "$CANONICAL_PID" > "$PIDFILE"
  say "Personal MCP already running pid=$CANONICAL_PID"
  if [ "${LUDOMADA_WATCHDOG_SKIP:-0}" != "1" ]; then
    if [ ! -f "$WATCHDOG_PIDFILE" ] || ! kill -0 "$(cat "$WATCHDOG_PIDFILE" 2>/dev/null || echo 0)" 2>/dev/null; then
      nohup bash "$PROJECT/scripts/ludomada-watchdog.sh" >/dev/null 2>&1 &
      echo $! > "$WATCHDOG_PIDFILE"
    fi
  fi
  exit 0
fi
for p in $(ps -eo pid=,args= 2>/dev/null | awk -v id="$TUNNEL_ID" -v port="$HEALTH_PORT" -v script="$SERVER_JS" '$0 ~ /tunnel-client run/ && index($0,id) && index($0, "127.0.0.1:" port) {print $1}'); do
  [ -n "$p" ] && kill "$p" 2>/dev/null || true
done
# Remove the old generated wrapper and mismatched PID file before regeneration.
rm -f "$STATE/run-personal-mcp-proot.sh" "$STATE/personal-mcp.pid"

if ! curl -fsS --max-time 2 http://127.0.0.1:8787/.well-known/oauth-protected-resource >/dev/null 2>&1; then
  if [ -f "$COMMANDER_ROOT/server.mjs" ]; then
    (cd "$COMMANDER_ROOT" && nohup node server.mjs --http >"$STATE/commander-server.log" 2>&1 </dev/null &)
    say "Commander MCP HTTP server starting"
  fi
fi

if ! curl -fsS --max-time 2 http://127.0.0.1:8788/ >/dev/null 2>&1; then
  if [ -f "$DASHBOARD_JS" ]; then
    (cd "$COMMANDER_ROOT" && nohup node dashboard.mjs >"$STATE/commander-dashboard.log" 2>&1 </dev/null &)
    say "Commander Web Panel starting"
  fi
fi

start_native_probe() {
  [ -x "$RUNTIME" ] || return 1
  "$RUNTIME" --version | grep -q 'flavor=runtime' || return 1
  rm -f "$STATE/native-probe.log"
  set +e
  "$RUNTIME" run \
    --control-plane.tunnel-id "$TUNNEL_ID" \
    --control-plane.api-key "file:$KEY_FILE" \
    --mcp.command "$MCP_COMMAND" \
    --health.listen-addr "127.0.0.1:$HEALTH_PORT" \
    --mcp.startup-wait-timeout 30s \
    --log.file "$STATE/native-probe.log" \
    --log.format json \
    >/dev/null 2>"$STATE/native-probe.stderr" &
  PROBE_PID=$!
  sleep 2
  if kill -0 "$PROBE_PID" 2>/dev/null; then
    kill "$PROBE_PID" 2>/dev/null || true
    return 0
  fi
  wait "$PROBE_PID" 2>/dev/null || true
  return 1
}

start_proot() {
  [ -x "$PROOT_TUNNEL" ] || die "working proot tunnel-client not found"
  cat >"$STATE/run-personal-mcp-proot.sh" <<EOF
#!/bin/sh
set -eu
export CONTROL_PLANE_API_KEY="\$(cat "$KEY_FILE")"
exec "$PROOT_TUNNEL" run \
  --control-plane.tunnel-id "$TUNNEL_ID" \
  --control-plane.api-key env:CONTROL_PLANE_API_KEY \
  --mcp.command "$MCP_COMMAND" \
  --health.listen-addr "127.0.0.1:$HEALTH_PORT" \
  --mcp.startup-wait-timeout 30s \
  --log.file "$PROOT_LOG" \
  --log.format json
EOF
  chmod 700 "$STATE/run-personal-mcp-proot.sh"
  proot-distro login debian -- /bin/sh "$STATE/run-personal-mcp-proot.sh" >"$PROOT_LOG.wrapper" 2>&1 &
  echo $! >"$PIDFILE"
}

if [ -f "$PIDFILE" ] && kill -0 "$(cat "$PIDFILE")" 2>/dev/null; then
  say "Personal MCP already running pid=$(cat "$PIDFILE")"
  exit 0
fi

rm -f "$PIDFILE"

say "Starting Personal MCP..."
if start_native_probe; then
  say "Native runtime compatible."
  nohup "$RUNTIME" run \
    --control-plane.tunnel-id "$TUNNEL_ID" \
    --control-plane.api-key "file:$KEY_FILE" \
    --mcp.command "$MCP_COMMAND" \
    --health.listen-addr "127.0.0.1:$HEALTH_PORT" \
    --mcp.startup-wait-timeout 30s \
    --log.file "$LOG" \
    --log.format json \
    >/dev/null 2>&1 &
  echo $! >"$PIDFILE"
else
  say "Native runtime blocked on this Android kernel; using Debian proot."
  start_proot
fi

READY=0
for _ in $(seq 1 30); do
  if curl -fsS --max-time 2 "http://127.0.0.1:$HEALTH_PORT/healthz" >/dev/null 2>&1; then
    READY=1
    break
  fi
  sleep 1
done
# Record the actual tunnel-client PID (proot parent may already have exited).
SERVICE_PID="$(ps -eo pid=,args= 2>/dev/null | awk -v port="$HEALTH_PORT" -v script="$SERVER_JS" '$0 ~ /tunnel-client run/ && index($0,"--health.listen-addr 127.0.0.1:" port) {print $1; exit}')"
[ -n "$SERVICE_PID" ] && echo "$SERVICE_PID" >"$PIDFILE"
if [ "$READY" -eq 1 ]; then
  say "READY"
else
  say "NOT_READY — inspect $LOG or $PROOT_LOG"
fi

if [ "${LUDOMADA_WATCHDOG_SKIP:-0}" != "1" ]; then
  if [ ! -f "$WATCHDOG_PIDFILE" ] || ! kill -0 "$(cat "$WATCHDOG_PIDFILE" 2>/dev/null || echo 0)" 2>/dev/null; then
    chmod 700 "$PROJECT/scripts/ludomada-watchdog.sh" 2>/dev/null || true
    nohup bash "$PROJECT/scripts/ludomada-watchdog.sh" >/dev/null 2>&1 &
    echo $! > "$WATCHDOG_PIDFILE"
  fi
fi

printf '\nLudoMada Commander\n'
printf '  Web Panel : http://127.0.0.1:8788/\n'
printf '  MCP Health: http://127.0.0.1:%s/healthz\n' "$HEALTH_PORT"
