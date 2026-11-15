#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
export ANDROID_USER_HOME="$PWD/.android"
export ANDROID_AVD_HOME="$PWD/.android/avd"
export ANDROID_SDK_ROOT="$PWD/.tools/android-sdk"
EMULATOR="${EMULATOR:-$HOME/Library/Android/sdk/emulator/emulator}"
if [[ ! -x "$EMULATOR" ]]; then echo 'Set EMULATOR to your Android SDK emulator executable.' >&2;exit 1;fi
if [[ ! -f "$ANDROID_SDK_ROOT/system-images/android-34/android-tv/arm64/system.img" ]]; then
 curl -L --fail https://dl.google.com/android/repository/sys-img/android-tv/arm64-v8a-34_r03.zip -o .tools/tv-system.zip
 mkdir -p "$ANDROID_SDK_ROOT/system-images/android-34/android-tv"
 unzip -q .tools/tv-system.zip -d "$ANDROID_SDK_ROOT/system-images/android-34/android-tv"
fi
python3 - <<'PY'
from pathlib import Path
root=Path.cwd();d=root/'.android/avd/Velagram_TV.avd';d.mkdir(parents=True,exist_ok=True)
(root/'.android/avd/Velagram_TV.ini').write_text(f'avd.ini.encoding=UTF-8\npath={d}\ntarget=android-34\n')
if not (d/'config.ini').exists():
 (d/'config.ini').write_text('''AvdId=Velagram_TV
avd.ini.displayname=Velagram TV
abi.type=arm64-v8a
hw.cpu.arch=arm64
hw.cpu.ncore=4
hw.ramSize=2048
hw.lcd.width=1920
hw.lcd.height=1080
hw.lcd.density=320
hw.keyboard=yes
hw.dPad=yes
hw.mainKeys=yes
hw.gpu.enabled=yes
hw.gpu.mode=auto
hw.battery=no
disk.dataPartition.size=4G
showDeviceFrame=no
skin.name=1920x1080
skin.path=_no_skin
image.sysdir.1=system-images/android-34/android-tv/arm64/
tag.id=android-tv
''')
PY
exec "$EMULATOR" -avd Velagram_TV -no-snapshot -no-audio -gpu host "$@"
