#!/usr/bin/env bash
# Deploys the server from main to production on the VM: backs up the database first (on the VM in
# ~/backups/manual, copied to ~/everyday-backups/manual on this PC), then copies the code and rebuilds
# only the API, which runs any new Flyway migration on start. Usage: deploy/deploy-server.sh <label>,
# e.g. before-v14; the label names the backup.
set -euo pipefail
cd "$(dirname "$0")/.."

label=${1:?"Give a label for the backup, e.g. before-v14."}
[[ "$label" =~ ^[a-z0-9-]+$ ]] || { echo "Label: lowercase letters, digits and dashes only." >&2; exit 1; }
if [[ -n "$(git status --porcelain)" ]]; then
  echo "Commit your changes first." >&2; exit 1
fi
if [[ "$(git branch --show-current)" != "main" ]]; then
  echo "Deploy from main (merge dev into main first)." >&2; exit 1
fi

file="everyday-$label-$(date +%F).sql.gz"
# An empty or tiny dump means it failed: stop before touching the server.
ssh oracle "set -euo pipefail; cd ~/everyday-app/deploy && set -a && . ./.env && set +a
  mkdir -p ~/backups/manual
  docker compose exec -T db pg_dump -U \"\$POSTGRES_USER\" \"\$POSTGRES_DB\" | gzip > ~/backups/manual/$file
  size=\$(stat -c %s ~/backups/manual/$file)
  if [ \"\$size\" -lt 1000 ]; then echo 'Backup FAILED (empty dump).' >&2; exit 1; fi
  echo \"Backup ok: ~/backups/manual/$file (\$size bytes)\""
mkdir -p ~/everyday-backups/manual
scp -q "oracle:~/backups/manual/$file" ~/everyday-backups/manual/
echo "Copied to ~/everyday-backups/manual/$file"

# Never .env (it exists only on the server) or build output. The web pages go up with publish-pages.sh,
# and the APK with publish-release.sh, so a server deploy never puts back older pages.
rsync -az --exclude build --exclude .gradle --exclude .env --exclude /deploy/apps --exclude /deploy/download \
  server deploy oracle:~/everyday-app/
ssh oracle 'cd ~/everyday-app/deploy && docker compose up -d --build api'
echo "Waiting for the API to start..."
sleep 20
ssh oracle 'cd ~/everyday-app/deploy && docker compose logs --since 2m api | grep -E "Successfully applied|now at version|Schema .* is up to date|Application started|ERROR" | tail -5'
