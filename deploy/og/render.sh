#!/usr/bin/env bash
# Renders og.html into deploy/apps/img/og.png (1200x630) with headless Firefox.
# On Hyprland, run it through `hyprctl dispatch 'hl.dsp.exec_cmd(...)'` so nothing touches your windows.
set -euo pipefail
cd "$(dirname "$0")"
profile=$(mktemp -d)
trap 'rm -rf "$profile"' EXIT
out=$(mktemp --suffix .png)
firefox --headless --no-remote --profile "$profile" --window-size 1200,630 \
  --screenshot "$out" "file://$PWD/og.html" >/dev/null 2>&1
magick "$out" -crop 1200x630+0+0 +repage -strip ../apps/img/og.png
rm -f "$out"
echo "Wrote deploy/apps/img/og.png"
