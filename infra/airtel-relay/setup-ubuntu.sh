#!/usr/bin/env bash
set -euo pipefail

: "${AIRTEL_RELAY_DOMAIN:?set AIRTEL_RELAY_DOMAIN}"
: "${AIRTEL_RELAY_TOKEN:?set AIRTEL_RELAY_TOKEN}"
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"

if command -v apt-get >/dev/null 2>&1; then
  apt-get update
  apt-get install -y debian-keyring debian-archive-keyring apt-transport-https curl gnupg
  curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' |
    gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
  curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' |
    tee /etc/apt/sources.list.d/caddy-stable.list >/dev/null
  apt-get update
  apt-get install -y caddy
else
  echo "This script currently supports apt-based Oracle/Ubuntu images." >&2
  exit 2
fi

install -d -m 700 /etc/caddy
export SCRIPT_DIR
python3 - <<'PY'
from pathlib import Path
import os
template = (Path(os.environ["SCRIPT_DIR"]) / "Caddyfile.template").read_text()
template = template.replace("{$AIRTEL_RELAY_DOMAIN}", os.environ["AIRTEL_RELAY_DOMAIN"])
template = template.replace("{$AIRTEL_RELAY_TOKEN}", os.environ["AIRTEL_RELAY_TOKEN"])
Path("/etc/caddy/Caddyfile").write_text(template)
PY

chmod 600 /etc/caddy/Caddyfile
caddy validate --config /etc/caddy/Caddyfile
systemctl enable caddy
systemctl restart caddy
systemctl --no-pager --full status caddy
