#!/data/data/com.termux/files/usr/bin/bash
set -u
BASE="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
source "$BASE/.ludomada-agent/pipeline.conf"
mkdir -p "$STATE_DIR" "$LOG_DIR"
PID_FILE="$STATE_DIR/pipeline.pid"
SUP_LOG="$LOG_DIR/supervisor.log"

is_running() {
  [ -f "$PID_FILE" ] || return 1
  pid=$(cat "$PID_FILE" 2>/dev/null || true)
  [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null
}

start_pipeline() {
  if is_running; then return 0; fi
  nohup "$BASE/.ludomada-agent/pipeline.sh" >>"$SUP_LOG" 2>&1 &
  echo $! > "$PID_FILE"
  echo "SUPERVISOR=STARTED PID=$(cat "$PID_FILE")" >> "$SUP_LOG"
}

check_once() {
  if ! is_running; then
    rm -f "$PID_FILE"
    start_pipeline
  fi
  if [ -f "$STATE_DIR/last-result" ]; then
    result=$(cat "$STATE_DIR/last-result")
    printf 'SUPERVISOR health=%s result=%s\n' "$(cat "$HEALTH_FILE" 2>/dev/null || echo unknown)" "$result"
  else
    echo "SUPERVISOR health=starting result=none"
  fi
}

case "${1:-once}" in
  once) check_once ;;
  daemon)
    while :; do
      check_once
      sleep "$SUPERVISOR_INTERVAL"
    done
    ;;
  stop)
    if is_running; then kill "$(cat "$PID_FILE")" 2>/dev/null || true; fi
    rm -f "$PID_FILE"
    echo "SUPERVISOR=STOPPED"
    ;;
  *) echo "usage: supervisor.sh {once|daemon|stop}"; exit 2 ;;
esac
