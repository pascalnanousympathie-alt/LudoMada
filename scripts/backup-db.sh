#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BACKUP_DIR="$PROJECT_DIR/backups"
TIMESTAMP="$(date +%Y%m%d_%H%M%S)"
BACKUP_FILE="$BACKUP_DIR/ludomada_${TIMESTAMP}.sql.gz"

mkdir -p "$BACKUP_DIR"

if [ -z "${DATABASE_URL:-}" ]; then
  if [ -f "$PROJECT_DIR/.env" ]; then
    set -a
    . "$PROJECT_DIR/.env"
    set +a
  else
    echo "ERROR: DATABASE_URL is not set"
    exit 1
  fi
fi

if [ -z "${DATABASE_URL:-}" ]; then
  echo "ERROR: DATABASE_URL is required"
  exit 1
fi

echo "Creating PostgreSQL backup..."
echo "Output: $BACKUP_FILE"

pg_dump "$DATABASE_URL" \
  --format=plain \
  --no-owner \
  --no-privileges \
  | gzip > "$BACKUP_FILE"

if [ ! -s "$BACKUP_FILE" ]; then
  echo "ERROR: backup file is empty"
  rm -f "$BACKUP_FILE"
  exit 1
fi

echo "Backup created successfully."
echo "Size: $(du -h "$BACKUP_FILE" | cut -f1)"

echo "Testing gzip integrity..."
gzip -t "$BACKUP_FILE"

echo "Backup integrity: OK"

echo "Applying retention policy: keep latest 7 backups..."

mapfile -t BACKUPS < <(
  ls -1t "$BACKUP_DIR"/ludomada_*.sql.gz 2>/dev/null || true
)

if [ "${#BACKUPS[@]}" -gt 7 ]; then
  for OLD_BACKUP in "${BACKUPS[@]:7}"; do
    echo "Removing old backup: $OLD_BACKUP"
    rm -f "$OLD_BACKUP"
  done
fi

echo "Retention complete."
echo "Backups currently stored: $(find "$BACKUP_DIR" -maxdepth 1 -type f -name 'ludomada_*.sql.gz' | wc -l)"
