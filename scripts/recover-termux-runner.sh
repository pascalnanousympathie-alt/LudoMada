#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

# Recover only the already-registered LudoMada Actions runner.
# Never re-register the runner, remove its credentials, or print its config secrets.
target_repo="pascalnanousympathie-alt/LudoMada"
runner_dir=""

while IFS= read -r marker; do
  # A .runner marker can belong to another project. Select only this repo's registration.
  if ! grep -Fq "$target_repo" "$marker" 2>/dev/null; then
    continue
  fi
  candidate="$(dirname "$marker")"
  if [ -x "$candidate/run.sh" ]; then
    runner_dir="$candidate"
    break
  fi
done < <(find "$HOME" -maxdepth 7 -type f -name .runner -print 2>/dev/null)

if [ -z "$runner_dir" ]; then
  echo "ERROR: no existing configured Actions runner for $target_repo was found under $HOME"
  echo "No re-registration was attempted. Existing files and credentials were left untouched."
  exit 2
fi

# Use the runner's diagnostics rather than launching duplicate listeners.
if command -v pgrep >/dev/null 2>&1 && pgrep -f '[R]unner.Listener' >/dev/null 2>&1; then
  echo "RUNNER_LISTENER_PROCESS_EXISTS"
  echo "runner_dir=$runner_dir"
  echo "No second listener was started."
  echo "Inspect runner logs in: $runner_dir/_diag"
  exit 4
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
  echo "Keep Termux available and the phone awake while queued CI jobs run."
else
  echo "ERROR: runner process exited during startup. Recent log:"
  tail -n 80 "$log_file" 2>/dev/null || true
  exit 3
fi
