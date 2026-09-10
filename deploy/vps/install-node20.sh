#!/usr/bin/env bash
set -euo pipefail

echo "=== Ludo Mada - Node.js 20 LTS ==="

if [ "$(id -u)" -ne 0 ]; then
  echo "ERROR: run this script as root"
  exit 1
fi

if ! command -v apt-get >/dev/null 2>&1; then
  echo "ERROR: Debian/Ubuntu apt-based VPS required"
  exit 1
fi

export DEBIAN_FRONTEND=noninteractive

apt-get update

apt-get install -y \
  ca-certificates \
  curl \
  gnupg

install -d -m 0755 /etc/apt/keyrings

if [ ! -f /etc/apt/keyrings/nodesource.gpg ]; then
  curl -fsSL https://deb.nodesource.com/gpgkey/nodesource-repo.gpg.key \
    | gpg --dearmor -o /etc/apt/keyrings/nodesource.gpg
  chmod 0644 /etc/apt/keyrings/nodesource.gpg
fi

echo "deb [signed-by=/etc/apt/keyrings/nodesource.gpg] https://deb.nodesource.com/node_20.x nodistro main" \
  > /etc/apt/sources.list.d/nodesource.list

apt-get update
apt-get install -y nodejs

echo
echo "=== Node versions ==="
node --version
npm --version

case "$(node -p 'process.versions.node.split(".")[0]')" in
  20)
    echo "NODE20_INSTALL=OK"
    ;;
  *)
    echo "ERROR: Node.js 20.x was not installed"
    exit 1
    ;;
esac
