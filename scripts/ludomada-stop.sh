#!/data/data/com.termux/files/usr/bin/bash
set -u
PIDFILE="${HOME:-/data/data/com.termux/files/home}/.config/ludomada/personal-mcp-runtime.pid"
if [ -f "$PIDFILE" ]; then
  PID="$(cat "$PIDFILE")"
  kill "$PID" 2>/dev/null || true
  rm -f "$PIDFILE"
  echo "LudoMada Commander tunnel stopped"
else
  echo "LudoMada Commander tunnel not running"
fi
