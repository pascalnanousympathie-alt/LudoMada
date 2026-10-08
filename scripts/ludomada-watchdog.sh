#!/data/data/com.termux/files/usr/bin/bash
set -u
HOME_DIR="${HOME:-/data/data/com.termux/files/home}"
PROJECT="${LUDOMADA_PROJECT_DIR:-$HOME_DIR/LudoMada}"
STATE="$HOME_DIR/.config/ludomada"
PORT="${PERSONAL_MCP_HEALTH_PORT:-8790}"
LOG="$STATE/watchdog.log"
PIDFILE="$STATE/watchdog.pid"
INTERVAL="${LUDOMADA_WATCHDOG_INTERVAL:-20}"
FAILS=0

mkdir -p "$STATE"
WATCHDOG_LOCK="$STATE/watchdog.lock"
if ! mkdir "$WATCHDOG_LOCK" 2>/dev/null; then
  exit 0
fi
echo $$ > "$PIDFILE"
trap 'rm -f "$PIDFILE"; rm -rf "$WATCHDOG_LOCK"' EXIT INT TERM

while :; do
  if [ -f "$STATE/stop.flag" ]; then
    exit 0
  fi
  ok=1
  curl -fsS --max-time 4 "http://127.0.0.1:$PORT/healthz" >/dev/null 2>&1 || ok=0
  curl -fsS --max-time 4 "http://127.0.0.1:8788/health" >/dev/null 2>&1 || ok=0
  curl -fsS --max-time 4 "http://127.0.0.1:8787/.well-known/oauth-protected-resource" >/dev/null 2>&1 || ok=0
  if [ "$ok" -eq 1 ]; then
    FAILS=0
  else
    FAILS=$((FAILS+1))
    printf "%s watchdog check failed (%s/3)\n" "$(date -Is)" "$FAILS" >> "$LOG"
    if [ "$FAILS" -ge 3 ] && [ ! -f "$STATE/stop.flag" ]; then
      printf "%s restarting personal MCP control plane\n" "$(date -Is)" >> "$LOG"
      LUDOMADA_WATCHDOG_SKIP=1 LUDOMADA_PROJECT_DIR="$PROJECT" bash "$PROJECT/scripts/ludomada-connect.sh" >> "$LOG" 2>&1 || true
      FAILS=0
    fi
  fi
  sleep "$INTERVAL"
done
