#!/bin/bash
# Keep downloaded build tools, credentials and signing material in this project.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p .tools config
if [[ -z "${JAVA_HOME:-}" && -d '/Applications/Android Studio.app/Contents/jbr/Contents/Home' ]]; then
 export JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
fi
if [[ -n "${JAVA_HOME:-}" ]]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
if ! command -v java >/dev/null; then echo 'Install JDK 17+ or set JAVA_HOME.' >&2; exit 1; fi
if [[ ! -f config/local.properties ]]; then cp config/local.properties.example config/local.properties; fi
if [[ $# -gt 0 ]]; then
 python3 - "$1" <<'PY'
from pathlib import Path
import sys,plistlib
src=Path(sys.argv[1]);values={}
for line in (src/'Config/Local.xcconfig').read_text().splitlines():
 if '=' in line and not line.strip().startswith('//'):
  key,value=line.split('=',1)
  if key.strip() in ('TMDB_READ_ACCESS_TOKEN','SUBDL_API_KEY'):values[key.strip()]=value.strip()
tg=plistlib.loads((src/'Config/TelegramConfiguration.local.plist').read_bytes())
values['TELEGRAM_API_ID']=str(tg['api_id']);values['TELEGRAM_API_HASH']=tg['api_hash']
Path('config/local.properties').write_text('\n'.join(k+'='+v for k,v in values.items())+'\n')
print('Copied service configuration; no credential values displayed.')
PY
fi
if [[ ! -f config/velagram-tv.jks ]]; then
 keytool -genkeypair -keystore config/velagram-tv.jks -storepass velagram-local -alias velagram -keypass velagram-local -dname 'CN=Velagram TV Local' -keyalg RSA -keysize 3072 -validity 10000 -noprompt
fi
if [[ ! -f local.properties ]]; then
 if [[ -n "${ANDROID_HOME:-}" && -d "$ANDROID_HOME/platforms/android-35" ]]; then
  printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
 else
  sdk="$PWD/.tools/android-sdk"
  mkdir -p "$sdk/platforms" "$sdk/build-tools" "$sdk/licenses"
  curl --fail -L https://dl.google.com/android/repository/platform-35_r02.zip -o .tools/platform.zip
  unzip -q .tools/platform.zip -d .tools/platform-extract
  mv .tools/platform-extract/* "$sdk/platforms/android-35"
  case "$(uname -s)" in Darwin) host=macosx;; Linux) host=linux;; *) echo 'Set ANDROID_HOME to an installed Android SDK.' >&2;exit 1;; esac
  curl --fail -L "https://dl.google.com/android/repository/build-tools_r36_${host}.zip" -o .tools/build-tools.zip
  unzip -q .tools/build-tools.zip -d .tools/build-tools-extract
  mv .tools/build-tools-extract/* "$sdk/build-tools/36.0.0"
  # The local SDK license already accepted in Android Studio can be reused.
  if [[ -d "$HOME/Library/Android/sdk/licenses" ]]; then cp "$HOME/Library/Android/sdk/licenses/"* "$sdk/licenses/";
  else echo 'Set ANDROID_HOME to an SDK whose licenses you have accepted using sdkmanager --licenses.' >&2;exit 1; fi
  printf 'sdk.dir=%s\n' "$sdk" > local.properties
 fi
fi
printf 'Ready. Run ./scripts/build.sh assembleRelease\n'
