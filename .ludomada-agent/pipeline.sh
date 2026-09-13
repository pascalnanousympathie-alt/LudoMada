#!/data/data/com.termux/files/usr/bin/bash
set -u
BASE="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
source "$BASE/.ludomada-agent/pipeline.conf"
mkdir -p "$STATE_DIR" "$LOG_DIR"
exec 9>"$LOCK_FILE"
flock -n 9 || { echo "PIPELINE=ALREADY_RUNNING"; exit 2; }

run_stage() {
  local name="$1"; shift
  local log="$LOG_DIR/${name}-$(date +%Y%m%d-%H%M%S).log"
  echo "STAGE=$name START=$(date -Is)"
  if timeout "$TASK_TIMEOUT" "$@" >"$log" 2>&1; then
    echo "STAGE=$name RESULT=PASS LOG=$log"
    return 0
  fi
  local rc=$?
  echo "STAGE=$name RESULT=FAIL RC=$rc LOG=$log"
  return "$rc"
}

cd "$BASE" || exit 1
printf 'running\n' > "$HEALTH_FILE"
start=$(date +%s)
fail=0

run_stage git-status "$BASE/.ludomada-agent/agent.sh" git-status || fail=1
run_stage diff-check "$BASE/.ludomada-agent/agent.sh" diff-check || fail=1
run_stage build "$BASE/.ludomada-agent/agent.sh" build || fail=1
run_stage test "$BASE/.ludomada-agent/agent.sh" test || fail=1
run_stage security-test "$BASE/.ludomada-agent/agent.sh" security-test || fail=1
run_stage integration-test "$BASE/.ludomada-agent/agent.sh" integration-test || fail=1
run_stage concurrency-test "$BASE/.ludomada-agent/agent.sh" concurrency-test || fail=1

if [ "$fail" -eq 0 ]; then
  printf 'healthy\n' > "$HEALTH_FILE"
  printf 'PASS\n' > "$STATE_DIR/last-result"
  echo "PIPELINE=PASS DURATION=$(( $(date +%s)-start ))s"
else
  printf 'degraded\n' > "$HEALTH_FILE"
  printf 'FAIL\n' > "$STATE_DIR/last-result"
  echo "PIPELINE=FAIL DURATION=$(( $(date +%s)-start ))s"
  exit 1
fi
