#!/data/data/com.termux/files/usr/bin/bash
set -u
HOME_DIR="${HOME:-/data/data/com.termux/files/home}"
STATE="$HOME_DIR/.config/ludomada"
PIDFILE="$STATE/personal-mcp-runtime.pid"
WATCHDOG_PIDFILE="$STATE/watchdog.pid"
PORT="${PERSONAL_MCP_HEALTH_PORT:-8790}"
printf 'LudoMada Commander status\n'
printf '  runtime: '
SERVICE_PID="$(ps -eo pid=,args= 2>/dev/null | awk -v port="$PORT" '$0 ~ /tunnel-client run/ && index($0,"--health.listen-addr 127.0.0.1:" port) {print $1; exit}')"
if [ -n "$SERVICE_PID" ]; then
  echo "RUNNING pid=$SERVICE_PID"
elif [ -f "$PIDFILE" ] && kill -0 "$(cat "$PIDFILE")" 2>/dev/null; then
  echo "RUNNING pid=$(cat "$PIDFILE")"
else
  echo "STOPPED"
fi
printf '  watchdog: '
if [ -f "$WATCHDOG_PIDFILE" ] && kill -0 "$(cat "$WATCHDOG_PIDFILE" 2>/dev/null || echo 0)" 2>/dev/null; then echo "RUNNING pid=$(cat "$WATCHDOG_PIDFILE")"; else echo STOPPED; fi
printf '  mcp-http: '
if curl -fsS --max-time 3 http://127.0.0.1:8787/.well-known/oauth-protected-resource >/dev/null 2>&1; then echo READY; else echo DOWN; fi
printf '  health : '
if curl -fsS --max-time 3 "http://127.0.0.1:$PORT/healthz" >/dev/null 2>&1; then echo READY; else echo NOT_READY; fi
printf '  web    : '
if curl -fsS --max-time 3 "http://127.0.0.1:8788/" >/dev/null 2>&1; then echo "http://127.0.0.1:8788/"; else echo DOWN; fi
