#!/usr/bin/env bash
set -euo pipefail

APP_DIR="/opt/LudoMada"
APP_USER="ludomada"

echo "=== Ludo Mada VPS Provisioning ==="

if [ "$(id -u)" -ne 0 ]; then
  echo "ERROR: run this script as root"
  exit 1
fi

if ! command -v apt-get >/dev/null 2>&1; then
  echo "ERROR: Debian/Ubuntu apt-based VPS required"
  exit 1
fi

export DEBIAN_FRONTEND=noninteractive

echo "=== Updating system ==="
apt-get update
apt-get upgrade -y

echo "=== Installing base packages ==="
apt-get install -y \
  ca-certificates \
  curl \
  git \
  nginx \
  postgresql \
  postgresql-contrib \
  certbot \
  python3-certbot-nginx \
  ufw \
  unzip

echo "=== Creating application user ==="
if ! id "$APP_USER" >/dev/null 2>&1; then
  useradd --system --create-home --shell /usr/sbin/nologin "$APP_USER"
fi

echo "=== Creating application directory ==="
mkdir -p "$APP_DIR"
chown "$APP_USER:$APP_USER" "$APP_DIR"
chmod 0755 "$APP_DIR"

echo "=== Enabling services ==="
systemctl enable postgresql
systemctl enable nginx

systemctl restart postgresql
systemctl restart nginx

echo "=== Firewall ==="
ufw allow OpenSSH
ufw allow 80/tcp
ufw allow 443/tcp

echo "Firewall rules configured."
echo "IMPORTANT: run 'ufw enable' only after confirming SSH access."

echo
echo "=== Versions ==="
node --version 2>/dev/null || echo "Node.js: NOT INSTALLED YET"
psql --version
nginx -v 2>&1
certbot --version
ufw version

echo
echo "PROVISIONING_BASE=OK"
echo "NEXT: install Node.js 20 LTS, deploy application, configure PostgreSQL, DNS, and Let's Encrypt."
