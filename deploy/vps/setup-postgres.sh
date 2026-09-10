#!/usr/bin/env bash
set -euo pipefail

DB_NAME="ludomada"
DB_USER="ludomada_app"

echo "=== Ludo Mada - PostgreSQL Production Setup ==="

if [ "$(id -u)" -ne 0 ]; then
  echo "ERROR: run this script as root"
  exit 1
fi

if ! command -v psql >/dev/null 2>&1; then
  echo "ERROR: PostgreSQL is not installed"
  exit 1
fi

if ! systemctl is-active --quiet postgresql; then
  echo "Starting PostgreSQL..."
  systemctl enable postgresql
  systemctl start postgresql
fi

echo
echo "Database: $DB_NAME"
echo "Application user: $DB_USER"
echo

read -r -s -p "Enter strong password for $DB_USER: " DB_PASSWORD
echo

if [ -z "$DB_PASSWORD" ]; then
  echo "ERROR: password cannot be empty"
  exit 1
fi

if [ "${#DB_PASSWORD}" -lt 32 ]; then
  echo "ERROR: password must be at least 32 characters"
  exit 1
fi

export DB_NAME DB_USER DB_PASSWORD

sudo -u postgres env \
  DB_NAME="$DB_NAME" \
  DB_USER="$DB_USER" \
  DB_PASSWORD="$DB_PASSWORD" \
  psql -v ON_ERROR_STOP=1 <<'SQL'
SELECT format(
  'CREATE ROLE %I LOGIN PASSWORD %L',
  :'DB_USER',
  :'DB_PASSWORD'
)
WHERE NOT EXISTS (
  SELECT FROM pg_roles WHERE rolname = :'DB_USER'
)
\gexec

SELECT format(
  'ALTER ROLE %I WITH LOGIN PASSWORD %L',
  :'DB_USER',
  :'DB_PASSWORD'
)
\gexec

SELECT format(
  'CREATE DATABASE %I OWNER %I',
  :'DB_NAME',
  :'DB_USER'
)
WHERE NOT EXISTS (
  SELECT FROM pg_database WHERE datname = :'DB_NAME'
)
\gexec

SELECT format(
  'ALTER DATABASE %I OWNER TO %I',
  :'DB_NAME',
  :'DB_USER'
)
\gexec
SQL

unset DB_PASSWORD

echo
echo "=== PostgreSQL Verification ==="

sudo -u postgres psql -tAc \
  "SELECT datname FROM pg_database WHERE datname='$DB_NAME';"

sudo -u postgres psql -tAc \
  "SELECT rolname FROM pg_roles WHERE rolname='$DB_USER';"

echo
echo "POSTGRES_SETUP=OK"
echo
echo "IMPORTANT:"
echo "Use this DATABASE_URL format in .env.production:"
echo "postgresql://$DB_USER:<PASSWORD>@127.0.0.1:5432/$DB_NAME"
