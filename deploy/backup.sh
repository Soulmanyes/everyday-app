#!/usr/bin/env bash
# Daily database backup on the VM (run by cron, see CLAUDE.md). Keeps 30 daily and 12 monthly
# compressed dumps in ~/backups; this PC pulls copies with deploy/pull-backups.sh.
set -euo pipefail
cd "$(dirname "$0")"
set -a; . ./.env; set +a

daily=~/backups/daily
monthly=~/backups/monthly
mkdir -p "$daily" "$monthly"
file="$daily/everyday-$(date +%F).sql.gz"
tmp="$file.partial"

docker compose exec -T db pg_dump -U "$POSTGRES_USER" "$POSTGRES_DB" | gzip > "$tmp"
# An empty or tiny file means the dump failed; keep the previous backups untouched.
if [ "$(stat -c %s "$tmp")" -lt 1000 ]; then
  rm -f "$tmp"; echo "$(date -Is) backup FAILED (empty dump)" >&2; exit 1
fi
mv "$tmp" "$file"
if [ "$(date +%d)" = "01" ]; then
  cp "$file" "$monthly/everyday-$(date +%Y-%m).sql.gz"
fi

# Keep the newest 30 daily and 12 monthly (names sort by date).
prune() { find "$1" -maxdepth 1 -name 'everyday-*.sql.gz' | sort -r | tail -n +"$(($2 + 1))" | xargs -r rm -f; }
prune "$daily" 30
prune "$monthly" 12
echo "$(date -Is) backup ok: $file ($(stat -c %s "$file") bytes)"
