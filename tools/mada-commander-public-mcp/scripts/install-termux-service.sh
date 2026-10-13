#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

EXPECTED_PREFIX="/data/data/com.termux/files/usr"
if [[ "${PREFIX:-}" != "$EXPECTED_PREFIX" ]]; then
  echo "ERROR: run this script inside Termux on the Android device." >&2
  exit 2
fi

PACKAGE_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
AGENT_FILE="$PACKAGE_DIR/agent.mjs"
CONFIG_FILE="$HOME/.config/mada-commander/agent.json"
SERVICE_NAME="mada-commander"
SERVICE_DIR="$PREFIX/var/service/$SERVICE_NAME"
RUN_FILE="$SERVICE_DIR/run"
LOG_RUN="$SERVICE_DIR/log/run"
BOOT_FILE="$HOME/.termux/boot/10-mada-commander"
NODE_BIN="$(command -v node || true)"

if [[ -z "$NODE_BIN" ]]; then
  echo "ERROR: Node.js is missing. Run: pkg install nodejs-lts" >&2
  exit 2
fi
if [[ ! -f "$AGENT_FILE" ]]; then
  echo "ERROR: agent.mjs was not found at $AGENT_FILE" >&2
  exit 2
fi
if [[ ! -f "$CONFIG_FILE" ]]; then
  echo "ERROR: this device is not paired yet. Pair it once before installing the service." >&2
  exit 2
fi
if ! command -v sv-enable >/dev/null 2>&1 || [[ ! -x "$PREFIX/share/termux-services/svlogger" ]]; then
  echo "ERROR: termux-services is missing. Run: pkg install termux-services" >&2
  echo "Then close and reopen Termux, and rerun this installer." >&2
  exit 2
fi

if [[ -e "$SERVICE_DIR" && ! -d "$SERVICE_DIR" ]]; then
  echo "ERROR: service path exists but is not a directory: $SERVICE_DIR" >&2
  exit 3
fi
if [[ -e "$RUN_FILE" ]] && ! grep -qF "$AGENT_FILE" "$RUN_FILE"; then
  echo "ERROR: refusing to overwrite an existing service that does not point to this Mada Commander agent:" >&2
  echo "  $RUN_FILE" >&2
  exit 3
fi
mkdir -p "$SERVICE_DIR" "$SERVICE_DIR/log"

shell_quote() {
  local value="$1"
  value="${value//\'/\'\\\'\'}"
  printf "'%s'" "$value"
}

{
  printf '#!%s/bin/sh\n' "$PREFIX"
  printf 'exec '
  shell_quote "$NODE_BIN"
  printf ' '
  shell_quote "$AGENT_FILE"
  printf ' run\n'
} > "$RUN_FILE"
chmod 700 "$RUN_FILE"
ln -sfn "$PREFIX/share/termux-services/svlogger" "$LOG_RUN"

mkdir -p "$HOME/.termux/boot"
cat > "$BOOT_FILE" <<'BOOT'
#!/data/data/com.termux/files/usr/bin/sh
# Keep Mada Commander available after Android restarts. Requires Termux:Boot.
termux-wake-lock
sleep 5
sv up mada-commander
BOOT
chmod 700 "$BOOT_FILE"

sv-enable "$SERVICE_NAME"

echo
echo "Mada Commander service installed and enabled."
echo "Service status: sv status $SERVICE_NAME"
echo "Agent status (also sends a live heartbeat): node $AGENT_FILE status"
echo "Logs: tail -n 80 \"$PREFIX/var/log/sv/$SERVICE_NAME/current\""
echo
echo "For restart-after-Android-boot, install the Termux:Boot app from the same trusted Termux source."
echo "If Android still kills Termux, allow background activity and exclude Termux from battery optimization."
