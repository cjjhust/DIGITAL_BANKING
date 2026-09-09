#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
backup_dir="$project_root/backups"
timestamp="$(date +%Y%m%d_%H%M%S)"
backup_file="$backup_dir/banking_db_$timestamp.sql"

mkdir -p "$backup_dir"
docker compose -f "$project_root/docker-compose.yml" exec -T db \
  pg_dumpall -U admin > "$backup_file"

echo "Database backup written to $backup_file"
