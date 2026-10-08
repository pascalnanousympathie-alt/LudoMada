#!/data/data/com.termux/files/usr/bin/bash
set -u
HOME_DIR="${HOME:-/data/data/com.termux/files/home}"
PIDFILE="$HOME_DIR/.config/ludomada/personal-mcp-runtime.pid"
PORT="${PERSONAL_MCP_HEALTH_PORT:-8790}"
FOUND=0
for p in $(ps -eo pid=,args= 2>/dev/null | awk -v port="$PORT" '$0 ~ /tunnel-client run/ && index($0,"--health.listen-addr 127.0.0.1:" port) {print $1}'); do
  [ -n "$p" ] && { kill "$p" 2>/dev/null || true; FOUND=1; }
done
if [ -f "$PIDFILE" ]; then
  kill "$(cat "$PIDFILE")" 2>/dev/null || true
  rm -f "$PIDFILE"
  FOUND=1
fi
if [ "$FOUND" -eq 1 ]; then echo "LudoMada Commander tunnel stopped"; else echo "LudoMada Commander tunnel not running"; fi
