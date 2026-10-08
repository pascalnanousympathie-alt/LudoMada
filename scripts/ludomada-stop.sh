#!/data/data/com.termux/files/usr/bin/bash
set -u
HOME_DIR="${HOME:-/data/data/com.termux/files/home}"
STATE="$HOME_DIR/.config/ludomada"
STOP_FLAG="$STATE/stop.flag"
PIDFILE="$HOME_DIR/.config/ludomada/personal-mcp-runtime.pid"
WATCHDOG_PIDFILE="$HOME_DIR/.config/ludomada/watchdog.pid"
PORT="${PERSONAL_MCP_HEALTH_PORT:-8790}"
COMMANDER_REPO="$HOME_DIR/LudoMada/tools/personal-mcp-commander"
COMMANDER_EXTERNAL="$HOME_DIR/ludomada-mcp-commander"
mkdir -p "$STATE"
touch "$STOP_FLAG"
FOUND=0
WATCHDOG_FOUND=0
is_owned_commander_pid() {
  pid="$1"
  cwd="$(readlink -f "/proc/$pid/cwd" 2>/dev/null || true)"
  case "$cwd" in
    "$COMMANDER_REPO"|"$COMMANDER_REPO"/*|"$COMMANDER_EXTERNAL"|"$COMMANDER_EXTERNAL"/*|"$HOME_DIR/LudoMada"|"$HOME_DIR/LudoMada"/*) return 0 ;;
    *) return 1 ;;
  esac
}
WATCHDOG_PIDS="$(ps -eo pid=,args= 2>/dev/null | awk '$0 ~ /ludomada-watchdog/ {print $1}')"
for p in $WATCHDOG_PIDS; do
  [ -n "$p" ] && { kill "$p" 2>/dev/null || true; WATCHDOG_FOUND=1; }
done
sleep 1
for p in $WATCHDOG_PIDS; do
  if [ -n "$p" ] && kill -0 "$p" 2>/dev/null; then kill -9 "$p" 2>/dev/null || true; fi
done
for p in $(ps -eo pid=,args= 2>/dev/null | awk -v port="$PORT" '$0 ~ /tunnel-client run/ && index($0,"--health.listen-addr 127.0.0.1:" port) {print $1}'); do
  [ -n "$p" ] && { kill "$p" 2>/dev/null || true; FOUND=1; }
done
sleep 1
for p in $(ps -eo pid=,args= 2>/dev/null | awk -v port="$PORT" '$0 ~ /tunnel-client run/ && index($0,"--health.listen-addr 127.0.0.1:" port) {print $1}'); do
  [ -n "$p" ] && { kill -9 "$p" 2>/dev/null || true; FOUND=1; }
done
for p in $(ps -eo pid=,args= 2>/dev/null | awk '$0 ~ /node .*server\.mjs --http/ {print $1}'); do
  if is_owned_commander_pid "$p"; then kill "$p" 2>/dev/null || true; FOUND=1; fi
done
for p in $(ps -eo pid=,args= 2>/dev/null | awk '$0 ~ /node .*dashboard\.mjs/ {print $1}'); do
  if is_owned_commander_pid "$p"; then kill "$p" 2>/dev/null || true; FOUND=1; fi
done
if [ -f "$WATCHDOG_PIDFILE" ]; then
  kill "$(cat "$WATCHDOG_PIDFILE")" 2>/dev/null || true
  rm -f "$WATCHDOG_PIDFILE"
  WATCHDOG_FOUND=1
fi
if [ -f "$PIDFILE" ]; then
  kill "$(cat "$PIDFILE")" 2>/dev/null || true
  rm -f "$PIDFILE"
  FOUND=1
fi
rm -rf "$HOME_DIR/.config/ludomada/connect.lock" "$HOME_DIR/.config/ludomada/watchdog.lock"
if [ "$FOUND" -eq 1 ] || [ "$WATCHDOG_FOUND" -eq 1 ]; then echo "LudoMada Commander services stopped"; else echo "LudoMada Commander services not running"; fi
