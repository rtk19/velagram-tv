#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
ADB="${ADB:-$PWD/.tools/android-sdk/platform-tools/adb}"
if [[ ! -x "$ADB" ]]; then ADB="$HOME/Library/Android/sdk/platform-tools/adb"; fi
APK="${APK:-$PWD/app/build/outputs/apk/release/app-release.apk}"
if [[ ! -x "$ADB" ]]; then echo 'Set ADB to your platform-tools/adb executable.' >&2; exit 1; fi
if [[ ! -f "$APK" ]]; then ./scripts/build.sh assembleRelease; fi
if [[ $# -gt 0 ]]; then
 for tv in "$@"; do
  "$ADB" connect "$tv"
  if ! "$ADB" -s "$tv" get-state >/dev/null 2>&1; then echo "TV $tv is not connected and authorized." >&2; exit 2; fi
  "$ADB" -s "$tv" install -r "$APK"
  "$ADB" -s "$tv" shell am start -n com.velagram.tv/.MainActivity
 done
else
 devices=$("$ADB" devices | awk 'NR>1 && $2=="device" {print $1}')
 if [[ -z "$devices" ]]; then echo 'No authorized devices. Run: adb pair IP:PAIRING_PORT CODE, then scripts/install-tv.sh IP:ADB_PORT' >&2; exit 2; fi
 while IFS= read -r tv; do "$ADB" -s "$tv" install -r "$APK"; "$ADB" -s "$tv" shell am start -n com.velagram.tv/.MainActivity; done <<< "$devices"
fi
