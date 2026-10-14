#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

# Recover the already-registered LudoMada GitHub Actions runner.
# This does not create a new registration or print runner credentials.
if command -v pgrep >/dev/null 2>&1 && pgrep -f '[R]unner.Listener' >/dev/null 2>&1; then
  echo "RUNNER_ALREADY_RUNNING"
  exit 0
fi

runner_dir=""
while IFS= read -r marker; do
  candidate="$(dirname "$marker")"
  if [ -x "$candidate/run.sh" ]; then
    runner_dir="$candidate"
    break
  fi
done < <(find "$HOME" -maxdepth 7 -type f -name .runner -print 2>/dev/null)

if [ -z "$runner_dir" ]; then
  echo "ERROR: cannot find an existing configured Actions runner under $HOME"
  echo "No re-registration was attempted. Keep the existing configuration and inspect the runner install path."
  exit 2
fi

if command -v termux-wake-lock >/dev/null 2>&1; then
  termux-wake-lock >/dev/null 2>&1 || true
fi

log_file="$HOME/ludomada-runner-recovery.log"
cd "$runner_dir"
nohup ./run.sh >"$log_file" 2>&1 </dev/null &
runner_pid=$!
printf '%s\n' "$runner_pid" > "$HOME/.ludomada-runner-recovery.pid"
sleep 3

if kill -0 "$runner_pid" 2>/dev/null; then
  echo "RUNNER_START_SUBMITTED"
  echo "runner_dir=$runner_dir"
  echo "log_file=$log_file"
  echo "Keep Termux available and the phone awake while the queued CI jobs run."
else
  echo "ERROR: runner process exited during startup. Tail the log:"
  tail -n 80 "$log_file" 2>/dev/null || true
  exit 3
fi
