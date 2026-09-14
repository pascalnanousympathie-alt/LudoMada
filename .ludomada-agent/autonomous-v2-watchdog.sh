#!/data/data/com.termux/files/usr/bin/bash
set -u
BASE="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
source "$BASE/asep-v2.conf"
PID_FILE="$STATE_DIR/asep-v2.pid"
LOG="$LOG_DIR/asep-v2-watchdog.log"
mkdir -p "$STATE_DIR" "$LOG_DIR"

if command -v termux-wake-lock >/dev/null 2>&1; then
  termux-wake-lock >/dev/null 2>&1 || true
fi

running=0
if [ -f "$PID_FILE" ]; then
  pid="$(cat "$PID_FILE" 2>/dev/null || true)"
  if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
    running=1
  fi
fi

if [ "$running" -eq 0 ]; then
  rm -f "$PID_FILE"
  echo "[$(date -Is)] watchdog starting autonomous-v2" >>"$LOG"
  nohup setsid "$BASE/autonomous-v2.sh" daemon >>"$LOG_DIR/asep-v2-daemon.out" 2>&1 < /dev/null &
  echo $! > "$PID_FILE"
else
  echo "[$(date -Is)] watchdog healthy pid=$pid" >>"$LOG"
fi
