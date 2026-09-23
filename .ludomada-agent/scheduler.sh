#!/data/data/com.termux/files/usr/bin/bash
set -u
BASE="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
source "$BASE/.ludomada-agent/pipeline.conf"

# Make the persisted local Railway secret available to the supervised pipeline.
RAILWAY_SECRET_FILE="${LUDOMADA_RAILWAY_TOKEN_FILE:-$HOME/.config/ludomada/railway-token}"
if [ -r "$RAILWAY_SECRET_FILE" ]; then
  IFS= read -r RAILWAY_TOKEN < "$RAILWAY_SECRET_FILE" || true
  export RAILWAY_TOKEN
fi
mkdir -p "$STATE_DIR" "$LOG_DIR"

case "${1:-once}" in
  once)
    "$BASE/.ludomada-agent/supervisor.sh" once
    ;;
  daemon)
    SCHEDULER_LOCK="$STATE_DIR/scheduler.lock"
    exec 9>"$SCHEDULER_LOCK"
    flock -n 9 || { echo "SCHEDULER=ALREADY_RUNNING"; exit 0; }
    while :; do
      "$BASE/.ludomada-agent/supervisor.sh" once
      sleep "$SCHEDULER_INTERVAL"
    done
    ;;
  *)
    echo "usage: scheduler.sh {once|daemon}"
    exit 2
    ;;
esac
