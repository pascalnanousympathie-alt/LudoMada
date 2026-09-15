#!/data/data/com.termux/files/usr/bin/bash
set -u
BASE="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
source "$BASE/asep-v2.conf"
source "$BASE/credential-policy.sh"
mkdir -p "$STATE_DIR" "$LOG_DIR" "$BROWSER_DIR"
PID_FILE="$STATE_DIR/asep-v2.pid"
LOCK_FILE="$STATE_DIR/asep-v2.lock"
LOG="$LOG_DIR/asep-v2.log"
STATUS="$STATE_DIR/asep-v2.env"
exec 9>"$LOCK_FILE"
flock -n 9 || { echo "ASEP_V2=ALREADY_RUNNING"; exit 0; }

now(){ date -Is; }
write_status(){
  local phase="$1" status="$2" next="${3:-}" reason="${4:-}";
  {
    printf 'updated_at=%q\n' "$(now)"
    printf 'phase=%q\nstatus=%q\nnext=%q\nreason=%q\n' "$phase" "$status" "$next" "$reason"
    printf 'commit=%q\n' "$(git -C "$PROJECT" rev-parse --short HEAD 2>/dev/null || echo unknown)"
  } > "$STATUS.tmp" && mv "$STATUS.tmp" "$STATUS"
  if [ -x "$BASE/task-state.sh" ]; then
    "$BASE/task-state.sh" set "$status" "$phase" "CP-V2-$phase" "$next" "$reason" >/dev/null 2>&1 || true
  fi
}

wake_lock(){
  if [ "$WAKE_LOCK" = 1 ] && command -v termux-wake-lock >/dev/null 2>&1; then
    termux-wake-lock >/dev/null 2>&1 || true
  fi
}

cdp_up(){
  curl -fsS --connect-timeout 2 --max-time 4 "$CDP_URL/json/version" >/dev/null 2>&1
}

start_chromium(){
  cdp_up && return 0
  [ -x "$CHROMIUM_BIN" ] || return 1
  mkdir -p "$CDP_PROFILE" "$HOME/.cache/ludomada-tmp"
  TMPDIR="$HOME/.cache/ludomada-tmp" nohup "$CHROMIUM_BIN" \
    --headless --no-sandbox --disable-gpu --disable-dev-shm-usage \
    --remote-debugging-port=9222 --user-data-dir="$CDP_PROFILE" \
    about:blank >>"$LOG_DIR/chromium-cdp.log" 2>&1 < /dev/null &
  echo $! > "$STATE_DIR/chromium-cdp.pid"
  for _ in $(seq 1 20); do cdp_up && return 0; sleep 1; done
  return 1
}

run_browser(){
  start_chromium || { write_status BROWSER BLOCKED BROWSER_CDP "Chromium/CDP unavailable"; return 10; }
  timeout "$WORKER_TIMEOUT" python3 "$BROWSER_DIR/multiworker-smoke.py" >>"$LOG" 2>&1
}

TREE_STATE="$STATE_DIR/validated-tree.sha"
tree_fingerprint(){
  git -C "$PROJECT" status --porcelain=v1 --untracked-files=all | sha256sum | awk '{print $1}'
}
run_checks(){
  cd "$PROJECT" || return 1
  local fp current
  current="$(tree_fingerprint)"
  if [ -f "$TREE_STATE" ] && [ "$(cat "$TREE_STATE")" = "$current" ]; then
    echo "[$(now)] FAST_GATE=SKIPPED tree-unchanged" >>"$LOG"
    return 0
  fi
  # Fast local gate: no database migration on the phone. GitHub CI owns the
  # disposable PostgreSQL migration gate; this keeps Termux responsive.
  timeout "$WORKER_TIMEOUT" npm run verify:local >>"$LOG" 2>&1 || return $?
  fp="$(tree_fingerprint)"
  printf '%s\n' "$fp" > "$TREE_STATE"
}

run_codex(){
  [ -x "$BASE/codex-worker.sh" ] || return 1
  "$BASE/codex-worker.sh" >>"$LOG" 2>&1
}

loop(){
  wake_lock
  write_status BOOT RUNNING BROWSER "Starting autonomous control plane"
  load_autonomous_credentials
  while :; do
    wake_lock
    if ! cdp_up; then
      write_status BROWSER RUNNING BROWSER "CDP unavailable; restarting Chromium"
      if start_chromium; then
        echo "[$(now)] CDP=RESTORED" >>"$LOG"
      else
        write_status BROWSER BLOCKED BROWSER "Chromium/CDP unavailable"
        sleep "$LOOP_INTERVAL"
        continue
      fi
    fi

    write_status BROWSER RUNNING MULTIWORKER "Running six browser workers"
    if run_browser; then
      write_status PAYMENT RUNNING PAYMENT_TESTS "Browser workers PASS; running payment tests"
    else
      write_status BROWSER RETRYING BROWSER "Browser worker smoke failed"
      sleep 10
      continue
    fi

    if run_checks; then
      write_status VALIDATION PASS NEXT "Fast local gate PASS; continue remote CI validation"
      credential_policy_snapshot > "$STATE_DIR/credential-flags.env"
      echo "[$(now)] CHECKPOINT=FAST_LOCAL_GATE_PASS" >>"$LOG"
      if [ "${CODEX_BLOCKED_UNTIL:-0}" -gt "$(date +%s)" ]; then
        echo "[$(now)] CODEX_WORKER=SKIPPED_QUOTA_BACKOFF" >>"$LOG"
      elif run_codex; then
        echo "[$(now)] CODEX_WORKER=PASS" >>"$LOG"
      else
        codex_rc=$?
        if [ "$codex_rc" -eq 75 ]; then
          echo "[$(now)] CODEX_WORKER=QUOTA_BACKOFF" >>"$LOG"
          CODEX_BLOCKED_UNTIL=$(( $(date +%s) + 21600 ))
        else
          echo "[$(now)] CODEX_WORKER=BLOCKED rc=$codex_rc" >>"$LOG"
        fi
      fi
    else
      write_status PAYMENT RETRYING PAYMENT_TESTS "Payment provider tests failed; retrying"
    fi

    # The controller deliberately does not perform real-money/payment submission.
    # Credential-gated or approval-gated actions remain BLOCKED without stopping other work.
    sleep "$LOOP_INTERVAL"
  done
}

case "${1:-daemon}" in
  daemon)
    echo $$ > "$PID_FILE"
    trap 'rm -f "$PID_FILE"; exit 0' INT TERM EXIT
    loop
    ;;
  status)
    if [ -f "$STATUS" ]; then cat "$STATUS"; else echo "ASEP_V2=NOT_STARTED"; fi
    ;;
  stop)
    if [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
      kill "$(cat "$PID_FILE")" 2>/dev/null || true
    fi
    echo "ASEP_V2=STOP_REQUESTED"
    ;;
  *) echo "usage: autonomous-v2.sh {daemon|status|stop}"; exit 2 ;;
esac
