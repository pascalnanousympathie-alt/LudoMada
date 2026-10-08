#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

HOME_DIR="${HOME:-/data/data/com.termux/files/home}"
PROJECT="${LUDOMADA_PROJECT_DIR:-$HOME_DIR/LudoMada}"
ROOT="$PROJECT/tools/personal-mcp-commander"

[ -f "$ROOT/package.json" ] || { echo "Commander package missing: $ROOT" >&2; exit 2; }
command -v npm >/dev/null 2>&1 || { echo "npm missing" >&2; exit 2; }
command -v node >/dev/null 2>&1 || { echo "node missing" >&2; exit 2; }

echo "[Commander] installing production dependencies"
npm --prefix "$ROOT" install --omit=dev

echo "[Commander] syntax validation"
node --check "$ROOT/server.mjs"
node --check "$ROOT/control-plane.mjs"
node --check "$ROOT/dashboard.mjs"

echo "[Commander] MCP smoke test"
echo "[Commander] production audit"
(cd "$ROOT" && node production-audit.mjs)
(cd "$ROOT" && node smoke.mjs)

chmod 700   "$PROJECT/scripts/ludomada-connect.sh"   "$PROJECT/scripts/ludomada-status.sh"   "$PROJECT/scripts/ludomada-stop.sh"   "$PROJECT/scripts/ludomada-watchdog.sh"

echo "[Commander] READY"
