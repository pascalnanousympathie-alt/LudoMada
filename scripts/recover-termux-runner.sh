#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

# Recover only the already-registered LudoMada Actions runner.
# This does not re-register the runner, delete its configuration, or print credentials.
target_repo="pascalnanousympathie-alt/LudoMada"
runner_dir=""

while IFS= read -r marker; do
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

# Restart only listener/launcher processes whose working directory is this runner's directory.
matching_runner_pids() {
  for proc in /proc/[0-9]*; do
    [ -r "$proc/cmdline" ] || continue
    pid="${proc##*/}"
    cmd="$(tr '\0' ' ' < "$proc/cmdline" 2>/dev/null || true)"
    case "$cmd" in
      *Runner.Listener*|*run.sh*) ;;
      *) continue ;;
    esac
    cwd="$(readlink "$proc/cwd" 2>/dev/null || true)"
    [ "$cwd" = "$runner_dir" ] && printf '%s\n' "$pid"
  done
}

old_pids="$(matching_runner_pids || true)"
if [ -n "$old_pids" ]; then
  echo "RESTARTING_STALE_LUDOMADA_RUNNER"
  while IFS= read -r pid; do
    [ -n "$pid" ] || continue
    kill "$pid" 2>/dev/null || true
  done <<< "$old_pids"
  sleep 2
  old_pids="$(matching_runner_pids || true)"
  if [ -n "$old_pids" ]; then
    echo "ERROR: old runner process is still stopping; not launching a duplicate."
    echo "runner_dir=$runner_dir"
    echo "remaining_pids=$old_pids"
    exit 5
  fi
fi

if command -v termux-wake-lock >/dev/null 2>&1; then
  termux-wake-lock >/dev/null 2>&1 || true
fi

log_file="$HOME/ludomada-runner-recovery.log"
cd "$runner_dir"
printf '\n=== LudoMada runner recovery %s ===\n' "$(date -Iseconds 2>/dev/null || date)" >> "$log_file"
nohup ./run.sh >> "$log_file" 2>&1 </dev/null &
runner_pid=$!
printf '%s\n' "$runner_pid" > "$HOME/.ludomada-runner-recovery.pid"
sleep 3

if kill -0 "$runner_pid" 2>/dev/null; then
  echo "RUNNER_START_SUBMITTED"
  echo "runner_dir=$runner_dir"
  echo "log_file=$log_file"
  echo "Keep Termux open and the phone awake until GitHub shows the runner online."
else
  echo "ERROR: runner process exited during startup. Recent log:"
  tail -n 80 "$log_file" 2>/dev/null || true
  exit 3
fi
