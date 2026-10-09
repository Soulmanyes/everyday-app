#!/usr/bin/env bash
# Builds the signed release APK and publishes it on https://everyday-home.duckdns.org/download/.
# Run from main after merging dev, with versionCode/versionName bumped in android/app/build.gradle.kts.
# Phones running an older versionCode then show "Update available".
set -euo pipefail
cd "$(dirname "$0")/.."

if [[ -n "$(git status --porcelain)" ]]; then
  echo "Commit your changes first." >&2; exit 1
fi
if [[ "$(git branch --show-current)" != "main" ]]; then
  echo "Publish from main (merge dev into main first)." >&2; exit 1
fi

(cd android && ./gradlew -q assembleRelease)
meta=android/app/build/outputs/apk/release/output-metadata.json
code=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["elements"][0]["versionCode"])' "$meta")
name=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["elements"][0]["versionName"])' "$meta")

out=deploy/download
cp android/app/build/outputs/apk/release/app-release.apk "$out/everyday.apk"
printf '{"versionCode": %s, "versionName": "%s", "url": "https://everyday-home.duckdns.org/download/everyday.apk"}\n' "$code" "$name" > "$out/version.json"

# The APK goes up before version.json, so phones are never told about a file that isn't there yet.
ssh oracle 'mkdir -p ~/everyday-app/deploy/download'
rsync -az "$out/index.html" "$out/everyday.apk" oracle:~/everyday-app/deploy/download/
rsync -az "$out/version.json" oracle:~/everyday-app/deploy/download/
git tag -f "v$name"
echo "Published $name (versionCode $code). Push the tag with: git push origin v$name"
