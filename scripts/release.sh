#!/usr/bin/env bash
# Build the signed release APK, verify it, and copy it to dist/.
set -euo pipefail
cd "$(dirname "$0")/.."

[[ -f keystore.properties ]] || { echo "keystore.properties missing: release APK would be unsigned. See docs/MAINTAINING.md." >&2; exit 1; }
sdk=$(grep -E '^sdk.dir=' local.properties | cut -d= -f2-)
bt=$(ls -d "$sdk"/build-tools/* | sort -V | tail -1)

./gradlew -q clean testDebugUnitTest assembleRelease lintRelease
apk=app/build/outputs/apk/release/app-release.apk

perms=$("$bt/aapt2" dump permissions "$apk" | grep uses-permission || true)
echo "$perms"
if echo "$perms" | grep -q INTERNET; then echo "ERROR: INTERNET permission present" >&2; exit 1; fi
if "$bt/aapt2" dump badging "$apk" | grep -q debuggable; then echo "ERROR: APK is debuggable" >&2; exit 1; fi
"$bt/apksigner" verify --print-certs "$apk" | grep -E 'DN|SHA-256'

ver=$("$bt/aapt2" dump badging "$apk" | grep -oE "versionName='[^']+'" | cut -d"'" -f2)
mkdir -p dist
out="dist/Detach-$ver.apk"
cp "$apk" "$out"
(cd dist && sha256sum "Detach-$ver.apk" > "Detach-$ver.apk.sha256")
echo "→ $out"
cat "dist/Detach-$ver.apk.sha256"
