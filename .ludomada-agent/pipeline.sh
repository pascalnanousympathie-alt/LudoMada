#!/data/data/com.termux/files/usr/bin/bash
set -u
BASE="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
source "$BASE/.ludomada-agent/pipeline.conf"
STATE_DIR="$BASE/.ludomada-agent/state"
LOG_DIR="$BASE/.ludomada-agent/logs"
mkdir -p "$STATE_DIR" "$LOG_DIR"
# The orchestrator owns the release lock. This wrapper has a separate lock.
exec 9>"$STATE_DIR/wrapper.lock"
flock -n 9 || { echo "PIPELINE=ALREADY_RUNNING"; exit 2; }
exec timeout "$RELEASE_MAX_RUNTIME" "$BASE/.ludomada-agent/release-orchestrator.sh"
