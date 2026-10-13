#!/data/data/com.termux/files/usr/bin/bash
set -u

export PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
EXPECTED_PREFIX="/data/data/com.termux/files/usr"
if [[ "$PREFIX" != "$EXPECTED_PREFIX" ]]; then
  echo "ERROR: run this script inside Termux on the Android device." >&2
  exit 2
fi
# Make checks use the actual Termux runit directories, not inherited /var/service values.
export SVDIR="$PREFIX/var/service"
export LOGDIR="$PREFIX/var/log"
export PATH="$PREFIX/bin:$PATH"

PACKAGE_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
SERVICE_NAME="mada-commander"
STATUS=0

echo "== runit service =="
sv status "$SERVICE_NAME" || STATUS=1

echo
echo "== live agent status (sends one heartbeat) =="
node "$PACKAGE_DIR/agent.mjs" status || STATUS=1

echo
echo "== recent service log (last 40 lines) =="
LOG_FILE="$PREFIX/var/log/sv/$SERVICE_NAME/current"
if [[ -r "$LOG_FILE" ]]; then
  tail -n 40 "$LOG_FILE"
else
  echo "No service log is available yet: $LOG_FILE"
fi

exit "$STATUS"
