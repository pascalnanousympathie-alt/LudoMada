#!/data/data/com.termux/files/usr/bin/bash
set -u
BASE="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
source "$BASE/.ludomada-agent/pipeline.conf"
mkdir -p "$STATE_DIR" "$LOG_DIR"

case "${1:-once}" in
  once)
    "$BASE/.ludomada-agent/supervisor.sh" once
    ;;
  daemon)
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
