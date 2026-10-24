#!/usr/bin/env bash
# Publishes the web pages (deploy/apps/ and the download page) to the VM. Works from any branch: the pages
# don't depend on the app or server version, so they go up when they change, never with a release or a
# server deploy (deploy-server.sh and publish-release.sh leave them alone).
# Check that everything on the pages keeps its license terms before publishing (see CLAUDE.md "Licenses").
set -euo pipefail
cd "$(dirname "$0")/.."

if [[ -n "$(git status --porcelain deploy/apps deploy/download/index.html deploy/download/join.html)" ]]; then
  echo "Commit the page changes first." >&2; exit 1
fi

ssh oracle 'mkdir -p ~/everyday-app/deploy/apps ~/everyday-app/deploy/download'
# No --delete: the server also holds files published by other scripts (Gesture Cam's archive, the APK).
rsync -az deploy/apps/ oracle:~/everyday-app/deploy/apps/
rsync -az deploy/download/index.html deploy/download/join.html oracle:~/everyday-app/deploy/download/
echo "Published the pages from $(git branch --show-current) ($(git rev-parse --short HEAD))."
