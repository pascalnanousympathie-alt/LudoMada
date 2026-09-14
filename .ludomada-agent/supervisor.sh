#!/data/data/com.termux/files/usr/bin/bash
set -u
BASE="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
source "$BASE/.ludomada-agent/pipeline.conf"

# Load Railway project token from a user-owned secret outside the repository.
# The token is never written to repo files, state, or logs.
RAILWAY_SECRET_FILE="${LUDOMADA_RAILWAY_TOKEN_FILE:-$HOME/.config/ludomada/railway-token}"
if [ -r "$RAILWAY_SECRET_FILE" ]; then
  IFS= read -r RAILWAY_TOKEN < "$RAILWAY_SECRET_FILE" || true
  export RAILWAY_TOKEN
fi
mkdir -p "$STATE_DIR" "$LOG_DIR"
PID_FILE="$STATE_DIR/pipeline.pid"
SUP_LOG="$LOG_DIR/supervisor.log"
SUP_LOCK="$STATE_DIR/supervisor.lock"
exec 8>"$SUP_LOCK"
if [ "${1:-once}" = "daemon" ]; then flock -n 8 || { echo "SUPERVISOR=ALREADY_RUNNING"; exit 0; }; fi
STATE="$STATE_DIR/release-state.env"

is_running() {
  [ -f "$PID_FILE" ] || return 1
  pid=$(cat "$PID_FILE" 2>/dev/null || true)
  [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null
}

terminal_state() {
  [ -f "$STATE" ] || return 1
  unset status phase next
  # State is generated locally by release-orchestrator.sh; no secrets are written.
  source "$STATE"
  [ "${status:-}" = BLOCKED ] || [ "${phase:-}" = FINAL_RELEASE ]
}

start_pipeline() {
  if is_running || terminal_state; then return 0; fi
  nohup "$BASE/.ludomada-agent/pipeline.sh" >>"$SUP_LOG" 2>&1 &
  echo $! > "$PID_FILE"
  echo "SUPERVISOR=STARTED PID=$(cat "$PID_FILE")" >> "$SUP_LOG"
}
check_once() {
  if ! is_running; then
    rm -f "$PID_FILE"
    start_pipeline
  fi
  if [ -f "$STATE" ]; then
    unset status phase next updated_at
    source "$STATE"
    printf 'SUPERVISOR phase=%s status=%s next=%s updated=%s\n' "${phase:-unknown}" "${status:-unknown}" "${next:-}" "${updated_at:-}"
  else
    echo "SUPERVISOR phase=starting status=none"
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
