#!/usr/bin/env bash
# Install Detach on an Android phone over USB.
# Usage: ./install-usb.sh Detach-<version>.apk
set -euo pipefail

apk="${1:-}"
if [[ -z "$apk" || ! -f "$apk" ]]; then
  echo "Usage: $0 path/to/Detach-<version>.apk" >&2
  exit 1
fi
if ! command -v adb >/dev/null; then
  echo "adb not found. Install Android platform-tools first (see docs/INSTALL.md)." >&2
  exit 1
fi

echo "Waiting for the phone… (plug it in, then tap 'Allow' on the USB debugging popup)"
adb wait-for-device
state=$(adb get-state 2>/dev/null || true)
if [[ "$state" != "device" ]]; then
  echo "Phone not ready (state: ${state:-none}). Unlock it and tap 'Allow' on the USB debugging popup." >&2
  exit 1
fi

echo "Installing $apk…"
adb install -r "$apk"

# Best effort: lets Detach turn itself back on after a "pause for payments" (see docs/INSTALL.md).
if adb shell pm grant io.github.detach android.permission.WRITE_SECURE_SETTINGS >/dev/null 2>&1; then
  echo "Allowed Detach to turn itself back on after a pause."
else
  echo "Note: couldn't grant WRITE_SECURE_SETTINGS; after a pause you'll turn Detach back on by hand (see docs/INSTALL.md)."
fi

echo "Opening Detach…"
adb shell am start -n io.github.detach/.MainActivity >/dev/null 2>&1 || true

cat <<'MSG'

Done. On the phone, follow "Set up Detach":
  Accessibility → Detach → turn on "Use Detach".
You can turn USB debugging off afterwards; Detach keeps working.
MSG
