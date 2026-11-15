#!/usr/bin/env python3
"""Exercise the APK on an already running emulator; save screenshots and fail on crashes."""
from pathlib import Path
import subprocess,sys,time,xml.etree.ElementTree as ET,re,os
ROOT=Path(__file__).resolve().parents[1]
adb=Path(os.environ.get('ADB',str(ROOT/'.tools/android-sdk/platform-tools/adb')))
serial=sys.argv[1] if len(sys.argv)>1 else 'emulator-5554'
out=ROOT/'artifacts/screenshots';out.mkdir(parents=True,exist_ok=True)
def run(*args):return subprocess.run([str(adb),'-s',serial,*args],capture_output=True,check=True).stdout
run('logcat','-c')
run('shell','am','force-stop','com.velagram.tv')
run('shell','am','start','-W','-n','com.velagram.tv/.MainActivity')
time.sleep(2)
for page in ['Home','Movies','Series','Search','Telegram','Live TV','Library','More']:
 run('shell','uiautomator','dump','/sdcard/velagram-smoke.xml')
 xml=run('shell','cat','/sdcard/velagram-smoke.xml')
 nodes=ET.fromstring(xml).iter('node')
 node=next((n for n in nodes if n.get('content-desc')==page and n.get('clickable')=='true'),None)
 if node is None:raise RuntimeError(f'Navigation item missing: {page}')
 x1,y1,x2,y2=map(int,re.findall(r'\d+',node.get('bounds')))
 # Verify D-pad events are accepted as well as selecting the observed navigation node.
 run('shell','input','keyevent','21')
 run('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))
 time.sleep(1.5)
 (out/(page.lower().replace(' ','-')+'-release.png')).write_bytes(run('exec-out','screencap','-p'))
 if not run('shell','pidof','com.velagram.tv').strip():raise RuntimeError(f'App exited on {page}')
 print(page+': launched')
crashes=run('logcat','-d','-s','AndroidRuntime:E').decode()
if 'FATAL EXCEPTION' in crashes:raise RuntimeError(crashes)
print('Navigation smoke passed; screenshots: '+str(out))
