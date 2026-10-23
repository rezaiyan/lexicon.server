#!/usr/bin/env bash
# Dumps the production database to a gzipped SQL file and prunes old dumps.
#
# Run from the directory holding docker-compose.yml and .env, e.g. nightly from cron:
#   15 3 * * * cd /opt/lexicon && ./scripts/backup-db.sh >> backups/backup.log 2>&1
#
# Restore (stops nothing; run on an empty database or after dropping it):
#   gunzip -c backups/lexicondb-YYYYmmdd-HHMMSS.sql.gz | \
#     docker compose exec -T postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"
#
# Dumps live on the same disk as the database, so copy BACKUP_DIR off the VPS as well
# (e.g. rclone/rsync to object storage) to survive losing the server.
set -euo pipefail

BACKUP_DIR="${BACKUP_DIR:-backups}"
RETENTION_DAYS="${RETENTION_DAYS:-14}"

# Same defaults as docker-compose.yml; .env overrides them
POSTGRES_DB=lexicondb
POSTGRES_USER=lexicon_user
if [ -f .env ]; then
  POSTGRES_DB="$(grep -oP '^POSTGRES_DB=\K.*' .env || echo "$POSTGRES_DB")"
  POSTGRES_USER="$(grep -oP '^POSTGRES_USER=\K.*' .env || echo "$POSTGRES_USER")"
fi

mkdir -p "$BACKUP_DIR"
chmod 700 "$BACKUP_DIR"
file="$BACKUP_DIR/${POSTGRES_DB}-$(date -u +%Y%m%d-%H%M%S).sql.gz"
tmp="$file.partial"
# A failed or interrupted dump leaves nothing behind
trap 'rm -f "$tmp"' EXIT

# --clean --if-exists: the dump drops and recreates objects, so it restores over an existing schema
docker compose exec -T postgres pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --clean --if-exists --no-owner \
  | gzip -9 > "$tmp"

# An empty or truncated dump must never replace a good one
gzip -t "$tmp"
if [ "$(gunzip -c "$tmp" | head -c 4096 | wc -c)" -lt 100 ]; then
  echo "Backup looks empty: $tmp" >&2
  exit 1
fi
mv "$tmp" "$file"
chmod 600 "$file"
echo "$(date -u +%FT%TZ) backup written: $file ($(du -h "$file" | cut -f1))"

find "$BACKUP_DIR" -name "${POSTGRES_DB}-*.sql.gz" -mtime +"$RETENTION_DAYS" -print -delete
