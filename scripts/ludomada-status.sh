#!/data/data/com.termux/files/usr/bin/bash
set -u
HOME_DIR="${HOME:-/data/data/com.termux/files/home}"
STATE="$HOME_DIR/.config/ludomada"
PIDFILE="$STATE/personal-mcp-runtime.pid"
PORT="${PERSONAL_MCP_HEALTH_PORT:-8790}"
printf 'LudoMada Commander status\n'
printf '  runtime: '
if [ -f "$PIDFILE" ] && kill -0 "$(cat "$PIDFILE")" 2>/dev/null; then
  echo "RUNNING pid=$(cat "$PIDFILE")"
else
  echo "STOPPED"
fi
printf '  health : '
if curl -fsS --max-time 3 "http://127.0.0.1:$PORT/healthz" >/dev/null 2>&1; then echo READY; else echo NOT_READY; fi
printf '  web    : '
if curl -fsS --max-time 3 "http://127.0.0.1:8788/" >/dev/null 2>&1; then echo "http://127.0.0.1:8788/"; else echo DOWN; fi
