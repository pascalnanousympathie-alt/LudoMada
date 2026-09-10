#!/usr/bin/env bash
set -euo pipefail

APP_DIR="/opt/LudoMada"
SERVICE_NAME="ludomada"
DEPLOY_USER="ludomada"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "=== Ludo Mada VPS Deployment ==="

if [ "$(id -u)" -ne 0 ]; then
  echo "ERROR: run this script as root"
  exit 1
fi

for cmd in systemctl nginx curl install; do
  if ! command -v "$cmd" >/dev/null 2>&1; then
    echo "ERROR: required command not found: $cmd"
    exit 1
  fi
done

if [ ! -d "$APP_DIR" ]; then
  echo "ERROR: $APP_DIR does not exist"
  exit 1
fi

if ! id "$DEPLOY_USER" >/dev/null 2>&1; then
  echo "ERROR: user $DEPLOY_USER does not exist"
  exit 1
fi

if [ ! -f "$APP_DIR/.env.production" ]; then
  echo "ERROR: $APP_DIR/.env.production is missing"
  exit 1
fi

if [ ! -f "$APP_DIR/dist/server.js" ]; then
  echo "ERROR: $APP_DIR/dist/server.js is missing"
  exit 1
fi

if [ ! -f "$SCRIPT_DIR/ludomada.service" ]; then
  echo "ERROR: ludomada.service is missing"
  exit 1
fi

if [ ! -f "$SCRIPT_DIR/nginx.conf" ]; then
  echo "ERROR: nginx.conf is missing"
  exit 1
fi

chown -R "$DEPLOY_USER:$DEPLOY_USER" "$APP_DIR"

install -m 0644 "$SCRIPT_DIR/ludomada.service" \
  /etc/systemd/system/ludomada.service

install -m 0644 "$SCRIPT_DIR/nginx.conf" \
  /etc/nginx/sites-available/ludomada.conf

mkdir -p /etc/nginx/sites-enabled

ln -sf /etc/nginx/sites-available/ludomada.conf \
  /etc/nginx/sites-enabled/ludomada.conf

nginx -t

systemctl daemon-reload
systemctl enable "$SERVICE_NAME"
systemctl restart "$SERVICE_NAME"

echo
echo "=== SERVICE STATUS ==="
systemctl --no-pager --full status "$SERVICE_NAME" || true

echo
echo "=== LOCAL HEALTH CHECK ==="
curl -fsS http://127.0.0.1:3000/health
echo
curl -fsS http://127.0.0.1:3000/ready
echo

echo
echo "DEPLOYMENT_CHECK=OK"
