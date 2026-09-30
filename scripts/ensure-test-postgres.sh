#!/usr/bin/env bash
set -euo pipefail

HOST="${PG_TEST_HOST:-127.0.0.1}"
PORT="${PG_TEST_PORT:-54329}"
USER_NAME="${PG_TEST_USER:-ludomada_test}"
DB_NAME="${PG_TEST_DB:-ludomada_test}"
PASSWORD="${PG_TEST_PASSWORD:-ludomada_test}"
DATA_DIR="${PG_TEST_DATA_DIR:-${RUNNER_TEMP:-/tmp}/ludomada-pg-${PORT}}"

ready() {
  PGPASSWORD="$PASSWORD" pg_isready -h "$HOST" -p "$PORT" -U "$USER_NAME" -d "$DB_NAME" >/dev/null 2>&1
}

if command -v pg_isready >/dev/null 2>&1 && ready; then
  echo "PostgreSQL test database already available at $HOST:$PORT/$DB_NAME"
  exit 0
fi

if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
  echo "Starting PostgreSQL test service with Docker Compose"
  docker compose -f docker-compose.test.yml up -d postgres
  for _ in $(seq 1 30); do
    if ready; then
      echo "PostgreSQL Docker service is ready"
      exit 0
    fi
    sleep 2
  done
  docker compose -f docker-compose.test.yml logs --no-color postgres || true
fi

if command -v initdb >/dev/null 2>&1 && command -v pg_ctl >/dev/null 2>&1 && command -v psql >/dev/null 2>&1; then
  echo "Starting ephemeral PostgreSQL with local binaries"
  mkdir -p "$DATA_DIR"
  if [ ! -s "$DATA_DIR/PG_VERSION" ]; then
    rm -rf "$DATA_DIR"
    initdb -D "$DATA_DIR" --auth=trust >/dev/null
  fi
  pg_ctl -D "$DATA_DIR" -o "-h $HOST -p $PORT" -w start
  cleanup() {
    pg_ctl -D "$DATA_DIR" -m fast -w stop >/dev/null 2>&1 || true
  }
  trap cleanup EXIT

  PGPASSWORD= psql -h "$HOST" -p "$PORT" -d postgres -v ON_ERROR_STOP=1 -c "DO \$\$ BEGIN CREATE ROLE \"$USER_NAME\" LOGIN PASSWORD '$PASSWORD' SUPERUSER; EXCEPTION WHEN duplicate_object THEN ALTER ROLE \"$USER_NAME\" WITH LOGIN PASSWORD '$PASSWORD' SUPERUSER; END \$\$;" >/dev/null
  PGPASSWORD="$PASSWORD" psql -h "$HOST" -p "$PORT" -d postgres -v ON_ERROR_STOP=1 -tc "SELECT 1 FROM pg_database WHERE datname='$DB_NAME'" | grep -q 1 || PGPASSWORD="$PASSWORD" createdb -h "$HOST" -p "$PORT" -U "$USER_NAME" "$DB_NAME"
  if ready; then
    echo "Ephemeral PostgreSQL is ready at $HOST:$PORT/$DB_NAME"
    # Keep the background server alive after this shell exits.
    trap - EXIT
    exit 0
  fi
fi

echo "POSTGRES_TEST_DATABASE_UNAVAILABLE"
echo "Expected $HOST:$PORT/$DB_NAME with user $USER_NAME"
exit 1
