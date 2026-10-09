#!/usr/bin/env bash
# Runs on the owner's PC (systemd user timer, see deploy/pc/): copies the VM's database backups to
# ~/everyday-backups/vm, so a copy exists off the VM. Never deletes local copies.
set -euo pipefail
dest=~/everyday-backups/vm
mkdir -p "$dest"
rsync -a -e "ssh -o BatchMode=yes" oracle:backups/daily oracle:backups/monthly "$dest/"
echo "$(date -Is) pulled backups to $dest"
